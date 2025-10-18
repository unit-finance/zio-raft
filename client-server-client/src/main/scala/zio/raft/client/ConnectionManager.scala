package zio.raft.client

import zio._
import zio.raft.protocol._
import scodec.bits.ByteVector

/**
 * Client connection state management and request queuing.
 * 
 * Manages:
 * - Connection states (Connecting, Connected, Disconnected)
 * - Request queuing based on connection state
 * - Session creation and continuation
 * - Leader redirection handling
 * - Keep-alive mechanism
 */
trait ConnectionManager {
  
  /**
   * Get current connection state.
   */
  def currentState: UIO[ClientConnectionState]
  
  /**
   * Start connection process (transition to Connecting).
   */
  def startConnection(): UIO[Unit]
  
  /**
   * Handle successful session establishment (transition to Connected).
   */
  def sessionEstablished(sessionId: SessionId): UIO[Unit]
  
  /**
   * Handle session rejection with potential leader redirection.
   */
  def handleSessionRejected(rejection: SessionRejected): UIO[Boolean]
  
  /**
   * Handle connection failure (transition to Connecting or Disconnected).
   */
  def handleConnectionFailure(error: Throwable): UIO[Unit]
  
  /**
   * Gracefully disconnect (transition to Disconnected).
   */
  def disconnect(): UIO[Unit]
  
  /**
   * Submit a client request based on current connection state.
   * 
   * @param request Client request to submit
   * @param promise Promise to complete when response is received
   * @return Action to take (queue, queue+send, or reject)
   */
  def submitRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector]
  ): UIO[RequestAction]
  
  /**
   * Get all pending requests that should be resent.
   * Called when transitioning from Connecting to Connected.
   */
  def getPendingRequests(): UIO[List[PendingRequest]]
  
  /**
   * Handle successful client response.
   */
  def handleClientResponse(response: ClientResponse): UIO[Unit]
  
  /**
   * Handle request error.
   */
  def handleRequestError(error: RequestError): UIO[Unit]
  
  /**
   * Get current session ID if connected.
   */
  def getSessionId(): UIO[Option[SessionId]]
  
  /**
   * Check if session is active.
   */
  def isSessionActive(sessionId: SessionId): UIO[Boolean]
  
  /**
   * Close current session.
   */
  def closeSession(sessionId: SessionId): UIO[Boolean]
}

object ConnectionManager {
  
  /**
   * Create a ConnectionManager with the given configuration.
   */
  def make(config: ClientConfig): UIO[ConnectionManager] = 
    for {
      state <- Ref.make[ClientConnectionState](Disconnected)
      sessionId <- Ref.make[Option[SessionId]](None)
      pendingRequests <- Ref.make(Map.empty[RequestId, PendingRequest])
      requestIdCounter <- Ref.make(0L)
    } yield new ConnectionManagerImpl(state, sessionId, pendingRequests, requestIdCounter, config)
  
  /**
   * Create a ConnectionManager for testing.
   */
  def create(): ConnectionManager = {
    // Test implementation
    new ConnectionManagerTestImpl()
  }
}

/**
 * Internal implementation of ConnectionManager.
 */
