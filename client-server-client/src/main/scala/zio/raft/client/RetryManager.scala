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
trait RetryManager {
  
  /**
   * Add a pending request for tracking.
   */
  def addPendingRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector]
  ): UIO[Unit]
  
  /**
   * Check if a request is currently pending.
   */
  def hasPendingRequest(requestId: RequestId): UIO[Boolean]
  
  /**
   * Complete a pending request with success response.
   */
  def completePendingRequest(requestId: RequestId, response: ByteVector): UIO[Unit]
  
  /**
   * Complete a pending request with error.
   */
  def completePendingRequestWithError(requestId: RequestId, error: RequestErrorReason): UIO[Unit]
  
  /**
   * Update last sent timestamp for a request.
   */
  def updateLastSent(requestId: RequestId, sentAt: Instant): UIO[Unit]
  
  /**
   * Get last sent timestamp for a request.
   */
  def getLastSent(requestId: RequestId): UIO[Option[Instant]]
  
  /**
   * Get requests that have timed out.
   */
  def getTimedOutRequests(currentTime: Instant, timeout: Duration): UIO[List[RequestId]]
  
  /**
   * Resend a timed out request (update timestamp).
   */
  def resendRequest(requestId: RequestId, resentAt: Instant): UIO[Boolean]
  
  /**
   * Handle connection state change.
   * 
   * @param oldState Previous connection state
   * @param newState New connection state
   * @param timestamp Optional timestamp for state change
   * @return List of requests to resend (when transitioning to Connected)
   */
  def handleStateChange(
    oldState: ClientConnectionState,
    newState: ClientConnectionState,
    timestamp: Instant = Instant.now()
  ): UIO[List[ClientRequest]]
  
  /**
   * Handle a new request based on connection state.
   */
  def handleRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector],
    connectionState: ClientConnectionState
  ): UIO[RequestAction]
  
  /**
   * Get all pending requests.
   */
  def getAllPendingRequests(): UIO[Map[RequestId, PendingRequest]]
  
  /**
   * Clear all pending requests (with optional error).
   */
  def clearAllPendingRequests(error: Option[RequestErrorReason] = None): UIO[Unit]
}

object RetryManager {
  
  /**
   * Create a RetryManager with the given configuration.
   */
  def make(config: ClientConfig): UIO[RetryManager] = 
    for {
      pendingRequests <- Ref.make(Map.empty[RequestId, PendingRequest])
    } yield new RetryManagerImpl(pendingRequests, config)
  
  /**
   * Create a RetryManager for testing.
   */
  def create(): RetryManager = {
    // Test implementation
    new RetryManagerTestImpl()
  }
}

/**
 * Internal implementation of RetryManager.
 */
private class RetryManagerImpl(
  pendingRequests: Ref[Map[RequestId, PendingRequest]],
  config: ClientConfig
) extends RetryManager {
  
  override def addPendingRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector]
  ): UIO[Unit] = 
    for {
      now <- Clock.instant
      pendingRequest = PendingRequest(request, promise, now, now)
      _ <- pendingRequests.update(_.updated(request.requestId, pendingRequest))
    } yield ()
  
  override def hasPendingRequest(requestId: RequestId): UIO[Boolean] = 
    pendingRequests.get.map(_.contains(requestId))
  
  override def completePendingRequest(requestId: RequestId, response: ByteVector): UIO[Unit] = 
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
  
  override def completePendingRequestWithError(requestId: RequestId, error: RequestErrorReason): UIO[Unit] = 
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
  
  override def updateLastSent(requestId: RequestId, sentAt: Instant): UIO[Unit] = 
    pendingRequests.update { requestsMap =>
      requestsMap.get(requestId) match {
        case Some(pendingRequest) =>
          val updated = pendingRequest.copy(lastSentAt = sentAt)
          requestsMap.updated(requestId, updated)
        case None =>
          requestsMap
      }
    }
  
  override def getLastSent(requestId: RequestId): UIO[Option[Instant]] = 
    pendingRequests.get.map(_.get(requestId).map(_.lastSentAt))
  
  override def getTimedOutRequests(currentTime: Instant, timeout: Duration): UIO[List[RequestId]] = 
    for {
      requestsMap <- pendingRequests.get
      timedOut = requestsMap.filter { case (_, pendingRequest) =>
        val timeSinceLastSent = java.time.Duration.between(pendingRequest.lastSentAt, currentTime)
        timeSinceLastSent.compareTo(java.time.Duration.ofSeconds(timeout.toSeconds)) > 0
      }.keys.toList
    } yield timedOut
  
  override def resendRequest(requestId: RequestId, resentAt: Instant): UIO[Boolean] = 
    pendingRequests.modify { requestsMap =>
      requestsMap.get(requestId) match {
        case Some(pendingRequest) =>
          val updated = pendingRequest.copy(lastSentAt = resentAt)
          (true, requestsMap.updated(requestId, updated))
        case None =>
          (false, requestsMap)
      }
    }
  
  override def handleStateChange(
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
  
  override def handleRequest(
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
  
  override def getAllPendingRequests(): UIO[Map[RequestId, PendingRequest]] = 
    pendingRequests.get
  
  override def clearAllPendingRequests(error: Option[RequestErrorReason]): UIO[Unit] = 
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

/**
 * Test implementation for RetryManager.
 */
private class RetryManagerTestImpl extends RetryManager {
  
  override def addPendingRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector]
  ): UIO[Unit] = 
    ZIO.unit
  
  override def hasPendingRequest(requestId: RequestId): UIO[Boolean] = 
    ZIO.succeed(false)
  
  override def completePendingRequest(requestId: RequestId, response: ByteVector): UIO[Unit] = 
    ZIO.unit
  
  override def completePendingRequestWithError(requestId: RequestId, error: RequestErrorReason): UIO[Unit] = 
    ZIO.unit
  
  override def updateLastSent(requestId: RequestId, sentAt: Instant): UIO[Unit] = 
    ZIO.unit
  
  override def getLastSent(requestId: RequestId): UIO[Option[Instant]] = 
    ZIO.succeed(None)
  
  override def getTimedOutRequests(currentTime: Instant, timeout: Duration): UIO[List[RequestId]] = 
    ZIO.succeed(List.empty)
  
  override def resendRequest(requestId: RequestId, resentAt: Instant): UIO[Boolean] = 
    ZIO.succeed(false)
  
  override def handleStateChange(
    oldState: ClientConnectionState,
    newState: ClientConnectionState,
    timestamp: Instant
  ): UIO[List[ClientRequest]] = 
    ZIO.succeed(List.empty)
  
  override def handleRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector],
    connectionState: ClientConnectionState
  ): UIO[RequestAction] = 
    ZIO.succeed(RejectRequest)
  
  override def getAllPendingRequests(): UIO[Map[RequestId, PendingRequest]] = 
    ZIO.succeed(Map.empty)
  
  override def clearAllPendingRequests(error: Option[RequestErrorReason]): UIO[Unit] = 
    ZIO.unit
}

// RequestAction type is defined in ConnectionManager.scala
