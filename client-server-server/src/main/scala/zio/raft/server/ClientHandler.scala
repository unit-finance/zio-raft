package zio.raft.server

import zio._
import zio.raft.protocol._
import scodec.bits.ByteVector

/**
 * Individual client connection handling.
 * 
 * Handles protocol-level message processing:
 * - Session operations (create, continue, close)
 * - Keep-alive processing
 * - Client request forwarding
 * - Server response delivery
 */
trait ClientHandler {
  
  /**
   * Process an incoming client message.
   * 
   * @param routingId ZeroMQ routing ID of the client
   * @param message Client message to process
   * @return Server response message, if any
   */
  def processClientMessage(
    routingId: zio.zmq.RoutingId,
    message: ClientMessage
  ): UIO[Option[ServerMessage]]
  
  /**
   * Handle client disconnection.
   * 
   * @param routingId ZeroMQ routing ID that disconnected
   */
  def handleClientDisconnection(routingId: zio.zmq.RoutingId): UIO[Unit]
  
  /**
   * Deliver a server-initiated request to a client.
   * 
   * @param sessionId Target session ID
   * @param request Server request to deliver
   * @return Success if delivered, false if session not connected
   */
  def deliverServerRequest(
    sessionId: SessionId,
    request: ServerRequest
  ): UIO[Boolean]
  
  /**
   * Deliver a client response from the Raft state machine.
   * 
   * @param sessionId Target session ID
   * @param response Client response to deliver
   * @return Success if delivered, false if session not connected
   */
  def deliverClientResponse(
    sessionId: SessionId,
    response: ClientResponse
  ): UIO[Boolean]
  
  /**
   * Send error response to a client.
   * 
   * @param routingId Target routing ID
   * @param error Error to send
   */
  def sendError(
    routingId: zio.zmq.RoutingId,
    error: RequestError
  ): UIO[Unit]
}

object ClientHandler {
  
  /**
   * Create a ClientHandler with the given dependencies.
   */
  def make(
    sessionManager: SessionManager,
    zmqTransport: ZmqTransport,
    config: ServerConfig
  ): UIO[ClientHandler] = 
    ZIO.succeed(new ClientHandlerImpl(sessionManager, zmqTransport, config))
}

/**
 * Internal implementation of ClientHandler.
 */
