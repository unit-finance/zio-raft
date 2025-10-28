package zio.raft.protocol

import scodec.*
import scodec.bits.*
import scodec.codecs.*
import java.time.Instant
import zio.raft.protocol.RejectionReason.*
import zio.raft.protocol.SessionCloseReason.*

/** scodec binary serialization codecs for ZIO Raft protocol messages.
  *
  * This module provides binary serialization support for all protocol messages using scodec, including:
  *   - Protocol signature and version handling
  *   - Discriminated union encoding for message hierarchies
  *   - Type-safe serialization for newtypes
  *   - Backward/forward compatibility support
  */
object Codecs {

  // ============================================================================
  // BASIC TYPE CODECS
  // ============================================================================

  /** Codec for protocol signature validation.
    */
  val protocolSignatureCodec: Codec[Unit] = {
    constant(ByteVector(PROTOCOL_SIGNATURE))
  }

  /** Codec for protocol version.
    */
  val protocolVersionCodec: Codec[Byte] = {
    byte.exmap(
      version =>
        if (version == PROTOCOL_VERSION) Attempt.Successful(version)
        else Attempt.Failure(Err(s"Unsupported protocol version: $version, expected: $PROTOCOL_VERSION")),
      version => Attempt.Successful(version)
    )
  }

  /** Codec for constant protocol version.
    */
  val constantProtocolVersionCodec: Codec[Unit] = {
    constant(ByteVector(PROTOCOL_VERSION))
  }

  /** Protocol header codec (signature + version).
    */
  val protocolHeaderCodec: Codec[Unit] = {
    protocolSignatureCodec ~> constantProtocolVersionCodec
  }

  // ============================================================================
  // NEWTYPE CODECS
  // ============================================================================

  /** Codec for SessionId (UUID string).
    */
  implicit val sessionIdCodec: Codec[SessionId] = {
    variableSizeBytes(uint16, utf8).xmap(
      str => SessionId(str),
      sessionId => SessionId.unwrap(sessionId)
    )
  }

  /** Codec for RequestId (Long counter).
    */
  implicit val requestIdCodec: Codec[RequestId] = {
    int64.xmap(
      id => RequestId(id),
      requestId => RequestId.unwrap(requestId)
    )
  }

  /** Codec for MemberId from protocol.
    */
  implicit val memberIdCodec: Codec[MemberId] = {
    variableSizeBytes(uint16, utf8).xmap(MemberId.fromString, MemberId.unwrap)
  }

  /** Codec for Nonce.
    */
  implicit val nonceCodec: Codec[Nonce] = {
    long(64).xmap(Nonce.fromLong, Nonce.unwrap)
  }

  /** Codec for Instant timestamps.
    */
  implicit val instantCodec: Codec[Instant] = {
    int64.xmap(
      epochMillis => Instant.ofEpochMilli(epochMillis),
      instant => instant.toEpochMilli
    )
  }

  /** Codec for ByteVector payloads.
    */
  implicit val payloadCodec: Codec[ByteVector] = {
    variableSizeBytes(int32, bytes)
  }

  /** Codec for correlationId (opaque string).
    */
  implicit val correlationIdCodec: Codec[CorrelationId] = {
    variableSizeBytes(uint16, utf8).xmap(CorrelationId.fromString, CorrelationId.unwrap)
  }

  /** Codec for string-to-string maps (capabilities).
    */
  implicit val capabilitiesCodec: Codec[Map[String, String]] = {
    listOfN(uint16, (variableSizeBytes(uint16, utf8) :: variableSizeBytes(uint16, utf8)).as[(String, String)]).xmap(
      list => list.toMap,
      map => map.toList
    )
  }

  // ============================================================================
  // REASON ENUM CODECS
  // ============================================================================

