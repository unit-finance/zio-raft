package zio.kvstore

import zio.raft.{Command, Index, HMap}
import zio.raft.sessionstatemachine.{SessionStateMachine, ServerRequestForSession, StateWriter}
import zio.raft.protocol.SessionId
import zio.stream.{Stream, ZStream}
import zio.{Chunk, UIO, ZIO}
import zio.prelude.Newtype

import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs.{ascii, discriminated, fixedSizeBytes, utf8_32, variableSizeBits, variableSizeBytes, int32}
import java.time.Instant
import scodec.bits.ByteVector
import zio.stream.ZPipeline

// Simple KV store - no sessions, no server requests
// We just use the session framework for the template pattern and HMap

sealed trait KVCommand extends Command

object KVCommand:
  case class Set(key: String, value: String) extends KVCommand:
    type Response = Unit

  case class Get(key: String) extends KVCommand:
    type Response = Option[String]
  
  // Codec for KVCommand (needed by ZmqRpc)
  val getCodec = utf8_32.as[Get]
  val setCodec = (utf8_32 :: utf8_32).as[Set]
  given commandCodec: Codec[KVCommand] = discriminated[KVCommand]
    .by(fixedSizeBytes(1, ascii))
    .typecase("S", setCodec)
    .typecase("G", getCodec)

// KV Schema - single prefix for key-value data
object KVKey extends Newtype[String]
type KVKey = KVKey.Type
given HMap.KeyLike[KVKey] = HMap.KeyLike.forNewtype(KVKey)

type KVSchema = ("kv", KVKey, String) *: EmptyTuple
type KVCompleteSchema = Tuple.Concat[zio.raft.sessionstatemachine.SessionSchema, KVSchema]

// KV store with proper streaming serialization using TypeclassMap
class KVStateMachine(
  codecs: HMap.TypeclassMap[KVCompleteSchema, Codec]
) extends SessionStateMachine[KVCommand, Nothing, KVSchema]:
  val keyCodec = variableSizeBytes(int32, scodec.codecs.bytes)
  val codec = keyCodec.flatZip(key => {
    val fullKey = key.toArray
    val prefix = HMap.extractPrefix(fullKey).getOrElse {
      throw new IllegalStateException("Invalid key: no prefix found")
    }
    val valueCodec: Codec[Any] = codecs.forPrefix(prefix)
    variableSizeBytes(int32, valueCodec)
  })

  
  protected def applyCommand(command: KVCommand, createdAt: Instant): StateWriter[HMap[KVCompleteSchema], ServerRequestForSession[Nothing], command.Response] =
    command match
      case KVCommand.Set(key, value) =>
        for {
          state <- StateWriter.get[HMap[KVCompleteSchema]]
          newState = state.updated["kv"](KVKey(key), value)
          _ <- StateWriter.set(newState)
        } yield ().asInstanceOf[command.Response]
      
      case KVCommand.Get(key) =>
        for {
          state <- StateWriter.get[HMap[KVCompleteSchema]]
          result = state.get["kv"](KVKey(key))
        } yield result.asInstanceOf[command.Response]
  
  protected def handleSessionCreated(sessionId: SessionId, capabilities: Map[String, String], createdAt: Instant): StateWriter[HMap[KVCompleteSchema], ServerRequestForSession[Nothing], Unit] =
    StateWriter.succeed(())
  
  protected def handleSessionExpired(sessionId: SessionId, capabilities: Map[String, String], createdAt: Instant): StateWriter[HMap[KVCompleteSchema], ServerRequestForSession[Nothing], Unit] =
    StateWriter.succeed(())
  
  override def takeSnapshot(state: HMap[KVCompleteSchema]): Stream[Nothing, Byte] =
    val rawMap = state.toRaw      
    
    // Stream each entry separately - true streaming, no loading all into memory
    ZStream.fromIterable(rawMap).mapZIO { case (fullKey, value) =>
      ZIO.attempt(Chunk.from(codec.encode((ByteVector(fullKey), value)).require.toByteArray)).orDie
    }.flattenChunks
  
  override def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[KVCompleteSchema]] =
    stream.chunks
      .via(CodecPipeline.decoder(codec))
      .runFold(Map.empty[Array[Byte], Any]) { case (entries, (key, value)) =>        
        entries + (key.toArray -> value)
      }
      .map(HMap.fromRaw[KVCompleteSchema])
      .orDieWith(err => new RuntimeException(s"Failed to restore from snapshot: ${err.messageWithContext}"))
  
  override def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean = 
    false  // Disable snapshots for now

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
