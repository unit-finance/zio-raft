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

sealed trait StreamItem
object StreamItem {
  case object Tick extends StreamItem
  case class Message(message: RPCMessage) extends StreamItem
  case class CommandMessage[A <: Command](
      message: A,
      promise: Promise[Nothing, Response]
  ) extends StreamItem
}
class Raft[A <: Command](
    memberId: MemberId,
    peers: Peers,
    state: Ref[State],
    commandsQueue: Queue[CommandMessage[A]],
    stable: Stable,
    logStore: LogStore[A],
    codec: Codec[A],
    stateMachine: StateMachine[A],
    rpc: RPC[A],
    waitingResponse: Ref[Map[A, Promise[Nothing, Response]]]
) {
  val electionTimeout = 150
  val rpcTimeout = 50.millis
  val batchSize = 100
  val numberOfServers = peers.length + 1

  def makeElectionTimeout =
    for
      now <- zio.clock.instant
      interval <- zio.random
        .nextIntBetween(electionTimeout, electionTimeout * 2)
        .map(_.millis)
    yield now.plus(interval)

  def stepDown(term: Term) =
    for
      _ <- stable.newTerm(term, None)
      electionTimeout <- makeElectionTimeout
      _ <- state.update(s =>
        State.Follower(s.commintIndex, s.lastApplied, electionTimeout)
      )
    yield term

  def convertToFollower =
    for
      electionTimeout <- makeElectionTimeout
      _ <- state.update(s =>
        State.Follower(s.commintIndex, s.lastApplied, electionTimeout)
      )
    yield ()

  def resetElectionTimer =
    for
      electionTimeout <- makeElectionTimeout
      _ <- state.update {
        case f: State.Follower  => f.copy(electionTimeout = electionTimeout)
        case c: State.Candidate => c.copy(electionTimeout = electionTimeout)
        case s                  => s
      }
    yield ()

  def updateCommitIndex(f: Index => Index): UIO[Unit] =
    state.update(s => s.withCommitIndex(f(s.commintIndex)))

  def handleRequestVoteRequest(
      r: RequestVoteRequest
  ): URIO[Clock & Random, RequestVoteResult] =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < r.term then stepDown(r.term)
        else ZIO.succeed(currentTerm)
      votedFor <- stable.votedFor
      lastTerm <- logStore.lastTerm
      lastIndex <- logStore.lastIndex
      result <-
        if (
          currentTerm == r.term && (votedFor.contains(
            r.candidateId
          ) || votedFor.isEmpty) &&
          (r.lastLogTerm > lastTerm ||
            (r.lastLogTerm == lastTerm &&
              r.lastLogIndex >= lastIndex))
        )
        then
          resetElectionTimer.as(
            RequestVoteResult.Granted(memberId, currentTerm)
          )
        else ZIO.succeed(RequestVoteResult.Rejected(memberId, currentTerm))
    yield result

  def handleRequestVoteReply(r: RequestVoteResult) =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < r.term then stepDown(r.term)
        else ZIO.succeed(currentTerm)

      s <- state.get
      _ <- s match
        case candidate: State.Candidate =>
          r match
            case _: RequestVoteResult.Rejected => ZIO.unit
            case g: RequestVoteResult.Granted =>
              state.set(candidate.addVote(g.from))
        case _ => ZIO.unit
    yield ()

  def handleAppendEntriesRequest(
      r: AppendEntriesRequest[A]
  ): URIO[Clock & Random, AppendEntriesResult] =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < r.term then stepDown(r.term)
        else ZIO.succeed(currentTerm)

      result <-
        if currentTerm != r.term then
          ZIO.succeed(AppendEntriesResult.Failure(memberId, currentTerm))
        else
          for
            _ <- convertToFollower
            lastIndex <- logStore.lastIndex
            localPreviousLogTerm <- logStore.logTerm(r.previousIndex)
            result <-
              if r.previousIndex.isZero || (r.previousIndex <= lastIndex && localPreviousLogTerm == r.previousTerm)
              then
                for
                  // TODO: omptimize this code
                  matchIndex <- ZIO.foldLeft(r.entries)(
                    r.previousIndex.plusOne
                  )((index, entry) =>
                    for
                      logTerm <- logStore.logTerm(index)
                      _ <- ZIO.when(logTerm != entry.term)(
                        logStore.deleteFrom(index)
                      )
                      _ <- logStore.storeLog(entry)
                    yield index.plusOne
                  )

                  _ <- updateCommitIndex(commitIndex =>
                    Index.max(commitIndex, r.leaderCommitIndex)
                  )
                yield AppendEntriesResult.Success(
                  memberId,
                  currentTerm,
                  matchIndex
                )
              else
                ZIO.succeed(AppendEntriesResult.Failure(memberId, currentTerm))
          yield result
    yield result

  def handleAppendEntriesReply(r: AppendEntriesResult) =
    for
      currentTerm <- stable.currentTerm
      currentTerm <-
        if currentTerm < r.term then stepDown(r.term)
        else ZIO.succeed(currentTerm)

      s <- state.get
      _ <- s match
        case leader: State.Leader if currentTerm == r.term =>
          r match
            case AppendEntriesResult.Success(from, _, index) =>
              val currentIndex = leader.matchIndex.get(from)
              state.set(
                leader
                  .withMatchIndex(from, Index.max(currentIndex, index))
                  .withNextIndex(from, index.plusOne)
                  .withRPCDueNow(from)
              )
            case AppendEntriesResult.Failure(from, _) =>
              val nextIndex = leader.nextIndex.get(from)
              state.set(
                leader
                  .withNextIndex(from, Index.max(Index.one, nextIndex.minusOne))
                  .withRPCDueNow(from)
              )
        case _ => ZIO.unit
    yield ()

  def startNewElectionRule =
    def start =
      for
        currentTerm <- Stable.currentTerm
        _ <- Stable.newTerm(currentTerm.plusOne, Some(memberId))
        electionTimeout <- makeElectionTimeout
        _ <- state.update(s =>
          State.Candidate(
            RPCDue.makeNow(peers),
            1,
            s.commintIndex,
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
        lastIndex <- logStore.lastIndex
        _ <- state.set(
          Leader(
            NextIndex(lastIndex.plusOne),
            MatchIndex.empty,
            RPCDue.makeNever(peers),
            HeartbeatDue.empty,
            c.commintIndex,
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
          for
            nTerm <- logStore.logTerm(n)
            _ <- ZIO.when(nTerm == currentTerm)(
              state.set(l.withCommitIndex(Index.max(n, l.commintIndex)))
            )
          yield ()
        case _ => ZIO.unit
    yield ()

  def applyToStateMachineRule = 
    for
      s <- state.get
      newState <- applyToStateMachine(s)
      _ <- state.set(newState)
    yield ()

  def applyToStateMachine(state: State): UIO[State]  = 
    if state.commintIndex > state.lastApplied then 
      val state1 = state.increaseLatApplied
      for {
        command <- logStore.getLog(state1.lastApplied).map(_.get) //todo: Handle onot found
        // TODO: stateMachine.apply
        // TODO: leader only, complete promise
        (_, res) <- stateMachine.apply(command.command)
        _ <- state match
          case l: Leader => for {
                                map <- waitingResponse.getAndUpdate(_.removed(command.command))
                                promise = map.get(command.command).get //todo: handle not found ?
                                _ <- promise.succeed(res)
                            } yield ()
          case _ => ZIO.unit
        state2 <- applyToStateMachine(state1)
      } yield state2
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
                Index.min(l.commintIndex, lastIndex)
              )
            )

            _ <- state.set(
              l.withHeartbeaetDue(
                peer,
                now.plus((electionTimeout / 2).millis)
              )
            )
          yield ()
        case _ => ZIO.unit
    yield ()

  def sendAppendEntriesRule(peer: MemberId) =
    for
      s <- state.get
      now <- zio.clock.instant
      leaderLastIndex <- logStore.lastIndex
      _ <- s match
        case l: Leader
            if l.rpcDue
              .due(now, peer) && leaderLastIndex >= l.nextIndex.get(peer) =>
          val nextIndex = l.nextIndex.get(peer)
          val previousIndex = nextIndex.minusOne
          val lastIndex =
            Index.min(previousIndex.plus(batchSize), leaderLastIndex)
          val matchIndex = l.matchIndex.get(peer)

          for
            currentTerm <- stable.currentTerm
            previousTerm <- logStore.logTerm(previousIndex)
            entries <-
              if lastIndex < nextIndex then ZIO.succeed(List.empty)
              else logStore.getLogs(nextIndex, lastIndex)

            _ <- rpc.sendAppendEntires(
              peer,
              AppendEntriesRequest(
                currentTerm,
                memberId,
                previousIndex,
                previousTerm,
                entries,
                Index.min(l.commintIndex, lastIndex)
              )
            )

            _ <- state.set(
              l.withRPCDue(peer, now.plus(rpcTimeout))
                .withHeartbeaetDue(
                  peer,
                  now.plus((electionTimeout / 2).millis)
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
            lastIndex <- logStore.lastIndex
            lastIndexTerm <- logStore.logTerm(lastIndex)
            _ <- state.set(c.withRPCDue(peer, now.plus(rpcTimeout)))
            _ <- rpc.sendRequestVote(
              peer,
              RequestVoteRequest(
                currentTerm,
                memberId,
                lastIndex,
                lastIndexTerm
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

  def handleMessage(message: RPCMessage): URIO[Clock with Random, Unit] = {
    message match
      case r: RequestVoteRequest =>
        for {
          res <- handleRequestVoteRequest(r)
          // todo: send res
        } yield ()
      case r: RequestVoteResult => handleRequestVoteReply(r)
      case r: AppendEntriesRequest[A] =>
        for {
          res <- handleAppendEntriesRequest(r)
        } yield ()

      case r: AppendEntriesResult =>
        for {
          _ <- handleAppendEntriesReply(r)
        } yield ()
  }

  def handleCommand(command: A): ZIO[Any, Nothing, Unit] = 
    state.get.flatMap {
      case Leader(nextIndex, matchIndex, rpcDue, heartbeatDue, commintIndex, lastApplied) => 
        for 
          lastIndex <- logStore.lastIndex
          lastTerm <- logStore.lastTerm
          entry = LogEntry[A](command, lastTerm, lastIndex.plusOne) // todo: store in log storage
          _ <- logStore.storeLog(entry)
        yield ()
      case _ =>
        // TODO: die the promise...
        ZIO.dieMessage("Not a leader")
    }
  

  def handleStreamItem(item: StreamItem) =
    item match
      case Tick =>
        preRules &&& postRules
      case Message(message) =>
        for {
          _ <- preRules
          _ <- handleMessage(message)
          _ <- postRules
        } yield ()
      case commandMessage: CommandMessage[A] =>
        for {
          _ <- preRules
          _ <- handleCommand(commandMessage.message)
          _ <- postRules
        } yield ()

  def sendCommand(command: A): UIO[Response] =
    //todo: leader only
     for {
      promise <- Promise.make[Nothing, Response]
      _ <- waitingResponse.update(map => map + (command -> promise))
      _ <- commandsQueue.offer(CommandMessage(command, promise))
      res <- promise.await
    } yield (res)

  def run =
    val tick = ZStream.repeat(StreamItem.Tick)
    val messages = ZStream
      .fromQueue(rpc.incomingMessages)
      .map(Message(_))
    val commandMessage =
      ZStream.fromQueue(this.commandsQueue)
    ZStream
      .mergeAllUnbounded(16)(tick, messages, commandMessage)
      .foreach(handleStreamItem)

}