  /** Codec for RejectionReason.
    */
  implicit val rejectionReasonCodec: Codec[RejectionReason] = {
    discriminated[RejectionReason]
      .by(uint8)
      .subcaseP(1) { case NotLeader => NotLeader }(provide(NotLeader))
      .subcaseP(2) { case RejectionReason.SessionExpired => RejectionReason.SessionExpired }(provide(
        RejectionReason.SessionExpired
      ))
      .subcaseP(3) { case InvalidCapabilities => InvalidCapabilities }(provide(InvalidCapabilities))
  }

  /** Codec for SessionCloseReason.
    */
  implicit val sessionCloseReasonCodec: Codec[SessionCloseReason] = {
    discriminated[SessionCloseReason]
      .by(uint8)
      .subcaseP(1) { case Shutdown => Shutdown }(provide(Shutdown))
      .subcaseP(2) { case NotLeaderAnymore => NotLeaderAnymore }(provide(NotLeaderAnymore))
      .subcaseP(3) { case SessionError => SessionError }(provide(SessionError))
      .subcaseP(4) { case ConnectionClosed => ConnectionClosed }(provide(ConnectionClosed))
      .subcaseP(5) { case SessionCloseReason.SessionExpired => SessionCloseReason.SessionExpired }(provide(
        SessionCloseReason.SessionExpired
      ))
  }

  /** Codec for CloseReason.
    */
  implicit val closeReasonCodec: Codec[CloseReason] = {
    discriminated[CloseReason]
      .by(uint8)
      .subcaseP(1) { case CloseReason.ClientShutdown => CloseReason.ClientShutdown }(
        provide(CloseReason.ClientShutdown)
      )
  }

  // ============================================================================
  // CLIENT MESSAGE CODECS
  // ============================================================================

  /** Codec for Query message. */
  implicit def queryCodec: Codec[Query] = {
    (correlationIdCodec :: payloadCodec :: instantCodec).as[Query]
  }

  /** Codec for CreateSession message.
    */
  implicit val createSessionCodec: Codec[CreateSession] = {
    (capabilitiesCodec :: nonceCodec).as[CreateSession]
  }

  /** Codec for ContinueSession message.
    */
  implicit val continueSessionCodec: Codec[ContinueSession] = {
    (sessionIdCodec :: nonceCodec).as[ContinueSession]
  }

  /** Codec for KeepAlive message.
    */
  implicit val keepAliveCodec: Codec[KeepAlive] = {
    instantCodec.as[KeepAlive]
  }

  /** Codec for ClientRequest message.
    */
  implicit val clientRequestCodec: Codec[ClientRequest] = {
    (requestIdCodec :: requestIdCodec :: payloadCodec :: instantCodec).as[ClientRequest]
  }

  /** Codec for ServerRequestAck message.
    */
  implicit val serverRequestAckCodec: Codec[ServerRequestAck] = {
    requestIdCodec.as[ServerRequestAck]
  }

  /** Codec for CloseSession message.
    */
  implicit val closeSessionCodec: Codec[CloseSession] = {
    closeReasonCodec.as[CloseSession]
  }

  /** Codec for ConnectionClosed client message. Simple constant codec since it's a case object with no parameters.
    * Note: Uses fully qualified name to avoid conflict with SessionCloseReason.ConnectionClosed
    */
  implicit val connectionClosedClientMessageCodec: Codec[zio.raft.protocol.ConnectionClosed.type] = {
    provide(zio.raft.protocol.ConnectionClosed)
  }

  /** Discriminated codec for all ClientMessage types.
    */
  implicit val clientMessageCodec: Codec[ClientMessage] = {
    protocolHeaderCodec ~> discriminated[ClientMessage]
      .by(uint8)
      .typecase(1, createSessionCodec)
      .typecase(2, continueSessionCodec)
      .typecase(3, keepAliveCodec)
      .typecase(4, clientRequestCodec)
      .typecase(5, serverRequestAckCodec)
      .typecase(6, closeSessionCodec)
      .typecase(7, connectionClosedClientMessageCodec)
      .typecase(8, queryCodec)
  }

