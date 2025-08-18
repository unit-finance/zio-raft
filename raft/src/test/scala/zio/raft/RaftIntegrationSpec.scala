package zio.raft

import zio.test.*
import zio.test.TestAspect.withLiveClock
import zio.{ZIO, durationInt}
import zio.Ref
import zio.Scope

object RaftIntegrationSpec extends ZIOSpecDefault:

  private def findTheNewLeader(
      currentLeader: Raft[Int, TestCommands],
      raft1: Raft[Int, TestCommands],
      raft2: Raft[Int, TestCommands],
      raft3: Raft[Int, TestCommands]
  ) =
    for
      r1IsLeader <- raft1.isTheLeader
      r2IsLeader <- raft2.isTheLeader
      r3IsLeader <- raft3.isTheLeader
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

  private def makeRaft(enableSnapshot: Boolean = false): ZIO[Scope, Nothing, (Raft[Int, TestCommands], Ref[Boolean], Raft[Int, TestCommands], Ref[Boolean], Raft[Int, TestCommands], Ref[Boolean])] =
    for
      rpc <- TestRpc.make[TestCommands](3)
      stateMachine1 = TestStateMachine.make(enableSnapshot)
      stateMachine2 = TestStateMachine.make(enableSnapshot)
      stateMachine3 = TestStateMachine.make(enableSnapshot)
      peers = Array(
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
      raft1 <- Raft.makeScoped(
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
      _ <- raft1.bootstrap
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

        _ <- r1.sendCommand(Increase)
        _ <- r1.sendCommand(Increase)
        _ <- r1.sendCommand(Increase)
        x <- r1.sendCommand(Get)
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
        _ <- r1.sendCommand(Increase)
        _ <- r1.sendCommand(Increase)
        _ <- r1.sendCommand(Increase)
        x <- r1.sendCommand(Get)
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
        _ <- leader.sendCommand(Increase)
        _ <- leader.sendCommand(Increase)
        _ <- leader.sendCommand(Increase)
        x <- leader.sendCommand(Get)
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

        _ <- r1.sendCommand(Increase)

        // stop the leader
        _ <- killSwitch1.set(false)

        // find the new leader
        leader <- waitForNewLeader(r1, r1, r2, r3)
        _ <- leader.sendCommand(Increase)
        actualState <- leader.sendCommand(Get)
        logs = leader.logStore.stream(Index.one, Index(10))
        actualLogs <- logs.runCollect.map(_.map(_.command))
        expectedLogs = List(None, Some(Increase), None, Some(Increase), Some(Get))
      yield assertTrue(actualLogs == expectedLogs && actualState == 2)
    },
    test("read returns the correct state with multiple writes") {
      for
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft()

        _ <- r1.sendCommand(Increase)
        _ <- r1.sendCommand(Increase)  
        _ <- r1.sendCommand(Increase)
        
        readResult1 <- r1.readState
        
        _ <- r1.sendCommand(Increase)

        readResult2 <- r1.readState
      yield assertTrue(readResult1 == 3 && readResult2 == 4)
    },
    test("read returns the correct state when there are no writes") {
      for
        (
          r1,
          killSwitch1,
          r2,
          killSwitch2,
          r3,
          killSwitch3
        ) <- makeRaft()
        
        // Read the state without any commands (should be initial state = 0)
        readResult <- r1.readState
        
      yield assertTrue(readResult == 0)
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
        
        // Stop the leader (r1) to make r2 and r3 followers/candidates
        _ <- killSwitch1.set(false)
        
        // Try to read from a non-leader node (should fail with NotALeaderError)
        readResult <- r2.readState.either
        
      yield assertTrue(
        readResult.isLeft && readResult.left.exists(_.isInstanceOf[NotALeaderError])
      )
    }
  ) @@ withLiveClock
