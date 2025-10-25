package zio.raft.sessionstatemachine

import zio.{UIO, Chunk, ZIO}
import zio.raft.{HMap, StateMachine}
import zio.stream.{Stream, ZStream}
import scodec.Codec

/** Mixin trait providing scodec-based serialization for HMap state.
  *
  * This trait provides default implementations of takeSnapshot and restoreFromSnapshot using scodec codecs and
  * CodecPipeline for efficient streaming.
  *
  * Can be mixed into any StateMachine using HMap with SessionSchema[R, SR] ++ UserSchema.
  *
  * @tparam R
  *   Response marker type (sealed trait encompassing all command responses)
  * @tparam SR
  *   Server request type
  * @tparam UserSchema
  *   The user-defined schema
  *
  * @example
  *   {{{
  * sealed trait MyResponse
  * given Codec[MyResponse] = ...
  * given Codec[MyValueType] = ...
  *
  * class MyStateMachine extends SessionStateMachine[MyCmd, MyResponse, MyServerReq, MyUserSchema]
  *   with ScodecSerialization[MyResponse, MyServerReq, MyUserSchema]:
  *
  *   val codecs = summon[HMap.TypeclassMap[Schema, Codec]]
  *   // takeSnapshot and restoreFromSnapshot provided!
  *   }}}
  */
trait ScodecSerialization[R, SR, UserSchema <: Tuple]:
  this: StateMachine[HMap[Tuple.Concat[SessionSchema[R, SR], UserSchema]], ?] =>

  // Type alias for convenience
  type Schema = Tuple.Concat[SessionSchema[R, SR], UserSchema]

  /** TypeclassMap providing codecs for all value types in the schema.
    *
    * Users must provide this - typically via summon after defining given Codec instances.
    */
  val codecs: HMap.TypeclassMap[Schema, Codec]

  /** Shared entry codec for serialization/deserialization.
    *
    * Encoding: (fullKey: ByteVector, value: Any)
    *   - fullKey is length-prefixed (contains prefix + separator + key bytes)
    *   - value is length-prefixed with codec determined by extracting prefix from fullKey
    *
    * This codec enables streaming serialization where each entry is self-delimiting.
    */
  protected val entryCodec: Codec[(scodec.bits.ByteVector, Any)] =
    import scodec.codecs.{variableSizeBytes, int32}
    import scodec.bits.ByteVector

    val keyCodec = variableSizeBytes(int32, scodec.codecs.bytes)
    keyCodec.flatZip { key =>
      val fullKey = key.toArray
      val prefix = HMap.extractPrefix(fullKey).getOrElse {
        throw new IllegalStateException("Invalid key: no prefix found")
      }
      val valueCodec: Codec[Any] = codecs.forPrefix(prefix)
      variableSizeBytes(int32, valueCodec)
    }

  /** Serialize HMap state to a stream of bytes using scodec.
    *
    * Uses TypeclassMap to get appropriate codec for each prefix's value type. Streams entries with 1MB chunks to handle
    * large values efficiently.
    */
  def takeSnapshot(state: HMap[Schema]): Stream[Nothing, Byte] =
    import scodec.bits.ByteVector

    val rawMap = state.toRaw
    val chunkSize = 1024 * 1024 * 8 // 8 million bits = 1 MB

    // Stream each entry separately - true streaming
    ZStream.fromIterable(rawMap).flatMap { case (fullKey, value) =>
      ZStream.fromZIO(
        ZIO.fromEither {
          entryCodec.encode((ByteVector(fullKey), value)).toEither
        }
      ).flatMap { bv =>
        ZStream.unfoldChunk(bv) { bv =>
          if bv.isEmpty then None
          else
            Some((
              Chunk.fromArray(bv.take(chunkSize).toByteArray),
              bv.drop(chunkSize)
            ))
        }
      }.orDieWith(err => new RuntimeException(s"Failed to encode snapshot: ${err.messageWithContext}"))
    }

  /** Deserialize HMap state from a stream of bytes using scodec.
    *
    * Uses TypeclassMap to get appropriate codec for each prefix's value type. Uses CodecPipeline for stateful decoding
    * across chunk boundaries.
    */
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[Schema]] =
    stream.chunks
      .via(CodecPipeline.decoder(entryCodec))
      .runFold(Map.empty[Array[Byte], Any]) { case (entries, (key, value)) =>
        entries + (key.toArray -> value)
      }
      .map(HMap.fromRaw[Schema])
      .orDieWith(err => new RuntimeException(s"Failed to restore from snapshot: ${err.messageWithContext}"))
end ScodecSerialization
