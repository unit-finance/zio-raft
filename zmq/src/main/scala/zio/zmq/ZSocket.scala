package zio.zmq

import java.nio.charset.StandardCharsets
import org.zeromq.ZMQException
import zio.{Chunk, ZIO, ZManaged}
import zmq.{Msg, SocketBase, ZMQ, ZError as ZmqError}
import zio.zmq.ZContext

import java.util.concurrent.atomic.AtomicBoolean
import zio.stream.ZStream
import zio.ChunkBuilder

class ZSocket private (socket: SocketBase, blocking: zio.blocking.Blocking.Service):
  def pollIn: ZIO[Any, ZMQException, Boolean] =
    for
      ready <- ZIO
                .effect:
                  val events = socket.poll(ZMQ.ZMQ_POLLIN, 0, null)

                  if events == ZMQ.ZMQ_POLLIN then true
                  else if events == -1 && socket.errno() == ZmqError.EAGAIN then false
                  else throw new ZMQException(socket.errno())
                .refineToOrDie[ZMQException]
      result <- if ready then ZIO.effectTotal(true)
               else
                 val canceled = new AtomicBoolean(false)                 
                   blocking
                   .effectBlockingCancelable {
                     val events = socket.poll(ZMQ.ZMQ_POLLIN, -1, canceled)

                     // We actually should repeat until error is not EAGAIN
                     if events == ZMQ.ZMQ_POLLIN then true
                     else throw new ZMQException(socket.errno())
                   }(ZIO.effect(socket.cancel(canceled)).orDie)
                   .refineToOrDie[ZMQException]
    yield result

  private def receiveImmediatelyIntoChunkBuilder(builder: ChunkBuilder[Msg], max: Int, size: Int): Chunk[Msg] =
    val msg = socket.recv(ZMQ.ZMQ_DONTWAIT)

    if msg != null && size < max - 1 then
      receiveImmediatelyIntoChunkBuilder(builder += msg, max, size + 1)
    else if msg != null then
      (builder += msg).result()
    else if socket.errno() == ZmqError.EAGAIN then builder.result()
    else throw new ZMQException(socket.errno())

  private def receiveChunk(canceled: AtomicBoolean, chunkSize: Int) =
    for
      _   <- ZIO.effectTotal(canceled.set(false))
      msg <- receiveMsgWait(canceled)
      builder = ChunkBuilder.make[Msg](chunkSize)
      chunk <- ZIO.effect(receiveImmediatelyIntoChunkBuilder(builder += msg, chunkSize, 1)).refineToOrDie[ZMQException]
    yield chunk

  def receiveChunkImmediately(max: Int) =
    ZIO.effect(receiveImmediatelyIntoChunkBuilder(ChunkBuilder.make(max), max, 0)).refineToOrDie[ZMQException]

  def stream: ZStream[Any, ZMQException, Msg] = stream(chunkSize = 100)

  def stream(chunkSize: Int): ZStream[Any, ZMQException, Msg] =
    ZStream
      .succeed(new AtomicBoolean(false))
      .flatMap(canceled => ZStream.repeatEffectChunk(receiveChunk(canceled, chunkSize)))

  private def receiveMsgWait(canceled: AtomicBoolean): ZIO[Any, ZMQException, Msg] =
    for
      msg <- ZIO
              .effect:
                val msg = socket.recv(ZMQ.ZMQ_DONTWAIT)

                if msg != null then msg
                else if socket.errno() == ZmqError.EAGAIN then null
                else throw new ZMQException(socket.errno())
              .refineToOrDie[ZMQException]

      msg <- if msg != null then ZIO.succeed(msg)
            else
              blocking
                .effectBlockingCancelable {
                  val msg = socket.recv(0, canceled)
                  if msg != null then msg
                  else throw new ZMQException(socket.errno())
                }(ZIO.effect(socket.cancel(canceled)).orDie)
                .refineToOrDie[ZMQException]
                .retryWhile(_.getErrorCode() == ZmqError.EAGAIN)
    yield msg

  def receiveMsg: ZIO[Any, ZMQException, Msg] = receiveMsgWait(new AtomicBoolean(false))

  def receive = receiveMsg.map(m => Chunk.fromArray(m.data()))

  def receiveString = receive.map(c => new String(c.toArray, StandardCharsets.UTF_8))

  def receiveStringWithRoutingId =
    receiveMsg.map(m => (RoutingId(m.getRoutingId), new String(m.data(), StandardCharsets.UTF_8)))

  def receiveWithRoutingId =
    receiveMsg.map(m => (RoutingId(m.getRoutingId), Chunk.fromArray(m.data())))

  def receiveMsgImmediately: ZIO[Any, ZMQException, Option[Msg]] =
    ZIO
      .effect:
        val msg = socket.recv(ZMQ.ZMQ_DONTWAIT)

        if msg != null then Some(msg)
        else if socket.errno() == ZmqError.EAGAIN then None
        else throw new ZMQException(socket.errno())
      .refineToOrDie[ZMQException]

  def receiveImmediately = receiveMsgImmediately.map(_.map(m => Chunk.fromArray(m.data())))

  def receiveWithRoutingIdImmediately =
    receiveMsgImmediately.map(_.map(m => (RoutingId(m.getRoutingId), Chunk.fromArray(m.data()))))

  def send(routingId: RoutingId, bytes: Array[Byte]) =
    val msg = new Msg(bytes)
    msg.setRoutingId(routingId.value)
    sendMsg(msg)

  def sendString(routingId: RoutingId, s: String) =
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    val msg = new Msg(bytes)
    msg.setRoutingId(routingId.value)
    sendMsg(msg)

  def sendMsg(msg: Msg): ZIO[Any, ZMQException, Unit] =
    for
      success <- ZIO
                  .effect:
                    val success = socket.send(msg, ZMQ.ZMQ_DONTWAIT)

                    if success then success
                    else if socket.errno() == ZmqError.EAGAIN then false
                    else throw new ZMQException(socket.errno())
                  .refineToOrDie[ZMQException]

      msg <- if success then ZIO.unit
            else
              val canceled = new AtomicBoolean(false)
              blocking
                .effectBlockingCancelable {
                  val success = socket.send(msg, 0, canceled)

                  // We actually should repeat until error is not EAGAIN
                  if !success then
                    throw new ZMQException(socket.errno())

                }(ZIO.effect(socket.cancel(canceled)).orDie)
                .refineToOrDie[ZMQException]
    yield msg

  def send(bytes: Array[Byte]) = sendMsg(new Msg(bytes))

  def sendString(s: String) =
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    send(bytes)

  def sendMsgImmediately(msg: Msg): ZIO[Any, ZMQException, Boolean] =
    ZIO
      .effect:
        val success = socket.send(msg, ZMQ.ZMQ_DONTWAIT)

        if success then success
        else if socket.errno() == ZmqError.EAGAIN then false
        else throw new ZMQException(socket.errno())
      .refineToOrDie[ZMQException]

  def sendImmediately(bytes: Array[Byte]) = sendMsgImmediately(new Msg(bytes))

  def sendImmediately(routingId: RoutingId, bytes: Array[Byte]) =
    val msg = new Msg(bytes)
    msg.setRoutingId(routingId.value)
    sendMsgImmediately(msg)

  def bind(address: String): ZIO[Any, ZMQException, Unit] =
    ZIO
      .effect(socket.bind(address) match {
        case true  => ()
        case false => throw new ZMQException(socket.errno())
      })
      .refineToOrDie[ZMQException]

  def connect(address: String) =
    ZIO
      .effect(
        socket.connect(address) match {
          case true  => ()
          case false => throw new ZMQException(socket.errno())
        }
      )
      .refineToOrDie[ZMQException]

  def connectPeer(address: String): ZIO[Any, ZMQException, RoutingId] =
    ZIO
      .effect(socket.connectPeer(address) match {
        case 0         => throw new ZMQException(socket.errno())
        case routingId => RoutingId(routingId)
      })
      .refineToOrDie[ZMQException]

  def disconnectPeer(routingId: RoutingId): ZIO[Any, ZMQException, Boolean] =
    ZIO
      .effect(socket.disconnectPeer(routingId.value))
      .refineToOrDie[ZMQException]

  def disconnect(address: String) =
    ZIO
      .effect(
        socket.termEndpoint(address) match {
          case true  => ()
          case false => throw new ZMQException(socket.errno())
        }
      )
      .refineToOrDie[ZMQException]

  val options = new ZOptions(socket)

