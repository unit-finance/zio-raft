package zio.raft

import zio.{Ref, ZIO, UIO, URIO}
import zio.raft.RequestVoteResult.Granted
import zio.Has
import zio.raft.RequestVoteResult.Rejected
import zio.duration.*
import zio.clock.Clock
import zio.random.Random
import zio.raft.State.Candidate
import zio.raft.State.Leader
import java.time.Instant
import zio.stream.ZStream
import zio.raft.StreamItem.Tick
import zio.raft.StreamItem.Message
import zio.raft.StreamItem.CommandMessage
import zio.Queue
import zio.ZQueue
import zio.Promise
import zio.raft.AppendEntriesResult.Success
import zio.raft.AppendEntriesResult.Failure
import zio.logging.log
import zio.logging.Logging
import zio.raft.State.Follower

sealed trait StreamItem[A <: Command]
object StreamItem:
  case class Tick[A <: Command]() extends StreamItem[A]
  case class Message[A <: Command](message: RPCMessage[A]) extends StreamItem[A]
  trait CommandMessage[A <: Command] extends StreamItem[A]:
    val command: A
    val promise: CommandPromise[command.Response]

  // object CommandPromise
  //   def apply()    

class Raft[A <: Command](
    memberId: MemberId,
    peers: Peers,
    state: Ref[State],
    commandsQueue: Queue[CommandMessage[A]],
    stable: Stable,
    logStore: LogStore[A],    
    rpc: RPC[A],
    stateMachine: Ref[StateMachine[A]], // This should be part of state?
    pendingCommands: PendingCommands // This should be part of leader state?
):
  val rpcTimeout = 50.millis
  val batchSize = 100
  val numberOfServers = peers.length + 1 

  def stepDown(newTerm: Term, leaderId: Option[MemberId]) =
    for      
      _ <- stable.newTerm(newTerm, None)
      _ <- pendingCommands.reset(leaderId)
      electionTimeout <- makeElectionTimeout
      _ <- state.update(s =>
        State.Follower(s.commitIndex, s.lastApplied, electionTimeout, leaderId)
      )
      _ <- log.debug(s"Following $leaderId")
    yield newTerm

  def convertToFollower(leaderId: MemberId) =
    for
      electionTimeout <- makeElectionTimeout

      s <- state.get

      _ <- s match
        case s: State.Follower if s.leaderId != Some(leaderId) =>
          log.debug(s"Following $leaderId")
        case s: State.Follower => ZIO.unit
        case s => 
          log.debug(s"Following $leaderId")
      
      _ <- state.set(State.Follower(s.commitIndex, s.lastApplied, electionTimeout, Some(leaderId)))      
    yield ()

  def resetElectionTimer =
    for
      electionTimeout <- makeElectionTimeout
      _ <- state.update:
        case f: State.Follower  => f.copy(electionTimeout = electionTimeout)
        case c: State.Candidate => c.copy(electionTimeout = electionTimeout)
        case s                  => s
    yield ()

  def makeElectionTimeout =
    for
      now <- zio.clock.instant
      interval <- zio.random
        .nextIntBetween(Raft.electionTimeout, Raft.electionTimeout * 2)
        .map(_.millis)
    yield now.plus(interval)

  def setCommitIndex(commitIndex: Index): UIO[Unit] =
    state.update(s => s.withCommitIndex(commitIndex))

  def handleRequestVoteRequest(m: RequestVoteRequest[A]) =
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

  def handleRequestVoteReply(m: RequestVoteResult[A]) =
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

  def handleAppendEntriesRequest(
      m: AppendEntriesRequest[A]
  ): URIO[Clock & Random & Logging, AppendEntriesResult[A]] =
    for      
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < m.term then stepDown(m.term, Some(m.leaderId))
        else ZIO.succeed(currentTerm)

      result <-
        if currentTerm > m.term then
          ZIO.succeed(AppendEntriesResult.Failure[A](memberId, currentTerm, m.previousIndex))
        else
          for
            _ <- convertToFollower(m.leaderId)
            lastIndex <- logStore.lastIndex
            localPreviousLogTerm <- logStore.logTerm(m.previousIndex)
            success = 
              m.previousIndex.isZero || (m.previousIndex <= lastIndex && localPreviousLogTerm == m.previousTerm) 
            result <-
              if success then
                for
                  // TODO: omptimize this code
                  index <- ZIO.foldLeft(m.entries)(
                    m.previousIndex
                  )((previousIndex, entry) =>
                    val index = previousIndex.plusOne
                    for                      
                      logTerm <- logStore.logTerm(index)
                      _ <- ZIO.when(logTerm != entry.term)(
                        logStore.deleteFrom(index)
                      )
                      _ <- logStore.storeLog(entry)
                    yield index
                  )

                  commitIndex = Index.min(m.leaderCommitIndex, index)
                  _ <- setCommitIndex(commitIndex)
                yield AppendEntriesResult.Success[A](
                  memberId,
                  currentTerm,
                  index
                )
              else
                ZIO.succeed(AppendEntriesResult.Failure[A](memberId, currentTerm, m.previousIndex))
          yield result
    yield result

  def handleAppendEntriesReply(m: AppendEntriesResult[A]) =
    for      
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < m.term then stepDown(m.term, None)
        else ZIO.succeed(currentTerm)

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
                  .withNextIndex(from, Index.max(currentNextIndex, index.plusOne))
              )
            case AppendEntriesResult.Failure(from, _, index) =>
              val currentNextIndex = leader.nextIndex.get(from)

              // we don't want to increase the index if we already processed a failure with a lower index
              state.set(
                leader
                  .withNextIndex(from, Index.max(Index.one, Index.min(currentNextIndex, index)))
              )
        case _ => ZIO.unit
    yield ()

  def startNewElectionRule =
    def start =
      for
        _ <- log.debug(s"start new election")
        currentTerm <- stable.currentTerm
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

    for
      now <- zio.clock.instant
      s <- state.get
      _ <- s match
        case f: State.Follower if now isAfter f.electionTimeout  => start
        case c: State.Candidate if now isAfter c.electionTimeout => start
        case _                                                   => ZIO.unit
    yield ()

  def becomeLeaderRule =
    def becomeLeader(c: Candidate) =
      for
        _ <- log.debug(s"become leader")
        lastLogIndex <- logStore.lastIndex
        _ <- state.set(
          Leader(
            NextIndex(lastLogIndex.plusOne),
            MatchIndex(peers),            
            HeartbeatDue.empty,
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

  def advanceCommitIndexRule =
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
              _ <- ZIO.when(nTerm == currentTerm)(state.set(l.withCommitIndex(n)))
              _ <- log.debug(s"advanceCommitIndexRule $nTerm $currentTerm ${l.commitIndex} ${n}").when(nTerm == currentTerm)
            yield ()
          else ZIO.unit
        case _ => ZIO.unit
    yield ()

  def applyToStateMachineRule =
    for
      s <- state.get
      _ <- log.debug(s"applyToStateMachineRule ${s.commitIndex} ${s.lastApplied}").when(s.commitIndex > s.lastApplied)
      newState <- applyToStateMachine(s)      
      _ <- state.set(newState)
    yield ()

  def applyToStateMachine(state: State): UIO[State] =    
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

  def sendHeartbeatRule(peer: MemberId) =
    for      
      s <- state.get
      now <- zio.clock.instant
      _ <- s match
        case l: Leader if l.heartbeatDue.due(now, peer) =>
          val nextIndex = l.nextIndex.get(peer)
          val previousIndex = nextIndex.minusOne
          for            
            currentTerm <- stable.currentTerm
            lastIndex <- logStore.lastIndex
            previousTerm <- logStore.logTerm(previousIndex)
            _ <- rpc.sendAppendEntires(
              peer,
              AppendEntriesRequest[A](
                currentTerm,
                memberId,
                previousIndex,
                previousTerm,
                List.empty[LogEntry[A]],
                l.commitIndex
              )
            )

            _ <- state.set(
              l.withHeartbeaetDue(
                peer,
                now.plus(Raft.heartbeartInterval)
              )
            )
          yield ()
        case _ => ZIO.unit
    yield ()

  def sendAppendEntriesRule(peer: MemberId) =
    for      
      s <- state.get
      now <- zio.clock.instant
      leaderLastLogIndex <- logStore.lastIndex
      _ <- s match
        case l: Leader
            if leaderLastLogIndex >= l.nextIndex.get(peer) =>
          val nextIndex = l.nextIndex.get(peer)
          val previousIndex = nextIndex.minusOne
          val lastIndex =
            Index.min(nextIndex.plus(batchSize - 1), leaderLastLogIndex)
            
          for
            currentTerm <- stable.currentTerm
            previousTerm <- logStore.logTerm(previousIndex)
            // TODO: check if nextIndex is in the logstore, otherwise send InstallSnapshot
            entries <- logStore.getLogs(nextIndex, lastIndex)
            
            _ <- log.debug(s"sendAppendEntriesRule $peer $leaderLastLogIndex $nextIndex").when(entries.nonEmpty)
            
            _ <- rpc.sendAppendEntires(
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

            _ <- state.set(
              l.withNextIndex(peer, lastIndex.plusOne)
                .withHeartbeaetDue(
                  peer,
                  now.plus(Raft.heartbeartInterval)
                )
            )
          yield ()
        case _ => ZIO.unit
    yield ()

  def sendRequestVoteRule(peer: MemberId) =
    for
      s <- state.get
      now <- zio.clock.instant
      _ <- s match
        case c: Candidate if c.rpcDue.due(now, peer) =>
          for            
            currentTerm <- stable.currentTerm
            lastLogIndex <- logStore.lastIndex
            lastLogTerm <- logStore.logTerm(lastLogIndex)
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

  def preRules =
    for
      _ <- startNewElectionRule
      _ <- ZIO.foreach_(peers)(p =>
        sendRequestVoteRule(p) &&& sendHeartbeatRule(p)
      )
    yield ()

  def postRules =
    for
      _ <- becomeLeaderRule
      _ <- advanceCommitIndexRule
      _ <- ZIO.foreach_(peers)(p =>
        sendAppendEntriesRule(p) &&& sendRequestVoteRule(p)
      )
      _ <- applyToStateMachineRule
    yield ()

  def handleMessage(message: RPCMessage[A]) =
    message match
      case r: RequestVoteRequest[A] =>
        for
          res <- handleRequestVoteRequest(r)
          _ <- rpc.sendRequestVoteResponse(r.candidateId, res)
        yield ()
      case r: RequestVoteResult[A] => handleRequestVoteReply(r)
      case r: AppendEntriesRequest[A] =>
        for
          res <- handleAppendEntriesRequest(r)
          _ <- ZIO.when(res.isInstanceOf[Failure[A]] || r.entries.nonEmpty)(
            log.debug(s"handleAppendEntriesRequest $r $res")
          )
          _ <- rpc.sendAppendEntriesResponse(r.leaderId, res)
        yield ()

      case r: AppendEntriesResult[A] =>
        for
          _ <- handleAppendEntriesReply(r)
        yield ()

  def handleRequestFromClient(
      command: A,
      promise: CommandPromise[command.Response]
  ): ZIO[Logging, Nothing, Unit] =
    state.get.flatMap:
      case Leader(
            nextIndex,
            matchIndex,            
            heartbeatDue,
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
          _ <- log.debug(s"handleCommand $entry")
          _ <- logStore.storeLog(entry)
          _ <- pendingCommands.add(entry.index, promise)
        yield ()
      case f: Follower =>
        promise.fail(NotALeaderError(f.leaderId)).unit
      case c: Candidate =>
        promise.fail(NotALeaderError(None)).unit

  def handleStreamItem(item: StreamItem[A]) =
    item match
      case Tick() =>
        preRules &&& postRules
      case Message(message) =>
        for
          _ <- preRules
          _ <- handleMessage(message)
          _ <- postRules
        yield ()
      case commandMessage: CommandMessage[A] =>
        for
          _ <- log.debug(s"${commandMessage.command}")
          _ <- preRules
          _ <- handleRequestFromClient(commandMessage.command, commandMessage.promise)
          _ <- postRules
        yield ()
        
  def isTheLeader =
    for
      s <- state.get
    yield if s.isInstanceOf[Leader] then true else false

  def sendCommand(commandArg: A): ZIO[Any, NotALeaderError, commandArg.Response] =
    // todo: leader only
    for
      promiseArg <- Promise.make[NotALeaderError, commandArg.Response]
      _ <- commandsQueue.offer(new CommandMessage {
        val command = commandArg
        val promise = promiseArg.asInstanceOf
      })
      res <- promiseArg.await
    yield (res)

  def run =
    val tick = ZStream.repeat(StreamItem.Tick[A]())
    val messages = rpc.incomingMessages
      .map(Message(_))
    val commandMessage =
      ZStream.fromQueue(this.commandsQueue)
    ZStream
      .mergeAllUnbounded(16)(tick, messages, commandMessage)
      .foreach(handleStreamItem)

end Raft

object Raft:
  // TODO: make configurable
  val initialElectionTimeout = 2000.millis
  val electionTimeout = 150
  val heartbeartInterval = (electionTimeout  / 2).millis

  def makeManaged[A <: Command](
      memberId: MemberId,
      peers: Peers,      
      stable: Stable,
      logStore: LogStore[A],      
      rpc: RPC[A],
      stateMachine: StateMachine[A]
  ) =
    for
      now <- zio.clock.instant.toManaged_
      electionTimeout = now.plus(initialElectionTimeout)
      state <- Ref.makeManaged[State](State.Follower(Index.zero, Index.zero, electionTimeout, None))
      commandsQueue <- Queue.unbounded[CommandMessage[A]].toManaged_
      refStateMachine <- Ref.makeManaged(stateMachine)
      pendingCommands <- PendingCommands.makeManaged
      raft = new Raft(
      memberId,
      peers,
      state,
      commandsQueue,
      stable,
      logStore,      
      rpc,
      refStateMachine,
      pendingCommands
    )
      _ <- raft.run.forkManaged
    yield raft
