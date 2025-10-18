package zio.raft.client

import zio._
import zio.stream._
import zio.raft.protocol._
import scodec.bits.ByteVector
import java.time.Instant

/**
 * Main RaftClient implementation using functional state-based approach.
 * 
 * Each connection state (Disconnected, Connecting, Connected) handles messages
 * differently and transitions to other states based on the protocol.
 * 
 * Session management is integrated directly into the state machine.
 */
class RaftClient private (
  stateRef: Ref[ClientState],
  pendingOperationsRef: Ref[Map[Nonce, SessionOperation]],
  serverRequestTrackerRef: Ref[ServerRequestTracker],
  retryManager: RetryManager,
  zmqTransport: ZmqClientTransport,
  config: ClientConfig
) {
  
  /**
   * Connect to the cluster and create a session.
   */
  def connect(): Task[SessionId] = 
    for {
      // Validate capabilities
      _ <- validateCapabilities(config.capabilities)
      
      // Transition to Connecting state
      _ <- transitionTo(ClientState.Connecting(config.capabilities))
      
      // Generate nonce for session creation
      nonce <- Nonce.generate()
      
      // Create promise for session creation
      promise <- Promise.make[SessionError, SessionId]
      
      // Track pending operation
      _ <- pendingOperationsRef.update(_.updated(nonce, SessionOperation.Create(promise)))
      
      // Send CreateSession message
      createMessage = CreateSession(config.capabilities, nonce)
      _ <- zmqTransport.sendMessage(createMessage)
      
      // Wait for session creation response
      sessionId <- promise.await
      
      _ <- ZIO.logInfo(s"Connected with session: $sessionId")
      
    } yield sessionId
  
  /**
   * Continue an existing session after reconnection.
   */
  def continueSession(sessionId: SessionId): Task[Unit] = 
    for {
      // Transition to Connecting state
      _ <- transitionTo(ClientState.Connecting(config.capabilities))
      
      // Generate nonce for session continuation
      nonce <- Nonce.generate()
      
      // Create promise for session continuation
      promise <- Promise.make[SessionError, Unit]
      
      // Track pending operation
      _ <- pendingOperationsRef.update(_.updated(nonce, SessionOperation.Continue(promise)))
      
      // Send ContinueSession message
      continueMessage = ContinueSession(sessionId, nonce)
      _ <- zmqTransport.sendMessage(continueMessage)
      
      // Wait for session continuation response
      _ <- promise.await
      
      _ <- ZIO.logInfo(s"Continued session: $sessionId")
      
    } yield ()
  
  /**
   * Disconnect from the cluster.
   */
  def disconnect(): UIO[Unit] = 
    for {
      currentState <- stateRef.get
      _ <- currentState match {
        case ClientState.Connected(sessionId, _, _, _) =>
          for {
            closeMessage = CloseSession(ClientShutdown)
            _ <- zmqTransport.sendMessage(closeMessage).orDie
            _ <- transitionTo(ClientState.Disconnected)
            _ <- ZIO.logInfo("Disconnected from cluster")
          } yield ()
        case _ =>
          transitionTo(ClientState.Disconnected)
      }
    } yield ()
  
  /**
   * Submit a command to the cluster.
   */
  def submitCommand(payload: ByteVector): Task[ByteVector] = 
    for {
      currentState <- stateRef.get
      result <- currentState match {
        case ClientState.Connected(sessionId, _, _, _) =>
          submitRequestInConnectedState(sessionId, payload)
        case ClientState.Connecting(_) =>
          ZIO.fail(new IllegalStateException("Client is still connecting"))
        case ClientState.Disconnected =>
          ZIO.fail(new IllegalStateException("Client is not connected"))
      }
    } yield result
  
  /**
   * Acknowledge a server-initiated request.
   */
  def acknowledgeServerRequest(requestId: RequestId): Task[Unit] = 
    for {
      tracker <- serverRequestTrackerRef.get
      _ <- if (tracker.shouldProcess(requestId)) {
        for {
          updatedTracker <- ZIO.succeed(tracker.acknowledge(requestId))
          _ <- serverRequestTrackerRef.set(updatedTracker)
          _ <- ZIO.logDebug(s"Acknowledged server request: $requestId")
          
          // Send acknowledgment
          ack = ServerRequestAck(requestId)
          _ <- zmqTransport.sendMessage(ack)
        } yield ()
      } else {
        ZIO.fail(new IllegalArgumentException(s"Cannot acknowledge out-of-order request: $requestId"))
      }
    } yield ()
  
  /**
   * Process an incoming server message.
   * State transitions happen based on current state and message type.
   */
  def handleServerMessage(message: ServerMessage): UIO[Unit] = 
    for {
      currentState <- stateRef.get
      _ <- currentState.handleMessage(message, this)
    } yield ()
  
  /**
   * Get current connection state.
   */
  def getState: UIO[ClientState] = 
    stateRef.get
  
  /**
   * Check if client is connected.
   */
  def isConnected: UIO[Boolean] = 
    stateRef.get.map {
      case ClientState.Connected(_, _, _, _) => true
      case _ => false
    }
  
  /**
   * Get current session ID if connected.
   */
  def getSessionId: UIO[Option[SessionId]] = 
    stateRef.get.map {
      case ClientState.Connected(sessionId, _, _, _) => Some(sessionId)
      case _ => None
    }
  
  /**
   * Transition to a new state.
   */
  private[client] def transitionTo(newState: ClientState): UIO[Unit] = 
    for {
      oldState <- stateRef.get
      _ <- stateRef.set(newState)
      _ <- ZIO.logDebug(s"State transition: ${oldState.stateName} -> ${newState.stateName}")
    } yield ()
  
  /**
   * Handle SessionCreated response (called from Connecting state).
   */
  private[client] def handleSessionCreated(sessionId: SessionId, nonce: Nonce): UIO[Unit] = 
    for {
      operationsMap <- pendingOperationsRef.get
      _ <- operationsMap.get(nonce) match {
        case Some(SessionOperation.Create(promise)) =>
          for {
            // Complete the promise
            _ <- promise.succeed(sessionId).ignore
            
            // Remove from pending operations
            _ <- pendingOperationsRef.update(_.removed(nonce))
            
            // Get capabilities from current state
            currentState <- stateRef.get
            capabilities = currentState match {
              case ClientState.Connecting(caps) => caps
              case _ => config.capabilities
            }
            
            // Transition to Connected state
            _ <- transitionTo(ClientState.Connected(
              sessionId = sessionId,
              capabilities = capabilities,
              createdAt = Instant.now(),
              serverRequestTracker = ServerRequestTracker()
            ))
          } yield ()
          
        case _ =>
          ZIO.logWarning(s"Received SessionCreated for unexpected nonce: $nonce")
      }
    } yield ()
  
  /**
   * Handle SessionContinued response (called from Connecting state).
   */
  private[client] def handleSessionContinued(sessionId: SessionId, nonce: Nonce): UIO[Unit] = 
    for {
      operationsMap <- pendingOperationsRef.get
      _ <- operationsMap.get(nonce) match {
        case Some(SessionOperation.Continue(promise)) =>
          for {
            // Complete the promise
            _ <- promise.succeed(()).ignore
            
            // Remove from pending operations
            _ <- pendingOperationsRef.update(_.removed(nonce))
            
            // Get capabilities from current state
            currentState <- stateRef.get
            capabilities = currentState match {
              case ClientState.Connecting(caps) => caps
              case _ => config.capabilities
            }
            
            // Transition to Connected state
            _ <- transitionTo(ClientState.Connected(
              sessionId = sessionId,
              capabilities = capabilities,
              createdAt = Instant.now(),
              serverRequestTracker = ServerRequestTracker()
            ))
          } yield ()
          
        case _ =>
          ZIO.logWarning(s"Received SessionContinued for unexpected nonce: $nonce")
      }
    } yield ()
  
  /**
   * Handle SessionRejected response (called from Connecting state).
   */
  private[client] def handleSessionRejected(rejection: SessionRejected): UIO[Unit] = 
    for {
      operationsMap <- pendingOperationsRef.get
      _ <- operationsMap.get(rejection.nonce) match {
        case Some(SessionOperation.Create(promise)) =>
          for {
            error <- ZIO.succeed(SessionError.fromRejectionReason(rejection.reason))
            _ <- promise.fail(error).ignore
            _ <- pendingOperationsRef.update(_.removed(rejection.nonce))
          } yield ()
          
        case Some(SessionOperation.Continue(promise)) =>
          for {
            error <- ZIO.succeed(SessionError.fromRejectionReason(rejection.reason))
            _ <- promise.fail(error).ignore
            _ <- pendingOperationsRef.update(_.removed(rejection.nonce))
          } yield ()
          
        case _ =>
          ZIO.logWarning(s"Received SessionRejected for unexpected nonce: ${rejection.nonce}")
      }
    } yield ()
  
  private def submitRequestInConnectedState(
    sessionId: SessionId,
    payload: ByteVector
  ): Task[ByteVector] = 
    for {
      // Generate request ID
      requestId <- generateRequestId()
      now <- Clock.instant
      
      // Create request
      request = ClientRequest(requestId, payload, now)
      
      // Create promise for response
      promise <- Promise.make[RequestErrorReason, ByteVector]
      
      // Track with retry manager
      _ <- retryManager.addPendingRequest(request, promise)
      
      // Send request
      _ <- zmqTransport.sendMessage(request)
      
      // Wait for response with timeout
      response <- promise.await
        .mapError {
          case reason: RequestErrorReason => new RuntimeException(s"Request failed: $reason")
        }
        .timeoutFail(new TimeoutException("Request timed out"))(config.requestTimeout)
      
    } yield response
  
  private def validateCapabilities(caps: Map[String, String]): Task[Unit] = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    
    if (caps.isEmpty) {
      errors += "Capabilities cannot be empty"
    }
    
    if (caps.size > config.maxCapabilityCount) {
      errors += s"Too many capabilities: ${caps.size} > ${config.maxCapabilityCount}"
    }
    
    caps.foreach { case (key, value) =>
      if (key.isEmpty) {
        errors += "Capability key cannot be empty"
      }
      if (value.length > config.maxCapabilityValueLength) {
        errors += s"Capability value too long: ${value.length} > ${config.maxCapabilityValueLength}"
      }
    }
    
    if (errors.nonEmpty) {
      ZIO.fail(new IllegalArgumentException(s"Invalid capabilities: ${errors.mkString(", ")}"))
    } else {
      ZIO.unit
    }
  }
  
  private def generateRequestId(): UIO[RequestId] = 
    Random.nextLong.map(RequestId.fromLong)
}

