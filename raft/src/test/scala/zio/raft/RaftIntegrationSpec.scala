package zio.raft

import zio.ZIO
import zio.duration.*
import zio.logging.LogFormat
import zio.logging.LogLevel
import zio.logging.Logging
import zio.test.DefaultMutableRunnableSpec
import zio.test.*

object RaftIntegrationSpec extends MutableRunnableSpec(zio.clock.Clock.live):
  val logging = Logging.console(
    logLevel = LogLevel.Debug,
    format = LogFormat.ColoredLogFormat()
  ) >>> zio.logging.Logging.withRootLoggerName("raft")

  private def findTheNewLeader(
      currentLeader: Raft[TestCommands],
      raft1: Raft[TestCommands],
      raft2: Raft[TestCommands],
      raft3: Raft[TestCommands]
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
      currentLeader: Raft[TestCommands],
      raft1: Raft[TestCommands],
      raft2: Raft[TestCommands],
      raft3: Raft[TestCommands]
  ) =
    findTheNewLeader(currentLeader, raft1, raft2, raft3)
      .tap:
        case None    => ZIO.sleep(100.millis)
        case Some(_) => ZIO.unit
      .repeatUntil(_.isDefined)
      .map(_.get)

  private def makeRaft(enableSnapshot: Boolean = false) =
    for
      rpc <- TestRpc.make[TestCommands](3).toManaged_
      stateMachine1 = TestStateMachine.make(enableSnapshot)
      stateMachine2 = TestStateMachine.make(enableSnapshot)
      stateMachine3 = TestStateMachine.make(enableSnapshot)
      peers = Array(
        MemberId("peer1"),
        MemberId("peer2"),
        MemberId("peer3")
      )
      stable1 <- Stable.makeInMemoryManaged
      stable2 <- Stable.makeInMemoryManaged
      stable3 <- Stable.makeInMemoryManaged
      logStore1 <- LogStore.makeInMemoryManaged[TestCommands]
      logStore2 <- LogStore.makeInMemoryManaged[TestCommands]
      logStore3 <- LogStore.makeInMemoryManaged[TestCommands]
      snapshotStore1 <- SnapshotStore.makeInMemoryManaged
      snapshotStore2 <- SnapshotStore.makeInMemoryManaged
      snapshotStore3 <- SnapshotStore.makeInMemoryManaged
      raft1 <- Raft.makeManaged(
        MemberId("peer1"),
        peers.filter(_ != MemberId("peer1")),
        stable1,
        logStore1,
        snapshotStore1,
        rpc(0)._1,
        stateMachine1
      )
      raft2 <- Raft.makeManaged(
        MemberId("peer2"),
        peers.filter(_ != MemberId("peer2")),
        stable2,
        logStore2,
        snapshotStore2,
        rpc(1)._1,
        stateMachine2
      )
      raft3 <- Raft.makeManaged(
        MemberId("peer3"),
        peers.filter(_ != MemberId("peer3")),
        stable3,
        logStore3,
        snapshotStore3,
        rpc(2)._1,
        stateMachine3
      )
      _ <- raft1.bootstrap.toManaged_
    yield (
      raft1,
      rpc(0)._2,
      raft2,
      rpc(1)._2,
      raft3,
      rpc(2)._2
    )

  testM("raft"):
    for res <- makeRaft()
        .use:
          case (
                r1,
                killSwitch1,
                r2,
                killSwitch2,
                r3,
                killSwitch3
              ) => {
            for
              _ <- r1.sendCommand(Increase)
              _ <- r1.sendCommand(Increase)
              _ <- r1.sendCommand(Increase)
              x <- r1.sendCommand(Get)
            yield (x)
          }
        .provideLayer(zio.ZEnv.live ++ logging)
    yield assertTrue(res == 3)

  testM(
    "raft, make sure consensus is reached even when 1 node is disconnected"
  ):
    for res <- makeRaft()
        .use:
          case (
                r1,
                killSwitch1,
                r2,
                killSwitch2,
                r3,
                killSwitch3
              ) => {
            for
              _ <- killSwitch2.set(false)
              _ <- r1.sendCommand(Increase)
              _ <- r1.sendCommand(Increase)
              _ <- r1.sendCommand(Increase)
              x <- r1.sendCommand(Get)
            yield (x)
          }
        .provideLayer(zio.ZEnv.live ++ logging)
    yield assertTrue(res == 3)

  testM("raft, make sure leader is replaced"):
    for res <- makeRaft()
        .use:
          case (
                r1,
                killSwitch1,
                r2,
                killSwitch2,
                r3,
                killSwitch3
              ) => {
            for
              // stop the leader
              _ <- killSwitch1.set(false)

              // find the new leader
              leader <- waitForNewLeader(r1, r1, r2, r3).debug("new leader")
              _ <- leader.sendCommand(Increase)
              _ <- leader.sendCommand(Increase)
              _ <- leader.sendCommand(Increase)
              x <- leader.sendCommand(Get)
            yield (x)
          }
        .provideLayer(zio.ZEnv.live ++ logging)
    yield assertTrue(res == 3)
