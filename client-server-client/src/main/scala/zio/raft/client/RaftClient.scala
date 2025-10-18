package zio.raft.client

import zio._
import zio.stream._
import zio.raft.protocol._
import scodec.bits.ByteVector
import java.time.Instant

/**
 * Main RaftClient implementation using pure functional stream-based approach.
 * 
 * The client uses a unified stream that merges:
 * - Client actions (commands, disconnect requests)
 * - Server messages (responses, session management)
 * - Keep-alive ticks
 * 
 * State transitions happen through stream folding, where each event
 * produces a new ClientState without mutable refs.
 */
class RaftClient private (
  zmqTransport: ZmqClientTransport,
  config: ClientConfig,
  actionQueue: Queue[ClientAction]
) {
  
  /**
   * Submit a command to the cluster.
   * Enqueues a command submission action to be processed by the stream.
   */
  def submitCommand(payload: ByteVector): Task[ByteVector] = 
    for {
      promise <- Promise.make[Throwable, ByteVector]
      action = ClientAction.SubmitCommand(payload, promise)
      _ <- actionQueue.offer(action)
      result <- promise.await
    } yield result
  
  /**
   * Disconnect from the cluster.
   * Enqueues a disconnect action to be processed by the stream.
   */
  def disconnect(): UIO[Unit] = 
    actionQueue.offer(ClientAction.Disconnect).unit
  
  /**
   * Acknowledge a server-initiated request.
   * This is called immediately when we receive a server request.
   */
  def acknowledgeServerRequest(requestId: RequestId): UIO[Unit] = 
    for {
      ack = ServerRequestAck(requestId)
      _ <- zmqTransport.sendMessage(ack).orDie
      _ <- ZIO.logDebug(s"Acknowledged server request: $requestId")
    } yield ()
}

object RaftClient {
  
  /**
   * Create and start a RaftClient.
   * The client automatically connects to the cluster on creation.
   */
  def make(addresses: List[String], capabilities: Map[String, String]): ZIO[Scope, Throwable, RaftClient] = 
    for {
      // Create and validate configuration
      config = ClientConfig.make(addresses, capabilities)
      validatedConfig <- ClientConfig.validated(config).mapError(new IllegalArgumentException(_))
      
      // Create ZMQ transport
      zmqTransport <- createZmqTransport(validatedConfig)
      
      // Create action queue for stream-based processing
      actionQueue <- Queue.unbounded[ClientAction]
      
      // Create client
      client = new RaftClient(
        zmqTransport,
        validatedConfig,
        actionQueue
      )
      
      // Start the main processing loop (forked)
      _ <- startMainLoop(client, zmqTransport, validatedConfig, actionQueue).fork
      
      // Automatically initiate connection
      _ <- initiateConnection(zmqTransport, validatedConfig, actionQueue)
      
    } yield client
  
  /**
   * Initiate connection - just sends CreateSession message.
   * Does not wait for response, which will be handled by the stream.
   */
  private def initiateConnection(
    transport: ZmqClientTransport,
    config: ClientConfig,
    actionQueue: Queue[ClientAction]
  ): Task[Unit] = 
    for {
      nonce <- Nonce.generate()
      createMessage = CreateSession(config.capabilities, nonce)
      _ <- transport.sendMessage(createMessage)
      _ <- ZIO.logInfo("CreateSession sent, transitioning to Connecting")
    } yield ()
  
  private def createZmqTransport(config: ClientConfig): UIO[ZmqClientTransport] = 
    ZIO.succeed(new ZmqClientTransportStub())
  