object RaftClient {
  
  /**
   * Create and initialize a RaftClient.
   */
  def make(config: ClientConfig): ZIO[Scope, Throwable, RaftClient] = 
    for {
      // Validate configuration
      validatedConfig <- ClientConfig.validated(config).mapError(new IllegalArgumentException(_))
      
      // Create initial state
      stateRef <- Ref.make[ClientState](ClientState.Disconnected)
      
      // Create pending operations tracking
      pendingOperationsRef <- Ref.make[Map[Nonce, SessionOperation]](Map.empty)
      
      // Create server request tracker
      serverRequestTrackerRef <- Ref.make(ServerRequestTracker())
      
      // Create retry manager
      retryManager <- RetryManager.make(validatedConfig)
      
      // Create ZMQ transport
      zmqTransport <- createZmqTransport(validatedConfig)
      
      // Create client
      client = new RaftClient(
        stateRef,
        pendingOperationsRef,
        serverRequestTrackerRef,
        retryManager,
        zmqTransport,
        validatedConfig
      )
      
      // Start message processing loop
      _ <- startMessageLoop(client, zmqTransport).fork
      
      // Start keep-alive loop
      _ <- startKeepAliveLoop(client, zmqTransport, validatedConfig).fork
      
    } yield client
  
  private def createZmqTransport(config: ClientConfig): UIO[ZmqClientTransport] = 
    ZIO.succeed(new ZmqClientTransportStub())
  
