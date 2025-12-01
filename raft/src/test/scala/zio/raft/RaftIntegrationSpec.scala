package zio.raft

import zio.test.*
import zio.test.TestAspect.withLiveClock
import zio.{Promise, ZIO, durationInt}
import zio.raft.LogEntry.NoopLogEntry
import zio.raft.LogEntry.CommandLogEntry
import zio.LogLevel
import zio.ZLogger
import zio.Cause
import zio.FiberId
import zio.FiberRefs
import zio.LogSpan
import zio.Trace
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

object RaftIntegrationSpec extends ZIOSpecDefault:

  // We use TestLogger instead of ZTestLogger because ZTestLogger can cause duplicated log lines which causes flakiness in our tests.
  class TestLogger extends ZLogger[String, Unit]:
    val messages: ConcurrentLinkedQueue[String] = new ConcurrentLinkedQueue()
    override def apply(
      trace: Trace,
      fiberId: FiberId,
      logLevel: LogLevel,
      message: () => String,
      cause: Cause[Any],
      context: FiberRefs,
      spans: List[LogSpan],
      annotations: Map[String, String]
    ): Unit =
      messages.add(message())

    def getMessages: List[String] = messages.asScala.toList

  private def findTheNewLeader(
    currentLeader: Raft[Int, TestCommands],
    raft1: Raft[Int, TestCommands],
    raft2: Raft[Int, TestCommands],
    raft3: Raft[Int, TestCommands]
  ) =
    for
      r1IsLeader <- raft1.isLeader
      r2IsLeader <- raft2.isLeader
      r3IsLeader <- raft3.isLeader
    yield
      if r3IsLeader && raft3 != currentLeader then Some(raft3)
      else if r2IsLeader && raft2 != currentLeader then Some(raft2)
      else if r1IsLeader && raft1 != currentLeader then Some(raft1)
      else None

  private def waitForNewLeader(
    currentLeader: Raft[Int, TestCommands],
    raft1: Raft[Int, TestCommands],
    raft2: Raft[Int, TestCommands],
    raft3: Raft[Int, TestCommands]
  ) =
    findTheNewLeader(currentLeader, raft1, raft2, raft3)
      .tap:
        case None    => ZIO.sleep(100.millis)
        case Some(_) => ZIO.unit
      .repeatUntil(_.isDefined)
      .map(_.get)

  private def makeRaft(enableSnapshot: Boolean = false) =
    for
      rpc <- TestRpc.make[TestCommands](3)
      stateMachine1 = TestStateMachine.make(enableSnapshot)
      stateMachine2 = TestStateMachine.make(enableSnapshot)
      stateMachine3 = TestStateMachine.make(enableSnapshot)
      peers = Set(
        MemberId("peer1"),
        MemberId("peer2"),
        MemberId("peer3")
      )
      stable1 <- Stable.makeInMemory
      stable2 <- Stable.makeInMemory
      stable3 <- Stable.makeInMemory
      logStore1 <- LogStore.makeInMemory[TestCommands]
      logStore2 <- LogStore.makeInMemory[TestCommands]
      logStore3 <- LogStore.makeInMemory[TestCommands]
      snapshotStore1 <- SnapshotStore.makeInMemory
      snapshotStore2 <- SnapshotStore.makeInMemory
      snapshotStore3 <- SnapshotStore.makeInMemory
      raft1 <- Raft.makeScoped[Int, TestCommands](
        MemberId("peer1"),
        peers.filter(_ != MemberId("peer1")),
        stable1,
        logStore1,
        snapshotStore1,
        rpc(0)._1,
        stateMachine1
      )
      raft2 <- Raft.makeScoped(
        MemberId("peer2"),
        peers.filter(_ != MemberId("peer2")),
        stable2,
        logStore2,
        snapshotStore2,
        rpc(1)._1,
        stateMachine2
      )
      raft3 <- Raft.makeScoped(
        MemberId("peer3"),
        peers.filter(_ != MemberId("peer3")),
        stable3,
        logStore3,
        snapshotStore3,
        rpc(2)._1,
        stateMachine3
      )
      // Drain raft actions to execute continuations during tests
      _ <- raft1.raftActions.foreach {
        case zio.raft.RaftAction.CommandContinuation(effect) => effect
        case _                                               => ZIO.unit
      }.forkScoped
      _ <- raft2.raftActions.foreach {
        case zio.raft.RaftAction.CommandContinuation(effect) => effect
        case _                                               => ZIO.unit
      }.forkScoped
      _ <- raft3.raftActions.foreach {
        case zio.raft.RaftAction.CommandContinuation(effect) => effect
        case _                                               => ZIO.unit
      }.forkScoped
      _ <- raft1.bootstrap
      _ <- (zio.Clock.sleep(Raft.electionTimeout.millis) *> raft1.isLeader).repeatUntil(identity)
    yield (
      raft1,
      rpc(0)._2,
      raft2,
      rpc(1)._2,
      raft3,
      rpc(2)._2
    )

  def spec = suite("Raft Integration Spec")(
    test("raft") {
      for
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft()

        _ <- r1.sendCommand(Increase, _ => ZIO.unit)
        _ <- r1.sendCommand(Increase, _ => ZIO.unit)
        _ <- r1.sendCommand(Increase, _ => ZIO.unit)
        p <- Promise.make[Nothing, Int]
        _ <- r1.sendCommand(Get, {
          case Right(v: Int) => p.succeed(v).unit
          case Left(_)  => ZIO.unit
        })
        x <- p.await
      yield assertTrue(x == 3)
    },
    test(
      "raft, make sure consensus is reached even when 1 node is disconnected"
    ) {
      for
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft()
        _ <- killSwitch2.set(false)
        _ <- r1.sendCommand(Increase, _ => ZIO.unit)
        _ <- r1.sendCommand(Increase, _ => ZIO.unit)
        _ <- r1.sendCommand(Increase, _ => ZIO.unit)
        p <- Promise.make[Nothing, Int]
        _ <- r1.sendCommand(Get, {
          case Right(v: Int) => p.succeed(v).unit
          case Left(_)  => ZIO.unit
        })
        x <- p.await
      yield assertTrue(x == 3)
    },
    test("raft, make sure leader is replaced") {
      for
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft()
        // stop the leader
        _ <- killSwitch1.set(false)

        // find the new leader
        leader <- waitForNewLeader(r1, r1, r2, r3)
        _ <- leader.sendCommand(Increase, _ => ZIO.unit)
        _ <- leader.sendCommand(Increase, _ => ZIO.unit)
        _ <- leader.sendCommand(Increase, _ => ZIO.unit)
        p <- Promise.make[Nothing, Int]
        _ <- leader.sendCommand(Get, {
          case Right(v: Int) => p.succeed(v).unit
          case Left(_)  => ZIO.unit
        })
        x <- p.await
      yield assertTrue(x == 3)
    },
    test("raft, noop command sent after leader elected") {
      for
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft()

        // Ensure the first Increase is fully applied before killing the leader
        inc1Done <- Promise.make[Nothing, Unit]
        _ <- r1.sendCommand(Increase, _ => inc1Done.succeed(()).unit)
        _ <- inc1Done.await

        // stop the leader
        _ <- killSwitch1.set(false)

        // find the new leader
        leader <- waitForNewLeader(r1, r1, r2, r3)
        // Ensure the second Increase is fully applied before reading logs/state
        inc2Done <- Promise.make[Nothing, Unit]
        _ <- leader.sendCommand(Increase, _ => inc2Done.succeed(()).unit)
        _ <- inc2Done.await
        pp <- Promise.make[Nothing, Int]
        _ <- leader.sendCommand(Get, {
          case Right(v: Int) => pp.succeed(v).unit
          case Left(_)  => ZIO.unit
        })
        actualState <- pp.await
        logs = leader.logStore.stream(Index.one, Index(10))
        actualLogs <- logs.runCollect.map(_.map {
          case a: NoopLogEntry       => None
          case a: CommandLogEntry[?] => Some(a.command)
        }.toList)
        expectedLogs = List(None, Some(Increase), None, Some(Increase), Some(Get))
      yield assertTrue(actualLogs == expectedLogs && actualState == 2)
    },
    test("read returns the correct state with multiple writes") {
      for
        testLogger <- ZIO.succeed(new TestLogger())
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft().provideSomeLayer(zio.Runtime.removeDefaultLoggers >>> zio.Runtime.addLogger(testLogger))

        // Making sure we call readState while there are queued write commands is difficult,
        // we use this approach to make sure there are some unhandled commands before we call readState, hopefully it won't be too flaky
        _ <- r1.sendCommand(Increase, _ => ZIO.unit).fork.repeatN(99)

        readResult1 <- r1.readState

        messages = testLogger.getMessages
        pendingHeartbeatLogCount = messages.count(_.contains("memberId=MemberId(peer1) read pending heartbeat"))
        pendingCommandLogCount = messages.count(_.contains("memberId=MemberId(peer1) read pending command"))
      yield assertTrue(readResult1 > 0) && assertTrue(pendingHeartbeatLogCount == 0) && assertTrue(
        pendingCommandLogCount == 1
      )
    } @@ TestAspect.flaky, // TODO (eran): because of the way this test is structured it is currently flaky, we'll need to find another way to send commands so the readState will have pending commands

    test("read returns the correct state when there are no pending writes.") {
      for
        testLogger <- ZIO.succeed(new TestLogger())
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft().provideSomeLayer(zio.Runtime.removeDefaultLoggers >>> zio.Runtime.addLogger(testLogger))

        // Ensure write is fully applied so no pending writes remain
        applied <- Promise.make[Nothing, Unit]
        _ <- r1.sendCommand(Increase, _ => applied.succeed(()).unit)
        _ <- applied.await

        // When this runs we should have no writes in the queue since the sendCommand call is blocking
        readResult <- r1.readState

        // verify read waits for heartbeat and not a write/noop command
        messages = testLogger.getMessages
        pendingHeartbeatLogCount = messages.count(_.contains("memberId=MemberId(peer1) read pending heartbeat"))
        pendingCommandLogCount = messages.count(_.contains("memberId=MemberId(peer1) read pending command"))
      yield assertTrue(readResult == 1) && assertTrue(pendingHeartbeatLogCount == 1) && assertTrue(
        pendingCommandLogCount == 0
      )
    },
    test("read returns the correct state when there are no writes and one follower is down.") {
      for
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft()

        _ <- r1.sendCommand(Increase, _ => ZIO.unit)
        _ <- killSwitch2.set(false)
        readResult <- r1.readState
      yield assertTrue(readResult == 1)
    },
    test("read fails when not leader") {
      for
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft()

        // Try to read from a non-leader node (should fail with NotALeaderError)
        readResult <- r2.readState.either
      yield assertTrue(
        readResult.isLeft && readResult.left.exists(_.isInstanceOf[NotALeaderError])
      )
    },
    test("isolated leader cannot apply commands") {
      for
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft()

        // Verify r1 is the leader initially
        _ <- r1.sendCommand(Increase, _ => ZIO.unit)
        initialState <- r1.readState

        // Isolate the leader (r1) from the rest of the cluster
        _ <- killSwitch1.set(false)

        // Try to send a command to the isolated leader
        // This can either timeout (if leader hasn't detected isolation yet)
        // or fail with NotALeaderError (if leader has stepped down)
        p <- Promise.make[Nothing, Either[NotALeaderError, Int]]
        _ <- r1.sendCommand(Increase, r => p.succeed(r.asInstanceOf[Either[NotALeaderError, Int]]).unit)
        commandResult <- p.await.timeout(2.seconds)
      yield assertTrue(
        initialState == 1 && // Verify initial command worked
          (commandResult match
              case None               => true // Command timed out - leader couldn't commit
              case Some(Left(_))      => true // Leader stepped down due to isolation
              case Some(Right(_))     => false
          )
      )
    } @@ TestAspect.timeout(5.seconds)
  ) @@ withLiveClock
end RaftIntegrationSpec
