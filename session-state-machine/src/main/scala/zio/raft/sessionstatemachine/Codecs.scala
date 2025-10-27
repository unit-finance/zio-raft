package zio.raft.sessionstatemachine

import zio.raft.protocol.RequestId
import scodec.Codec
import java.time.Instant

/** Scodec codec definitions for SessionStateMachine types.
  *
  * Provides codecs for:
  *   - SessionMetadata
  *   - RequestId
  *   - PendingServerRequest[SR] (automatically derived from payload codec)
  */
object Codecs:

  /** Codec for SessionMetadata.
    *
    * Encodes as: Map[String, String] (capabilities) ++ Long (timestamp)
    */
  given sessionMetadataCodec: Codec[SessionMetadata] =
    import scodec.codecs.*
    (list(utf8_32 :: utf8_32).xmap(_.toMap, _.toList) :: int64).xmap(
      { case (caps, ts) => SessionMetadata(caps, Instant.ofEpochMilli(ts)) },
      sm => (sm.capabilities, sm.createdAt.toEpochMilli)
    )

  /** Codec for RequestId.
    *
    * Encodes as: Long
    */
  given requestIdCodec: Codec[RequestId] =
    import scodec.codecs.int64
    int64.xmap(RequestId(_), RequestId.unwrap)

  /** Codec for PendingServerRequest - automatically derived from payload codec.
    *
    * Just provide a codec for your server request payload type SR, and the PendingServerRequest[SR] codec is
    * automatically available.
    *
    * Encodes as: SR (payload) ++ Long (timestamp)
    *
    * @tparam SR
    *   The server request payload type
    *
    * @example
    *   {{{
    * given Codec[MyServerRequest] = ...
    * // Codec[PendingServerRequest[MyServerRequest]] automatically available!
    *   }}}
    */
  given pendingServerRequestCodec[SR](using payloadCodec: Codec[SR]): Codec[PendingServerRequest[SR]] =
    import scodec.codecs.int64
    (payloadCodec :: int64).xmap(
      { case (payload, ts) => PendingServerRequest(payload, Instant.ofEpochMilli(ts)) },
      p => (p.payload, p.lastSentAt.toEpochMilli)
    )
