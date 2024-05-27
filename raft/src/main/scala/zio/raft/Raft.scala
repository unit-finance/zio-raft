package zio.raft

import java.time.Instant

import zio.raft.AppendEntriesResult.{Failure, Success}
import zio.raft.Raft.electionTimeout
import zio.raft.RequestVoteResult.{Granted, Rejected}
import zio.raft.State.{Candidate, Follower, Leader}
import zio.raft.StreamItem.{Bootstrap, CommandMessage, Message, Tick}
import zio.stream.ZStream
import zio.{Chunk, Promise, Queue, Ref, UIO, URIO, ZIO, durationInt}

sealed trait StreamItem[A <: Command]
object StreamItem:
  case class Tick[A <: Command]() extends StreamItem[A]
  case class Message[A <: Command](message: RPCMessage[A]) extends StreamItem[A]
  case class Bootstrap[A <: Command]() extends StreamItem[A]
  trait CommandMessage[A <: Command] extends StreamItem[A]:
    val command: A
    val promise: CommandPromise[command.Response]

class Raft[A <: Command](
    val memberId: MemberId,
    peers: Peers,
    private[raft] val state: Ref[State],
    commandsQueue: Queue[StreamItem[A]],
    stable: Stable,
    logStore: LogStore[A],
    snapshotStore: SnapshotStore,
    rpc: RPC[A],
    stateMachine: Ref[StateMachine[A]], // This should be part of state?
    pendingCommands: PendingCommands // This should be part of leader state?
):
  val rpcTimeout = 50.millis
  val batchSize = 100
  val numberOfServers = peers.length + 1

  private def stepDown(newTerm: Term, leaderId: Option[MemberId]) =
    for
      _ <- stable.newTerm(newTerm, None)
      _ <- pendingCommands.reset(leaderId)
      electionTimeout <- makeElectionTimeout

      _ <- state.update(s => State.Follower(s.commitIndex, s.lastApplied, electionTimeout, leaderId))
      currentTerm <- stable.currentTerm
      _ <- ZIO.logDebug(
        s"memberId=${this.memberId} Following $leaderId $currentTerm"
      )
    yield newTerm

  private def convertToFollower(leaderId: MemberId) =
    for
      electionTimeout <- makeElectionTimeout
      currentTerm <- stable.currentTerm

      s <- state.get

      _ <- s match
        case s: State.Follower if s.leaderId != Some(leaderId) =>
          ZIO.logDebug(
            s"memberId=${this.memberId} Following $leaderId $currentTerm"
          )
        case s: State.Follower => ZIO.unit
        case s =>
          ZIO.logDebug(
            s"memberId=${this.memberId} Following $leaderId $currentTerm"
          )

      _ <- state.set(
        State.Follower(
          s.commitIndex,
          s.lastApplied,
          electionTimeout,
          Some(leaderId)
        )
      )
    yield ()

  private def resetElectionTimer =
    for
      electionTimeout <- makeElectionTimeout
      _ <- state.update:
        case f: State.Follower  => f.copy(electionTimeout = electionTimeout)
        case c: State.Candidate => c.copy(electionTimeout = electionTimeout)
        case s                  => s
    yield ()

  private def makeElectionTimeout =
    for
      now <- zio.Clock.instant
      interval <- zio.Random
        .nextIntBetween(Raft.electionTimeout, Raft.electionTimeout * 2)
        .map(_.millis)
    yield now.plus(interval)

  private def setCommitIndex(commitIndex: Index): UIO[Unit] =
    state.update(s => s.withCommitIndex(commitIndex))

  private def handleRequestVoteRequest(m: RequestVoteRequest[A]) =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < m.term then stepDown(m.term, None)
        else ZIO.succeed(currentTerm)
      votedFor <- stable.votedFor
      lastTerm <- logStore.lastTerm
      lastIndex <- logStore.lastIndex
      result <-
        if (
          currentTerm == m.term && (votedFor.contains(
            m.candidateId
          ) || votedFor.isEmpty) &&
          (m.lastLogTerm > lastTerm ||
            (m.lastLogTerm == lastTerm &&
              m.lastLogIndex >= lastIndex))
        )
        then
          for
            _ <- stable.voteFor(m.candidateId)
            _ <- resetElectionTimer
          yield RequestVoteResult.Granted[A](memberId, currentTerm)
        else ZIO.succeed(RequestVoteResult.Rejected[A](memberId, currentTerm))
    yield result

  private def handleRequestVoteReply(m: RequestVoteResult[A]) =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < m.term then stepDown(m.term, None)
        else ZIO.succeed(currentTerm)

      s <- state.get
      _ <- s match
        case candidate: State.Candidate if currentTerm == m.term =>
          m match
            case m: RequestVoteResult.Rejected[A] =>
              state.set(candidate.ackRpc(m.from))
            case m: RequestVoteResult.Granted[A] =>
              state.set(candidate.addVote(m.from).ackRpc(m.from))
        case _ => ZIO.unit
    yield ()

  private def handleHeartbeatRequest(m: HeartbeatRequest[A]) =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < m.term then stepDown(m.term, Some(m.leaderId))
        else ZIO.succeed(currentTerm)

      _ <- convertToFollower(m.leaderId)
      _ <- resetElectionTimer
      s <- state.get
      lastIndex <- logStore.lastIndex
      commitIndex = Index.min(m.leaderCommitIndex, lastIndex)
      _ <- ZIO.when(m.leaderCommitIndex > s.commitIndex)(
        setCommitIndex(commitIndex)
      )
    yield HeartbeatResponse[A](memberId, currentTerm)

  private def handleHeartbeatResponse(m: HeartbeatResponse[A]) =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < m.term then stepDown(m.term, None)
        else ZIO.succeed(currentTerm)

      s <- state.get
      _ <- s match
        case l: State.Leader =>
          val wasPaused = l.replicationStatus.isPaused(m.from)
          val leader = l.withResume(m.from)
          for
            _ <- state.set(leader).when(wasPaused)
            lastIndex <- logStore.lastIndex
            matchIndex = l.matchIndex.get(m.from)

            _ <- ZIO.when(
              wasPaused && !leader.replicationStatus.isSnapshot(m.from)
            )(ZIO.logInfo(s"memberId=${this.memberId} Resuming $m.from"))

            // if the matchIndex is behind the last index, we need to send the append entries again for the peer to be able to resume
            _ <- ZIO.when(matchIndex < lastIndex)(
              sendAppendEntries(m.from, leader, lastIndex)
            )
          yield ()
        case _ => ZIO.unit
    yield ()

  private def handleAppendEntriesRequest(
      m: AppendEntriesRequest[A]
  ): URIO[Any, AppendEntriesResult[A]] =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < m.term then stepDown(m.term, Some(m.leaderId))
        else ZIO.succeed(currentTerm)
      s <- state.get

      result <-
        if currentTerm > m.term then
          ZIO.succeed(
            AppendEntriesResult
              .Failure[A](memberId, currentTerm, m.previousIndex, None)
          )
        else
          for
            _ <- convertToFollower(m.leaderId)
            lastIndex <- logStore.lastIndex
            localPreviousLogTerm <- logStore
              .logTerm(m.previousIndex)
              .someOrElse(Term.zero)
            success =
              m.previousIndex.isZero || (m.previousIndex <= lastIndex && localPreviousLogTerm == m.previousTerm)
            result <-
              if success && m.entries.isEmpty then
                val commitIndex =
                  Index.min(m.leaderCommitIndex, m.previousIndex)
                for _ <- ZIO.when(m.leaderCommitIndex > s.commitIndex)(
                    setCommitIndex(commitIndex)
                  )
                yield AppendEntriesResult.Success[A](
                  memberId,
                  currentTerm,
                  m.previousIndex
                )
              else if success then
                for
                  _ <- ZIO.when(m.previousIndex < lastIndex)(
                    ZIO.foreachDiscard(m.entries)(entry => {
                      for
                        logTerm <- logStore.logTerm(entry.index)
                        _ <- ZIO.when(
                          logTerm.isDefined && logTerm != Some(entry.term)
                        )(
                          logStore.deleteFrom(entry.index)
                        )
                      yield ()
                    })
                  )
                  _ <- logStore.storeLogs(m.entries)
                  messageLastIndex = m.entries.last.index
                  commitIndex = Index.min(m.leaderCommitIndex, messageLastIndex)
                  _ <- ZIO.when(m.leaderCommitIndex > s.commitIndex)(
                    setCommitIndex(commitIndex)
                  )
                yield AppendEntriesResult.Success[A](
                  memberId,
                  currentTerm,
                  messageLastIndex
                )
              else
                for (hintTerm, hintIndex) <- logStore.findConflictByTerm(
                    m.previousTerm,
                    Index.min(m.previousIndex, lastIndex)
                  )
                yield AppendEntriesResult.Failure[A](
                  memberId,
                  currentTerm,
                  m.previousIndex,
                  Some(hintTerm, hintIndex)
                )
          yield result
    yield result

  private def handleAppendEntriesReply(m: AppendEntriesResult[A]) =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < m.term then stepDown(m.term, None)
        else ZIO.succeed(currentTerm)

      _ <- ZIO.logDebug(
        s"memberId=${this.memberId} handleAppendEntriesReply $m $currentTerm"
      )

      s <- state.get
      _ <- s match
        case leader: State.Leader if currentTerm == m.term =>
          m match
            case AppendEntriesResult.Success(from, _, index) =>
              val currentMatchIndex = leader.matchIndex.get(from)
              val currentNextIndex = leader.nextIndex.get(from)

              // we might acknowledge and old message, so we need to check if the index is greater than the current one
              state.set(
                leader
                  .withMatchIndex(from, Index.max(currentMatchIndex, index))
                  .withResume(from)
              )
            case AppendEntriesResult.Failure(
                  from,
                  _,
                  _,
                  Some(hintTerm, hintIndex)
                ) =>
              for
                index <-
                  if hintTerm.isZero then ZIO.succeed(hintIndex)
                  else logStore.findConflictByTerm(hintTerm, hintIndex).map(_._2)
                currentNextIndex = leader.nextIndex.get(from)

                // we don't want to increase the index if we already processed a failure with a lower index
                _ <- state.set(
                  leader
                    .withNextIndex(
                      from,
                      Index.max(Index.one, Index.min(currentNextIndex, index))
                    )
                )
              yield ()
            case AppendEntriesResult.Failure(from, _, index, _) =>
              val currentNextIndex = leader.nextIndex.get(from)

              // we don't want to increase the index if we already processed a failure with a lower index
              state.set(
                leader
                  .withNextIndex(
                    from,
                    Index.max(Index.one, Index.min(currentNextIndex, index))
                  )
              )

        case _ => ZIO.unit
    yield ()

  private def handleInstallSnapshotRequest(r: InstallSnapshotRequest[A]) =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < r.term then stepDown(r.term, Some(r.leaderId))
        else ZIO.succeed(currentTerm)

      _ <- ZIO.logDebug(
        s"memberId=${this.memberId} handleInstallSnapshotRequest $r $currentTerm"
      )

      result <-
        if currentTerm > r.term then
          ZIO.succeed(
            InstallSnapshotResult.Failure[A](memberId, currentTerm, r.lastIndex)
          )
        else
          for
            _ <- convertToFollower(r.leaderId)
            _ <- ZIO.when(r.offset == 0)(
              snapshotStore.createNewPartialSnapshot(r.lastTerm, r.lastIndex)
            )
            written <- snapshotStore.writePartial(
              r.lastTerm,
              r.lastIndex,
              r.offset,
              r.data
            )
            result <-
              (r.done, written) match
                case (_, false) =>
                  ZIO.succeed(
                    InstallSnapshotResult
                      .Failure[A](memberId, currentTerm, r.lastIndex)
                  )
                case (false, true) =>
                  ZIO.succeed(
                    InstallSnapshotResult
                      .Success[A](memberId, currentTerm, r.lastIndex, false)
                  )
                case (true, true) =>
                  for
                    lastestSnapshotIndex <- snapshotStore.latestSnapshotIndex
                    result <-
                      if r.lastIndex > lastestSnapshotIndex then
                        for
                          snapshot <- snapshotStore.completePartial(
                            r.lastTerm,
                            r.lastIndex
                          )

                          // TODO: optimize, if the snapshot equals latest log entry, we can just discard the log up to that point and avoid restoring the snapshot
                          _ <- logStore.discardEntireLog(
                            snapshot.previousIndex,
                            snapshot.previousTerm
                          )

                          existingStateMachine <- stateMachine.get
                          newStateMachine <- existingStateMachine
                            .restoreFromSnapshot(snapshot.stream)
                          _ <- stateMachine.set(newStateMachine)
                          _ <- state.set(
                            State.Follower(
                              snapshot.previousIndex,
                              snapshot.previousIndex,
                              Instant.now.plus(Raft.initialElectionTimeout),
                              Some(r.leaderId)
                            )
                          )
                        yield InstallSnapshotResult.Success[A](
                          memberId,
                          currentTerm,
                          snapshot.previousIndex,
                          true
                        )
                      else
                        for _ <- snapshotStore.deletePartial(
                            r.lastTerm,
                            r.lastIndex
                          )
                        yield InstallSnapshotResult
                          .Success[A](memberId, currentTerm, r.lastIndex, true)
                  yield result
          yield result
    yield result

  private def handleInstallSnapshotReply(m: InstallSnapshotResult[A]) =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < m.term then stepDown(m.term, None)
        else ZIO.succeed(currentTerm)

      _ <- ZIO.logDebug(s"memberId=${this.memberId} handleInstallSnapshotReply $m")

      s <- state.get
      _ <- s match
        case l: State.Leader =>
          m match
            case InstallSnapshotResult.Success(from, _, index, done) =>
              val leader = l.withSnaphotResponse(from, Instant.now, index, done)
              for _ <- state.set(leader)
              yield ()
            case InstallSnapshotResult.Failure(from, _, index) =>
              val leader = l.withSnapshotFailure(from, Instant.now, index)
              for _ <- state.set(leader)
              yield ()
        case _ => ZIO.unit
    yield ()

  private def startElection =
    for
      currentTerm <- stable.currentTerm
      _ <- ZIO.logDebug(
        s"memberId=${this.memberId} start new election term ${currentTerm.plusOne}"
      )
      _ <- stable.newTerm(currentTerm.plusOne, Some(memberId))
      electionTimeout <- makeElectionTimeout
      _ <- state.update(s =>
        State.Candidate(
          RPCDue.makeNow(peers),
          1,
          s.commitIndex,
          s.lastApplied,
          electionTimeout
        )
      )
    yield ()

  private def startNewElectionRule =
    for
      now <- zio.Clock.instant
      s <- state.get
      currentTerm <- stable.currentTerm
      _ <- s match
        case f: State.Follower if now.isAfter(f.electionTimeout) && !currentTerm.isZero =>
          startElection
        case c: State.Candidate if now.isAfter(c.electionTimeout) =>
          startElection
        case _ => ZIO.unit
    yield ()

  private def handleBootstrap =
    for
      s <- state.get
      currentTerm <- stable.currentTerm
      _ <- s match
        case f: State.Follower if currentTerm.isZero => startElection
        case _                                       => ZIO.unit
    yield ()

  private def becomeLeaderRule =
    def becomeLeader(c: Candidate) =
      for
        currentTerm <- stable.currentTerm
        _ <- ZIO.logDebug(
          s"memberId=${this.memberId} become leader ${currentTerm}"
        )
        lastLogIndex <- logStore.lastIndex
        _ <- state.set(
          Leader(
            NextIndex(lastLogIndex.plusOne),
            MatchIndex(peers),
            HeartbeatDue.empty,
            ReplicationStatus(peers),
            c.commitIndex,
            c.lastApplied
          )
        )
      yield ()

    for
      s <- state.get
      _ <- s match
        case c: State.Candidate if c.voteGranted > numberOfServers / 2 =>
          becomeLeader(c)
        case _ => ZIO.unit
    yield ()

  private def advanceCommitIndexRule =
    for
      s <- state.get
      lastIndex <- logStore.lastIndex
      currentTerm <- stable.currentTerm
      _ <- s match
        case l: Leader =>
          val matchIndexes =
            (lastIndex :: l.matchIndex.indices).sortBy(_.value)
          val n = matchIndexes(numberOfServers / 2)
          if n > l.commitIndex then
            for
              nTerm <- logStore.logTerm(n)
              _ <- ZIO.when(nTerm.contains(currentTerm))(
                state.set(l.withCommitIndex(n))
              )
              _ <- ZIO
                .logDebug(
                  s"memberId=${this.memberId} advanceCommitIndexRule $nTerm $currentTerm ${l.commitIndex} ${n}"
                )
                .when(nTerm == currentTerm)
            yield ()
          else ZIO.unit
        case _ => ZIO.unit
    yield ()

  private def applyToStateMachineRule =
    for
      s <- state.get
      _ <- ZIO
        .logDebug(
          s"memberId=${this.memberId} applyToStateMachineRule ${s.commitIndex} ${s.lastApplied}"
        )
        .when(s.commitIndex > s.lastApplied)
      newState <- applyToStateMachine(s)
      _ <- state.set(newState)
    yield s != newState

  private def takeSnapshotRule =
    for
      (snapshotIndex, snapshotSize) <- snapshotStore.latestSnapshotIndexAndSize
      s <- state.get
      shouldTakeSnapshot <- stateMachine.get.map(
        _.shouldTakeSnapshot(snapshotIndex, snapshotSize, s.commitIndex)
      )
      _ <- ZIO.logDebug(
        s"memberId=${this.memberId} takeSnapshotRule $shouldTakeSnapshot $snapshotIndex ${s.commitIndex} $snapshotSize"
      )
      _ <- ZIO.when(shouldTakeSnapshot):
        for
          stream <- stateMachine.get.map(_.takeSnapshot)

          // The last applied should term be in the log
          previousTerm <- logStore
            .logTerm(s.lastApplied)
            .someOrFail(new Throwable("No log entry"))
            .orDie

          _ <- (snapshotStore
            .createNewSnapshot(
              Snapshot(s.lastApplied, previousTerm, stream)
            ) <*> logStore.discardLogUpTo(
            s.lastApplied
          )).fork // TODO: make discarding the log configurable (the user might want to control that)
        yield ()
    yield ()

  private def applyToStateMachine(state: State): UIO[State] =
    if state.commitIndex > state.lastApplied then
      val state1 = state.increaseLastApplied
      for
        logEntry <- logStore
          .getLog(state1.lastApplied)
          .map(_.get) // todo: Handle onot found
        response <- stateMachine.modify(_.apply(logEntry.command))
        _ <- state match
          case l: Leader => pendingCommands.complete(logEntry.index, response)
          case _         => ZIO.unit
        state2 <- applyToStateMachine(state1)
      yield state2
    else ZIO.succeed(state)

  private def sendHeartbeatRule(peer: MemberId) =
    for
      s <- state.get
      now <- zio.Clock.instant
      _ <- s match
        case l: Leader if l.heartbeatDue.due(now, peer) =>
          for
            currentTerm <- stable.currentTerm
            lastIndex <- logStore.lastIndex
            matchIndex = l.matchIndex.get(peer)
            commitIndex = Index.min(matchIndex, l.commitIndex)

            _ <- rpc.sendHeartbeat(
              peer,
              HeartbeatRequest[A](
                currentTerm,
                memberId,
                commitIndex
              )
            )

            _ <- state.set(
              l.withHeartbeatDue(
                peer,
                now.plus(Raft.heartbeartInterval)
              )
            )
          yield ()
        case _ => ZIO.unit
    yield ()

  private def sendAppendEntriesRule(peer: MemberId) =
    for
      s <- state.get
      leaderLastLogIndex <- logStore.lastIndex
      _ <- s match
        case l: Leader
            if leaderLastLogIndex >= l.nextIndex.get(
              peer
            ) && !l.replicationStatus.isPaused(peer) =>
          sendAppendEntries(peer, l, leaderLastLogIndex)
        case _ => ZIO.unit
    yield ()

  private def sendAppendEntries(
      peer: MemberId,
      l: Leader,
      leaderLastLogIndex: Index
  ) =
    val nextIndex = l.nextIndex.get(peer)
    val previousIndex = nextIndex.minusOne

    // TODO: should we use the message size instead of batchSize?
    val lastIndex = Index.min(nextIndex.plus(batchSize - 1), leaderLastLogIndex)
    for
      now <- zio.Clock.instant
      currentTerm <- stable.currentTerm
      previousTerm <- logStore.logTerm(previousIndex)
      maybeEntries <- logStore.getLogs(nextIndex, lastIndex)

      _ <- (previousTerm, maybeEntries) match
        case (Some(previousTerm), Some(entries)) =>
          for
            _ <- ZIO
              .logDebug(
                s"memberId=${this.memberId} sendAppendEntriesRule $peer $leaderLastLogIndex $nextIndex"
              )
              .when(entries.nonEmpty)

            sent <- rpc.sendAppendEntries(
              peer,
              AppendEntriesRequest(
                currentTerm,
                memberId,
                previousIndex,
                previousTerm,
                entries,
                l.commitIndex
              )
            )

            _ <-
              if sent then
                state.set(
                  l.withNextIndex(peer, lastIndex.plusOne)
                    .withHeartbeatDue(
                      peer,
                      now.plus(Raft.heartbeartInterval)
                    )
                )
              else
                ZIO
                  .logWarning(
                    s"memberId=${this.memberId} failed to send entries to peer $peer $nextIndex $leaderLastLogIndex, pausing peer"
                  )
                  .when(!sent) *>
                  state.set(l.withPause(peer))
          yield ()
        case _ =>
          for
            _ <- ZIO.logDebug(
              s"memberId=${this.memberId} sendAppendEntriesRule installSnapshot $peer $leaderLastLogIndex $nextIndex"
            )

            // This should not happen, we probably prefer not to fail here
            snapshot <- snapshotStore.latestSnapshot
              .someOrFail(new Throwable("No snapshot"))
              .orDie
            chunkZize = 1024L * 1024L

            // Empty snapshot is special case where we only send one message
            isEmpty <- snapshot.stream.runHead.map(_.isEmpty)

            _ <-
              if isEmpty then
                rpc
                  .sendInstallSnapshot(
                    peer,
                    InstallSnapshotRequest(
                      currentTerm,
                      memberId,
                      snapshot.previousIndex,
                      snapshot.previousTerm,
                      0,
                      true,
                      Chunk.empty
                    )
                  )
                  .fork
              else
                snapshot.stream
                  .grouped(chunkZize.toInt)
                  .zipWithNext
                  .zipWithIndex
                  .foreach { case ((chunk, next), index) =>
                    ZIO.logDebug(
                      s"memberId=${this.memberId} sendInstallSnapshot $peer $index"
                    )
                    rpc.sendInstallSnapshot(
                      peer,
                      InstallSnapshotRequest(
                        currentTerm,
                        memberId,
                        snapshot.previousIndex,
                        snapshot.previousTerm,
                        index * chunkZize,
                        next.isEmpty,
                        chunk
                      )
                    )
                  }
                  .fork

            _ <- state.set(
              l.withNextIndex(peer, snapshot.previousIndex.plusOne)
                .withSnapshot(peer, now, snapshot.previousIndex)
                .withHeartbeatDue(
                  peer,
                  now.plus(Raft.heartbeartInterval)
                )
            )
          yield ()
    yield ()

  private def sendRequestVoteRule(peer: MemberId) =
    for
      s <- state.get
      now <- zio.Clock.instant
      _ <- s match
        case c: Candidate if c.rpcDue.due(now, peer) =>
          for
            currentTerm <- stable.currentTerm
            lastLogIndex <- logStore.lastIndex
            lastLogTerm <- logStore.lastTerm

            _ <- state.set(c.withRPCDue(peer, now.plus(rpcTimeout)))
            _ <- rpc.sendRequestVote(
              peer,
              RequestVoteRequest[A](
                currentTerm,
                memberId,
                lastLogIndex,
                lastLogTerm
              )
            )
          yield ()
        case _ => ZIO.unit
    yield ()

  private def preRules =
    for
      _ <- startNewElectionRule
      _ <- ZIO.foreachDiscard(peers)(p => sendRequestVoteRule(p) <*> sendHeartbeatRule(p))
    yield ()

  private def postRules =
    for
      _ <- becomeLeaderRule
      _ <- advanceCommitIndexRule
      _ <- ZIO.foreachDiscard(peers)(p => sendAppendEntriesRule(p) <*> sendRequestVoteRule(p))
      changed <- applyToStateMachineRule
      _ <- ZIO.when(changed)(takeSnapshotRule)
    yield ()

  private def handleMessage(message: RPCMessage[A]) =
    message match
      case r: RequestVoteRequest[A] =>
        for
          res <- handleRequestVoteRequest(r)
          _ <- rpc.sendRequestVoteResponse(r.candidateId, res)
        yield ()
      case r: RequestVoteResult[A] => handleRequestVoteReply(r)
      case r: HeartbeatRequest[A] =>
        for
          res <- handleHeartbeatRequest(r)
          _ <- rpc.sendHeartbeatResponse(r.leaderId, res)
        yield ()
      case r: HeartbeatResponse[A] => handleHeartbeatResponse(r)
      case r: AppendEntriesRequest[A] =>
        for
          res <- handleAppendEntriesRequest(r)
          _ <- ZIO.when(res.isInstanceOf[Failure[A]] || r.entries.nonEmpty)(
            ZIO.logDebug(
              s"memberId=${this.memberId} handleAppendEntriesRequest $r $res"
            )
          )
          _ <- rpc.sendAppendEntriesResponse(r.leaderId, res)
        yield ()

      case r: AppendEntriesResult[A] =>
        handleAppendEntriesReply(r)
      case r: InstallSnapshotRequest[A] =>
        for
          res <- handleInstallSnapshotRequest(r)
          _ <- ZIO.logDebug(
            s"memberId=${this.memberId} handleInstallSnapshotRequest $r $res"
          )
          _ <- rpc.sendInstallSnapshotResponse(r.leaderId, res)
        yield ()
      case r: InstallSnapshotResult[A] =>
        handleInstallSnapshotReply(r)

  private def handleRequestFromClient(
      command: A,
      promise: CommandPromise[command.Response]
  ): ZIO[Any, Nothing, Unit] =
    state.get.flatMap:
      case Leader(
            nextIndex,
            matchIndex,
            heartbeatDue,
            replicationStatus,
            commintIndex,
            lastApplied
          ) =>
        for
          lastIndex <- logStore.lastIndex
          currentTerm <- stable.currentTerm
          entry = LogEntry[A](
            command,
            currentTerm,
            lastIndex.plusOne
          )
          _ <- ZIO.logDebug(s"memberId=${this.memberId} handleCommand $entry")
          _ <- logStore.storeLog(entry)
          _ <- pendingCommands.add(entry.index, promise)
        yield ()
      case f: Follower =>
        promise.fail(NotALeaderError(f.leaderId)).unit
      case c: Candidate =>
        promise.fail(NotALeaderError(None)).unit

  private[raft] def handleStreamItem(item: StreamItem[A]) =
    item match
      case Tick() =>
        preRules <*> postRules
      case Message(message) =>
        for
          _ <- preRules
          _ <- handleMessage(message)
          _ <- postRules
        yield ()
      case commandMessage: CommandMessage[A] =>
        for
          _ <- ZIO.logDebug(s"memberId=${this.memberId} ${commandMessage.command}")
          _ <- preRules
          _ <- handleRequestFromClient(
            commandMessage.command,
            commandMessage.promise
          )
          _ <- postRules
        yield ()
      case Bootstrap() =>
        for
          _ <- preRules
          _ <- handleBootstrap
          _ <- postRules
        yield ()

  // TODO: the correct implementation should enqueue this into the stream queue?
  def isTheLeader =
    for s <- state.get
    yield if s.isInstanceOf[Leader] then true else false

  def sendCommand(
      commandArg: A
  ): ZIO[Any, NotALeaderError, commandArg.Response] =
    // todo: leader only
    for
      promiseArg <- Promise.make[NotALeaderError, commandArg.Response]
      _ <- commandsQueue.offer(new CommandMessage {
        val command = commandArg
        val promise = promiseArg.asInstanceOf
      })
      res <- promiseArg.await
    yield (res)

  // bootstrap the node and wait until the node would become the leader, only works when the current term is zero
  def bootstrap =
    for
      _ <- commandsQueue.offer(
        StreamItem.Bootstrap()
      ) // TODO: use promise here and fail if the term is not zero
      _ <- (zio.Clock.sleep(electionTimeout.millis) *> isTheLeader)
        .repeatUntil(identity)
    yield ()

  // User of the library would decide on what index to use for the compaction
  def compact(index: Index) =
    for
      s <- state.get
      _ <- logStore.discardLogUpTo(Index.min(index, s.lastApplied))
    yield ()

  def run =
    val tick = ZStream.repeat(StreamItem.Tick[A]())
    val messages = rpc.incomingMessages
      .map(Message(_))
    val commandMessage =
      ZStream.fromQueue(this.commandsQueue)
    ZStream
      .mergeAllUnbounded(16)(tick, messages, commandMessage)
      .foreach(handleStreamItem)

  override def toString(): String =
    s"Raft $memberId"
