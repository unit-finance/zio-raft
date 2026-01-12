package zio.zmq

import zio.ZIO
import scalanative.unsafe
import scalanative.unsafe.Zone
import scalanative.libc.string.memcpy
import scalanative.unsafe.UnsafeRichArray
import scala.scalanative.unsigned.UInt
import zio.ChunkBuilder
import zio.Chunk
import zio.stream.ZStream
import zio.Scope
import scala.annotation.tailrec

class ZSocket(socket: zmq.Socket):
  def stream: ZStream[Any, ZMQException, Message] = stream(chunkSize = 100)

  def receive: zio.ZIO[Any, zio.zmq.ZMQException, Message] =
    ZIO.attemptBlocking {
      val msg = unsafe.stackalloc[zmq.Msg]()
      zmq.zmq_msg_init(msg)
      zmq.zmq_msg_recv(msg, socket, 0) match
        case -1 =>
          zmq.zmq_msg_close(msg)
          val errno = zmq.zmq_errno()
          throw new ZMQException(errno)
        case 0 =>
          zmq.zmq_msg_close(msg)
          val routingId = zmq.zmq_msg_routing_id(msg)
          Message(Array.empty, RoutingId(routingId.toInt))
        case _ =>
          val routingId = zmq.zmq_msg_routing_id(msg)
          val dataPtr = zmq.zmq_msg_data(msg)
          val dataSize = zmq.zmq_msg_size(msg)
          val data = Array.ofDim[Byte](dataSize.toInt)
          memcpy(data.at(0), dataPtr, dataSize)

          zmq.zmq_msg_close(msg)
          Message(data, RoutingId(routingId.toInt))
    }
      .refineToOrDie[ZMQException]
      .retryWhile(_.getErrorCode() == ZError.EAGAIN)

  def send(routingId: zio.zmq.RoutingId, bytes: Array[Byte]): zio.ZIO[Any, zio.zmq.ZMQException, Unit] =
    ZIO.attemptBlocking {
      val msg = unsafe.stackalloc[zmq.Msg]()
      val size = UInt.valueOf(bytes.length)
      zmq.zmq_msg_init_size(msg, size)
      zmq.zmq_msg_set_routing_id(msg, UInt.valueOf(routingId.value))

      val dataPtr = zmq.zmq_msg_data(msg)
      memcpy(dataPtr, bytes.at(0), size)
      zmq.zmq_msg_send(msg, socket, 0) match
        case -1 =>
          zmq.zmq_msg_close(msg)
          val errno = zmq.zmq_errno()
          throw new ZMQException(errno)
        case _ =>
          zmq.zmq_msg_close(msg)
          ()
    }
      .refineToOrDie[ZMQException]

  def send(bytes: Array[Byte]): zio.ZIO[Any, zio.zmq.ZMQException, Unit] =
    ZIO.attemptBlocking {
      val msg = unsafe.stackalloc[zmq.Msg]()
      val size = UInt.valueOf(bytes.length)
      zmq.zmq_msg_init_size(msg, size)

      val dataPtr = zmq.zmq_msg_data(msg)
      memcpy(dataPtr, bytes.at(0), size)
      zmq.zmq_msg_send(msg, socket, 0) match
        case -1 =>
          zmq.zmq_msg_close(msg)
          val errno = zmq.zmq_errno()
          throw new ZMQException(errno)
        case _ =>
          zmq.zmq_msg_close(msg)
          ()
    }
      .refineToOrDie[ZMQException]

  def sendImmediately(bytes: Array[Byte]): zio.ZIO[Any, zio.zmq.ZMQException, Boolean] =
    ZIO.attemptBlocking {
      val msg = unsafe.stackalloc[zmq.Msg]()
      val size = UInt.valueOf(bytes.length)
      zmq.zmq_msg_init_size(msg, size)

      val dataPtr = zmq.zmq_msg_data(msg)
      memcpy(dataPtr, bytes.at(0), size)
      zmq.zmq_msg_send(msg, socket, ZMQ_DONTWAIT) match
        case -1 =>
          zmq.zmq_msg_close(msg)
          val errno = zmq.zmq_errno()
          if errno == ZError.EAGAIN then false
          else throw new ZMQException(errno)
        case _ =>
          zmq.zmq_msg_close(msg)
          true
    }
      .refineToOrDie[ZMQException]

  def sendImmediately(routingId: zio.zmq.RoutingId, bytes: Array[Byte]): zio.ZIO[Any, zio.zmq.ZMQException, Boolean] =
    ZIO.attemptBlocking {
      val msg = unsafe.stackalloc[zmq.Msg]()
      val size = UInt.valueOf(bytes.length)
      zmq.zmq_msg_init_size(msg, size)
      zmq.zmq_msg_set_routing_id(msg, UInt.valueOf(routingId.value))

      val dataPtr = zmq.zmq_msg_data(msg)
      memcpy(dataPtr, bytes.at(0), size)
      zmq.zmq_msg_send(msg, socket, ZMQ_DONTWAIT) match
        case -1 =>
          zmq.zmq_msg_close(msg)
          val errno = zmq.zmq_errno()
          if errno == ZError.EAGAIN then false
          else throw new ZMQException(errno)
        case _ =>
          zmq.zmq_msg_close(msg)
          true
    }
      .refineToOrDie[ZMQException]

  @tailrec private def receiveMsgToChunkBuilder(
    builder: ChunkBuilder[Message],
    msg: unsafe.Ptr[zmq.Msg],
    count: Int,
    max: Int
  ): Chunk[Message] =
    zmq.zmq_msg_recv(msg, socket, if count == 0 then 0 else ZMQ_DONTWAIT) match
      case -1 =>
        val errno = zmq.zmq_errno()
        if errno == ZError.EAGAIN then
          builder.result()
        else
          zmq.zmq_msg_close(msg)
          throw new ZMQException(errno)
      case 0 =>
        val routingId = zmq.zmq_msg_routing_id(msg)
        builder += Message(Array.empty, RoutingId(routingId.toInt))

        if count + 1 == max then builder.result()
        else receiveMsgToChunkBuilder(builder, msg, count + 1, max)
      case _ =>
        val routingId = zmq.zmq_msg_routing_id(msg)
        val dataPtr = zmq.zmq_msg_data(msg)
        val dataSize = zmq.zmq_msg_size(msg)
        val data = Array.ofDim[Byte](dataSize.toInt)
        memcpy(data.at(0), dataPtr, dataSize)
        builder += Message(data, RoutingId(routingId.toInt))

        if count + 1 == max then builder.result()
        else receiveMsgToChunkBuilder(builder, msg, count + 1, max)

  def stream(chunkSize: Int): ZStream[Any, ZMQException, Message] =
    ZStream.repeatZIOChunk {
      ZIO.attemptBlocking {
        val msg = unsafe.stackalloc[zmq.Msg]()
        zmq.zmq_msg_init(msg)
        val builder = ChunkBuilder.make[Message](chunkSize)
        val chunk = receiveMsgToChunkBuilder(builder, msg, 0, chunkSize)
        zmq.zmq_msg_close(msg)
        chunk
      }.refineToOrDie[ZMQException]
    }

  def bind(address: String): zio.ZIO[Any, zio.zmq.ZMQException, Unit] =
    ZIO.attemptBlocking(
      Zone {
        zmq.zmq_bind(socket, unsafe.toCString(address))
      } match
        case 0 => ()
        case _ =>
          val errno = zmq.zmq_errno()
          throw new ZMQException(errno)
    ).refineToOrDie[ZMQException]

  def connect(address: String): zio.ZIO[Any, zio.zmq.ZMQException, Unit] =
    ZIO.attemptBlocking(
      Zone {
        zmq.zmq_connect(socket, unsafe.toCString(address))
      } match
        case 0 => ()
        case _ =>
          val errno = zmq.zmq_errno()
          throw new ZMQException(errno)
    ).refineToOrDie[ZMQException]

  def connectPeer(address: String): zio.ZIO[Any, zio.zmq.ZMQException, zio.zmq.RoutingId] =
    ZIO.attemptBlocking(
      Zone {
        zmq.zmq_connect_peer(socket, unsafe.toCString(address)).toInt
      } match
        case 0 =>
          val errno = zmq.zmq_errno()
          throw new ZMQException(errno)
        case routingId => RoutingId(routingId)
    ).refineToOrDie[ZMQException]

  def disconnectPeer(routingId: zio.zmq.RoutingId): zio.ZIO[Any, zio.zmq.ZMQException, Boolean] =
    ZIO.attemptBlocking(
      zmq.zmq_disconnect_peer(socket, UInt.valueOf(routingId.value)) match
        case 0 => true
        case _ =>
          val errno = zmq.zmq_errno()
          if errno == ZError.EHOSTUNREACH then false
          else throw new ZMQException(errno)
    ).refineToOrDie[ZMQException]

  def disconnect(address: String): zio.ZIO[Any, zio.zmq.ZMQException, Unit] =
    ZIO.attemptBlocking(
      Zone {
        zmq.zmq_disconnect(socket, unsafe.toCString(address))
      } match
        case 0 => ()
        case _ =>
          val errno = zmq.zmq_errno()
          throw new ZMQException(errno)
    ).refineToOrDie[ZMQException]

  val options = new ZOptions(socket)

  private[zmq] def close() =
    ZIO.attemptBlocking {
      val rc = zmq.zmq_close(socket)
      if rc != 0 then throw new ZMQException(zmq.zmq_errno())
    }.refineToOrDie[ZMQException]