private class ClientHandlerImpl(
  sessionManager: SessionManager,
  zmqTransport: ZmqTransport,
  config: ServerConfig
) extends ClientHandler {
  
  override def processClientMessage(
    routingId: zio.zmq.RoutingId,
    message: ClientMessage
  ): UIO[Option[ServerMessage]] = {
    message match {
      case CreateSession(capabilities, nonce) =>
        processCreateSession(routingId, capabilities, nonce)
      
      case ContinueSession(sessionId, nonce) =>
        processContinueSession(routingId, sessionId, nonce)
      
      case KeepAlive(timestamp) =>
        processKeepAlive(routingId, timestamp)
      
      case clientRequest: ClientRequest =>
        processClientRequest(routingId, clientRequest)
      
      case ServerRequestAck(requestId) =>
        processServerRequestAck(routingId, requestId)
      
      case CloseSession(reason) =>
        processCloseSession(routingId, reason)
    }
  }
  
  private def processCreateSession(
    routingId: zio.zmq.RoutingId,
    capabilities: Map[String, String],
    nonce: Nonce
  ): UIO[Option[ServerMessage]] = 
    for {
      result <- sessionManager.createSession(routingId, capabilities, nonce)
      response = result match {
        case Right(sessionId) =>
          Some(SessionCreated(sessionId, nonce))
        case Left(rejectionReason) =>
          Some(SessionRejected(rejectionReason, nonce, getCurrentLeader()))
      }
    } yield response
  
  private def processContinueSession(
    routingId: zio.zmq.RoutingId,
    sessionId: SessionId,
    nonce: Nonce
  ): UIO[Option[ServerMessage]] = 
    for {
      result <- sessionManager.continueSession(routingId, sessionId, nonce)
      response = result match {
        case Right(_) =>
          Some(SessionContinued(nonce))
        case Left(rejectionReason) =>
          Some(SessionRejected(rejectionReason, nonce, getCurrentLeader()))
      }
    } yield response
  
  private def processKeepAlive(
    routingId: zio.zmq.RoutingId,
    timestamp: java.time.Instant
  ): UIO[Option[ServerMessage]] = 
    for {
      updated <- sessionManager.updateKeepAlive(routingId)
      response = if (updated) {
        Some(KeepAliveResponse(timestamp)) // Echo client timestamp
      } else {
        Some(SessionClosed(SessionError, getCurrentLeader()))
      }
    } yield response
  
  private def processClientRequest(
    routingId: zio.zmq.RoutingId,
    clientRequest: ClientRequest
  ): UIO[Option[ServerMessage]] = 
    for {
      sessionIdOpt <- sessionManager.getSessionId(routingId)
      response <- sessionIdOpt match {
        case Some(_) =>
          // Session exists, request will be forwarded to Raft via ActionStream
          // Response will come back through deliverClientResponse
          ZIO.succeed(None)
        case None =>
          // No session for this routing ID
          val error = RequestError(
            reason = SessionTerminated,
            leaderId = getCurrentLeader()
          )
          ZIO.succeed(Some(error))
      }
    } yield response
  
  private def processServerRequestAck(
    routingId: zio.zmq.RoutingId,
    requestId: RequestId
  ): UIO[Option[ServerMessage]] = 
    for {
      // Process acknowledgment (update internal state if needed)
      _ <- ZIO.logDebug(s"Received server request ack: $requestId from $routingId")
    } yield None // No response needed for acks
  
  private def processCloseSession(
    routingId: zio.zmq.RoutingId,
    reason: CloseReason
  ): UIO[Option[ServerMessage]] = 
    for {
      sessionIdOpt <- sessionManager.handleDisconnection(routingId)
      _ <- sessionIdOpt match {
        case Some(sessionId) =>
          ZIO.logInfo(s"Session $sessionId closed by client: $reason")
        case None =>
          ZIO.logWarning(s"Close session request from unknown routing ID: $routingId")
      }
    } yield None // No response needed for close
  
  override def handleClientDisconnection(routingId: zio.zmq.RoutingId): UIO[Unit] = 
    for {
      sessionIdOpt <- sessionManager.handleDisconnection(routingId)
      _ <- sessionIdOpt match {
        case Some(sessionId) =>
          ZIO.logInfo(s"Client disconnected: session $sessionId, routing $routingId")
        case None =>
          ZIO.logDebug(s"Unknown client disconnected: routing $routingId")
      }
    } yield ()
  
  override def deliverServerRequest(
    sessionId: SessionId,
    request: ServerRequest
  ): UIO[Boolean] = 
    for {
      routingIdOpt <- getRoutingIdForSession(sessionId)
      delivered <- routingIdOpt match {
        case Some(routingId) =>
          zmqTransport.sendMessage(routingId, request).as(true)
            .catchAll { error =>
              ZIO.logError(s"Failed to deliver server request to $sessionId: $error").as(false)
            }
        case None =>
          ZIO.logWarning(s"Cannot deliver server request: session $sessionId not connected").as(false)
      }
    } yield delivered
  
  override def deliverClientResponse(
    sessionId: SessionId,
    response: ClientResponse
  ): UIO[Boolean] = 
    for {
      routingIdOpt <- getRoutingIdForSession(sessionId)
      delivered <- routingIdOpt match {
        case Some(routingId) =>
          zmqTransport.sendMessage(routingId, response).as(true)
            .catchAll { error =>
              ZIO.logError(s"Failed to deliver client response to $sessionId: $error").as(false)
            }
        case None =>
          ZIO.logWarning(s"Cannot deliver client response: session $sessionId not connected").as(false)
      }
    } yield delivered
  
  override def sendError(
    routingId: zio.zmq.RoutingId,
    error: RequestError
  ): UIO[Unit] = 
    zmqTransport.sendMessage(routingId, error)
      .catchAll { sendError =>
        ZIO.logError(s"Failed to send error to $routingId: $sendError")
      }
  
  private def getRoutingIdForSession(sessionId: SessionId): UIO[Option[zio.zmq.RoutingId]] = {
    // This would need to be implemented by querying SessionManager
    // for the current routing ID of a connected session
    ZIO.succeed(None) // Placeholder - needs SessionManager enhancement
  }
  
  private def getCurrentLeader(): Option[MemberId] = {
    // This would be provided by Raft integration
    None // Placeholder - needs Raft integration
  }
  
  /**
   * Deliver response to specific routing ID.
   */
  def deliverResponse(routingId: zio.zmq.RoutingId, message: ServerMessage): Task[Unit] = 
    zmqTransport.sendMessage(routingId, message)
  
  /**
   * Send error to session by routing ID.
   */
  def sendErrorToSession(sessionId: SessionId, error: RequestError): UIO[Unit] = 
    for {
      routingIdOpt <- getRoutingIdForSession(sessionId)
      _ <- routingIdOpt match {
        case Some(routingId) =>
          zmqTransport.sendMessage(routingId, error).orDie
        case None =>
          ZIO.logWarning(s"Cannot send error to session $sessionId: not connected")
      }
    } yield ()
}

/**
 * Note: ZmqTransport trait is defined in ZmqTransport.scala
 */
