package zio.raft.client

import zio._
import zio.stream._
import zio.raft.protocol._

/**
 * Client-side unified action stream processing.
 * 
 * Merges multiple event sources into a unified stream:
 * - Network messages from server (ZeroMQ)
 * - User client requests  
 * - Timer events (timeouts, keep-alives)
 * 
 * Produces connection state-aware actions and maintains
 * a separate stream for server-initiated requests.
 */
class ActionStream private (
  connectionManager: ConnectionManager,
  zmqMessageStream: ZStream[Any, Throwable, ServerMessage],
  userRequestQueue: Queue[UserClientRequestAction],
  serverRequestQueue: Queue[ServerRequest],
  config: ClientConfig
) {
  
  def networkMessageStream: ZStream[Any, Throwable, ClientAction] = 
    zmqMessageStream.map(NetworkMessageAction(_))
  
  def userRequestStream: ZStream[Any, Throwable, ClientAction] = 
    ZStream.fromQueue(userRequestQueue)
  
  def timerStream: ZStream[Any, Nothing, ClientAction] = {
    val timeoutStream = ZStream.tick(config.connectionTimeout).as(TimeoutCheckAction)
    val keepAliveStream = ZStream.tick(config.keepAliveInterval).as(SendKeepAliveAction)
    
    timeoutStream.merge(keepAliveStream)
  }
  
  def unifiedStream: ZStream[Any, Throwable, ClientAction] = {
    val network = networkMessageStream
    val user = userRequestStream
    val timer = timerStream
    
    network.merge(user).merge(timer)
  }
  
  def serverInitiatedRequestStream: ZStream[Any, Throwable, ServerRequest] = 
    ZStream.fromQueue(serverRequestQueue)
  
  def processAction(action: ClientAction): UIO[ActionResult] = {
    action match {
      case NetworkMessageAction(message) =>
        processNetworkMessage(message)
      
      case UserClientRequestAction(request, promise) =>
        processUserRequest(request, promise)
      
      case TimeoutCheckAction =>
        processTimeoutCheck()
      
      case SendKeepAliveAction =>
        processSendKeepAlive()
    }
  }
  
  private def processNetworkMessage(message: ServerMessage): UIO[ActionResult] = {
    message match {
      case SessionCreated(sessionId, nonce) =>
        for {
          _ <- connectionManager.sessionEstablished(sessionId)
          _ <- ZIO.logInfo(s"Session created: $sessionId")
        } yield ActionResult.Success
      
      case SessionContinued(nonce) =>
        for {
          _ <- ZIO.logInfo("Session continued successfully")
        } yield ActionResult.Success
      
      case rejection: SessionRejected =>
        for {
          handled <- connectionManager.handleSessionRejected(rejection)
          _ <- if (!handled) {
            ZIO.logError(s"Unhandled session rejection: ${rejection.reason}")
          } else {
            ZIO.unit
          }
        } yield ActionResult.Success
      
      case SessionClosed(reason, leaderId) =>
        for {
          _ <- ZIO.logInfo(s"Session closed by server: $reason")
          _ <- connectionManager.disconnect()
        } yield ActionResult.Success
      
      case KeepAliveResponse(timestamp) =>
        for {
          _ <- ZIO.logDebug(s"Keep-alive response received: $timestamp")
          // Could measure RTT here
        } yield ActionResult.Success
      
      case response: ClientResponse =>
        for {
          _ <- connectionManager.handleClientResponse(response)
        } yield ActionResult.Success
      
      case serverRequest: ServerRequest =>
        for {
          _ <- serverRequestQueue.offer(serverRequest)
          _ <- ZIO.logDebug(s"Server request queued: ${serverRequest.requestId}")
        } yield ActionResult.Success
      
      case error: RequestError =>
        for {
          _ <- connectionManager.handleRequestError(error)
        } yield ActionResult.Success
    }
  }
  
  private def processUserRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, scodec.bits.ByteVector]
  ): UIO[ActionResult] = 
    for {
      action <- connectionManager.submitRequest(request, promise)
      result <- action match {
        case QueueRequest =>
          // Request queued, will be sent when connected
          ZIO.succeed(ActionResult.Queued)
        case QueueAndSendRequest =>
          // Request queued and should be sent immediately
          ZIO.succeed(ActionResult.SendRequired(request))
        case RejectRequest =>
          // Request rejected due to disconnected state
          ZIO.succeed(ActionResult.Rejected)
      }
    } yield result
  
  private def processTimeoutCheck(): UIO[ActionResult] = 
    for {
      state <- connectionManager.currentState
      result <- state match {
        case Connecting =>
          // Check for connection timeout
          for {
            _ <- ZIO.logDebug("Timeout check in Connecting state")
            // Could implement timeout logic here
          } yield ActionResult.Success
        case Connected =>
          // Check for request timeouts
          for {
            _ <- ZIO.logDebug("Timeout check in Connected state")
            // Could check for timed-out requests here
          } yield ActionResult.Success
        case Disconnected =>
          // No timeout checks needed when disconnected
          ZIO.succeed(ActionResult.Success)
      }
    } yield result
  
  private def processSendKeepAlive(): UIO[ActionResult] = 
    for {
      state <- connectionManager.currentState
      result <- state match {
        case Connected =>
          for {
            now <- Clock.instant
            keepAlive = KeepAlive(now)
          } yield ActionResult.SendRequired(keepAlive)
        case _ =>
          // Don't send keep-alives when not connected
          ZIO.succeed(ActionResult.Success)
      }
    } yield result
  
  def getNetworkEventStream(): ZStream[Any, Throwable, ClientAction] = 
    networkMessageStream
  
  def getUserRequestStream(): ZStream[Any, Throwable, ClientAction] = 
    userRequestStream
  
  def getTimerEventStream(): ZStream[Any, Nothing, ClientAction] = 
    timerStream
  
  def getUnifiedActionStream(): ZStream[Any, Throwable, ClientAction] = 
    unifiedStream
  
  def simulateStreamError(): UIO[Unit] = 
    ZIO.logError("Simulated stream error")
  
  /**
   * Submit a user request to the action stream.
   */
  def submitUserRequest(
    request: ClientRequest,
    promise: Promise[RequestErrorReason, scodec.bits.ByteVector]
  ): UIO[Unit] = 
    userRequestQueue.offer(UserClientRequestAction(request, promise)).unit
}

object ActionStream {
  
  /**
   * Create an ActionStream with the given dependencies.
   */
  def make(
    connectionManager: ConnectionManager,
    zmqMessageStream: ZStream[Any, Throwable, ServerMessage],
    config: ClientConfig
  ): UIO[ActionStream] = 
    for {
      userRequestQueue <- Queue.unbounded[UserClientRequestAction]
      serverRequestQueue <- Queue.unbounded[ServerRequest]
    } yield new ActionStream(
      connectionManager,
      zmqMessageStream,
      userRequestQueue,
      serverRequestQueue,
      config
    )
}

/**
 * Result of processing a client action.
 */
sealed trait ActionResult

object ActionResult {
  case object Success extends ActionResult
  case object Queued extends ActionResult
  case object Rejected extends ActionResult
  case class SendRequired(message: ClientMessage) extends ActionResult
}