end ZSocket
object ZSocket:
  private def createSocket(
    socketType: Int,
    asType: Option[Int]
  ): ZIO[ZContext & Scope, ZMQException, ZSocket] =
    val create =
      for
        ctx <- ZIO.service[ZContext]
        handle <- ZIO
          .attemptBlocking(ctx.createSocket(socketType))
          .refineToOrDie[ZMQException]

        // TODO: as type is not supported in native yet
        // _ = asType.fold(())(asType =>
        //   zmq.
        //   handle.setSocketOpt(ZMQ.ZMQ_AS_TYPE, asType))

        // work around ZIO cannot interrupt blocking effect
        // this will cause receive message wait up to 1 seconds even when blocking
        _ =
          val value = unsafe.stackalloc[unsafe.CInt]()
          !value = 1000
          // ZMQ_RCVTIMEO expects pointer to int (milliseconds) and its size
          zmq.zmq_setsockopt(handle, 27, value, unsafe.sizeof[unsafe.CInt])
      yield new ZSocket(handle)

    ZIO
      .acquireRelease(create) { case socket =>
        socket.close().ignore
      }

  // def dealer = createSocket(ZMQ.ZMQ_CLIENT, Some(ZMQ.ZMQ_DEALER))

  // def router = createSocket(ZMQ.ZMQ_PEER, Some(ZMQ.ZMQ_ROUTER))

  def client = createSocket(SocketType.ZMQ_CLIENT, None)

  def server = createSocket(SocketType.ZMQ_SERVER, None)

  // def raw = createSocket(SocketType.ZMQ_RAW, None)

  def peer = createSocket(SocketType.ZMQ_PEER, None)
