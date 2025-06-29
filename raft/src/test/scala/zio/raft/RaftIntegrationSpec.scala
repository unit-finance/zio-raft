package zio.raft

import zio.test.*
import zio.test.TestAspect.withLiveClock
import zio.{ZIO, durationInt}

object RaftIntegrationSpec extends ZIOSpecDefault:

  private def findTheNewLeader(
      currentLeader: Raft[Int, Nothing, TestCommands],
      raft1: Raft[Int, Nothing, TestCommands],
      raft2: Raft[Int, Nothing, TestCommands],
      raft3: Raft[Int, Nothing, TestCommands]
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
      currentLeader: Raft[Int, Nothing, TestCommands],
      raft1: Raft[Int, Nothing, TestCommands],
      raft2: Raft[Int, Nothing, TestCommands],
      raft3: Raft[Int, Nothing, TestCommands]
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
        stateMachine1,
        0
      )
      raft2 <- Raft.makeScoped(
        MemberId("peer2"),
        peers.filter(_ != MemberId("peer2")),
        stable2,
        logStore2,
        snapshotStore2,
        rpc(1)._1,
        stateMachine2,
        0
      )
      raft3 <- Raft.makeScoped(
        MemberId("peer3"),
        peers.filter(_ != MemberId("peer3")),
        stable3,
        logStore3,
        snapshotStore3,
        rpc(2)._1,
        stateMachine3,
        0
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
        leader <- waitForNewLeader(r1, r1, r2, r3).debug("new leader")
        _ <- leader.sendCommand(Increase)
        _ <- leader.sendCommand(Increase)
        _ <- leader.sendCommand(Increase)
        x <- leader.sendCommand(Get)
      yield assertTrue(x == 3)
    }
  ) @@ withLiveClock
