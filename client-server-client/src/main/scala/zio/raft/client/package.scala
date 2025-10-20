package zio.raft

import zio.*

/** Client-side implementation for ZIO Raft client-server communication.
  *
  * This package provides:
  *   - Reactive client implementation with unified stream processing
  *   - Connection state management with request queuing
  *   - Simple timeout-based request handling
  *   - Session management with durable session continuation
  *   - Server-initiated request processing with consecutive ID tracking
  */
package object client {

  // Note: ClientConfig is defined in ClientConfig.scala
  // Note: ClientState is now defined in RaftClient.scala as a functional ADT

  // Server request processing result
  sealed trait ServerRequestResult

  object ServerRequestResult {

    /** Process this new request */
    case object Process extends ServerRequestResult

    /** Old request - already processed */
    case object OldRequest extends ServerRequestResult

    /** Out of order - skip this request */
    case object OutOfOrder extends ServerRequestResult
  }

  // Server-initiated request idempotency tracking
  case class ServerRequestTracker(
      lastAcknowledgedRequestId: protocol.RequestId = protocol.RequestId.zero // Start from 0 so first request is 1
  ) {

    /** Check what to do with this server request. Returns Process, OldRequest, or OutOfOrder.
      */
    def shouldProcess(requestId: protocol.RequestId): ServerRequestResult = {
      if (requestId == lastAcknowledgedRequestId.next) {
        ServerRequestResult.Process
      } else if (protocol.RequestId.unwrap(requestId) <= protocol.RequestId.unwrap(lastAcknowledgedRequestId)) {
        ServerRequestResult.OldRequest
      } else {
        ServerRequestResult.OutOfOrder
      }
    }

    /** Acknowledge a request ID, updating our tracking. Should only be called after successful processing.
      */
    def acknowledge(requestId: protocol.RequestId): ServerRequestTracker = {
      require(
        requestId == lastAcknowledgedRequestId.next,
        s"Can only acknowledge consecutive request ID. Expected ${lastAcknowledgedRequestId.next}, got $requestId"
      )
      copy(lastAcknowledgedRequestId = requestId)
    }
  }

  /** Wrapper for RequestId Ref to avoid confusion with updateAndGet. Starts from 0 and increments with .next.
    */
  case class RequestIdRef(ref: Ref[protocol.RequestId]) {
    def next: UIO[protocol.RequestId] = ref.updateAndGet(_.next)
  }

  object RequestIdRef {
    def make: UIO[RequestIdRef] =
      Ref.make(protocol.RequestId.zero).map(RequestIdRef(_))
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
