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
class ConnectionManager private (
  state: Ref[ClientConnectionState],
  sessionId: Ref[Option[SessionId]],
  pendingRequests: Ref[Map[RequestId, PendingRequest]],
  requestIdCounter: Ref[Long],
  config: ClientConfig
) {
  
  def currentState: UIO[ClientConnectionState] = 
    state.get
  
  def startConnection(): UIO[Unit] = 
    for {
      _ <- state.set(Connecting)
      _ <- ZIO.logInfo("Connection manager: transitioning to Connecting state")
    } yield ()
  
  def sessionEstablished(sessionId: SessionId): UIO[Unit] = 
    for {
      _ <- state.set(Connected)
      _ <- this.sessionId.set(Some(sessionId))
      _ <- ZIO.logInfo(s"Connection manager: session established $sessionId, transitioning to Connected")
    } yield ()
  
  def handleSessionRejected(rejection: SessionRejected): UIO[Boolean] = 
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
  
  def handleConnectionFailure(error: Throwable): UIO[Unit] = 
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
  
  def disconnect(): UIO[Unit] = 
    for {
      _ <- state.set(Disconnected)
      _ <- sessionId.set(None)
      // Error all pending requests
      _ <- errorAllPendingRequests(ConnectionLost)
      _ <- ZIO.logInfo("Connection manager: gracefully disconnected")
    } yield ()
  
  def submitRequest(
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
  
  def getPendingRequests(): UIO[List[PendingRequest]] = 
    pendingRequests.get.map(_.values.toList)
  
  def handleClientResponse(response: ClientResponse): UIO[Unit] = 
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
  
  def handleRequestError(error: RequestError): UIO[Unit] = 
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
  
  def getSessionId(): UIO[Option[SessionId]] = 
    sessionId.get
  
  def isSessionActive(sessionId: SessionId): UIO[Boolean] = 
    for {
      currentSessionId <- this.sessionId.get
      currentState <- state.get
    } yield currentSessionId.contains(sessionId) && currentState == Connected
  
  def closeSession(sessionId: SessionId): UIO[Boolean] = 
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
    } yield new ConnectionManager(state, sessionId, pendingRequests, requestIdCounter, config)
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
