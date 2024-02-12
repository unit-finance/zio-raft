package zio.raft

import zio.*
import zio.stream.ZStream

object TestRpc:

 def make[A <: Command](numberOfPeers: Int): ZIO[Any, Nothing, List[Tuple2[RPC[A], Ref[Boolean]]]] = 
    for 
      peer1 <- zio.Queue.unbounded[RPCMessage[A]]
      peer1Responding <- zio.Ref.make(true)
      peer2 <- zio.Queue.unbounded[RPCMessage[A]]
      peer2Responding <- zio.Ref.make(true)
      peer3 <- zio.Queue.unbounded[RPCMessage[A]]
      peer3Responding <- zio.Ref.make(true)
      peer1Peers = Map(
        MemberId("peer2") -> peer2,
        MemberId("peer3") -> peer3
      )
      peer1PeersRef <- zio.Ref.make(peer1Peers)
      peer2Peers = Map(
        MemberId("peer1") -> peer1,
        MemberId("peer3") -> peer3
      )
      peer2PeersRef <- zio.Ref.make(peer2Peers)
      peer3Peers = Map(
        MemberId("peer1") -> peer1,
        MemberId("peer2") -> peer2
      )
      peer3PeersRef <- zio.Ref.make(peer3Peers)

      
    yield List(
      (TestRpc(peer1, peer1Responding, peer1PeersRef), peer1Responding),
      (TestRpc(peer2, peer2Responding, peer2PeersRef), peer2Responding),
      (TestRpc(peer3, peer3Responding, peer3PeersRef), peer3Responding)
      )
    

case class TestRpc[A <: Command](myQueue: zio.Queue[RPCMessage[A]], isResponding: zio.Ref[Boolean], peers: zio.Ref[Map[MemberId, zio.Queue[RPCMessage[A]]]]) extends RPC[A]:
 override def sendRequestVoteResponse(
     candidateId: MemberId,
     response: RequestVoteResult[A]
 ): UIO[Unit] = peers.get.flatMap(_.get(candidateId).get.offer(response)).unit.unlessM(isResponding.get.map(!_))
 override def sendAppendEntriesResponse(
     leaderId: MemberId,
     response: AppendEntriesResult[A]
 ): UIO[Unit] = 
    peers.get.flatMap(_.get(leaderId).get.offer(response)).unit.unlessM(isResponding.get.map(!_))
 override def sendAppendEntires(
     peer: MemberId,
     request: AppendEntriesRequest[A]
 ): UIO[Unit] = 
    peers.get.flatMap(_.get(peer).get.offer(request)).unit.unlessM(isResponding.get.map(!_))
 override def sendRequestVote(peer: MemberId, m: RequestVoteRequest[A]): UIO[Unit] = 
    peers.get.flatMap(_.get(peer).get.offer(m)).unit.unlessM(isResponding.get.map(!_))
 override def incomingMessages: ZStream[Any, Nothing, RPCMessage[A]] = 
    ZStream.fromQueue(myQueue)
    .filterM(x => isResponding.get)