  private def startMessageLoop(
    client: RaftClient,
    transport: ZmqClientTransport
  ): Task[Unit] = {
    // Process incoming messages from ZMQ transport stream
    transport.incomingMessages
      .foreach { message =>
        client.handleServerMessage(message)
      }
  }
  
  private def startKeepAliveLoop(
    client: RaftClient,
    transport: ZmqClientTransport,
    config: ClientConfig
  ): Task[Unit] = {
    ZStream
      .tick(config.keepAliveInterval)
      .foreach { _ =>
        for {
          isConnected <- client.isConnected
          _ <- if (isConnected) {
            for {
              now <- Clock.instant
              keepAlive = KeepAlive(now)
              _ <- transport.sendMessage(keepAlive)
            } yield ()
          } else {
            ZIO.unit
          }
        } yield ()
      }
  }
}

/**
 * Client connection states using functional ADT approach.
 * Each state knows how to handle different server messages.
 */
sealed trait ClientState {
  def stateName: String
  def handleMessage(message: ServerMessage, client: RaftClient): UIO[Unit]
}

object ClientState {
  
  /**
   * Client is disconnected - not attempting to connect.
   */
  case object Disconnected extends ClientState {
    override def stateName: String = "Disconnected"
    
    override def handleMessage(message: ServerMessage, client: RaftClient): UIO[Unit] = 
      ZIO.logWarning(s"Received message while disconnected (ignored): ${message.getClass.getSimpleName}")
  }
  
