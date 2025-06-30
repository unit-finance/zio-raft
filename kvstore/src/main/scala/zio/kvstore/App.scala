package zio.kvstore

import zio.http.*
import zio.http.Method.{GET, POST}
import zio.http.codec.PathCodec
import zio.raft.zmq.ZmqRpc
import zio.raft.{Command, Index, MemberId, Raft, SnapshotStore, StateMachine}
import zio.stream.{Stream, ZStream}
import zio.zmq.ZContext
import zio.{Chunk, UIO, ZIO, ZIOAppArgs, ZLayer}
import zio.prelude.State

import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs.{ascii, discriminated, fixedSizeBytes, utf8_32}
import zio.raft.stores.FileStable
import zio.raft.stores.segmentedlog.SegmentedLog

sealed trait KVCommand extends Command

case class Set(key: String, value: String) extends KVCommand:
  type Response = Unit

case class Get(key: String) extends KVCommand:
  type Response = String

val mapCodec = scodec.codecs.list(utf8_32 :: utf8_32).xmap(_.toMap, _.toList)

object KVCommand:
  val getCodec = utf8_32.as[Get]
  val setCodec = (utf8_32 :: utf8_32).as[Set]
  given commandCodec: Codec[KVCommand] = discriminated[KVCommand]
    .by(fixedSizeBytes(1, ascii))
    .typecase("S", setCodec)
    .typecase("G", getCodec)

class KVStateMachine extends StateMachine[Map[String, String], KVCommand]:

  override def emptyState: Map[String, String] = Map.empty

  override def takeSnapshot(state: Map[String, String]): Stream[Nothing, Byte] =
    ZStream.fromChunk(Chunk.fromArray(mapCodec.encode(state).require.toByteArray))

  override def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[Map[String, String]] =
    // TODO: we need to improve the conversion of Stream to BitVector
    ZIO.scoped(
      stream.toInputStream
        .map(is => BitVector.fromInputStream(is, 1024))
        .map(bv => mapCodec.decodeValue(bv).require)
    )

  override def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean = false
  // commitIndex.value - lastSnaphotIndex.value > 2

  override def apply(command: KVCommand): State[Map[String, String], command.Response] =
    (command match
      case Set(k, v) => State.update((map: Map[String, String]) => map.updated(k, v))
      case Get(k)    => State.get.map((map: Map[String, String]) => map.get(k).getOrElse(""))
    ).map(_.asInstanceOf[command.Response])

class HttpServer(raft: Raft[Map[String, String], KVCommand]):

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
        args <- ZIOAppArgs.getArgs
        memberId = MemberId(args(0))
        peers = Map(
          "peer1" -> "tcp://localhost:5555",
          "peer2" -> "tcp://localhost:5556",
          "peer3" -> "tcp://localhost:5557"
        ).map((k, v) => MemberId(k) -> v)

        rpc <- ZmqRpc.make[KVCommand](
          peers(memberId),
          peers.removed(memberId)
        )
        stable <- FileStable.make(s"/tmp/raft/${memberId.value}/")
        logStore <- SegmentedLog.make[KVCommand](s"/tmp/raft/${memberId.value}/logstore")
        snapshotStore <- SnapshotStore.makeInMemory

        raft <- Raft.make(
          memberId,
          peers.removed(memberId).keys.toArray,
          stable,
          logStore,
          snapshotStore,
          rpc,
          new KVStateMachine
        )

        _ <- raft.run.forkScoped
        _ <- new HttpServer(raft).run.forkScoped.when(memberId == MemberId("peer1"))
        _ <- ZIO.never
      yield ()

    program.exitCode.provideSomeLayer(
      ZContext.live.orDie ++ zio.lmdb.Environment.test
    )
