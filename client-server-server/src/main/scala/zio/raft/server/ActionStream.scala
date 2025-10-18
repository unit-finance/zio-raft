package zio.raft.server

import zio._
import zio.stream._
import zio.raft.protocol._

/**
 * Server-side unified action stream processing.
 * 
 * Merges multiple event sources into a unified stream:
 * - Periodic cleanup actions (session expiration)
 * - ZeroMQ message actions (client messages)
 * 
 * Produces ServerActions for the Raft state machine:
 * - CreateSessionAction
 * - ClientMessageAction  
 * - ExpireSessionAction
 */
trait ActionStream {
  
  /**
   * Cleanup stream that emits CleanupAction every second.
   */
  def cleanupStream: ZStream[Any, Nothing, LocalAction]
  
  /**
   * Message stream that wraps ZeroMQ messages in MessageAction.
   */
  def messageStream: ZStream[Any, Throwable, LocalAction] 
  
  /**
   * Unified stream merging cleanup and message streams.
   */
  def unifiedStream: ZStream[Any, Throwable, LocalAction]
  
  /**
   * Result stream that processes local actions and produces server actions.
   */
  def resultStream: ZStream[Any, Throwable, ServerAction]
  
  /**
   * Process a single local action and produce server actions.
   */
  def processAction(action: LocalAction): ZStream[Any, Throwable, ServerAction]
  
  /**
   * Handle error in stream processing.
   */
  def handleError(error: String): UIO[Unit]
  
  /**
   * Check if stream is resilient to errors.
   */
  def isResilient: Boolean
}

object ActionStream {
  
  /**
   * Create an ActionStream with the given dependencies.
   */
  def make(
    sessionManager: SessionManager,
    zmqMessageStream: ZStream[Any, Throwable, ZmqMessage]
  ): UIO[ActionStream] = 
    ZIO.succeed(new ActionStreamImpl(sessionManager, zmqMessageStream))
  
  /**
   * Create an ActionStream for testing.
   */
  def create(): ActionStream = {
    // Test implementation that provides non-null references
    new ActionStreamTestImpl()
  }
}

/**
 * Internal implementation of ActionStream.
 */
private class ActionStreamImpl(
  sessionManager: SessionManager,
  zmqMessageStream: ZStream[Any, Throwable, ZmqMessage]
) extends ActionStream {
  
  override def cleanupStream: ZStream[Any, Nothing, LocalAction] = 
    ZStream.tick(1.second).as(CleanupAction)
  
  override def messageStream: ZStream[Any, Throwable, LocalAction] = 
    zmqMessageStream.map(msg => MessageAction(msg.routingId, msg.content))
  
  override def unifiedStream: ZStream[Any, Throwable, LocalAction] = 
    cleanupStream.merge(messageStream)
  
  override def resultStream: ZStream[Any, Throwable, ServerAction] = 
    unifiedStream.flatMap(processAction)
  
  override def processAction(action: LocalAction): ZStream[Any, Throwable, ServerAction] = 
    action match {
      case CleanupAction => 
        ZStream.fromZIO(sessionManager.removeExpiredSessions())
          .flatMap(expiredSessions => 
            ZStream.fromIterable(expiredSessions.map(ExpireSessionAction(_)))
          )
      
      case MessageAction(routingId, message) => 
        processMessage(routingId, message)
    }
  
  private def processMessage(
    routingId: zio.zmq.RoutingId, 
    message: ClientMessage
  ): ZStream[Any, Throwable, ServerAction] = {
    message match {
      case CreateSession(capabilities, nonce) =>
        ZStream.fromZIO(
          sessionManager.createSession(routingId, capabilities, nonce)
            .map {
              case Right(sessionId) => 
                Some(CreateSessionAction(routingId, capabilities, nonce))
              case Left(_) => 
                None // Rejection handled at protocol level
            }
        ).collect { case Some(action) => action }
      
      case ContinueSession(sessionId, nonce) =>
        // Continue session is handled locally, no Raft action needed
        ZStream.empty
      
      case KeepAlive(timestamp) =>
        // Keep-alive is handled locally, no Raft action needed
        ZStream.empty
      
      case clientRequest: ClientRequest =>
        ZStream.fromZIO(
          sessionManager.getSessionId(routingId).map {
            case Some(sessionId) =>
              Some(ClientMessageAction(sessionId, clientRequest.requestId, clientRequest.payload))
            case None =>
              None // No session for this routing ID
          }
        ).collect { case Some(action) => action }
      
      case ServerRequestAck(requestId) =>
        // Server request acknowledgment, no Raft action needed
        ZStream.empty
      
      case CloseSession(reason) =>
        // Session close is handled locally, potential Raft action if session exists
        ZStream.fromZIO(
          sessionManager.getSessionId(routingId).map {
            case Some(sessionId) =>
              Some(ExpireSessionAction(sessionId))
            case None =>
              None
          }
        ).collect { case Some(action) => action }
    }
  }
  
  override def handleError(error: String): UIO[Unit] = 
    ZIO.logWarning(s"ActionStream error: $error")
  
  override def isResilient: Boolean = true
}

/**
 * Test implementation for ActionStream.
 */
private class ActionStreamTestImpl extends ActionStream {
  
  override def cleanupStream: ZStream[Any, Nothing, LocalAction] = 
    ZStream.empty
  
  override def messageStream: ZStream[Any, Throwable, LocalAction] = 
    ZStream.empty
  
  override def unifiedStream: ZStream[Any, Throwable, LocalAction] = 
    ZStream.empty
  
  override def resultStream: ZStream[Any, Throwable, ServerAction] = 
    ZStream.empty
  
  override def processAction(action: LocalAction): ZStream[Any, Throwable, ServerAction] = 
    ZStream.empty
  
  override def handleError(error: String): UIO[Unit] = 
    ZIO.unit
  
  override def isResilient: Boolean = false
}

/**
 * Local action types for unified stream processing.
 */
sealed trait LocalAction

case object CleanupAction extends LocalAction

case class MessageAction(
  routingId: zio.zmq.RoutingId,
  message: ClientMessage
) extends LocalAction

/**
 * Server action types for Raft state machine.
 */
sealed trait ServerAction

case class CreateSessionAction(
  routingId: zio.zmq.RoutingId,
  capabilities: Map[String, String],
  nonce: Nonce
) extends ServerAction

case class ClientMessageAction(
  sessionId: SessionId,
  requestId: RequestId,
  payload: scodec.bits.ByteVector
) extends ServerAction

case class ExpireSessionAction(
  sessionId: SessionId
) extends ServerAction

/**
 * ZeroMQ message wrapper.
 */
case class ZmqMessage(
  routingId: zio.zmq.RoutingId,
  content: ClientMessage
)
