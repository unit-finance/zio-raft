package zio.zmq

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

import zio.stream.ZStream
import zio.{Chunk, ChunkBuilder, Scope, ZIO}

import org.zeromq.ZMQException
import zmq.{Msg, SocketBase, ZError as ZmqError, ZMQ}

class ZSocket private (
    socket: SocketBase
) {
  def pollIn: ZIO[Any, ZMQException, Boolean] =
    for {
      ready <- ZIO
        .attempt {
          val events = socket.poll(ZMQ.ZMQ_POLLIN, 0, null)

          if (events == ZMQ.ZMQ_POLLIN) true
          else if (events == -1 && socket.errno() == ZmqError.EAGAIN) false
          else throw new ZMQException(socket.errno())
        }
        .refineToOrDie[ZMQException]
      result <-
        if (ready) ZIO.succeedNow(true)
        else {
          val canceled = new AtomicBoolean(false)
          ZIO
            .attemptBlockingCancelable {
              val events = socket.poll(ZMQ.ZMQ_POLLIN, -1, canceled)

              // We actually should repeat until error is not EAGAIN
              if (events == ZMQ.ZMQ_POLLIN) true
              else throw new ZMQException(socket.errno())
            }(ZIO.attempt(socket.cancel(canceled)).orDie)
            .refineToOrDie[ZMQException]
        }
    } yield result

  private def receiveImmediatelyIntoChunkBuilder(
      builder: ChunkBuilder[Msg],
      max: Int,
      size: Int
  ): Chunk[Msg] = {
    val msg = socket.recv(ZMQ.ZMQ_DONTWAIT)

    if (msg != null && size < max - 1)
      receiveImmediatelyIntoChunkBuilder(builder += msg, max, size + 1)
    else if (msg != null) (builder += msg).result()
    else if (socket.errno() == ZmqError.EAGAIN) builder.result()
    else throw new ZMQException(socket.errno())
  }

  private def receiveChunk(canceled: AtomicBoolean, chunkSize: Int) =
    for {
      _ <- ZIO.succeed(canceled.set(false))
      msg <- receiveMsgWait(canceled)
      builder = ChunkBuilder.make[Msg](chunkSize)
      chunk <- ZIO
        .attempt(
          receiveImmediatelyIntoChunkBuilder(builder += msg, chunkSize, 1)
        )
        .refineToOrDie[ZMQException]
    } yield chunk

  def receiveChunkImmediately(max: Int) =
    ZIO
      .attempt(
        receiveImmediatelyIntoChunkBuilder(ChunkBuilder.make(max), max, 0)
      )
      .refineToOrDie[ZMQException]

  def stream: ZStream[Any, ZMQException, Msg] = stream(chunkSize = 100)

  def stream(chunkSize: Int): ZStream[Any, ZMQException, Msg] =
    ZStream
      .succeed(new AtomicBoolean(false))
      .flatMap(canceled => ZStream.repeatZIOChunk(receiveChunk(canceled, chunkSize)))

  private def receiveMsgWait(
      canceled: AtomicBoolean
  ): ZIO[Any, ZMQException, Msg] =
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
        else {
          zio.ZIO
            .attemptBlockingCancelable {
              val msg = socket.recv(0, canceled)
              if (msg != null) Right(msg)
              else Left(new ZMQException(socket.errno()))
            }(ZIO.attempt(socket.cancel(canceled)).orDie)
            .absolve
            .refineToOrDie[ZMQException]
            .retryWhile(_.getErrorCode() == ZmqError.EAGAIN)
        }
    } yield msg

  def receiveMsg: ZIO[Any, ZMQException, Msg] = receiveMsgWait(
    new AtomicBoolean(false)
  )

  def receive = receiveMsg.map(m => Chunk.fromArray(m.data()))

  def receiveString =
    receive.map(c => new String(c.toArray, StandardCharsets.UTF_8))

  def receiveStringWithRoutingId =
    receiveMsg.map(m => (RoutingId(m.getRoutingId), new String(m.data(), StandardCharsets.UTF_8)))

  def receiveWithRoutingId =
    receiveMsg.map(m => (RoutingId(m.getRoutingId), Chunk.fromArray(m.data())))

  def receiveMsgImmediately: ZIO[Any, ZMQException, Option[Msg]] =
    ZIO
      .attempt {
        val msg = socket.recv(ZMQ.ZMQ_DONTWAIT)

        if (msg != null) Some(msg)
        else if (socket.errno() == ZmqError.EAGAIN) None
        else throw new ZMQException(socket.errno())
      }
      .refineToOrDie[ZMQException]

  def receiveImmediately =
    receiveMsgImmediately.map(_.map(m => Chunk.fromArray(m.data())))

  def receiveWithRoutingIdImmediately =
    receiveMsgImmediately.map(
      _.map(m => (RoutingId(m.getRoutingId), Chunk.fromArray(m.data())))
    )

  def send(routingId: RoutingId, bytes: Array[Byte]) = {
    val msg = new Msg(bytes)
    msg.setRoutingId(routingId.value)
    sendMsg(msg)
  }

  def sendString(routingId: RoutingId, s: String) = {
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    val msg = new Msg(bytes)
    msg.setRoutingId(routingId.value)
    sendMsg(msg)
  }

  def sendMsg(msg: Msg): ZIO[Any, ZMQException, Unit] =
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

  def sendString(s: String) = {
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    send(bytes)
  }

  def sendMsgImmediately(msg: Msg): ZIO[Any, ZMQException, Boolean] =
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