private class ConnectionManagerImpl(
  state: Ref[ClientConnectionState],
  sessionId: Ref[Option[SessionId]],
  pendingRequests: Ref[Map[RequestId, PendingRequest]],
  requestIdCounter: Ref[Long],
  config: ClientConfig
) extends ConnectionManager {
  
  override def currentState: UIO[ClientConnectionState] = 
    state.get
  
  override def startConnection(): UIO[Unit] = 
    for {
      _ <- state.set(Connecting)
      _ <- ZIO.logInfo("Connection manager: transitioning to Connecting state")
    } yield ()
  
  override def sessionEstablished(sessionId: SessionId): UIO[Unit] = 
    for {
      _ <- state.set(Connected)
      _ <- this.sessionId.set(Some(sessionId))
      _ <- ZIO.logInfo(s"Connection manager: session established $sessionId, transitioning to Connected")
    } yield ()
  
  override def handleSessionRejected(rejection: SessionRejected): UIO[Boolean] = 
    for {
      _ <- ZIO.logWarning(s"Session rejected: ${rejection.reason}")
      handled <- rejection.reason match {
        case NotLeader =>
          // Handle leader redirection
          rejection.leaderId match {
            case Some(leaderId) =>
              for {
                _ <- ZIO.logInfo(s"Redirecting to leader: $leaderId")
                // Would update cluster addresses here
              } yield true
            case None =>
              ZIO.succeed(false)
          }
        case SessionNotFound =>
          // Session doesn't exist, need to create new one
          for {
            _ <- sessionId.set(None)
            _ <- state.set(Connecting)
          } yield true
        case _ =>
          ZIO.succeed(false)
      }
    } yield handled
  
  override def handleConnectionFailure(error: Throwable): UIO[Unit] = 
    for {
      currentState <- state.get
      _ <- currentState match {
        case Connected =>
          // Connection lost, transition to Connecting for reconnection
          for {
            _ <- state.set(Connecting)
            _ <- ZIO.logInfo(s"Connection lost, transitioning to Connecting: ${error.getMessage}")
          } yield ()
        case Connecting =>
          // Stay in Connecting state, will retry
          ZIO.logInfo(s"Connection attempt failed, staying in Connecting: ${error.getMessage}")
        case Disconnected =>
          // User-initiated disconnection, no action needed
          ZIO.unit
      }
    } yield ()
  
  override def disconnect(): UIO[Unit] = 
    for {
      _ <- state.set(Disconnected)
      _ <- sessionId.set(None)
      // Error all pending requests
      _ <- errorAllPendingRequests(ConnectionLost)
      _ <- ZIO.logInfo("Connection manager: gracefully disconnected")
    } yield ()
  
  override def submitRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector]
  ): UIO[RequestAction] = 
    for {
      currentState <- state.get
      now <- Clock.instant
      pendingRequest = PendingRequest(request, promise, now, now)
      action <- currentState match {
        case Connecting =>
          // Queue request, don't send yet
          for {
            _ <- pendingRequests.update(_.updated(request.requestId, pendingRequest))
          } yield QueueRequest
        case Connected =>
          // Queue and send request
          for {
            _ <- pendingRequests.update(_.updated(request.requestId, pendingRequest))
          } yield QueueAndSendRequest
        case Disconnected =>
          // Reject request
          for {
            _ <- promise.fail(NotConnected).ignore
          } yield RejectRequest
      }
    } yield action
  
  override def getPendingRequests(): UIO[List[PendingRequest]] = 
    pendingRequests.get.map(_.values.toList)
  
  override def handleClientResponse(response: ClientResponse): UIO[Unit] = 
    for {
      requestsMap <- pendingRequests.get
      _ <- requestsMap.get(response.requestId) match {
        case Some(pendingRequest) =>
          for {
            _ <- pendingRequest.promise.succeed(response.result).ignore
            _ <- pendingRequests.update(_.removed(response.requestId))
          } yield ()
        case None =>
          ZIO.logWarning(s"Received response for unknown request: ${response.requestId}")
      }
    } yield ()
  
  override def handleRequestError(error: RequestError): UIO[Unit] = 
    for {
      // RequestError is a general error that affects the entire session
      // Fail all pending requests with this error reason
      _ <- errorAllPendingRequests(error.reason)
      _ <- ZIO.logWarning(s"Received session error: ${error.reason}, leader: ${error.leaderId}")
      // If it's a NotLeaderRequest error, we might want to update our leader info
      _ <- error.leaderId match {
        case Some(leaderId) if error.reason == NotLeaderRequest =>
          ZIO.logInfo(s"Updating leader to: $leaderId")
        case _ => ZIO.unit
      }
    } yield ()
  
  override def getSessionId(): UIO[Option[SessionId]] = 
    sessionId.get
  
  override def isSessionActive(sessionId: SessionId): UIO[Boolean] = 
    for {
      currentSessionId <- this.sessionId.get
      currentState <- state.get
    } yield currentSessionId.contains(sessionId) && currentState == Connected
  
  override def closeSession(sessionId: SessionId): UIO[Boolean] = 
    for {
      currentSessionId <- this.sessionId.get
      result <- if (currentSessionId.contains(sessionId)) {
        for {
          _ <- this.sessionId.set(None)
          _ <- state.set(Disconnected)
          _ <- errorAllPendingRequests(SessionTerminated)
        } yield true
      } else {
        ZIO.succeed(false)
      }
    } yield result
  
  private def errorAllPendingRequests(reason: RequestErrorReason): UIO[Unit] = 
    for {
      requestsMap <- pendingRequests.get
      _ <- ZIO.foreachDiscard(requestsMap.values) { pendingRequest =>
        pendingRequest.promise.fail(reason).ignore
      }
      _ <- pendingRequests.set(Map.empty)
    } yield ()
  
  /**
   * Generate next request ID.
   */
  def nextRequestId(): UIO[RequestId] = 
    requestIdCounter.updateAndGet(_ + 1).map(RequestId.fromLong)
}

/**
 * Test implementation for ConnectionManager.
 */
private class ConnectionManagerTestImpl extends ConnectionManager {
  
  override def currentState: UIO[ClientConnectionState] = 
    ZIO.succeed(Disconnected)
  
  override def startConnection(): UIO[Unit] = 
    ZIO.unit
  
  override def sessionEstablished(sessionId: SessionId): UIO[Unit] = 
    ZIO.unit
  
  override def handleSessionRejected(rejection: SessionRejected): UIO[Boolean] = 
    ZIO.succeed(false)
  
  override def handleConnectionFailure(error: Throwable): UIO[Unit] = 
    ZIO.unit
  
  override def disconnect(): UIO[Unit] = 
    ZIO.unit
  
  override def submitRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, ByteVector]
  ): UIO[RequestAction] = 
    ZIO.succeed(RejectRequest)
  
  override def getPendingRequests(): UIO[List[PendingRequest]] = 
    ZIO.succeed(List.empty)
  
  override def handleClientResponse(response: ClientResponse): UIO[Unit] = 
    ZIO.unit
  
  override def handleRequestError(error: RequestError): UIO[Unit] = 
    ZIO.unit
  
  override def getSessionId(): UIO[Option[SessionId]] = 
    ZIO.succeed(None)
  
  override def isSessionActive(sessionId: SessionId): UIO[Boolean] = 
    ZIO.succeed(false)
  
  override def closeSession(sessionId: SessionId): UIO[Boolean] = 
    ZIO.succeed(false)
}

/**
 * Actions to take when submitting a request.
 */
sealed trait RequestAction

case object QueueRequest extends RequestAction
case object QueueAndSendRequest extends RequestAction  
case object RejectRequest extends RequestAction

// Note: Additional RequestErrorReason values (NotConnected, ConnectionLost, SessionTerminated) 
// are now defined in the protocol Messages.scala file
