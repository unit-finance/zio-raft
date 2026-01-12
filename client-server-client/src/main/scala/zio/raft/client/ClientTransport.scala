package zio.raft.client

import zio.*
import zio.stream.*
import zio.raft.protocol.*
import scodec.bits.BitVector
import zio.zmq.{ZContext, ZSocket}
import zio.raft.protocol.Codecs.{clientMessageCodec, serverMessageCodec}

/** Transport abstraction for client-server communication.
  */
trait ClientTransport {
  def connect(address: String): ZIO[Any, Throwable, Unit]
  def disconnect(): ZIO[Any, Throwable, Unit]
  def sendMessage(message: ClientMessage): ZIO[Any, Throwable, Unit]
  def incomingMessages: ZStream[Any, Throwable, ServerMessage]
}

object ClientTransport {

  /** Create a ZeroMQ-based client transport.
    */
  def make(config: ClientConfig): ZIO[ZContext & Scope, Throwable, ClientTransport] =
    for {
      socket <- ZSocket.client
      _ <- socket.options.setLinger(0)
      _ <- socket.options.setHeartbeat(1.seconds, 10.second, 30.second)
      timeoutConnectionClosed = serverMessageCodec
        .encode(SessionClosed(SessionCloseReason.ConnectionClosed, None))
        .require
        .toByteArray
      _ <- socket.options.setHiccupMessage(timeoutConnectionClosed)
      _ <- socket.options.setHighWatermark(200000, 200000)
      lastAddressRef <- Ref.make(Option.empty[String])
      transport = new Zmq(socket, lastAddressRef)
    } yield transport

  /** ZeroMQ-based implementation of ClientTransport.
    */
  private[client] class Zmq(socket: ZSocket, lastAddressRef: Ref[Option[String]])
      extends ClientTransport {
    override def connect(address: String): ZIO[Any, Throwable, Unit] =
      for {
        _ <- lastAddressRef.set(Some(address))
        _ <- socket.connect(address)
      } yield ()

    override def disconnect(): ZIO[Any, Throwable, Unit] =
      for {
        lastAddress <- lastAddressRef.get
        _ <- lastAddress match {
          case Some(address) => socket.disconnect(address)
          case None          => ZIO.unit
        }
        _ <- lastAddressRef.set(None)
      } yield ()

    override def sendMessage(message: ClientMessage): ZIO[Any, Throwable, Unit] =
      for {
        bytes <- ZIO.attempt(clientMessageCodec.encode(message).require.toByteArray)
        _ <- socket.sendImmediately(bytes)
      } yield ()

    override def incomingMessages: ZStream[Any, Throwable, ServerMessage] =
      socket.stream.mapZIO { msg =>
        ZIO.attempt(serverMessageCodec.decode(BitVector(msg.data)).require.value)
      }
  }
}
