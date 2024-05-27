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
import zio.http.Method.{GET, POST}
import zio.http.*
import zio.ZIO
import zio.raft.Snapshot
import zio.UIO
import zio.raft.SnapshotStore
import zio.raft.Index
import zio.stream.Stream
import zio.stream.ZStream
import scodec.bits.BitVector
import zio.http.codec.PathCodec
import zio.ZIOAppArgs

sealed trait KVCommand extends Command

case class Set(key: String, value: String) extends KVCommand:
  type Response = Unit

case class Get(key: String) extends KVCommand:
  type Response = String

val mapCodec = scodec.codecs.list(utf8_32 :: utf8_32).xmap(_.toMap, _.toList)

class KVStateMachine(map: Map[String, String]) extends StateMachine[KVCommand]:

  override def takeSnapshot: Stream[Nothing, Byte] =
    ZStream
      .fromZIO(ZIO.attempt(Chunk.fromArray(mapCodec.encode(map).require.toByteArray)).orDie)
      .flattenChunks // TODO: we need to improve the conversion of ByteVector to Stream and Chunk

  override def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[StateMachine[KVCommand]] =
    // TODO: we need to improve the conversion of Stream to BitVector
    ZIO.scoped(
      stream.toInputStream
        .map(is => BitVector.fromInputStream(is, 1024))
        .map(bv => mapCodec.decodeValue(bv).require)
        .map(m => KVStateMachine(m))
    )

  override def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean = false
  // commitIndex.value - lastSnaphotIndex.value > 2

  override def apply(command: KVCommand): (command.Response, StateMachine[KVCommand]) =
    command match
      case Set(k, v) => (().asInstanceOf[command.Response], KVStateMachine(map.updated(k, v)))
      case Get(k)    => (map.get(k).getOrElse("").asInstanceOf[command.Response], this)

class HttpServer(raft: Raft[KVCommand]):

  val app = Routes(
    GET / "" -> handler(ZIO.succeed(Response.text("Hello World!"))),
    GET / string("key") -> handler((k: String, _: Request) =>
      raft
        .sendCommand(Get(k))
        .map(r => Response.text(r))
        .catchAll(err => ZIO.succeed(Response.text(err.toString).status(Status.BadRequest)))
    ),
    POST / string("key") / string("value") -> handler((k: String, v: String, _: Request) =>
      raft
        .sendCommand(Set(k, v))
        .map(_ => Response.text("OK"))
        .catchAll(err => ZIO.succeed(Response.text(err.toString).status(Status.BadRequest)))
    )
  )

  def run = Server.serve(app).provide(Server.defaultWithPort(8090))

object KVStoreApp extends zio.ZIOAppDefault:
  override def run =
    val program =
      for
        _ <- ZIO.unit
        args <- ZIOAppArgs.getArgs
        memberId = MemberId(args(0))
        peers = Map(
          "peer1" -> "tcp://localhost:5555",
          "peer2" -> "tcp://localhost:5556",
          "peer3" -> "tcp://localhost:5557"
        ).map((k, v) => MemberId(k) -> v)

        getCodec = utf8_32.as[Get]
        setCodec = (utf8_32 :: utf8_32).as[Set]

        commandCodec = discriminated[KVCommand]
          .by(fixedSizeBytes(1, ascii))
          .typecase("S", setCodec)
          .typecase("G", getCodec)

        rpc <- ZmqRpc.make[KVCommand](
          peers(memberId),
          peers.removed(memberId),
          commandCodec
        )
        stable <- Stable.makeInMemory
        logStore <- LogStore.makeInMemory[KVCommand]
        snapshotStore <- SnapshotStore.makeInMemory

        raft <- Raft.make(
          memberId,
          peers.removed(memberId).keys.toArray,
          stable,
          logStore,
          snapshotStore,
          rpc,
          new KVStateMachine(Map.empty)
        )

        _ <- raft.run.forkScoped
        _ <- new HttpServer(raft).run.forkScoped.when(memberId == MemberId("peer1"))
        _ <- ZIO.never
      yield ()

    program.exitCode.provideSomeLayer(ZContext.live.orDie)
