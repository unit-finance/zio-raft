package zio.raft

import zio.ZIO
import zio.duration.*
import zio.logging.LogFormat
import zio.logging.LogLevel
import zio.logging.Logging
import zio.test.DefaultMutableRunnableSpec
import zio.test.*

object RaftIntegrationSpec extends MutableRunnableSpec(zio.clock.Clock.live):
    val logging = Logging.console(logLevel = LogLevel.Debug, format = LogFormat.ColoredLogFormat()) >>> zio.logging.Logging.withRootLoggerName("raft")
    private def findTheLeader(raft1: Raft[TestCommands], raft2: Raft[TestCommands], raft3: Raft[TestCommands]) =
        for 
            r1IsLeader <- raft1.isTheLeader
            r2IsLeader <- raft2.isTheLeader
            r3IsLeader <- raft3.isTheLeader
        yield if r3IsLeader then raft3 else if r2IsLeader then raft2 else if r1IsLeader then raft1 else throw new Exception("no leader")

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
            res <- makeRaft.use{
            case (r1,rpc1, killSwitch1, r2, rpc2, killSwitch2, r3, rpc3, killSwitch3) => {
                for 
                    _ <- ZIO.sleep(5.seconds).provideLayer(zio.clock.Clock.live)
                    leader <- findTheLeader(r1, r2, r3)
                    _ <- leader.sendCommand(Increase)
                    _ <- leader.sendCommand(Increase)
                    _ <- leader.sendCommand(Increase)
                    x <- leader.sendCommand(Get)
                yield (x)
            }
            }.provideLayer(zio.ZEnv.live ++ logging)
        yield assertTrue(res == 3)
    
    testM("raft, make sure consensus is reached even when 1 node is disconnected"):
        for       
            res <- makeRaft.use{
                case (r1,rpc1, killSwitch1, r2, rpc2, killSwitch2, r3, rpc3, killSwitch3) => {
                for 
                    _ <- ZIO.sleep(5.seconds).provideLayer(zio.clock.Clock.live)
                    _ <- killSwitch1.set(false)
                    leader <- findTheLeader(r1, r2, r3)
                    _ <- leader.sendCommand(Increase)
                    _ <- leader.sendCommand(Increase)
                    _ <- leader.sendCommand(Increase)
                    x <- leader.sendCommand(Get)
                yield (x)
            }
            }.provideLayer(zio.ZEnv.live ++ logging)
        yield assertTrue(res == 3)
            
                
    testM("raft, make sure leader is replaced"):
            for       
                res <- makeRaft.use{
                    case (r1,rpc1, killSwitch1, r2, rpc2, killSwitch2, r3, rpc3, killSwitch3) => {
                    for 
                        _ <- ZIO.sleep(5.seconds).provideLayer(zio.clock.Clock.live)
                        
                        r1IsLeader <- r1.isTheLeader.debug("r1 is leader")
                        r2IsLeader <- r2.isTheLeader.debug("r2 is leader")
                        r3IsLeader <- r3.isTheLeader.debug("r3 is leader")
                        beforeLeader <- findTheLeader(r1, r2, r3)
                        _ <- ZIO.debug(s"before leader is ${beforeLeader.getMemberId}")
                        //stop the leader
                        _ <- if r1IsLeader then killSwitch1.set(false) else if r2IsLeader then killSwitch2.set(false) else if r3IsLeader then killSwitch3.set(false) else throw new Exception("no leader")
                        _ <- ZIO.sleep(20.seconds).provideLayer(zio.clock.Clock.live)
                        // find the new leader
                        leaders <- (ZIO.foreach(List(r1,r2,r3))(r => r.isTheLeader.map(res => (r, res))).map(_.filter(_._2).map(_._1)).map(_.filter(_ != beforeLeader)).map(_.headOption) <* ZIO.sleep(10.second)).repeatUntil(_.isDefined)
                        leader = leaders.get
                        _ <- leader.sendCommand(Increase)
                        _ <- leader.sendCommand(Increase)
                        _ <- leader.sendCommand(Increase)
                        x <- leader.sendCommand(Get)
                    yield (x)
                }
                }.provideLayer(zio.ZEnv.live ++ logging)
            yield assertTrue(res == 3)

