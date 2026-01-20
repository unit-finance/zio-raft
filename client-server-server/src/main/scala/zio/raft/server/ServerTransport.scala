package zio.raft.server

import zio.*
import zio.stream.*
import zio.raft.protocol.*
import zio.zmq.{RoutingId, ZContext, ZSocket}
import scodec.bits.BitVector
import zio.raft.protocol.Codecs.{clientMessageCodec, serverMessageCodec}
import zio.zmq.ignoreHostUnreachable

/** Transport abstraction for server-client communication.
  */
trait ServerTransport:
  def disconnect(routingId: RoutingId): Task[Unit]
  def sendMessage(routingId: RoutingId, message: ServerMessage): Task[Unit]
  def incomingMessages: ZStream[Any, Throwable, ServerTransport.IncomingMessage]

object ServerTransport:

  /** Incoming message from a client with routing information.
    */
  case class IncomingMessage(
    routingId: RoutingId,
    message: ClientMessage
  )

  /** Create a ZeroMQ-based server transport.
    */
  def make(config: ServerConfig): ZIO[ZContext & Scope, Throwable, ServerTransport] =
    for
      socket <- ZSocket.server
      _ <- socket.options.setDisconnectMessage(
        clientMessageCodec.encode(ConnectionClosed).require.toByteArray
      )
      _ <- socket.options.setLinger(10000) // Allow ten seconds to process messages from peer before terminating
      _ <- socket.options.setHighWatermark(200000, 200000)
      _ <- socket.bind(config.bindAddress)
      transport = new Zmq(socket)
    yield transport

  /** ZeroMQ-based implementation of ServerTransport.
    */
  private[server] class Zmq(socket: ZSocket) extends ServerTransport:
    override def disconnect(routingId: RoutingId): Task[Unit] =
      for
        _ <- socket.disconnectPeer(routingId)
      yield ()

    override def sendMessage(routingId: RoutingId, message: ServerMessage): Task[Unit] =
      for
        bytes <- ZIO.attempt(serverMessageCodec.encode(message).require.toByteArray)
        _ <- socket.sendImmediately(routingId, bytes).unit.ignoreHostUnreachable
      yield ()

    override def incomingMessages: ZStream[Any, Throwable, IncomingMessage] =
      socket.stream.mapZIO { msg =>
        ZIO.logDebug(s"[incomingMessages] Received message: $msg") *>
        ZIO.attempt(
          IncomingMessage(RoutingId(msg.getRoutingId), clientMessageCodec.decode(BitVector(msg.data())).require.value)
        )
      }
