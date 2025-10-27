package zio.kvstore

import zio.raft.{Command, Index, HMap}
import zio.raft.sessionstatemachine.{SessionStateMachine, ScodecSerialization, ServerRequestForSession, StateWriter}
import zio.raft.sessionstatemachine.asResponseType // Extension method
import zio.raft.protocol.SessionId
import zio.{ZIO}
import zio.prelude.Newtype

import scodec.Codec
import scodec.codecs.{ascii, discriminated, fixedSizeBytes, utf8_32}
import java.time.Instant

// Simple KV store - no sessions, no server requests
// We just use the session framework for the template pattern and HMap

sealed trait KVCommand extends Command

object KVCommand:
  case class Set(key: String, value: String) extends KVCommand:
    type Response = SetDone

  case class Get(key: String) extends KVCommand:
    type Response = GetResult

  // Codec for KVCommand (needed by ZmqRpc)
  val getCodec = utf8_32.as[Get]
  val setCodec = (utf8_32 :: utf8_32).as[Set]
  given commandCodec: Codec[KVCommand] = discriminated[KVCommand]
    .by(fixedSizeBytes(1, ascii))
    .typecase("S", setCodec)
    .typecase("G", getCodec)

// Response marker type - encompasses all KV command responses
sealed trait KVResponse
case class SetDone() extends KVResponse
case class GetResult(value: Option[String]) extends KVResponse

// KV Schema - single prefix for key-value data
object KVKey extends Newtype[String]
type KVKey = KVKey.Type
given HMap.KeyLike[KVKey] = HMap.KeyLike.forNewtype(KVKey)

type KVSchema = ("kv", KVKey, String) *: EmptyTuple
type KVCompleteSchema = Tuple.Concat[zio.raft.sessionstatemachine.SessionSchema[KVResponse, KVServerRequest], KVSchema]

// Dummy server request type (KV store doesn't use server requests)
case class NoServerRequest()

type KVServerRequest = NoServerRequest

// KV store with scodec serialization mixin
class KVStateMachine extends SessionStateMachine[KVCommand, KVResponse, KVServerRequest, KVSchema]
    with ScodecSerialization[KVResponse, KVServerRequest, KVSchema]:

  // Import provided codecs from Codecs object
  import zio.raft.sessionstatemachine.Codecs.{sessionMetadataCodec, requestIdCodec, pendingServerRequestCodec}

  // Provide codecs for response types
  given Codec[SetDone] = scodec.codecs.provide(SetDone())
  given Codec[GetResult] = scodec.codecs.optional(scodec.codecs.bool, utf8_32).xmap(
    opt => GetResult(opt),
    gr => gr.value
  )

  // Provide codec for response marker type
  given Codec[KVResponse] = discriminated[KVResponse]
    .by(fixedSizeBytes(1, ascii))
    .typecase("S", summon[Codec[SetDone]])
    .typecase("G", summon[Codec[GetResult]])

  // Provide codec for our value types
  given Codec[String] = utf8_32

  // Dummy codec for NoServerRequest
  given Codec[NoServerRequest] = scodec.codecs.provide(NoServerRequest())

  val codecs = summon[HMap.TypeclassMap[Schema, Codec]]

  protected def applyCommand(
    createdAt: Instant,
    sessionId: SessionId,
    command: KVCommand
  ): StateWriter[HMap[KVCompleteSchema], ServerRequestForSession[KVServerRequest], command.Response & KVResponse] =
    command match
      case set @ KVCommand.Set(key, value) =>
        for
          state <- StateWriter.get[HMap[KVCompleteSchema]]
          newState = state.updated["kv"](KVKey(key), value)
          _ <- StateWriter.set(newState)
        yield SetDone().asResponseType(command, set)

      case get @ KVCommand.Get(key) =>
        for
          state <- StateWriter.get[HMap[KVCompleteSchema]]
          result = state.get["kv"](KVKey(key))
        yield GetResult(result).asResponseType(command, get)

  protected def handleSessionCreated(
    createdAt: Instant,
    sessionId: SessionId,
    capabilities: Map[String, String]
  ): StateWriter[HMap[KVCompleteSchema], ServerRequestForSession[KVServerRequest], Unit] =
    StateWriter.succeed(())

  protected def handleSessionExpired(
    createdAt: Instant,
    sessionId: SessionId,
    capabilities: Map[String, String]
  ): StateWriter[HMap[KVCompleteSchema], ServerRequestForSession[KVServerRequest], Unit] =
    StateWriter.succeed(())

  // takeSnapshot and restoreFromSnapshot are now provided by SessionStateMachine base class!
  // They use the TypeclassMap[Schema, Codec] passed in constructor

  override def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
    false // Disable snapshots for now
end KVStateMachine

// HttpServer commented out for now - SessionCommand type system complexity
// The KVStateMachine implementation demonstrates:
// - SessionStateMachine extension
// - HMap with typed schema
// - Scodec-based serialization
// - TypeclassMap usage

// TODO: Implement proper HTTP server with session management

object KVStoreApp extends zio.ZIOAppDefault:
  override def run =
    // Simplified demo - just show that KVStateMachine compiles
    // Full integration would require session management setup
    ZIO.succeed(println("KVStore with SessionStateMachine - compilation successful")).exitCode