object ZSocket {
  private def createSocket(socketType: Int, asType: Option[Int]) =
    val create =
      for 
        ctx <- ZIO.service[ZContext]
        blocking <- ZIO.service[zio.blocking.Blocking.Service]
        handle <- blocking.effectBlocking(ctx.createSocket(socketType))
        
        _ = asType.fold(())(asType => handle.setSocketOpt(ZMQ.ZMQ_AS_TYPE, asType))
        // work around ZIO cannot interrupt blocking effect
        // this will cause receive message wait up to 1 seconds even when blocking
        _ = handle.setSocketOpt(ZMQ.ZMQ_RCVTIMEO, 1000)
      yield (new ZSocket(handle, blocking), handle)

    ZManaged
      .make(create)((_, handle) => zio.blocking.effectBlocking(handle.close()).ignore)
      .map((socket, _) => socket)        

  def dealer = createSocket(ZMQ.ZMQ_CLIENT, Some(ZMQ.ZMQ_DEALER))

  def router = createSocket(ZMQ.ZMQ_PEER, Some(ZMQ.ZMQ_ROUTER))

  def client = createSocket(ZMQ.ZMQ_CLIENT, None)

  def server = createSocket(ZMQ.ZMQ_SERVER, None)

  def raw = createSocket(ZMQ.ZMQ_RAW, None)

  def peer = createSocket(ZMQ.ZMQ_PEER, None)

}