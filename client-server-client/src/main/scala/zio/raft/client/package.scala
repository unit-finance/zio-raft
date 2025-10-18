package zio.raft

import zio.*

/**
 * Client-side implementation for ZIO Raft client-server communication.
 * 
 * This package provides:
 * - Reactive client implementation with unified stream processing
 * - Connection state management with request queuing
 * - Simple timeout-based request handling
 * - Session management with durable session continuation
 * - Server-initiated request processing with consecutive ID tracking
 */
package object client {

  // Client configuration defaults
  val DEFAULT_KEEP_ALIVE_INTERVAL: Duration = 30.seconds
  val DEFAULT_CONNECTION_TIMEOUT: Duration = 5.seconds
  val DEFAULT_SESSION_TIMEOUT: Duration = 90.seconds
  val DEFAULT_REQUEST_TIMEOUT: Duration = 10.seconds

  // Client connection states for request queuing behavior
  sealed trait ClientConnectionState
  case object Connecting extends ClientConnectionState    // Attempting connection/session
  case object Connected extends ClientConnectionState     // Active session, can send requests  
  case object Disconnected extends ClientConnectionState  // User-initiated disconnection

  // Client action types for unified stream processing
  sealed trait ClientAction
  case class NetworkMessageAction(message: protocol.ServerMessage) extends ClientAction
  case class UserClientRequestAction(
    request: protocol.ClientRequest,
    promise: Promise[protocol.RequestErrorReason, scodec.bits.ByteVector]
  ) extends ClientAction
  case object TimeoutCheckAction extends ClientAction
  case object SendKeepAliveAction extends ClientAction

  // Pending request tracking for timeout logic
  case class PendingRequest(
    request: protocol.ClientRequest,
    promise: Promise[protocol.RequestErrorReason, scodec.bits.ByteVector],
    createdAt: java.time.Instant,
    lastSentAt: java.time.Instant
  )

  // Note: ClientConfig is defined in ClientConfig.scala

  // Client connection state with request management
  case class ClientState(
    connectionState: ClientConnectionState = Disconnected,
    sessionId: Option[protocol.SessionId] = None,
    pendingRequests: Map[protocol.RequestId, PendingRequest] = Map.empty,
    capabilities: Map[String, String] = Map.empty,
    config: ClientConfig = ClientConfig(),
    serverRequestTracker: ServerRequestTracker = ServerRequestTracker()
  )

  // Server-initiated request idempotency tracking
  case class ServerRequestTracker(
    lastAcknowledgedRequestId: protocol.RequestId = protocol.RequestId.zero  // Last consecutive request ID we acknowledged
  ) {
    /**
     * Check if we should process this server request.
     * Only process if it's the next consecutive request ID.
     */
    def shouldProcess(requestId: protocol.RequestId): Boolean = {
      requestId == lastAcknowledgedRequestId.next
    }
    
    /**
     * Acknowledge a request ID, updating our tracking.
     * Should only be called after successful processing.
     */
    def acknowledge(requestId: protocol.RequestId): ServerRequestTracker = {
      require(requestId == lastAcknowledgedRequestId.next, 
        s"Can only acknowledge consecutive request ID. Expected ${lastAcknowledgedRequestId.next}, got $requestId")
      copy(lastAcknowledgedRequestId = requestId)
    }
  }
}