  // ============================================================================
  // SERVER MESSAGE CODECS
  // ============================================================================

  /** Codec for QueryResponse message. */
  implicit def queryResponseCodec: Codec[QueryResponse] = {
    (correlationIdCodec :: payloadCodec).as[QueryResponse]
  }

  /** Codec for SessionCreated message.
    */
  implicit val sessionCreatedCodec: Codec[SessionCreated] = {
    (sessionIdCodec :: nonceCodec).as[SessionCreated]
  }

  /** Codec for SessionContinued message.
    */
  implicit val sessionContinuedCodec: Codec[SessionContinued] = {
    nonceCodec.as[SessionContinued]
  }

  /** Codec for SessionRejected message.
    */
  implicit val sessionRejectedCodec: Codec[SessionRejected] = {
    (rejectionReasonCodec :: nonceCodec :: optional(bool, memberIdCodec)).as[SessionRejected]
  }

  /** Codec for SessionClosed message.
    */
  implicit val sessionClosedCodec: Codec[SessionClosed] = {
    (sessionCloseReasonCodec :: optional(bool, memberIdCodec)).as[SessionClosed]
  }

  /** Codec for KeepAliveResponse message.
    */
  implicit val keepAliveResponseCodec: Codec[KeepAliveResponse] = {
    instantCodec.as[KeepAliveResponse]
  }

  /** Codec for ClientResponse message.
    */
  implicit val clientResponseCodec: Codec[ClientResponse] = {
    (requestIdCodec :: payloadCodec).as[ClientResponse]
  }

  /** Codec for ServerRequest message.
    */
  implicit val serverRequestCodec: Codec[ServerRequest] = {
    (requestIdCodec :: payloadCodec :: instantCodec).as[ServerRequest]
  }

  /** Codec for RequestErrorReason.
    */
  implicit val requestErrorReasonCodec: Codec[RequestErrorReason] = {
    discriminated[RequestErrorReason]
      .by(uint8)
      .subcaseP(1) { case RequestErrorReason.ResponseEvicted => RequestErrorReason.ResponseEvicted }(provide(
        RequestErrorReason.ResponseEvicted
      ))
  }

  /** Codec for RequestError message.
    */
  implicit val requestErrorCodec: Codec[RequestError] = {
    (requestIdCodec :: requestErrorReasonCodec).as[RequestError]
  }

  /** Discriminated codec for all ServerMessage types.
    */
  implicit val serverMessageCodec: Codec[ServerMessage] = {
    protocolHeaderCodec ~> discriminated[ServerMessage]
      .by(uint8)
      .typecase(1, sessionCreatedCodec)
      .typecase(2, sessionContinuedCodec)
      .typecase(3, sessionRejectedCodec)
      .typecase(4, sessionClosedCodec)
      .typecase(5, keepAliveResponseCodec)
      .typecase(6, clientResponseCodec)
      .typecase(7, serverRequestCodec)
      .typecase(8, requestErrorCodec)
      .typecase(9, queryResponseCodec)
  }

  // ============================================================================
  // UTILITY FUNCTIONS
  // ============================================================================

  /** Encode a ClientMessage to bytes.
    */
  def encodeClientMessage(message: ClientMessage): Attempt[ByteVector] = {
    clientMessageCodec.encode(message).map(_.bytes)
  }

  /** Decode bytes to a ClientMessage.
    */
  def decodeClientMessage(bytes: ByteVector): Attempt[ClientMessage] = {
    clientMessageCodec.decode(bytes.bits).map(_.value)
  }

  /** Encode a ServerMessage to bytes.
    */
  def encodeServerMessage(message: ServerMessage): Attempt[ByteVector] = {
    serverMessageCodec.encode(message).map(_.bytes)
  }

  /** Decode bytes to a ServerMessage.
    */
  def decodeServerMessage(bytes: ByteVector): Attempt[ServerMessage] = {
    serverMessageCodec.decode(bytes.bits).map(_.value)
  }

}