  /**
   * Main processing loop using unified stream with fold.
   * Each fold iteration returns a new ClientState.
   */
  private def startMainLoop(
    client: RaftClient,
    transport: ZmqClientTransport,
    config: ClientConfig,
    actionQueue: Queue[ClientAction]
  ): Task[Unit] = {
    
    // Create unified stream merging all event sources
    val actionStream = ZStream.fromQueue(actionQueue)
      .map(StreamEvent.Action(_))
    
    val messageStream = transport.incomingMessages
      .map(StreamEvent.ServerMsg(_))
    
    val keepAliveStream = ZStream.tick(config.keepAliveInterval)
      .map(_ => StreamEvent.KeepAliveTick)
    
    val unifiedStream = actionStream
      .merge(messageStream)
      .merge(keepAliveStream)
    
    // Initial state: Connecting with first address
    val initialState = ClientState.Connecting(
      capabilities = config.capabilities,
      nonce = None,
      addresses = config.clusterAddresses,
      currentAddressIndex = 0,
      retryManager = RetryManager.empty(config)
    )
    
    // Fold over unified stream, each fold returns new ClientState
    unifiedStream
      .runFold(initialState) { (state, event) =>
        processEvent(client, transport, config, state, event)
      }
      .unit
  }
  
  /**
   * Process a single event and return new state.
   * All state transitions happen here through pure functions.
   */
  private def processEvent(
    client: RaftClient,
    transport: ZmqClientTransport,
    config: ClientConfig,
    state: ClientState,
    event: StreamEvent
  ): UIO[ClientState] = {
    event match {
      case StreamEvent.Action(action) =>
        handleAction(client, transport, state, action)
      
      case StreamEvent.ServerMsg(message) =>
        handleServerMessage(client, transport, state, message)
      
      case StreamEvent.KeepAliveTick =>
        handleKeepAliveTick(transport, state)
    }
  }
  
  /**
   * Handle client actions based on current state.
   */
  private def handleAction(
    client: RaftClient,
    transport: ZmqClientTransport,
    state: ClientState,
    action: ClientAction
  ): UIO[ClientState] = {
    action match {
      case ClientAction.Disconnect =>
        state match {
          case s: ClientState.Connected =>
            for {
              closeMessage = CloseSession(ClientShutdown)
              _ <- transport.sendMessage(closeMessage).orDie
              _ <- ZIO.logInfo("Disconnected from cluster")
            } yield ClientState.Disconnected
          case _ =>
            ZIO.succeed(ClientState.Disconnected)
        }
      
      case ClientAction.SubmitCommand(payload, promise) =>
        state match {
          case s: ClientState.Connected =>
            for {
              requestId <- generateRequestId()
              now <- Clock.instant
              request = ClientRequest(requestId, payload, now)
              newState <- s.addPendingRequest(request, promise)
              _ <- transport.sendMessage(request).orDie
            } yield newState
          case _ =>
            promise.fail(new IllegalStateException("Client is not connected")).ignore.as(state)
        }
      
      case ClientAction.AcknowledgeServerRequest(requestId) =>
        // Acknowledgment happens immediately in the acknowledgeServerRequest method
        ZIO.succeed(state)
      
      case ClientAction.Connect =>
        // Not implemented - connect happens automatically on startup
        ZIO.succeed(state)
      
      case ClientAction.ProcessServerMessage(message) =>
        // This case shouldn't occur since messages come directly in the stream
        handleServerMessage(client, transport, state, message)
    }
  }
  
  /**
   * Handle server messages based on current state.
   * Each state knows how to handle different message types.
   */
  private def handleServerMessage(
    client: RaftClient,
    transport: ZmqClientTransport,
    state: ClientState,
    message: ServerMessage
  ): UIO[ClientState] = {
    state match {
      case s: ClientState.Disconnected =>
        ZIO.logWarning(s"Received message while disconnected: ${message.getClass.getSimpleName}").as(state)
      
      case s: ClientState.Connecting =>
        handleMessageInConnecting(transport, s, message)
      
      case s: ClientState.Connected =>
        handleMessageInConnected(client, transport, s, message)
    }
  }
  
