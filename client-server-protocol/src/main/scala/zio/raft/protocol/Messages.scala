package zio.raft.protocol

import java.time.Instant
import scodec.bits.ByteVector

/**
 * Protocol message definitions for ZIO Raft client-server communication.
 *
 * This module defines the complete message hierarchy for bidirectional
 * communication between Raft clients and servers, including:
 * - Session management (create, continue, close)
 * - Command submission and response handling
 * - Keep-alive/heartbeat protocol
 * - Server-initiated work dispatch with acknowledgment
 * - Error handling and leader redirection
 */

// ============================================================================
// CLIENT MESSAGES (Client → Server)
// ============================================================================

/**
 * Base trait for all client-to-server messages.
 */
sealed trait ClientMessage

/**
 * Create a new durable session with the Raft cluster.
 *
 * @param capabilities Client capability definitions (name -> version/config)
 * @param nonce Client-generated nonce for response correlation
 */
case class CreateSession(
  capabilities: Map[String, String],
  nonce: Nonce
) extends ClientMessage {
  require(capabilities.nonEmpty, "Capabilities must be non-empty")
}

/**
 * Resume an existing durable session after reconnection.
 *
 * @param sessionId The session ID to resume (from previous SessionCreated)
 * @param nonce Client-generated nonce for response correlation
 */
case class ContinueSession(
  sessionId: SessionId,
  nonce: Nonce
) extends ClientMessage

/**
 * Heartbeat message to maintain session liveness.
 * Server derives session ID from ZeroMQ routing ID.
 *
 * @param timestamp Client-generated timestamp for RTT measurement
 */
case class KeepAlive(
  timestamp: Instant
) extends ClientMessage

/**
 * Generic client request for both read and write operations.
 * Server derives session ID from ZeroMQ routing ID.
 *
 * @param requestId Unique identifier for request deduplication and correlation
 * @param payload Binary payload containing the actual command or query
 * @param createdAt Timestamp when request was created (for debugging/monitoring)
 */
case class ClientRequest(
  requestId: RequestId,
  payload: ByteVector,
  createdAt: Instant
) extends ClientMessage

/**
 * Acknowledgment of server-initiated request receipt.
 * Server derives session ID from ZeroMQ routing ID.
 *
 * @param requestId The server request ID being acknowledged
 */
case class ServerRequestAck(
  requestId: RequestId
) extends ClientMessage

/**
 * Explicit session termination by client.
 * Server derives session ID from ZeroMQ routing ID.
 *
 * @param reason Why the client is closing the session
 */
case class CloseSession(
  reason: CloseReason
) extends ClientMessage

// ============================================================================
// SERVER MESSAGES (Server → Client)
// ============================================================================

/**
 * Base trait for all server-to-client messages.
 */
sealed trait ServerMessage

/**
 * Successful session creation with server-generated session ID.
 *
 * @param sessionId Server-generated unique session identifier
 * @param nonce Echoed client nonce from CreateSession request
 */
case class SessionCreated(
  sessionId: SessionId,
  nonce: Nonce
) extends ServerMessage

/**
 * Successful session resumption.
 * Server responds to same ZeroMQ routing ID, no session ID needed.
 *
 * @param nonce Echoed client nonce from ContinueSession request
 */
case class SessionContinued(
  nonce: Nonce
) extends ServerMessage

/**
 * Session creation or continuation rejection.
 * Server responds to same ZeroMQ routing ID, no session ID needed.
 *
 * @param reason Why the session was rejected
 * @param nonce Echoed client nonce from request for validation
 * @param leaderId Optional current leader ID for client redirection
 */
case class SessionRejected(
  reason: RejectionReason,
  nonce: Nonce,
  leaderId: Option[MemberId]
) extends ServerMessage

/**
 * Server-initiated session termination.
 * Server responds to same ZeroMQ routing ID, no session ID needed.
 *
 * @param reason Why the server is closing the session
 * @param leaderId Optional leader ID if reason is leadership-related
 */
case class SessionClosed(
  reason: SessionCloseReason,
  leaderId: Option[MemberId]
) extends ServerMessage

/**
 * Heartbeat acknowledgment echoing client timestamp.
 * Server responds to same ZeroMQ routing ID, no session ID needed.
 *
 * @param timestamp Echoed timestamp from client KeepAlive for RTT measurement
 */
