package zio.zmq

import java.util.concurrent.atomic.AtomicBoolean

import zio.stream.ZStream
import zio.{Chunk, ChunkBuilder, ZIO}

import zmq.{Msg, SocketBase, ZMQ}
import zio.zmq.{RoutingId, ZError as ZmqError}
import zio.Scope

class ZSocket(
  socket: SocketBase
) {

  private def receiveImmediatelyIntoChunkBuilder(
    builder: ChunkBuilder[Message],
    max: Int,
    size: Int
  ): Chunk[Message] = {
    val msg = socket.recv(ZMQ.ZMQ_DONTWAIT)

    if (msg != null && size < max - 1)
      receiveImmediatelyIntoChunkBuilder(builder += Message(msg), max, size + 1)
    else if (msg != null) (builder += Message(msg)).result()
    else if (socket.errno() == ZmqError.EAGAIN) builder.result()
    else throw new ZMQException(socket.errno())
  }

  private def receiveChunk(canceled: AtomicBoolean, chunkSize: Int) =
    for {
      _ <- ZIO.succeed(canceled.set(false))
      msg <- receiveMsgWait(canceled)
      builder = ChunkBuilder.make[Message](chunkSize)
      chunk <- ZIO
        .attempt(
          receiveImmediatelyIntoChunkBuilder(builder += msg, chunkSize, 1)
        )
        .refineToOrDie[ZMQException]
    } yield chunk

  def stream: ZStream[Any, ZMQException, Message] = stream(chunkSize = 100)

  def stream(chunkSize: Int): ZStream[Any, ZMQException, Message] =
    ZStream
      .succeed(new AtomicBoolean(false))
      .flatMap(canceled => ZStream.repeatZIOChunk(receiveChunk(canceled, chunkSize)))

  private def receiveMsgWait(
    canceled: AtomicBoolean
  ): ZIO[Any, ZMQException, Message] =
    for {
      msg <- ZIO
        .attempt {
          val msg = socket.recv(ZMQ.ZMQ_DONTWAIT)

          if (msg != null) msg
          else if (socket.errno() == ZmqError.EAGAIN) null
          else throw new ZMQException(socket.errno())
        }
        .refineToOrDie[ZMQException]

      msg <-
        if (msg != null) ZIO.succeed(msg)
        else
          zio.ZIO
            .attemptBlockingCancelable {
              val msg = socket.recv(0, canceled)
              if (msg != null) Right(msg)
              else Left(new ZMQException(socket.errno()))
            }(ZIO.attempt(socket.cancel(canceled)).orDie)
            .absolve
            .refineToOrDie[ZMQException]
            .retryWhile(_.getErrorCode() == ZmqError.EAGAIN)
    } yield Message(msg)

  def receive: ZIO[Any, ZMQException, Message] = receiveMsgWait(
    new AtomicBoolean(false)
  )

  private def receiveMsgImmediately: ZIO[Any, ZMQException, Option[Msg]] =
    ZIO
      .attempt {
        val msg = socket.recv(ZMQ.ZMQ_DONTWAIT)

        if (msg != null) Some(msg)
        else if (socket.errno() == ZmqError.EAGAIN) None
        else throw new ZMQException(socket.errno())
      }
      .refineToOrDie[ZMQException]

  def send(routingId: RoutingId, bytes: Array[Byte]) = {
    val msg = new Msg(bytes)
    msg.setRoutingId(routingId.value)
    sendMsg(msg)
  }

  private def sendMsg(msg: Msg): ZIO[Any, ZMQException, Unit] =
    for {
      success <- ZIO
        .attempt {
          val success = socket.send(msg, ZMQ.ZMQ_DONTWAIT)

          if (success) success
          else if (socket.errno() == ZmqError.EAGAIN) false
          else throw new ZMQException(socket.errno())
        }
        .refineToOrDie[ZMQException]

      msg <-
        if (success) ZIO.unit
        else {
          val canceled = new AtomicBoolean(false)
          ZIO
            .attemptBlockingCancelable {
              val success = socket.send(msg, 0, canceled)

              // We actually should repeat until error is not EAGAIN
              if (!success) throw new ZMQException(socket.errno())

            }(ZIO.attempt(socket.cancel(canceled)).orDie)
            .refineToOrDie[ZMQException]
        }
    } yield msg

  def send(bytes: Array[Byte]) = sendMsg(new Msg(bytes))

  private def sendMsgImmediately(msg: Msg): ZIO[Any, ZMQException, Boolean] =
    ZIO
      .attempt {
        val success = socket.send(msg, ZMQ.ZMQ_DONTWAIT)

        if (success) success
        else if (socket.errno() == ZmqError.EAGAIN) false
        else throw new ZMQException(socket.errno())
      }
      .refineToOrDie[ZMQException]

  def sendImmediately(bytes: Array[Byte]) = sendMsgImmediately(new Msg(bytes))

  def sendImmediately(routingId: RoutingId, bytes: Array[Byte]) = {
    val msg = new Msg(bytes)
    msg.setRoutingId(routingId.value)
    sendMsgImmediately(msg)
  }

  def bind(address: String): ZIO[Any, ZMQException, Unit] =
    ZIO
      .attempt(socket.bind(address) match {
        case true  => ()
        case false => throw new ZMQException(socket.errno())
      })
      .refineToOrDie[ZMQException]

  def connect(address: String) =
    ZIO
      .attempt(
        socket.connect(address) match {
          case true  => ()
          case false => throw new ZMQException(socket.errno())
        }
      )
      .refineToOrDie[ZMQException]

  def connectPeer(address: String): ZIO[Any, ZMQException, RoutingId] =
    ZIO
      .attempt(socket.connectPeer(address) match {
        case 0         => throw new ZMQException(socket.errno())
        case routingId => RoutingId(routingId)
      })
      .refineToOrDie[ZMQException]

  def disconnectPeer(routingId: RoutingId): ZIO[Any, ZMQException, Boolean] =
    ZIO
      .attempt(socket.disconnectPeer(routingId.value))
      .refineToOrDie[ZMQException]

  def disconnect(address: String) =
    ZIO
      .attempt(
        socket.termEndpoint(address) match {
          case true  => ()
          case false => throw new ZMQException(socket.errno())
        }
      )
      .refineToOrDie[ZMQException]

  val options = new ZOptions(socket)
}

