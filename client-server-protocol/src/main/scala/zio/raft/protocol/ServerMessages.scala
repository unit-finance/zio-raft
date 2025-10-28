package zio.raft.protocol

import java.time.Instant
import scodec.bits.ByteVector

/** Server-to-client message definitions for ZIO Raft client-server communication.
  *
  * This module defines all messages sent from servers to clients, including:
  *   - Session management responses
  *   - Command execution results
  *   - Keep-alive responses
  *   - Server-initiated work dispatch
  *   - Error handling and leader redirection
  */

/** Base trait for all server-to-client messages.
  */
sealed trait ServerMessage

/** Successful session creation with server-generated session ID.
  *
  * @param sessionId
  *   Server-generated unique session identifier
  * @param nonce
  *   Echoed client nonce from CreateSession request
  */
case class SessionCreated(
  sessionId: SessionId,
  nonce: Nonce
) extends ServerMessage

/** Successful session resumption. Server responds to same ZeroMQ routing ID, no session ID needed.
  *
  * @param nonce
  *   Echoed client nonce from ContinueSession request
  */
case class SessionContinued(
  nonce: Nonce
) extends ServerMessage

/** Session creation or continuation rejection. Server responds to same ZeroMQ routing ID, no session ID needed.
  *
  * @param reason
  *   Why the session was rejected
  * @param nonce
  *   Echoed client nonce from request for validation
  * @param leaderId
  *   Optional current leader ID for client redirection
  */
case class SessionRejected(
  reason: RejectionReason,
  nonce: Nonce,
  leaderId: Option[MemberId]
) extends ServerMessage

/** Server-initiated session termination. Server responds to same ZeroMQ routing ID, no session ID needed.
  *
  * @param reason
  *   Why the server is closing the session
  * @param leaderId
  *   Optional leader ID if reason is leadership-related
  */
case class SessionClosed(
  reason: SessionCloseReason,
  leaderId: Option[MemberId]
) extends ServerMessage

/** Heartbeat acknowledgment echoing client timestamp. Server responds to same ZeroMQ routing ID, no session ID needed.
  *
  * @param timestamp
  *   Echoed timestamp from client KeepAlive for RTT measurement
  */
case class KeepAliveResponse(
  timestamp: Instant
) extends ServerMessage

/** Client request execution result. Server responds to same ZeroMQ routing ID, no session ID needed.
  *
  * @param requestId
  *   Echoed request ID from ClientRequest for correlation
  * @param result
  *   Binary result data from command/query execution
  */
case class ClientResponse(
  requestId: RequestId,
  result: ByteVector
) extends ServerMessage

/** Response to a read-only Query. Server responds to same ZeroMQ routing ID, no session ID needed.
  *
  * @param correlationId
  *   Echoed client correlation ID from Query
  * @param result
  *   Binary result data from query execution
  */
case class QueryResponse(
  correlationId: CorrelationId,
  result: ByteVector
) extends ServerMessage

/** RequestError indicates the server could not return the original response because it was deterministically evicted
  * per lowestPendingRequestId. Session ID is implied by routing.
  *
  * @param requestId
  *   The client request ID this error refers to
  * @param reason
  *   The error reason (currently only ResponseEvicted)
  */
case class RequestError(
  requestId: RequestId,
  reason: RequestErrorReason
) extends ServerMessage

/** Server-initiated work dispatch to client (one-way pattern). Server targets via ZeroMQ routing ID, no session ID
  * needed.
  *
  * @param requestId
  *   Unique identifier for acknowledgment correlation
  * @param payload
  *   Binary work payload for client processing
  * @param createdAt
  *   Timestamp when request was created
  */
case class ServerRequest(
  requestId: RequestId,
  payload: ByteVector,
  createdAt: Instant
) extends ServerMessage

// ============================================================================
// REASON ENUMS
// ============================================================================

/** Reasons for session rejection.
  */
sealed trait RejectionReason

object RejectionReason {

  /** Server is not the current Raft leader.
    */
  case object NotLeader extends RejectionReason

  /** Requested session ID does not exist (expired or never created).
    */
  case object SessionExpired extends RejectionReason

  /** Client capabilities are invalid or unsupported.
    */
  case object InvalidCapabilities extends RejectionReason
}

/** Reasons for server-initiated session closure.
  */
sealed trait SessionCloseReason

object SessionCloseReason {

  /** Server is shutting down gracefully.
    */
  case object Shutdown extends SessionCloseReason

  /** Server lost leadership and cannot serve clients.
    */
  case object NotLeaderAnymore extends SessionCloseReason

  /** Client sent unexpected/invalid message for current session state.
    */
  case object SessionError extends SessionCloseReason

  /** Client connection was closed by the server or OS (e.g. timeout, network error).
    */
  case object ConnectionClosed extends SessionCloseReason

  /** Session expired due to client inactivity (no keep-alive messages).
    */
  case object SessionExpired extends SessionCloseReason
}

/** Reasons for RequestError.
  */
sealed trait RequestErrorReason

object RequestErrorReason {
  case object ResponseEvicted extends RequestErrorReason
}
