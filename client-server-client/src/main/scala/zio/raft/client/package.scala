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


  // Note: ClientConfig is defined in ClientConfig.scala
  // Note: ClientState is now defined in RaftClient.scala as a functional ADT

  // Server-initiated request idempotency tracking
  case class ServerRequestTracker(
    lastAcknowledgedRequestId: protocol.RequestId = protocol.RequestId.fromLong(-1L)  // Start from -1 so first request is 0
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
  
  /**
   * Wrapper for RequestId Ref to avoid confusion with updateAndGet.
   * Starts from 0 and increments with .next.
   */
  case class RequestIdRef(ref: Ref[protocol.RequestId]) {
    def next: UIO[protocol.RequestId] = ref.updateAndGet(_.next)
  }
  
  object RequestIdRef {
    def make: UIO[RequestIdRef] =
      Ref.make(protocol.RequestId.fromLong(0L)).map(RequestIdRef(_))
  }
  
  // Client actions for stream-based processing
  sealed trait ClientAction
  
  object ClientAction {
    case object Connect extends ClientAction
    case object Disconnect extends ClientAction
    case class SubmitCommand(
      payload: scodec.bits.ByteVector,
      promise: Promise[Throwable, scodec.bits.ByteVector]
    ) extends ClientAction
  }
}