  /**
   * Handle messages in Connecting state.
   */
  private def handleMessageInConnecting(
    transport: ZmqClientTransport,
    state: ClientState.Connecting,
    message: ServerMessage
  ): UIO[ClientState] = {
    message match {
      case SessionCreated(sessionId, responseNonce) =>
        // Validate nonce if we have one
        state.nonce match {
          case Some(expectedNonce) if expectedNonce != responseNonce =>
            ZIO.logWarning(s"Nonce mismatch, ignoring SessionCreated").as(state)
          case _ =>
            for {
              _ <- ZIO.logInfo(s"Session created: $sessionId")
              now <- Clock.instant
            } yield ClientState.Connected(
              sessionId = sessionId,
              capabilities = state.capabilities,
              createdAt = now,
              serverRequestTracker = ServerRequestTracker(),
              retryManager = state.retryManager
            )
        }
      
      case SessionContinued(responseNonce) =>
        // Validate nonce
        state.nonce match {
          case Some(expectedNonce) if expectedNonce != responseNonce =>
            ZIO.logWarning(s"Nonce mismatch, ignoring SessionContinued").as(state)
          case _ =>
            ZIO.logInfo("Session continued successfully").as(state)
        }
      
      case rejection: SessionRejected =>
        handleRejectionInConnecting(transport, state, rejection)
      
      case other =>
        ZIO.logWarning(s"Unexpected message in Connecting: ${other.getClass.getSimpleName}").as(state)
    }
  }
  
  /**
   * Handle session rejection in Connecting state.
   */
  private def handleRejectionInConnecting(
    transport: ZmqClientTransport,
    state: ClientState.Connecting,
    rejection: SessionRejected
  ): UIO[ClientState] = {
    // Validate nonce
    state.nonce match {
      case Some(expectedNonce) if expectedNonce != rejection.nonce =>
        ZIO.logWarning(s"Nonce mismatch, ignoring SessionRejected").as(state)
      case _ =>
        rejection.reason match {
          case NotLeader =>
            // Try next address
            state.nextAddress match {
              case Some((address, newState)) =>
                for {
                  _ <- ZIO.logInfo(s"Not leader, trying next address: $address")
                  nonce <- Nonce.generate()
                  createMessage = CreateSession(newState.capabilities, nonce)
                  _ <- transport.sendMessage(createMessage).orDie
                } yield newState.copy(nonce = Some(nonce))
              case None =>
                // No more addresses, loop back to start
                for {
                  _ <- ZIO.logInfo("No more addresses, retrying from beginning")
                  nonce <- Nonce.generate()
                  createMessage = CreateSession(state.capabilities, nonce)
                  _ <- transport.sendMessage(createMessage).orDie
                } yield state.copy(currentAddressIndex = 0, nonce = Some(nonce))
            }
          
          case SessionNotFound =>
            // Fatal error
            ZIO.dieMessage("Session not found on server - cannot continue")
          
          case _ =>
            ZIO.logWarning(s"Session rejected: ${rejection.reason}").as(ClientState.Disconnected)
        }
    }
  }
  
  /**
   * Handle messages in Connected state.
   */
  private def handleMessageInConnected(
    client: RaftClient,
    transport: ZmqClientTransport,
    state: ClientState.Connected,
    message: ServerMessage
  ): UIO[ClientState] = {
    message match {
      case response: ClientResponse =>
        // Complete pending request
        state.completePendingRequest(response.requestId, response.result)
      
      case KeepAliveResponse(timestamp) =>
        ZIO.logDebug(s"Keep-alive acknowledged").as(state)
      
      case serverRequest: ServerRequest =>
        // Server request should be acknowledged immediately
        for {
          _ <- client.acknowledgeServerRequest(serverRequest.requestId)
          _ <- ZIO.logInfo(s"Received and acknowledged server request: ${serverRequest.requestId}")
        } yield state
      
      case error: RequestError =>
        handleRequestError(transport, state, error)
      
      case SessionClosed(reason, leaderId) =>
        for {
          _ <- ZIO.logInfo(s"Session closed by server: $reason")
        } yield ClientState.Disconnected
      
      case other =>
        ZIO.logWarning(s"Unexpected message in Connected: ${other.getClass.getSimpleName}").as(state)
    }
  }
  