end Raft

object Raft:
  // TODO: make configurable
  val initialElectionTimeout = 2000.millis
  val electionTimeout = 150
  val heartbeartInterval = (electionTimeout / 2).millis

  def make[A <: Command](
      memberId: MemberId,
      peers: Peers,
      stable: Stable,
      logStore: LogStore[A],
      snapshotStore: SnapshotStore,
      rpc: RPC[A],
      stateMachine: StateMachine[A]
  ) =
    for
      now <- zio.Clock.instant
      electionTimeout = now.plus(initialElectionTimeout)
      snapshot <- snapshotStore.latestSnapshot
      restoredStateMachine <-
        snapshot match
          case None => ZIO.succeed(stateMachine)
          case Some(snapshot) =>
            stateMachine.restoreFromSnapshot(snapshot.stream)
      previousIndex = snapshot.map(_.previousIndex).getOrElse(Index.zero)

      refStateMachine <- Ref.make(restoredStateMachine)
      state <- Ref.make[State](
        State.Follower(previousIndex, previousIndex, electionTimeout, None)
      )
      commandsQueue <- Queue
        .unbounded[StreamItem[A]] // TODO: should this be bounded for pack-pressure?

      pendingCommands <- PendingCommands.make
      raft = new Raft(
        memberId,
        peers,
        state,
        commandsQueue,
        stable,
        logStore,
        snapshotStore,
        rpc,
        refStateMachine,
        pendingCommands
      )
    yield raft

  def makeScoped[A <: Command](
      memberId: MemberId,
      peers: Peers,
      stable: Stable,
      logStore: LogStore[A],
      snapshotStore: SnapshotStore,
      rpc: RPC[A],
      stateMachine: StateMachine[A]
  ) =
    for
      raft <- make(
        memberId,
        peers,
        stable,
        logStore,
        snapshotStore,
        rpc,
        stateMachine
      )
      _ <- raft.run.forkScoped
    yield raft