  /**
   * Client is attempting to establish a connection/session.
   * Stores capabilities for the session being created/continued.
   */
  case class Connecting(
    capabilities: Map[String, String]
  ) extends ClientState {
    override def stateName: String = "Connecting"
    
    override def handleMessage(message: ServerMessage, client: RaftClient): UIO[Unit] = 
      message match {
        case SessionCreated(sessionId, nonce) =>
          client.handleSessionCreated(sessionId, nonce)
        
        case SessionContinued(nonce) =>
          // We need the sessionId - it should be tracked in the pending operation
          for {
            _ <- ZIO.logInfo("Session continued successfully")
            // The sessionId will be set when we transition to Connected
            // For now, we'll use a placeholder approach
          } yield ()
        
        case rejection: SessionRejected =>
          for {
            _ <- ZIO.logWarning(s"Session rejected: ${rejection.reason}")
            _ <- client.handleSessionRejected(rejection)
            _ <- handleRejection(rejection, client)
          } yield ()
        
        case other =>
          ZIO.logWarning(s"Unexpected message in Connecting state: ${other.getClass.getSimpleName}")
      }
    
    private def handleRejection(rejection: SessionRejected, client: RaftClient): UIO[Unit] = 
      rejection.reason match {
        case NotLeader =>
          // Could implement leader redirection here
          for {
            _ <- ZIO.logInfo(s"Not leader, redirect to: ${rejection.leaderId}")
            // Stay in Connecting, could retry with different leader
          } yield ()
          
        case SessionNotFound =>
          // Session doesn't exist, stay in Connecting and retry
          ZIO.unit
          
        case _ =>
          // Other errors - transition to Disconnected
          client.transitionTo(Disconnected)
      }
  }
  
