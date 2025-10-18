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
  config: ClientConfig,
  actionQueue: Queue[ClientAction]
) {
  
  /**
   * Internal method to initiate connection to the cluster.
   * Called automatically on client startup.
   */
  private def initiateConnection(): Task[SessionId] = 
    for {
      // Validate capabilities
      _ <- validateCapabilities(config.capabilities)
      
      // Transition to Connecting state
      _ <- transitionTo(ClientState.Connecting(config.capabilities, nonce = None, addresses = config.clusterAddresses, currentAddressIndex = 0))
      
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
   * Internal method to continue an existing session after reconnection.
   * Called automatically by the state machine when reconnecting.
   */
  private def continueSessionInternal(sessionId: SessionId): Task[Unit] = 
    for {
      // Generate nonce for session continuation
      nonce <- Nonce.generate()
      
      // Transition to Connecting state with nonce
      _ <- transitionTo(ClientState.Connecting(
        capabilities = config.capabilities,
        nonce = Some(nonce),
        addresses = config.clusterAddresses,
        currentAddressIndex = 0
      ))
      
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
   * Enqueues a disconnect action to be processed by the stream.
   */
  def disconnect(): UIO[Unit] = 
    for {
      _ <- actionQueue.offer(ClientAction.Disconnect)
      _ <- ZIO.logDebug("Disconnect action enqueued")
    } yield ()
  
  /**
   * Submit a command to the cluster.
   * Enqueues a command submission action to be processed by the stream.
   */
  def submitCommand(payload: ByteVector): Task[ByteVector] = 
    for {
      promise <- Promise.make[Throwable, ByteVector]
      action = ClientAction.SubmitCommand(payload, promise)
      _ <- actionQueue.offer(action)
      _ <- ZIO.logDebug("Command submission action enqueued")
      result <- promise.await
    } yield result
  
  /**
   * Acknowledge a server-initiated request.
   * Enqueues an acknowledgment action to be processed by the stream.
   */
  def acknowledgeServerRequest(requestId: RequestId): UIO[Unit] = 
    for {
      _ <- actionQueue.offer(ClientAction.AcknowledgeServerRequest(requestId))
      _ <- ZIO.logDebug(s"Acknowledgment action enqueued for request: $requestId")
    } yield ()
  
  /**
   * Process an incoming server message.
   * State transitions happen based on current state and message type.
   */
  def handleServerMessage(message: ServerMessage): UIO[Unit] = 
    for {
      // Handle promise completion for session operations
      _ <- message match {
        case SessionCreated(sessionId, nonce) =>
          completeSessionOperation(nonce, sessionId)
        case SessionContinued(nonce) =>
          completeSessionContinuation(nonce)
        case rejection: SessionRejected =>
          failSessionOperation(rejection.nonce, rejection.reason)
        case response: ClientResponse =>
          retryManager.completePendingRequest(response.requestId, response.result)
        case error: RequestError =>
          retryManager.completePendingRequestWithError(error.requestId, error.reason)
        case _ =>
          ZIO.unit
      }
      
      // Handle state transition
      currentState <- stateRef.get
      newState <- currentState.handleMessage(message)
      _ <- if (newState != currentState) {
        stateRef.set(newState) *> ZIO.logDebug(s"State transition: ${currentState.stateName} -> ${newState.stateName}")
      } else {
        ZIO.unit
      }
    } yield ()
  
  private def completeSessionOperation(nonce: Nonce, sessionId: SessionId): UIO[Unit] =
    for {
      operationsMap <- pendingOperationsRef.get
      _ <- operationsMap.get(nonce) match {
        case Some(SessionOperation.Create(promise)) =>
          promise.succeed(sessionId).ignore *> pendingOperationsRef.update(_.removed(nonce))
        case _ =>
          ZIO.logWarning(s"Received SessionCreated for unexpected nonce: $nonce")
      }
    } yield ()
  
  private def completeSessionContinuation(nonce: Nonce): UIO[Unit] =
    for {
      operationsMap <- pendingOperationsRef.get
      _ <- operationsMap.get(nonce) match {
        case Some(SessionOperation.Continue(promise)) =>
          promise.succeed(()).ignore *> pendingOperationsRef.update(_.removed(nonce))
        case _ =>
          ZIO.logWarning(s"Received SessionContinued for unexpected nonce: $nonce")
      }
    } yield ()
  
  private def failSessionOperation(nonce: Nonce, reason: RejectionReason): UIO[Unit] =
    for {
      operationsMap <- pendingOperationsRef.get
      _ <- operationsMap.get(nonce) match {
        case Some(SessionOperation.Create(promise)) =>
          val error = SessionError.fromRejectionReason(reason)
          promise.fail(error).ignore *> pendingOperationsRef.update(_.removed(nonce))
        case Some(SessionOperation.Continue(promise)) =>
          val error = SessionError.fromRejectionReason(reason)
          promise.fail(error).ignore *> pendingOperationsRef.update(_.removed(nonce))
        case _ =>
          ZIO.logWarning(s"Received SessionRejected for unexpected nonce: $nonce")
      }
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
        .timeoutFail(new TimeoutException("Request timed out"))(ClientConfig.REQUEST_TIMEOUT)
      
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
   * Automatically connects to the cluster on creation.
   */
  def make(addresses: List[String], capabilities: Map[String, String]): ZIO[Scope, Throwable, RaftClient] = 
    for {
      // Create and validate configuration
      config = ClientConfig.make(addresses, capabilities)
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
      
      // Create action queue for stream-based processing
      actionQueue <- Queue.unbounded[ClientAction]
      
      // Create client
      client = new RaftClient(
        stateRef,
        pendingOperationsRef,
        serverRequestTrackerRef,
        retryManager,
        zmqTransport,
        validatedConfig,
        actionQueue
      )
      
      // Start action processing loop
      _ <- startActionProcessingLoop(client, actionQueue).fork
      
      // Start message processing loop (enqueues actions)
      _ <- startMessageLoop(client, zmqTransport, actionQueue).fork
      
      // Start keep-alive loop
      _ <- startKeepAliveLoop(client, zmqTransport, validatedConfig).fork
      
      // Automatically initiate connection
      _ <- client.initiateConnection().fork
      
    } yield client
  
  private def createZmqTransport(config: ClientConfig): UIO[ZmqClientTransport] = 
    ZIO.succeed(new ZmqClientTransportStub())
  
  /**
   * Action processing loop - processes actions from the queue using fold.
   * State transitions happen through the fold function.
   */
  private def startActionProcessingLoop(
    client: RaftClient,
    actionQueue: Queue[ClientAction]
  ): Task[Unit] = {
    ZStream
      .fromQueue(actionQueue)
      .foreach { action =>
        processAction(client, action)
      }
  }
  
  /**
   * Process a single action based on current state.
   */
  private def processAction(client: RaftClient, action: ClientAction): Task[Unit] = {
    action match {
      case ClientAction.Disconnect =>
        for {
          currentState <- client.stateRef.get
          _ <- currentState match {
            case ClientState.Connected(sessionId, _, _, _) =>
              for {
                closeMessage = CloseSession(ClientShutdown)
                _ <- client.zmqTransport.sendMessage(closeMessage)
                _ <- client.transitionTo(ClientState.Disconnected)
                _ <- ZIO.logInfo("Disconnected from cluster")
              } yield ()
            case _ =>
              client.transitionTo(ClientState.Disconnected)
          }
        } yield ()
      
      case ClientAction.SubmitCommand(payload, promise) =>
        for {
          currentState <- client.stateRef.get
          _ <- currentState match {
            case ClientState.Connected(sessionId, _, _, _) =>
              client.submitRequestInConnectedState(sessionId, payload)
                .foldZIO(
                  error => promise.fail(error).ignore,
                  result => promise.succeed(result).ignore
                )
            case _ =>
              promise.fail(new IllegalStateException("Client is not connected")).ignore
          }
        } yield ()
      
      case ClientAction.AcknowledgeServerRequest(requestId) =>
        for {
          tracker <- client.serverRequestTrackerRef.get
          _ <- if (tracker.shouldProcess(requestId)) {
            for {
              updatedTracker <- ZIO.succeed(tracker.acknowledge(requestId))
              _ <- client.serverRequestTrackerRef.set(updatedTracker)
              ack = ServerRequestAck(requestId)
              _ <- client.zmqTransport.sendMessage(ack)
              _ <- ZIO.logDebug(s"Acknowledged server request: $requestId")
            } yield ()
          } else {
            ZIO.logWarning(s"Cannot acknowledge out-of-order request: $requestId")
          }
        } yield ()
      
      case ClientAction.ProcessServerMessage(message) =>
        client.handleServerMessage(message)
      
      case ClientAction.Connect =>
        // Not implemented - connect happens automatically on startup
        ZIO.unit
    }
  }
  
  private def startMessageLoop(
    client: RaftClient,
    transport: ZmqClientTransport,
    actionQueue: Queue[ClientAction]
  ): Task[Unit] = {
    // Process incoming messages by enqueuing them as actions
    transport.incomingMessages
      .foreach { message =>
        actionQueue.offer(ClientAction.ProcessServerMessage(message))
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
  def handleMessage(message: ServerMessage): UIO[ClientState]
}

object ClientState {
  
  /**
   * Client is disconnected - not attempting to connect.
   */
  case object Disconnected extends ClientState {
    override def stateName: String = "Disconnected"
    
    override def handleMessage(message: ServerMessage): UIO[ClientState] = 
      ZIO.logWarning(s"Received message while disconnected (ignored): ${message.getClass.getSimpleName}").as(this)
  }
  
  /**
   * Client is attempting to establish a connection/session.
   * Stores capabilities for the session being created/continued.
   * The nonce is stored in the state to validate responses.
   */
  case class Connecting(
    capabilities: Map[String, String],
    nonce: Option[Nonce],
    addresses: List[String],
    currentAddressIndex: Int
  ) extends ClientState {
    override def stateName: String = "Connecting"
    
    def nextAddress: Option[(String, Connecting)] = {
      val nextIndex = currentAddressIndex + 1
      if (nextIndex < addresses.length) {
        Some((addresses(nextIndex), copy(currentAddressIndex = nextIndex)))
      } else {
        None
      }
    }
    
    override def handleMessage(message: ServerMessage): UIO[ClientState] = 
      message match {
        case SessionCreated(sessionId, responseNonce) =>
          // Validate nonce if we have one
          nonce match {
            case Some(expectedNonce) if expectedNonce != responseNonce =>
              ZIO.logWarning(s"Received SessionCreated with mismatched nonce, ignoring").as(this)
            case _ =>
              // Transition to Connected state
              for {
                _ <- ZIO.logInfo(s"Session created: $sessionId")
                now <- Clock.instant
              } yield Connected(
                sessionId = sessionId,
                capabilities = capabilities,
                createdAt = now,
                serverRequestTracker = ServerRequestTracker()
              )
          }
        
        case SessionContinued(responseNonce) =>
          // Validate nonce if we have one
          nonce match {
            case Some(expectedNonce) if expectedNonce != responseNonce =>
              ZIO.logWarning(s"Received SessionContinued with mismatched nonce, ignoring").as(this)
            case _ =>
              // We need to recover the sessionId from somewhere - this is a design issue
              // For now, log and stay in connecting
              ZIO.logWarning("SessionContinued received but sessionId not tracked properly").as(this)
          }
        
        case rejection: SessionRejected =>
          // Validate nonce if we have one
          nonce match {
            case Some(expectedNonce) if expectedNonce != rejection.nonce =>
              ZIO.logWarning(s"Received SessionRejected with mismatched nonce, ignoring").as(this)
            case _ =>
              handleRejection(rejection)
          }
        
        case other =>
          ZIO.logWarning(s"Unexpected message in Connecting state: ${other.getClass.getSimpleName}").as(this)
      }
    
    private def handleRejection(rejection: SessionRejected): UIO[ClientState] = 
      rejection.reason match {
        case NotLeader =>
          // Try next address in the list
          nextAddress match {
            case Some((address, newState)) =>
              for {
                _ <- ZIO.logInfo(s"Not leader, trying next address: $address")
              } yield newState
            case None =>
              // No more addresses, stay in Connecting (will retry from beginning)
              ZIO.logInfo("No more addresses to try, staying in Connecting").as(this)
          }
          
        case SessionNotFound =>
          // Session doesn't exist - this is a fatal error for session continuation
          ZIO.dieMessage("Session not found on server - cannot continue")
          
        case _ =>
          // Other errors - transition to Disconnected
          ZIO.logWarning(s"Session rejected: ${rejection.reason}, transitioning to Disconnected").as(Disconnected)
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
    
    override def handleMessage(message: ServerMessage): UIO[ClientState] = 
      message match {
        case response: ClientResponse =>
          // Stay in Connected state, response handled elsewhere
          ZIO.logDebug(s"Received response for request: ${response.requestId}").as(this)
        
        case KeepAliveResponse(timestamp) =>
          ZIO.logDebug(s"Keep-alive acknowledged: $timestamp").as(this)
        
        case serverRequest: ServerRequest =>
          // Server request should be enqueued for processing
          ZIO.logInfo(s"Received server request: ${serverRequest.requestId}").as(this)
        
        case error: RequestError =>
          handleRequestError(error)
        
        case SessionClosed(reason, leaderId) =>
          for {
            _ <- ZIO.logInfo(s"Session closed by server: $reason")
          } yield Disconnected
        
        case other =>
          ZIO.logWarning(s"Unexpected message in Connected state: ${other.getClass.getSimpleName}").as(this)
      }
    
    private def handleRequestError(error: RequestError): UIO[ClientState] = 
      error.reason match {
        case NotLeaderRequest =>
          for {
            _ <- ZIO.logInfo(s"Not leader, redirect to: ${error.leaderId}")
            // Stay connected, error will be handled by retry manager
          } yield this
        
        case SessionTerminated =>
          for {
            _ <- ZIO.logWarning("Session terminated by server")
          } yield Disconnected
        
        case _ =>
          ZIO.logWarning(s"Request error: ${error.reason}").as(this)
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
