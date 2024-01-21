package zio.raft

import zio.test.DefaultMutableRunnableSpec
import zio.test.*
import zio.ZIO
import zio.logging.Logging
import zio.logging.LogLevel
import zio.logging.LogFormat
import zio.duration.*

object RaftIntegrationSpec extends DefaultMutableRunnableSpec:
    val logging = Logging.console(logLevel = LogLevel.Debug, format = LogFormat.ColoredLogFormat()) >>> zio.logging.Logging.withRootLoggerName("raft")
    private def makeRaft =
        for 
            rpc <- TestRpc.make[TestCommands](3).toManaged_
                    stateMachine1 = TestStateMachine.make
                    stateMachine2 = TestStateMachine.make
                    stateMachine3 = TestStateMachine.make
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
            raft1 <- Raft.makeManaged(
                MemberId("peer1"),
                peers.filter(_ != MemberId("peer1")),
                stable1,
                logStore1,
                rpc(0)._1,
                stateMachine1
            )
            raft2 <- Raft.makeManaged(
                MemberId("peer2"),
                peers.filter(_ != MemberId("peer2")),
                stable2,
                logStore2,
                rpc(1)._1,
                stateMachine2
            )
            raft3 <- Raft.makeManaged(
                MemberId("peer3"),
                peers.filter(_ != MemberId("peer3")),
                stable3,
                logStore3,
                rpc(2)._1,
                stateMachine3
            )
        yield (raft1, rpc(0)._1, rpc(0)._2, raft2, rpc(1)._1, rpc(1)._2 , raft3, rpc(2)._1, rpc(2)._2)
    testM("raft"):
        for       
            _ <- ZIO.unit
            res <- makeRaft.use{
            case (r1,rpc1, killSwitch1, r2, rpc2, killSwitch2, r3, rpc3, killSwitch3) => {
                for 
                    _ <- ZIO.unit
                    
                    _ <- ZIO.sleep(5.seconds).provideLayer(zio.clock.Clock.live)
                    r1IsLeader <- r1.isTheLeader.debug("r1 is leader")
                    r2IsLeader <- r2.isTheLeader.debug("r2 is leader")
                    r3IsLeader <- r3.isTheLeader.debug("r3 is leader")
                    leader = if r1IsLeader then r1 else if r2IsLeader then r2 else if r3IsLeader then r3 else throw new Exception("no leader")
                    _ <- leader.sendCommand(Increase)
                    _ <- leader.sendCommand(Increase)
                    _ <- leader.sendCommand(Increase)
                    x <- leader.sendCommand(Get)
                yield (x)
            }
            }.provideLayer(zio.ZEnv.live ++ logging)
        yield assertTrue(res == 3)
  
