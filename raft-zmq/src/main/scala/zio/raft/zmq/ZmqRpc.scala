package zio.raft.zmq

import zio.raft.{RPC, Command}
import zio.raft.RequestVoteResult
import zio.Queue
import zio.raft.AppendEntriesRequest
import zio.raft.RequestVoteRequest
import zio.UIO
import zio.raft.RPCMessage
import zio.raft.MemberId
import zio.raft.Command
import zio.raft.AppendEntriesResult
import zio.zmq.ZSocket
import zio.zmq.RoutingId
import zio.Managed
import zio.ZLayer
import scodec.bits.BitVector
import zio.ZIO
import zio.stream.ZStream
import scodec.Codec
import zio.logging.Logging
import zio.stream.ZTransducer

class ZmqRpc[A <: Command](server: ZSocket, clients: Map[MemberId, ZSocket], codec: Codec[A], clock: zio.clock.Clock.Service, log: zio.logging.Logger[String])
    extends RPC[A]:

  override def sendAppendEntires(
      peer: MemberId,
      request: AppendEntriesRequest[A]
  ): UIO[Unit] = 
    val client = clients(peer)
    val message = RpcMessageCodec.codec[A](codec).encode(request).require.toByteArray    

    for            
      sent <- client.sendImmediately(message).orElseSucceed(false)
      _ <- log.debug(s"sending entries to $peer $request").when(request.entries.nonEmpty && sent)
      //_ <- log.debug(s"sending heartbeat to $peer").when(request.entries.isEmpty && sent)
    yield ()

  override def sendRequestVoteResponse(
      candidateId: MemberId,
      response: RequestVoteResult[A]
  ): UIO[Unit] = 
    val client = clients(candidateId)
    val message = RpcMessageCodec.codec[A](codec).encode(response).require.toByteArray
    client.sendImmediately(message).ignore

  override def sendRequestVote(
      peer: MemberId,
      m: RequestVoteRequest[A]
  ): UIO[Unit] = 
    val client = clients(peer)
    val message = RpcMessageCodec.codec[A](codec).encode(m).require.toByteArray
    client.sendImmediately(message).ignore

  override def sendAppendEntriesResponse(
      leaderId: MemberId,
      response: AppendEntriesResult[A]
  ): UIO[Unit] = 
    val client = clients(leaderId)
    val message = RpcMessageCodec.codec[A](codec).encode(response).require.toByteArray
    client.sendImmediately(message).ignore

  override def incomingMessages = 
    server.stream.mapM { message =>      
      val bytes = message.data()
      val rpcMessage = RpcMessageCodec.codec[A](codec).decodeValue(BitVector(bytes)).toEither
      ZIO.succeed(rpcMessage)
    }
    .collectRight // TODO: log errors
    .transduce(RemoveDuplicate[A](clock)) // Because the raft messaging might be very chatty, we want to remove duplicates
    .catchAll(err => ZStream.die(err))    

object ZmqRpc:
  def makeManaged[A <: Command](bindAddress: String, peers: Map[MemberId, String], codec: Codec[A]) =
    for
      logger <- Managed.service[zio.logging.Logger[String]]      
      clock <- Managed.service[zio.clock.Clock.Service]
      
      clients <- Managed.foreach(peers)((peerId, peerAddress) =>
        for
          client <- ZSocket.client
          _ <- client.options.setImmediate(false).toManaged_
          _ <- client.connect(peerAddress).toManaged_
        yield (peerId, client)
      )

      server <- ZSocket.server            
      _ <- server.bind(bindAddress).toManaged_
    yield new ZmqRpc(server, clients.toMap, codec, clock, logger)