  /**
   * Handle request errors in Connected state.
   */
  private def handleRequestError(
    transport: ZmqClientTransport,
    state: ClientState.Connected,
    error: RequestError
  ): UIO[ClientState] = {
    error.reason match {
      case NotLeaderRequest =>
        for {
          _ <- ZIO.logInfo(s"Not leader, redirect to: ${error.leaderId}")
          // Transition to Connecting to find new leader
          nonce <- Nonce.generate()
          continueMessage = ContinueSession(state.sessionId, nonce)
          _ <- transport.sendMessage(continueMessage).orDie
        } yield ClientState.Connecting(
          capabilities = state.capabilities,
          nonce = Some(nonce),
          addresses = List.empty, // TODO: use addresses from config
          currentAddressIndex = 0,
          retryManager = state.retryManager
        )
      
      case SessionTerminated =>
        for {
          _ <- ZIO.logWarning("Session terminated by server")
        } yield ClientState.Disconnected
      
      case _ =>
        ZIO.logWarning(s"Request error: ${error.reason}").as(state)
    }
  }
  
  /**
   * Handle keep-alive tick.
   */
  private def handleKeepAliveTick(
    transport: ZmqClientTransport,
    state: ClientState
  ): UIO[ClientState] = {
    state match {
      case s: ClientState.Connected =>
        for {
          now <- Clock.instant
          keepAlive = KeepAlive(now)
          _ <- transport.sendMessage(keepAlive).orDie
        } yield state
      case _ =>
        ZIO.succeed(state)
    }
  }
  
  private def generateRequestId(): UIO[RequestId] = 
    Random.nextLong.map(RequestId.fromLong)
}

/**
 * Client connection states using functional ADT approach.
 * Each state contains all data needed and returns new states on transitions.
 */
sealed trait ClientState {
  def stateName: String
}

object ClientState {
  
  /**
   * Client is disconnected - not attempting to connect.
   */
  case object Disconnected extends ClientState {
    override def stateName: String = "Disconnected"
  }
  
  /**
   * Client is attempting to establish a connection/session.
   */
  case class Connecting(
    capabilities: Map[String, String],
    nonce: Option[Nonce],
    addresses: List[String],
    currentAddressIndex: Int,
    retryManager: RetryManager
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
  }
  
  /**
   * Client has an active session and can send/receive requests.
   */
  case class Connected(
    sessionId: SessionId,
    capabilities: Map[String, String],
    createdAt: Instant,
    serverRequestTracker: ServerRequestTracker,
    retryManager: RetryManager,
    pendingRequests: Map[RequestId, Promise[Throwable, ByteVector]] = Map.empty
  ) extends ClientState {
    override def stateName: String = s"Connected($sessionId)"
    
    def addPendingRequest(
      request: ClientRequest,
      promise: Promise[Throwable, ByteVector]
    ): UIO[Connected] = {
      ZIO.succeed(copy(pendingRequests = pendingRequests.updated(request.requestId, promise)))
    }
    
    def completePendingRequest(
      requestId: RequestId,
      result: ByteVector
    ): UIO[Connected] = {
      pendingRequests.get(requestId) match {
        case Some(promise) =>
          promise.succeed(result).ignore.as(
            copy(pendingRequests = pendingRequests.removed(requestId))
          )
        case None =>
          ZIO.logWarning(s"Received response for unknown request: $requestId").as(this)
      }
    }
  }
}

/**
 * Events in the unified stream.
 */
private sealed trait StreamEvent

private object StreamEvent {
  case class Action(action: ClientAction) extends StreamEvent
  case class ServerMsg(message: ServerMessage) extends StreamEvent
  case object KeepAliveTick extends StreamEvent
}

/**
 * ZeroMQ client transport abstraction.
 */
trait ZmqClientTransport {
  def sendMessage(message: ClientMessage): Task[Unit]
  def incomingMessages: ZStream[Any, Throwable, ServerMessage]
}

/**
 * Stub implementation for compilation.
 */
private class ZmqClientTransportStub extends ZmqClientTransport {
  override def sendMessage(message: ClientMessage): Task[Unit] = 
    ZIO.logDebug(s"Stub: sending message: ${message.getClass.getSimpleName}")
  
  override def incomingMessages: ZStream[Any, Throwable, ServerMessage] = 
    ZStream.empty
}

/**
 * Exception thrown when operations timeout.
 */
class TimeoutException(message: String) extends RuntimeException(message)
