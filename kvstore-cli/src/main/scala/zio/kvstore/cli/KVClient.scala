package zio.kvstore.cli

import zio.*
import zio.stream.*
import zio.raft.client.RaftClient
import zio.raft.protocol.MemberId
import scodec.Codec
import scodec.bits.ByteVector
import zio.kvstore.protocol.{KVClientRequest, KVClientResponse, KVServerRequest}
import zio.kvstore.protocol.KVClientRequest.given
import zio.kvstore.protocol.KVClientResponse.given
import zio.kvstore.protocol.KVServerRequest.given

final class KVClient private (private val raft: RaftClient):

  def run(): ZIO[Scope, Throwable, Unit] = raft.run().unit

  def connect(): ZIO[Any, Throwable, Unit] = raft.connect()

  def set(key: String, value: String): ZIO[Any, Throwable, Unit] =
    val payload: ByteVector = summon[Codec[KVClientRequest]].encode(KVClientRequest.Set(key, value)).require.bytes
    raft.submitCommand(payload).map(bytes => summon[Codec[Unit]].decode(bytes.bits).require.value)

  def get(key: String): ZIO[Any, Throwable, Option[String]] =
    val payload: ByteVector = summon[Codec[KVClientRequest]].encode(KVClientRequest.Get(key)).require.bytes
    raft.submitCommand(payload).map(bytes => summon[Codec[Option[String]]].decode(bytes.bits).require.value)

  // Registers the watch on the server; notifications are emitted via `notifications` stream
  def watch(key: String): ZIO[Any, Throwable, Unit] =
    val payload: ByteVector = summon[Codec[KVClientRequest]].encode(KVClientRequest.Watch(key)).require.bytes
    raft.submitCommand(payload).map(bytes => summon[Codec[Unit]].decode(bytes.bits).require.value)

  // Stream of server-side watch notifications
  val notifications: ZStream[Any, Nothing, KVServerRequest.Notification] =
    raft.serverRequests.map { env =>
      summon[Codec[KVServerRequest]].decode(env.payload.bits).require.value
    }.collect { case n: KVServerRequest.Notification => n }

object KVClient:
  private val defaultCapabilities: Map[String, String] = Map("app" -> "kvstore-cli")

  def make(endpoints: Map[MemberId, String]): ZIO[zio.zmq.ZContext & Scope, Throwable, KVClient] =
    RaftClient.make(endpoints, defaultCapabilities).map(new KVClient(_))
