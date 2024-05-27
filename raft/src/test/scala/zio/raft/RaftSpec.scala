package zio.raft

import zio.test.*
import zio.raft.RequestVoteResult
import zio.raft.HeartbeatRequest
import zio.raft.State.Candidate
import zio.raft.State.Follower
import zio.raft.State.Leader
import zio.ZIO
import zio.raft.StreamItem.CommandMessage
import zio.raft.Index.range
import zio.raft.AppendEntriesResult.Success
import zio.Scope

object RaftSpec extends ZIOSpecDefault:
  def makeRaft(memberId: MemberId, peers: Peers, enableSnapshot: Boolean) =
    (for
      stable <- Stable.makeInMemory
      logStore <- LogStore.makeInMemory[TestCommands]
      snapshotStore <- SnapshotStore.makeInMemory
      rpc <- zio.Queue
        .unbounded[(MemberId, RPCMessage[TestCommands])]
        .map(new MockRpc(_))
      raft <- Raft.make(
        memberId,
        peers,
        stable,
        logStore,
        snapshotStore,
        rpc,
        new TestStateMachine(0, enableSnapshot)
      )
    yield (raft, rpc))

  def isCandidate(raft: Raft[TestCommands]) =
    for s <- raft.state.get
    yield if s.isInstanceOf[Candidate] then true else false

  def isFollower(raft: Raft[TestCommands]) =
    for s <- raft.state.get
    yield if s.isInstanceOf[Follower] then true else false

  def expectFollower(raft: Raft[TestCommands]) =
    raft.state.get.flatMap:
      case f: Follower => ZIO.succeed(f)
      case _           => ZIO.die(new Exception("Expected follower"))

  def getLeader(raft: Raft[TestCommands]) =
    for s <- raft.state.get
    yield s match
      case Follower(commitIndex, lastApplied, electionTimeout, leaderId) =>
        leaderId
      case _: Candidate => None
      case Leader(
            nextIndex,
            matchIndex,
            heartbeatDue,
            replicationStatus,
            commitIndex,
            lastApplied
          ) =>
        Some(raft.memberId)

  def handleHeartbeat(
      raft: Raft[TestCommands],
      term: Term,
      leaderId: MemberId,
      commitIndex: Index
  ) =
    raft.handleStreamItem(
      StreamItem.Message[TestCommands](
        HeartbeatRequest(term, leaderId, commitIndex)
      )
    )

  def handleVoteGranted(
      raft: Raft[TestCommands],
      term: Term,
      memberId: MemberId
  ) =
    raft.handleStreamItem(
      StreamItem.Message[TestCommands](
        RequestVoteResult.Granted(memberId, term)
      )
    )

  def handelAppendEntries(
      raft: Raft[TestCommands],
      term: Term,
      leaderId: MemberId,
      previousIndex: Index,
      previousTerm: Term,
      entries: List[LogEntry[TestCommands]],
      leaderCommitIndex: Index
  ) =
    raft.handleStreamItem(
      StreamItem.Message[TestCommands](
        AppendEntriesRequest(
          term,
          leaderId,
          previousIndex,
          previousTerm,
          entries,
          leaderCommitIndex
        )
      )
    )

  def handleBootstrap(raft: Raft[TestCommands]) =
    raft.handleStreamItem(StreamItem.Bootstrap[TestCommands]())

  def handleTick(raft: Raft[TestCommands]) =
    raft.handleStreamItem(StreamItem.Tick[TestCommands]())

  def sendCommand(raft: Raft[TestCommands], commandArg: TestCommands) =
    for
      promiseArg <- zio.Promise.make[NotALeaderError, Int]
      _ <- raft.handleStreamItem(new CommandMessage[TestCommands] {
        val command: TestCommands = commandArg
        val promise: CommandPromise[Int] = promiseArg
      })
    yield ()

  def bootstrap(raft: Raft[TestCommands]) =
    for
      _ <- handleBootstrap(raft)
      _ <- handleVoteGranted(raft, Term(1), MemberId("peer2"))
      _ <- handleTick(raft)
    yield ()

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Raft Spec")(
    test("bootstrap") {
      for
        (raft, rpc) <- makeRaft(
          MemberId("peer1"),
          Array(MemberId("peer2"), MemberId("peer3")),
          false
        )
        _ <- handleBootstrap(raft)
        isCandidateAfterBootstarp <- isCandidate(raft)

        _ <- raft.state.get.debug

        messages <- rpc.queue.takeAll

        _ <- handleVoteGranted(raft, Term(1), MemberId("peer2"))
        isLeaderAfterGranted <- raft.isTheLeader

        expectedMessages: List[(MemberId, RPCMessage[TestCommands])] = List(
          MemberId("peer2") -> RequestVoteRequest(
            Term(1),
            MemberId("peer1"),
            Index(0),
            Term(0)
          ),
          MemberId("peer3") -> RequestVoteRequest(
            Term(1),
            MemberId("peer1"),
            Index(0),
            Term(0)
          )
        )
      yield assertTrue(
        messages == expectedMessages
      ) // isCandidateAfterBootstarp && isLeaderAfterGranted &&
    },
    test("check heartbeat is sent") {
      for
        (raft, rpc) <- makeRaft(
          MemberId("peer1"),
          Array(MemberId("peer2"), MemberId("peer3")),
          false
        )
        _ <- handleBootstrap(raft)
        _ <- handleVoteGranted(raft, Term(1), MemberId("peer2"))

        _ <- rpc.queue.takeAll

        _ <- handleTick(raft)

        messages <- rpc.queue.takeAll
        expectedMessage: List[(MemberId, RPCMessage[TestCommands])] = List(
          MemberId("peer2") -> HeartbeatRequest(
            Term(1),
            MemberId("peer1"),
            Index(0)
          ),
          MemberId("peer3") -> HeartbeatRequest(
            Term(1),
            MemberId("peer1"),
            Index(0)
          )
        )
      yield assertTrue(messages == expectedMessage)
    },
    test("become follower after heartbeat") {
      for
        (raft, _) <- makeRaft(
          MemberId("peer1"),
          Array(MemberId("peer2"), MemberId("peer3")),
          false
        )
        _ <- handleHeartbeat(raft, Term(1), MemberId("peer2"), Index(0))
        isFollower <- isFollower(raft)
        leader <- getLeader(raft)
      yield assertTrue(isFollower && leader == Some(MemberId("peer2")))
    },
    test("become follower after empty append entries") {
      for
        (raft, _) <- makeRaft(
          MemberId("peer1"),
          Array(MemberId("peer2"), MemberId("peer3")),
          false
        )
        _ <- handelAppendEntries(
          raft,
          Term(1),
          MemberId("peer2"),
          Index(0),
          Term(0),
          List.empty,
          Index(0)
        )
        isFollower <- isFollower(raft)
        leader <- getLeader(raft)
      yield assertTrue(isFollower && leader == Some(MemberId("peer2")))
    },
    test("become follower after append entries") {
      for
        (raft, _) <- makeRaft(
          MemberId("peer1"),
          Array(MemberId("peer2"), MemberId("peer3")),
          false
        )
        logEntry: LogEntry[TestCommands] = LogEntry(
          Increase,
          Term(1),
          Index(1)
        )
        _ <- handelAppendEntries(
          raft,
          Term(1),
          MemberId("peer2"),
          Index(0),
          Term(0),
          List(logEntry),
          Index(1)
        )
        follower <- expectFollower(raft)
        leader <- getLeader(raft)
      yield assertTrue(
        leader == Some(MemberId("peer2")) && follower.commitIndex == Index(
          1
        ) && follower.lastApplied == Index(1)
      )
    },
    test("leader send append entries") {
      for
        (raft, rpc) <- makeRaft(
          MemberId("peer1"),
          Array(MemberId("peer2"), MemberId("peer3")),
          false
        )
        _ <- bootstrap(raft)
        _ <- rpc.queue.takeAll

        _ <- sendCommand(raft, Increase)

        messages <- rpc.queue.takeAll
        expectedAppendEntry: RPCMessage[TestCommands] = AppendEntriesRequest(
          Term(1),
          MemberId("peer1"),
          Index(0),
          Term(0),
          List(LogEntry(Increase, Term(1), Index(1))),
          Index(0)
        )
        expectedMessages = List(
          MemberId("peer2") -> expectedAppendEntry,
          MemberId("peer3") -> expectedAppendEntry
        )
      yield assertTrue(messages == expectedMessages)
    },
    test("follower send append entries response") {
      for
        (raft, rpc) <- makeRaft(
          MemberId("peer1"),
          Array(MemberId("peer2"), MemberId("peer3")),
          false
        )

        _ <- handelAppendEntries(
          raft,
          Term(1),
          MemberId("peer2"),
          Index(0),
          Term(0),
          List(LogEntry(Increase, Term(1), Index(1))),
          Index(0)
        )

        messages <- rpc.queue.takeAll
        expectedMessages: List[(MemberId, RPCMessage[TestCommands])] = List(
          MemberId("peer2") -> AppendEntriesResult.Success(
            MemberId("peer1"),
            Term(1),
            Index(1)
          )
        )
      yield assertTrue(messages == expectedMessages)
    }
  )
end RaftSpec