case class KeepAliveResponse(
  timestamp: Instant
) extends ServerMessage

/**
 * Client request execution result.
 * Server responds to same ZeroMQ routing ID, no session ID needed.
 *
 * @param requestId Echoed request ID from ClientRequest for correlation
 * @param result Binary result data from command/query execution
 */
case class ClientResponse(
  requestId: RequestId,
  result: ByteVector
) extends ServerMessage

/**
 * Server-initiated work dispatch to client (one-way pattern).
 * Server targets via ZeroMQ routing ID, no session ID needed.
 *
 * @param requestId Unique identifier for acknowledgment correlation
 * @param payload Binary work payload for client processing
 * @param createdAt Timestamp when request was created
 */
case class ServerRequest(
  requestId: RequestId,
  payload: ByteVector,
  createdAt: Instant
) extends ServerMessage

/**
 * Client request processing error.
 * Server responds to same ZeroMQ routing ID, no session ID needed.
 *
 * @param reason Error classification and details
 * @param leaderId Optional leader ID for redirection
 */
case class RequestError(
  reason: RequestErrorReason,
  leaderId: Option[MemberId]
) extends ServerMessage

// ============================================================================
// REASON ENUMS
// ============================================================================

/**
 * Reasons for session rejection.
 */
sealed trait RejectionReason

/**
 * Server is not the current Raft leader.
 */
case object NotLeader extends RejectionReason

/**
 * Requested session ID does not exist (expired or never created).
 */
case object SessionNotFound extends RejectionReason

/**
 * Server-specific rejection reasons.
 */

/**
 * Session creation conflicts with existing session.
 */
case object SessionConflict extends RejectionReason

/**
 * Client is not authorized for this operation.
 */
case object NotAuthorized extends RejectionReason

/**
 * Client capabilities are invalid or unsupported.
 */
case object InvalidCapabilities extends RejectionReason

/**
 * Reasons for server-initiated session closure.
 */
sealed trait SessionCloseReason

/**
 * Server is shutting down gracefully.
 */
case object Shutdown extends SessionCloseReason

/**
 * Server lost leadership and cannot serve clients.
 */
case object NotLeaderAnymore extends SessionCloseReason

/**
 * Client sent unexpected/invalid message for current session state.
 */
case object SessionError extends SessionCloseReason

/**
 * Client connection was closed by the server or OS (e.g. timeout, network error).
 */
case object ConnectionClosed extends SessionCloseReason

/**
 * Session expired due to client inactivity (no keep-alive messages).
 */
case object SessionTimeout extends SessionCloseReason

/**
 * Reasons for client request processing errors.
 */
sealed trait RequestErrorReason

/**
 * Server is not the current Raft leader (extends NotLeader for requests).
 */
case object NotLeaderRequest extends RequestErrorReason

/**
 * Request payload is malformed or invalid.
 */
case object InvalidRequest extends RequestErrorReason

/**
 * Client connection-related error reasons.
 */

/**
 * Client is not currently connected to the server.
 */
case object NotConnected extends RequestErrorReason

/**
 * Client connection was closed by the server.
 */
case object ConnectionLost extends RequestErrorReason

/**
 * Client session was closed by the server.
 */
case object SessionTerminated extends RequestErrorReason

/**
 * Server-specific error reasons.
 */

/**
 * Protocol version is not supported by the server.
 */
case object UnsupportedVersion extends RequestErrorReason

/**
 * Request payload exceeds server limits.
 */
case object PayloadTooLarge extends RequestErrorReason

/**
 * Server is temporarily unavailable.
 */
case object ServiceUnavailable extends RequestErrorReason

/**
 * Server failed to process the request due to internal error.
 */
case object ProcessingFailed extends RequestErrorReason

/**
 * Request timed out on the server side.
 */
case object RequestTimeout extends RequestErrorReason

/**
 * Reasons for client-initiated session closure.
 */
sealed trait CloseReason

/**
 * Client is shutting down normally.
 */
case object ClientShutdown extends CloseReason

/**
 * Client switching to different server.
 */
case object SwitchingServer extends CloseReason

// ClientMessage and ServerMessage are used directly without a common abstraction
