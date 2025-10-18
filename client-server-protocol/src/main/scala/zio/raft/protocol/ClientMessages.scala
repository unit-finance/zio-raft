package zio.raft.protocol

import java.time.Instant
import scodec.bits.ByteVector

/**
 * Client-to-server message definitions for ZIO Raft client-server communication.
 *
 * This module defines all messages sent from clients to servers, including:
 * - Session management (create, continue, close)
 * - Command submission
 * - Keep-alive/heartbeat protocol
 * - Server request acknowledgment
 */

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

/**
 * Reasons for client-initiated session closure.
 */
sealed trait CloseReason

object CloseReason {
  /**
   * Client is shutting down normally.
   */
  case object ClientShutdown extends CloseReason
}
