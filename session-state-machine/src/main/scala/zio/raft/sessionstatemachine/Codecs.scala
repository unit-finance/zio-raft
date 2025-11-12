package zio.raft.sessionstatemachine

import zio.raft.protocol.RequestId
import scodec.Codec
import java.time.Instant
import scodec.codecs.{utf8_32, listOfN, uint8, discriminated, int32}
import zio.raft.protocol.Codecs.{sessionIdCodec, requestIdCodec as requestIdCodecProtocol, instantCodec}

/** Scodec codec definitions for SessionStateMachine types.
  *
  * Provides codecs for:
  *   - SessionMetadata
  *   - RequestId
  *   - PendingServerRequest[SR] (automatically derived from payload codec)
  */
object Codecs:
  val capabilitiesCodec: Codec[Map[String, String]] =
    listOfN(int32, (utf8_32 :: utf8_32).as[(String, String)]).xmap(_.toMap, _.toList)

  /** Codec for SessionMetadata.
    *
    * Encodes as: Map[String, String] (capabilities) ++ Long (timestamp)
    */
  given sessionMetadataCodec: Codec[SessionMetadata] =
    import scodec.codecs.*
    (capabilitiesCodec :: int64).xmap(
      { case (caps, ts) => SessionMetadata(caps, Instant.ofEpochMilli(ts)) },
      sm => (sm.capabilities, sm.createdAt.toEpochMilli)
    )

  /** Codec for RequestId.
    *
    * Encodes as: Long
    */
  given requestIdCodec: Codec[RequestId] = requestIdCodecProtocol

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

  /** Codec for SessionCommand, parameterized by UC (user command) and SR (server request payload). Requires codecs for
    * UC and SR in scope.
    */
  given sessionCommandCodec[UC <: zio.raft.Command, SR, E](using
    ucCodec: Codec[UC],
    srCodec: Codec[SR]
  ): Codec[SessionCommand[UC, SR, E]] =
    val clientRequestV0: Codec[SessionCommand.ClientRequest[UC, SR, E]] =
      (instantCodec :: sessionIdCodec :: requestIdCodec :: requestIdCodec :: ucCodec)
        .as[SessionCommand.ClientRequest[UC, SR, E]]
    val clientRequestCodec: Codec[SessionCommand.ClientRequest[UC, SR, E]] =
      (uint8 :: clientRequestV0).xmap(
        { case (_, cmd) => cmd },
        cmd => (0, cmd)
      )

    val serverRequestAckV0: Codec[SessionCommand.ServerRequestAck[SR]] =
      (instantCodec :: sessionIdCodec :: requestIdCodec).as[SessionCommand.ServerRequestAck[SR]]
    val serverRequestAckCodec: Codec[SessionCommand.ServerRequestAck[SR]] =
      (uint8 :: serverRequestAckV0).xmap(
        { case (_, cmd) => cmd },
        cmd => (0, cmd)
      )

    val createSessionV0: Codec[SessionCommand.CreateSession[SR]] =
      (instantCodec :: sessionIdCodec :: capabilitiesCodec).as[SessionCommand.CreateSession[SR]]
    val createSessionCodec: Codec[SessionCommand.CreateSession[SR]] =
      (uint8 :: createSessionV0).xmap(
        { case (_, cmd) => cmd },
        cmd => (0, cmd)
      )

    val sessionExpiredV0: Codec[SessionCommand.SessionExpired[SR]] =
      (instantCodec :: sessionIdCodec).as[SessionCommand.SessionExpired[SR]]
    val sessionExpiredCodec: Codec[SessionCommand.SessionExpired[SR]] =
      (uint8 :: sessionExpiredV0).xmap(
        { case (_, cmd) => cmd },
        cmd => (0, cmd)
      )

    val getRequestsForRetryV0: Codec[SessionCommand.GetRequestsForRetry[SR]] =
      (instantCodec :: instantCodec).as[SessionCommand.GetRequestsForRetry[SR]]
    val getRequestsForRetryCodec: Codec[SessionCommand.GetRequestsForRetry[SR]] =
      (uint8 :: getRequestsForRetryV0).xmap(
        { case (_, cmd) => cmd },
        cmd => (0, cmd)
      )

    discriminated[SessionCommand[UC, SR, E]]
      .by(uint8)
      .typecase(0, clientRequestCodec)
      .typecase(1, serverRequestAckCodec)
      .typecase(2, createSessionCodec)
      .typecase(3, sessionExpiredCodec)
      .typecase(4, getRequestsForRetryCodec)
  end sessionCommandCodec
end Codecs
