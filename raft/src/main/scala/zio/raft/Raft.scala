package zio.raft

import java.time.Instant

import zio.raft.AppendEntriesResult.{Failure, Success}
import zio.raft.Raft.electionTimeout
import zio.raft.RequestVoteResult.Rejected
import zio.raft.State.{Candidate, Follower, Leader}
import zio.raft.StreamItem.{Bootstrap, CommandMessage, Message, Tick}
import zio.stream.ZStream
import zio.{Chunk, Promise, Queue, Ref, UIO, URIO, ZIO, durationInt}
import zio.raft.StreamItem.Read

sealed trait StreamItem[A <: Command, S]
object StreamItem:
  case class Tick[A <: Command, S]() extends StreamItem[A, S]
  case class Message[A <: Command, S](message: RPCMessage[A]) extends StreamItem[A, S]
  case class Bootstrap[A <: Command, S]() extends StreamItem[A, S]
  case class Read[A <: Command, S](promise: Promise[NotALeaderError, S]) extends StreamItem[A, S]
  trait CommandMessage[A <: Command, S] extends StreamItem[A, S]:
    val command: A
    val promise: CommandPromise[command.Response]

class Raft[S, A <: Command](
    val memberId: MemberId,
    peers: Peers,
    private[raft] val raftState: Ref[State[S]],
    commandsQueue: Queue[StreamItem[A, S]],
    stable: Stable,
    logStore: LogStore[A],
    snapshotStore: SnapshotStore,
    rpc: RPC[A],
    stateMachine: StateMachine[S, A],
    appStateRef: Ref[S]
):
  val rpcTimeout = 50.millis
  val batchSize = 100
  val numberOfServers = peers.length + 1

  private def stepDown(newTerm: Term, leaderId: Option[MemberId]) =
    for
      _ <- stable.newTerm(newTerm, None)

      // Reset commands and reads if we are a leader before we transition to Follower
      currentState <- raftState.get
      _ <- currentState match
        case l: Leader[S] => l.stepDown(leaderId) // Called for side effects, so we ignore the new state
        case _            => ZIO.unit
      electionTimeout <- makeElectionTimeout

      _ <- raftState.update(s => State.Follower[S](s.commitIndex, s.lastApplied, electionTimeout, leaderId))
      currentTerm <- stable.currentTerm
      _ <- ZIO.logDebug(
        s"memberId=${this.memberId} Following $leaderId $currentTerm"
      )
    yield newTerm

  private def convertToFollower(leaderId: MemberId) =
    for
      electionTimeout <- makeElectionTimeout
      currentTerm <- stable.currentTerm

      s <- raftState.get

      _ <- s match
        case s: State.Follower[S] if s.leaderId != Some(leaderId) =>
          ZIO.logDebug(
            s"memberId=${this.memberId} Following $leaderId $currentTerm"
          )
        case s: State.Follower[S] => ZIO.unit
        case s =>
          ZIO.logDebug(
            s"memberId=${this.memberId} Following $leaderId $currentTerm"
          )

      _ <- raftState.set(
        State.Follower[S](
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
      _ <- raftState.update:
        case f: State.Follower[S]  => f.copy(electionTimeout = electionTimeout)
        case c: State.Candidate[S] => c.copy(electionTimeout = electionTimeout)
        case s                     => s
    yield ()

  private def makeElectionTimeout =
    for
      now <- zio.Clock.instant
      interval <- zio.Random
        .nextIntBetween(Raft.electionTimeout, Raft.electionTimeout * 2)
        .map(_.millis)
    yield now.plus(interval)

  private def setCommitIndex(commitIndex: Index): UIO[Unit] =
    raftState.update(s => s.withCommitIndex(commitIndex))

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

      s <- raftState.get
      _ <- s match
        case candidate: State.Candidate[S] if currentTerm == m.term =>
          m match
            case m: RequestVoteResult.Rejected[A] =>
              raftState.set(candidate.ackRpc(m.from))
            case m: RequestVoteResult.Granted[A] =>
              raftState.set(candidate.addVote(m.from).ackRpc(m.from))
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
      s <- raftState.get
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

      s <- raftState.get
      _ <- s match
        case l: State.Leader[S] =>
          val wasPaused = l.replicationStatus.isPaused(m.from)
          val leader = l.withResume(m.from)
          for
            // TODO (eran): Implement this
            // TODO (eran): TBD how is the timestamp in heartbeat used? in theory we should always update all with heartbeats no?
            // _ <- pendingReads.heartbeat(m.timestamp, m.from, peers.length) // pending reads can get confirmed by: (1) waiting for write and it was applied (2) or was ready for heartbeat from tiemstamp and it arrived. when we have a new read that requires heartbeat we want to send a "HeartnbeatDueNow" message to followers so they reply immediately and we do not wait.

            // // when we have a new read, if we alreayd have existing reads, we want to add the timestamp of the earliest read (cheapest to get).

            _ <- raftState.set(leader).when(wasPaused)
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
      s <- raftState.get

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

      s <- raftState.get
      _ <- s match
        case leader: State.Leader[S] if currentTerm == m.term =>
          m match
            case AppendEntriesResult.Success(from, _, index) =>
              val currentMatchIndex = leader.matchIndex.get(from)
              val currentNextIndex = leader.nextIndex.get(from)

              // we might acknowledge and old message, so we need to check if the index is greater than the current one
              raftState.set(
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
                _ <- raftState.set(
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
              raftState.set(
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

                          newState <- stateMachine.restoreFromSnapshot(snapshot.stream)
                          _ <- appStateRef.set(newState)

                          _ <- raftState.set(
                            State.Follower[S](
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

      s <- raftState.get
      _ <- s match
        case l: State.Leader[S] =>
          m match
            case InstallSnapshotResult.Success(from, _, index, done) =>
              val leader = l.withSnaphotResponse(from, Instant.now, index, done)
              for _ <- raftState.set(leader)
              yield ()
            case InstallSnapshotResult.Failure(from, _, index) =>
              val leader = l.withSnapshotFailure(from, Instant.now, index)
              for _ <- raftState.set(leader)
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
      _ <- raftState.update(s =>
        State.Candidate[S](
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
      s <- raftState.get
      currentTerm <- stable.currentTerm

      _ <- s match
        case f: State.Follower[S] if now.isAfter(f.electionTimeout) && !currentTerm.isZero =>
          startElection
        case c: State.Candidate[S] if now.isAfter(c.electionTimeout) =>
          startElection
        case _ => ZIO.unit
    yield ()

  private def handleBootstrap =
    for
      s <- raftState.get
      currentTerm <- stable.currentTerm
      _ <- s match
        case f: State.Follower[S] if currentTerm.isZero => startElection
        case _                                          => ZIO.unit
    yield ()

  private def becomeLeaderRule =
    def becomeLeader(c: Candidate[S]) =
      for
        currentTerm <- stable.currentTerm
        _ <- ZIO.logDebug(
          s"memberId=${this.memberId} become leader ${currentTerm}"
        )
        lastLogIndex <- logStore.lastIndex
        _ <- raftState.set(
          Leader[S](
            NextIndex(lastLogIndex.plusOne),
            MatchIndex(peers),
            HeartbeatDue.empty,
            ReplicationStatus(peers),
            c.commitIndex,
            c.lastApplied,
            PendingReads.empty[S],
            PendingCommands.empty
          )
        )
      yield ()

    for
      s <- raftState.get
      _ <- s match
        case c: State.Candidate[S] if c.voteGranted > numberOfServers / 2 =>
          becomeLeader(c)
        case _ => ZIO.unit
    yield ()

  private def advanceCommitIndexRule =
    for
      s <- raftState.get
      lastIndex <- logStore.lastIndex
      currentTerm <- stable.currentTerm
      _ <- s match
        case l: Leader[S] =>
          val matchIndexes =
            (lastIndex :: l.matchIndex.indices).sortBy(_.value)
          val n = matchIndexes(numberOfServers / 2)
          if n > l.commitIndex then
            for
              nTerm <- logStore.logTerm(n)
              _ <- ZIO.when(nTerm.contains(currentTerm))(
                raftState.set(l.withCommitIndex(n))
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

  private def applyToStateMachineRule: ZIO[Any, Nothing, Boolean] =
    for
      s <- raftState.get
      newState <- applyToStateMachine(s)
      _ <- raftState.set(newState)
    yield s != newState

  private def takeSnapshotRule =
    for
      (snapshotIndex, snapshotSize) <- snapshotStore.latestSnapshotIndexAndSize
      s <- raftState.get
      shouldTakeSnapshot = stateMachine.shouldTakeSnapshot(snapshotIndex, snapshotSize, s.commitIndex)
      _ <- ZIO.logDebug(
        s"memberId=${this.memberId} takeSnapshotRule $shouldTakeSnapshot $snapshotIndex ${s.commitIndex} $snapshotSize"
      )
      _ <- ZIO.when(shouldTakeSnapshot):
        for
          appState <- appStateRef.get
          stream = stateMachine.takeSnapshot(appState)

          // The last applied should term be in the log
          previousTerm <- logStore
            .logTerm(s.lastApplied)
            .someOrFail(new Throwable("No log entry"))
            .orDie

          _ <- (snapshotStore
            .createNewSnapshot(
              Snapshot(s.lastApplied, previousTerm, stream)
            ))
            .fork
        yield ()
    yield ()

  private def applyToStateMachine(state: State[S]): ZIO[Any, Nothing, State[S]] =
    if state.commitIndex > state.lastApplied then
      ZIO.logDebug(s"memberId=${this.memberId} applyToStateMachineRule ${state.commitIndex} ${state.lastApplied}") *>
        logStore
          .stream(state.lastApplied.plusOne, state.commitIndex)
          .runFoldZIO(state)((state, logEntry) =>
            for
              appState <- appStateRef.get
              (newState, response) <- stateMachine.apply(logEntry.command).toZIOWithState(appState)
              _ <- appStateRef.set(newState)
              newRaftState <- state match
                case l: Leader[S] => l.completeCommands(logEntry.index, response, newState)
                case _            => ZIO.succeed(state)
            yield newRaftState.increaseLastApplied
          )
    else ZIO.succeed(state)

  private def sendHeartbeatRule(peer: MemberId) =
    for
      s <- raftState.get
      now <- zio.Clock.instant
      _ <- s match
        case l: Leader[S] if l.heartbeatDue.due(now, peer) =>
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

            _ <- raftState.set(
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
      s <- raftState.get
      leaderLastLogIndex <- logStore.lastIndex
      _ <- s match
        case l: Leader[S]
            if leaderLastLogIndex >= l.nextIndex.get(
              peer
            ) && !l.replicationStatus.isPaused(peer) =>
          sendAppendEntries(peer, l, leaderLastLogIndex)
        case _ => ZIO.unit
    yield ()

  private def sendAppendEntries(
      peer: MemberId,
      l: Leader[S],
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
                raftState.set(
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
                  raftState.set(l.withPause(peer))
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

            _ <- raftState.set(
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
      s <- raftState.get
      now <- zio.Clock.instant
      _ <- s match
        case c: Candidate[S]
            if c.rpcDue.due(
              now,
              peer
            ) =>
          for
            currentTerm <- stable.currentTerm
            lastTerm <- logStore.lastTerm
            lastIndex <- logStore.lastIndex
            _ <- raftState.set(c.withRPCDue(peer, now.plus(rpcTimeout)))
            _ <- rpc.sendRequestVote(
              peer,
              RequestVoteRequest[A](
                currentTerm,
                memberId,
                lastIndex,
                lastTerm
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

  private def handleMessage(message: RPCMessage[A]): ZIO[Any, Nothing, Unit] =
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
    raftState.get.flatMap:
      case l: Leader[S] =>
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

          _ <- raftState.set(l.withPendingCommand(entry.index, promise))
        yield ()
      case f: Follower[S] =>
        promise.fail(NotALeaderError(f.leaderId)).unit
      case c: Candidate[S] =>
        promise.fail(NotALeaderError(None)).unit

  private[raft] def handleStreamItem(item: StreamItem[A, S]): ZIO[Any, Nothing, Unit] =
    item match
      case Tick() =>
        preRules <*> postRules
      case Message(message) =>
        for
          _ <- ZIO.logDebug(s"[Message] memberId=${this.memberId} $message")
          _ <- preRules
          _ <- handleMessage(message)
          _ <- postRules
        yield ()
      case commandMessage: CommandMessage[A, S] =>
        for
          _ <- ZIO.logDebug(s"[CommandMessage] memberId=${this.memberId} ${commandMessage.command}")
          _ <- preRules
          _ <- handleRequestFromClient(
            commandMessage.command,
            commandMessage.promise
          )
          _ <- postRules
        yield ()
      case r: Read[A, S] =>
        for
          _ <- ZIO.logDebug(s"[Read] memberId=${this.memberId} $r")
          _ <- handleRead(r)
        yield ()
      case Bootstrap() =>
        for
          _ <- ZIO.logDebug(s"[Bootstrap] memberId=${this.memberId}")
          _ <- preRules
          _ <- handleBootstrap
          _ <- postRules
        yield ()

  // TODO: the correct implementation should enqueue this into the stream queue?
  def isTheLeader =
    for s <- raftState.get
    yield if s.isInstanceOf[Leader[S]] then true else false

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

  def handleRead(r: Read[A, S]): ZIO[Any, Nothing, Unit] =
    (for
      s <- raftState.get
      l <- s match
        case l: Leader[S] => ZIO.succeed(l)
        case f: Follower[S] => ZIO.fail(NotALeaderError(f.leaderId))
        case c: Candidate[S] => ZIO.fail(NotALeaderError(None))
      
      now <- zio.Clock.instant
      pendingReadEntry = l.pendingCommands.lastIndex match
        case Some(index) => PendingReadEntry.PendingCommand[S](r.promise, index)
        case None => PendingReadEntry.PendingHeartbeat[S](r.promise, now, peers)
      _ <- raftState.set(l.withPendingRead(pendingReadEntry))      
    yield ()).catchAll(e => r.promise.fail(e).unit)

  def readState: ZIO[Any, NotALeaderError, S] =
    for
      promise <- Promise.make[NotALeaderError, S]
      _ <- commandsQueue.offer(Read[A, S](promise))
      result <- promise.await
    yield result

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
      s <- raftState.get
      latestSnapshotIndex <- snapshotStore.latestSnapshotIndex
      _ <- logStore.discardLogUpTo(Index.min(index, latestSnapshotIndex))
    yield ()

  def run =
    val tick = ZStream.repeat(StreamItem.Tick[A, S]())
    val messages = rpc.incomingMessages
      .map(Message[A, S](_))
    val commandMessage =
      ZStream.fromQueue(this.commandsQueue)
    ZStream
      .mergeAllUnbounded(16)(
        tick,
        messages,
        commandMessage
      )
      .foreach(handleStreamItem)

  override def toString(): String =
    s"Raft $memberId"
end Raft

object Raft:
  // TODO: make configurable
  val initialElectionTimeout = 2000.millis
  val electionTimeout = 150
  val heartbeartInterval = (electionTimeout / 2).millis

  def make[S, A <: Command](
      memberId: MemberId,
      peers: Peers,
      stable: Stable,
      logStore: LogStore[A],
      snapshotStore: SnapshotStore,
      rpc: RPC[A],
      stateMachine: StateMachine[S, A]
  ) =
    for
      now <- zio.Clock.instant
      electionTimeout = now.plus(initialElectionTimeout)
      snapshot <- snapshotStore.latestSnapshot
      restoredState <-
        snapshot match
          case None => ZIO.succeed(stateMachine.emptyState)
          case Some(snapshot) =>
            for
              lastIndex <- logStore.lastIndex

              // If the snapshot is ahead of the log, we need to discard the log
              // TODO: can we only delete part of the log?
              _ <- ZIO.when(lastIndex <= snapshot.previousIndex)(
                logStore.discardEntireLog(snapshot.previousIndex, snapshot.previousTerm)
              )

              // Restore the state machine from the snapshot
              restoredState <- stateMachine.restoreFromSnapshot(snapshot.stream)
            yield restoredState
      restoredStateRef <- Ref.make(restoredState)
      previousIndex = snapshot.map(s => s.previousIndex).getOrElse(Index.zero)

      state <- Ref.make[State[S]](
        State.Follower[S](previousIndex, previousIndex, electionTimeout, None)
      )
      commandsQueue <- Queue.unbounded[StreamItem[A, S]] // TODO: should this be bounded for back-pressure?

      raft = new Raft[S, A](
        memberId,
        peers,
        state,
        commandsQueue,
        stable,
        logStore,
        snapshotStore,
        rpc,
        stateMachine,
        restoredStateRef
      )
    yield raft

  def makeScoped[S, A <: Command](
      memberId: MemberId,
      peers: Peers,
      stable: Stable,
      logStore: LogStore[A],
      snapshotStore: SnapshotStore,
      rpc: RPC[A],
      stateMachine: StateMachine[S, A]
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
