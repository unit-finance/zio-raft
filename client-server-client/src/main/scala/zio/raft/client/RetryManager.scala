package zio.raft.client

import zio._
import zio.raft.protocol._
import scodec.bits.ByteVector
import java.time.Instant

/**
 * Client retry manager for timeout-based request handling.
 * 
 * Manages:
 * - Pending request tracking with timestamps
 * - Timeout detection and retry logic
 * - Request state management during connection changes
 * - Request completion and error handling
 */
class RetryManager private (
  pendingRequests: Ref[Map[RequestId, PendingRequest]],
  config: ClientConfig
) {
  
  def addPendingRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector]
  ): UIO[Unit] = 
    for {
      now <- Clock.instant
      pendingRequest = PendingRequest(request, promise, now, now)
      _ <- pendingRequests.update(_.updated(request.requestId, pendingRequest))
    } yield ()
  
  def hasPendingRequest(requestId: RequestId): UIO[Boolean] = 
    pendingRequests.get.map(_.contains(requestId))
  
  def completePendingRequest(requestId: RequestId, response: ByteVector): UIO[Unit] = 
    for {
      requestsMap <- pendingRequests.get
      _ <- requestsMap.get(requestId) match {
        case Some(pendingRequest) =>
          for {
            _ <- pendingRequest.promise.succeed(response).ignore
            _ <- pendingRequests.update(_.removed(requestId))
          } yield ()
        case None =>
          ZIO.logWarning(s"Attempted to complete unknown request: $requestId")
      }
    } yield ()
  
  def completePendingRequestWithError(requestId: RequestId, error: RequestErrorReason): UIO[Unit] = 
    for {
      requestsMap <- pendingRequests.get
      _ <- requestsMap.get(requestId) match {
        case Some(pendingRequest) =>
          for {
            _ <- pendingRequest.promise.fail(error).ignore
            _ <- pendingRequests.update(_.removed(requestId))
          } yield ()
        case None =>
          ZIO.logWarning(s"Attempted to error unknown request: $requestId")
      }
    } yield ()
  
  def updateLastSent(requestId: RequestId, sentAt: Instant): UIO[Unit] = 
    pendingRequests.update { requestsMap =>
      requestsMap.get(requestId) match {
        case Some(pendingRequest) =>
          val updated = pendingRequest.copy(lastSentAt = sentAt)
          requestsMap.updated(requestId, updated)
        case None =>
          requestsMap
      }
    }
  
  def getLastSent(requestId: RequestId): UIO[Option[Instant]] = 
    pendingRequests.get.map(_.get(requestId).map(_.lastSentAt))
  
  def getTimedOutRequests(currentTime: Instant, timeout: Duration): UIO[List[RequestId]] = 
    for {
      requestsMap <- pendingRequests.get
      timedOut = requestsMap.filter { case (_, pendingRequest) =>
        val timeSinceLastSent = java.time.Duration.between(pendingRequest.lastSentAt, currentTime)
        timeSinceLastSent.compareTo(java.time.Duration.ofSeconds(timeout.toSeconds)) > 0
      }.keys.toList
    } yield timedOut
  
  def resendRequest(requestId: RequestId, resentAt: Instant): UIO[Boolean] = 
    pendingRequests.modify { requestsMap =>
      requestsMap.get(requestId) match {
        case Some(pendingRequest) =>
          val updated = pendingRequest.copy(lastSentAt = resentAt)
          (true, requestsMap.updated(requestId, updated))
        case None =>
          (false, requestsMap)
      }
    }
  
  def handleStateChange(
    oldState: ClientConnectionState,
    newState: ClientConnectionState,
    timestamp: Instant
  ): UIO[List[ClientRequest]] = {
    (oldState, newState) match {
      case (Connected, Connecting) =>
        // Connection lost, retain pending requests
        ZIO.succeed(List.empty)
      
      case (Connected, Disconnected) =>
        // User disconnect, error all pending requests
        for {
          requestsMap <- pendingRequests.get
          _ <- ZIO.foreachDiscard(requestsMap.values) { pendingRequest =>
            pendingRequest.promise.fail(ConnectionLost).ignore
          }
          _ <- pendingRequests.set(Map.empty)
        } yield List.empty
      
      case (Connecting, Connected) =>
        // Connection established, resend all pending requests
        for {
          requestsMap <- pendingRequests.get
          requests = requestsMap.values.map(_.request).toList
          // Update timestamps for all pending requests
          _ <- ZIO.foreachDiscard(requestsMap.values) { pendingRequest =>
            updateLastSent(pendingRequest.request.requestId, timestamp)
          }
        } yield requests
      
      case _ =>
        // Other transitions don't require special handling
        ZIO.succeed(List.empty)
    }
  }
  
  def handleRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector],
    connectionState: ClientConnectionState
  ): UIO[RequestAction] = {
    connectionState match {
      case Connecting =>
        // Queue request, don't send yet
        for {
          _ <- addPendingRequest(request, promise)
        } yield QueueRequest
      
      case Connected =>
        // Queue and send request
        for {
          _ <- addPendingRequest(request, promise)
        } yield QueueAndSendRequest
      
      case Disconnected =>
        // Reject request
        for {
          _ <- promise.fail(NotConnected).ignore
        } yield RejectRequest
    }
  }
  
  def getAllPendingRequests(): UIO[Map[RequestId, PendingRequest]] = 
    pendingRequests.get
  
  def clearAllPendingRequests(error: Option[RequestErrorReason]): UIO[Unit] = 
    for {
      requestsMap <- pendingRequests.get
      _ <- error match {
        case Some(err) =>
          ZIO.foreachDiscard(requestsMap.values) { pendingRequest =>
            pendingRequest.promise.fail(err).ignore
          }
        case None =>
          ZIO.unit
      }
      _ <- pendingRequests.set(Map.empty)
    } yield ()
}

object RetryManager {
  
  /**
   * Create a RetryManager with the given configuration.
   */
  def make(config: ClientConfig): UIO[RetryManager] = 
    for {
      pendingRequests <- Ref.make(Map.empty[RequestId, PendingRequest])
    } yield new RetryManager(pendingRequests, config)
}

// RequestAction type is defined in ConnectionManager.scala