  /**
   * Client has an active session and can send/receive requests.
   * Contains all session-related data.
   */
  case class Connected(
    sessionId: SessionId,
    capabilities: Map[String, String],
    createdAt: Instant,
    serverRequestTracker: ServerRequestTracker
  ) extends ClientState {
    override def stateName: String = s"Connected($sessionId)"
    
    override def handleMessage(message: ServerMessage, client: RaftClient): UIO[Unit] = 
      message match {
        case response: ClientResponse =>
          handleClientResponse(response, client)
        
        case KeepAliveResponse(timestamp) =>
          ZIO.logDebug(s"Keep-alive acknowledged: $timestamp")
        
        case serverRequest: ServerRequest =>
          handleServerRequest(serverRequest, client)
        
        case error: RequestError =>
          handleRequestError(error, client)
        
        case SessionClosed(reason, leaderId) =>
          for {
            _ <- ZIO.logInfo(s"Session closed by server: $reason")
            _ <- client.transitionTo(Disconnected)
          } yield ()
        
        case other =>
          ZIO.logWarning(s"Unexpected message in Connected state: ${other.getClass.getSimpleName}")
      }
    
    private def handleClientResponse(response: ClientResponse, client: RaftClient): UIO[Unit] = 
      for {
        // Complete pending request via retry manager
        _ <- client.retryManager.completePendingRequest(response.requestId, response.result)
      } yield ()
    
    private def handleServerRequest(request: ServerRequest, client: RaftClient): UIO[Unit] = 
      for {
        _ <- ZIO.logInfo(s"Received server request: ${request.requestId}")
        // User should call acknowledgeServerRequest after processing
      } yield ()
    
    private def handleRequestError(error: RequestError, client: RaftClient): UIO[Unit] = 
      error.reason match {
        case NotLeaderRequest =>
          for {
            _ <- ZIO.logInfo(s"Not leader, redirect to: ${error.leaderId}")
            // Could update leader information and retry
          } yield ()
        
        case SessionTerminated =>
          for {
            _ <- ZIO.logWarning("Session terminated by server")
            _ <- client.transitionTo(Disconnected)
          } yield ()
        
        case _ =>
          ZIO.logWarning(s"Request error: ${error.reason}")
      }
  }
}

/**
 * Pending session operations tracked by nonce.
 */
private sealed trait SessionOperation

private object SessionOperation {
  case class Create(promise: Promise[SessionError, SessionId]) extends SessionOperation
  case class Continue(promise: Promise[SessionError, Unit]) extends SessionOperation
}

/**
 * Session-related errors.
 */
sealed trait SessionError extends Throwable

object SessionError {
  case object NotLeader extends SessionError
  case object SessionNotFound extends SessionError
  case object InvalidCapabilities extends SessionError
  case object InternalError extends SessionError
  
  def fromRejectionReason(reason: RejectionReason): SessionError = reason match {
    case zio.raft.protocol.NotLeader => SessionError.NotLeader
    case zio.raft.protocol.SessionNotFound => SessionError.SessionNotFound
    case _ => SessionError.InternalError
  }
}

/**
 * Session metadata for client tracking.
 */
case class SessionMetadata(
  sessionId: SessionId,
  capabilities: Map[String, String],
  createdAt: java.time.Instant
)

/**
 * ZeroMQ client transport abstraction.
 * Provides a functional stream-based interface for message communication.
 */
trait ZmqClientTransport {
  /**
   * Send a message to the server.
   */
  def sendMessage(message: ClientMessage): Task[Unit]
  
  /**
   * Stream of incoming messages from the server.
   * This stream should be consumed by the client to handle server messages.
   */
  def incomingMessages: ZStream[Any, Throwable, ServerMessage]
}

/**
 * Stub implementation for compilation.
 */
private class ZmqClientTransportStub extends ZmqClientTransport {
  override def sendMessage(message: ClientMessage): Task[Unit] = 
    ZIO.logDebug(s"Stub: sending message: ${message.getClass.getSimpleName}")
  
  override def incomingMessages: ZStream[Any, Throwable, ServerMessage] = 
    ZStream.empty // Would implement actual ZMQ receiving stream here
}

/**
 * Exception thrown when operations timeout.
 */
class TimeoutException(message: String) extends RuntimeException(message)