object ZSocket {
  private def createSocket(
    socketType: Int,
    asType: Option[Int]
  ): ZIO[ZContext & Scope, ZMQException, ZSocket] = {
    val create =
      for {
        ctx <- ZIO.service[ZContext]
        handle <- ZIO
          .attemptBlocking(ctx.createSocket(socketType))
          .refineToOrDie[ZMQException]

        _ = asType.fold(())(asType => handle.setSocketOpt(ZMQ.ZMQ_AS_TYPE, asType))
        // work around ZIO cannot interrupt blocking effect
        // this will cause receive message wait up to 1 seconds even when blocking
        _ = handle.setSocketOpt(ZMQ.ZMQ_RCVTIMEO, 1000)
      } yield (new ZSocket(handle), handle)

    ZIO
      .acquireRelease(create) { case (_, handle) =>
        ZIO.attemptBlocking(handle.close()).ignore
      }
      .map { case (socket, _) => socket }
  }

  def dealer = createSocket(ZMQ.ZMQ_CLIENT, Some(ZMQ.ZMQ_DEALER))

  def router = createSocket(ZMQ.ZMQ_PEER, Some(ZMQ.ZMQ_ROUTER))

  def client = createSocket(ZMQ.ZMQ_CLIENT, None)

  def server = createSocket(ZMQ.ZMQ_SERVER, None)

  def raw = createSocket(ZMQ.ZMQ_RAW, None)

  def peer = createSocket(ZMQ.ZMQ_PEER, None)
}
