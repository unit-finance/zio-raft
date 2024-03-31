import zio.raft.Command
import zio.raft.zmq.ZmqRpc
import zio.Chunk
import scala.util.Try
import zio.raft.Raft
import zio.raft.MemberId
import zio.raft.Stable
import zio.raft.LogStore
import zio.raft.StateMachine
import scodec.Codec
import scodec.codecs.{discriminated, fixedSizeBytes, ascii, utf8_32}
import zio.ExitCode
import zio.URIO
import zio.zmq.ZContext
import zhttp.http.Method.GET
import zhttp.service.server.ServerChannelFactory
import zhttp.http.*
import zhttp.service.Server
import zio.ZIO
import zio.logging.*
import zio.raft.Snapshot
import zio.UIO
import zio.raft.SnapshotStore
import zio.raft.Index
import zio.stream.Stream
import zhttp.http.Method.POST
import zio.stream.ZStream
import scodec.bits.BitVector

sealed trait KVCommand extends Command

case class Set(key: String, value: String) extends KVCommand:
  type Response = Unit

case class Get(key: String) extends KVCommand:
  type Response = String

val mapCodec = scodec.codecs.list(utf8_32 :: utf8_32).xmap(_.toMap, _.toList)


class KVStateMachine(map: Map[String, String]) extends StateMachine[KVCommand]:

  override def takeSnapshot: Stream[Nothing, Byte] = 
    ZStream.fromEffect(ZIO.effect(Chunk.fromArray(mapCodec.encode(map).require.toByteArray)).orDie).flattenChunks // TODO: we need to improve the conversion of ByteVector to Stream and Chunk

  override def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[StateMachine[KVCommand]] = 
    // TODO: we need to improve the conversion of Stream to BitVector
    stream.toInputStream.map(is => BitVector.fromInputStream(is, 1024)).map(bv => mapCodec.decodeValue(bv).require).map(m => KVStateMachine(m)).useNow

  override def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean = false
    // commitIndex.value - lastSnaphotIndex.value > 2
  
  override def apply(command: KVCommand): (command.Response, StateMachine[KVCommand]) =
    command match
      case Set(k, v) => (().asInstanceOf[command.Response], KVStateMachine(map.updated(k, v)))
      case Get(k) => (map.get(k).getOrElse("").asInstanceOf[command.Response], this)

class HttpServer(raft: Raft[KVCommand]):

  val app = Http.collectZIO[Request]:
    case GET -> !! => ZIO.succeed(Response.text("Hello World!"))
    case GET -> !! / k => raft.sendCommand(Get(k)).map(r => Response.text(r)).catchAll(err => ZIO.succeed(Response.text(err.toString).setStatus(Status.BadRequest) ))
    case POST -> !! / k / v => raft.sendCommand(Set(k, v)).map(_ => Response.text("OK")).catchAll(err => ZIO.succeed(Response.text(err.toString).setStatus(Status.BadRequest) ))
    
  def run = Server.start(8090, app)

object KVStoreApp extends zio.App:
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = 
    val memberId = MemberId(args(0))
    val peers = Map(
      "peer1" -> "tcp://localhost:5555",
      "peer2" -> "tcp://localhost:5556",
      "peer3" -> "tcp://localhost:5557"
    ).map((k, v) => MemberId(k) -> v)

    val getCodec = utf8_32.as[Get]
    val setCodec = (utf8_32 :: utf8_32).as[Set]

    val commandCodec = discriminated[KVCommand].by(fixedSizeBytes(1, ascii))
      .typecase("S", setCodec)
      .typecase("G", getCodec)
    
    val raft =
      for
        rpc <- ZmqRpc.makeManaged[KVCommand](
          peers(memberId),
          peers.removed(memberId),
          commandCodec
        )
        stable <- Stable.makeInMemoryManaged
        logStore <- LogStore.makeInMemoryManaged[KVCommand]
        snapshotStore <- SnapshotStore.makeInMemoryManaged

        raft <- Raft.makeManaged(
          memberId,
          peers.removed(memberId).keys.toArray,
          stable,
          logStore,
          snapshotStore,
          rpc,
          new KVStateMachine(Map.empty)
        )
      yield raft

    val logging = Logging.console(logLevel = LogLevel.Debug, format = LogFormat.ColoredLogFormat()) >>> zio.logging.Logging.withRootLoggerName("raft")

    raft.use(raft => {
      for 
        _ <- new HttpServer(raft).run.fork.when(memberId == MemberId("peer1")) *> ZIO.never
      yield ()
    } ).exitCode.provideCustomLayer(ZContext.live.orDie ++ logging)
