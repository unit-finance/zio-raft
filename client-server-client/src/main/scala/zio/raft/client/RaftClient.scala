package zio.raft.client

import zio._
import zio.stream._
import zio.raft.protocol._
import scodec.bits.ByteVector
import java.time.Instant

/**
 * Main RaftClient implementation using pure functional state machine.
 * 
 * The client uses a unified stream that merges all events.
 * Each ClientState knows how to handle events and transition to new states.
 */
class RaftClient private (
  zmqTransport: ZmqClientTransport,
  config: ClientConfig,
  actionQueue: Queue[ClientAction],
  serverRequestQueue: Queue[ServerRequest]
) {
  
  def submitCommand(payload: ByteVector): Task[ByteVector] = 
    for {
      promise <- Promise.make[Throwable, ByteVector]
      action = ClientAction.SubmitCommand(payload, promise)
      _ <- actionQueue.offer(action)
      result <- promise.await
    } yield result
  
  def disconnect(): UIO[Unit] = 
    actionQueue.offer(ClientAction.Disconnect).unit
  
  /**
   * Stream of server-initiated requests for user to handle.
   */
  def serverRequests: ZStream[Any, Nothing, ServerRequest] =
    ZStream.fromQueue(serverRequestQueue)
}

object RaftClient {
  
  def make(addresses: List[String], capabilities: Map[String, String]): ZIO[Scope, Throwable, RaftClient] = 
    for {
      config = ClientConfig.make(addresses, capabilities)
      validatedConfig <- ClientConfig.validated(config).mapError(new IllegalArgumentException(_))
      
      zmqTransport <- createZmqTransport(validatedConfig)
      actionQueue <- Queue.unbounded[ClientAction]
      serverRequestQueue <- Queue.unbounded[ServerRequest]
      
      client = new RaftClient(zmqTransport, validatedConfig, actionQueue, serverRequestQueue)
      
      // Generate nonce and create initial state BEFORE starting loop
      nonce <- Nonce.generate()
      initialState <- createInitialState(validatedConfig, nonce)
      
      // Send CreateSession
      _ <- zmqTransport.sendMessage(CreateSession(validatedConfig.capabilities, nonce))
      
      // Start main loop with initial state
      _ <- startMainLoop(zmqTransport, validatedConfig, actionQueue, serverRequestQueue, initialState).fork
      
    } yield client
  
  private def createZmqTransport(config: ClientConfig): UIO[ZmqClientTransport] = 
    ZIO.succeed(new ZmqClientTransportStub())
  
  /**
   * Create initial state with proper nonce.
   */
  private def createInitialState(config: ClientConfig, nonce: Nonce): UIO[ClientState] =
    ZIO.succeed(
      ClientState.ConnectingNewSession(
        capabilities = config.capabilities,
        nonce = nonce,
        addresses = config.clusterAddresses,
        currentAddressIndex = 0
      )
    )
  
  /**
   * Main loop accepts initial state as parameter.
   */
  private def startMainLoop(
    transport: ZmqClientTransport,
    config: ClientConfig,
    actionQueue: Queue[ClientAction],
    serverRequestQueue: Queue[ServerRequest],
    initialState: ClientState
  ): Task[Unit] = {
    
    val actionStream = ZStream.fromQueue(actionQueue)
      .map(StreamEvent.Action(_))
    
    val messageStream = transport.incomingMessages
      .map(StreamEvent.ServerMsg(_))
    
    val keepAliveStream = ZStream.tick(config.keepAliveInterval)
      .map(_ => StreamEvent.KeepAliveTick)
    
    val unifiedStream = actionStream
      .merge(messageStream)
      .merge(keepAliveStream)
    
    // Pure functional state machine: state handles events
    unifiedStream
      .runFold(initialState) { (state, event) =>
        // State handles everything - just pass dependencies, no client reference
        state.handle(event, transport, config, serverRequestQueue)
      }
      .unit
  }
}

/**
 * Functional state machine: each state handles events and returns new states.
 */
sealed trait ClientState {
  def stateName: String
  def handle(
    event: StreamEvent,
    transport: ZmqClientTransport,
    config: ClientConfig,
    serverRequestQueue: Queue[ServerRequest]
  ): UIO[ClientState]
}

object ClientState {
  
  case object Disconnected extends ClientState {
    override def stateName: String = "Disconnected"
    
    override def handle(
      event: StreamEvent,
      transport: ZmqClientTransport,
      config: ClientConfig,
      serverRequestQueue: Queue[ServerRequest]
    ): UIO[ClientState] = {
      event match {
        case StreamEvent.Action(ClientAction.Connect) =>
          // Reconnect: generate nonce and transition to ConnectingNewSession
          for {
            nonce <- Nonce.generate()
            _ <- transport.sendMessage(CreateSession(config.capabilities, nonce)).orDie
          } yield ConnectingNewSession(
            capabilities = config.capabilities,
            nonce = nonce,
            addresses = config.clusterAddresses,
            currentAddressIndex = 0
          )
        
        case StreamEvent.ServerMsg(message) =>
          ZIO.logWarning(s"Received message while disconnected: ${message.getClass.getSimpleName}").as(this)
        
        case StreamEvent.Action(ClientAction.SubmitCommand(_, promise)) =>
          promise.fail(new IllegalStateException("Not connected")).ignore.as(this)
        
        case _ =>
          ZIO.succeed(this)
      }
    }
  }
  
  /**
   * Connecting to create a NEW session (no existing sessionId).
   */
  case class ConnectingNewSession(
    capabilities: Map[String, String],
    nonce: Nonce,
    addresses: List[String],
    currentAddressIndex: Int
  ) extends ClientState {
    override def stateName: String = "ConnectingNewSession"
    
    override def handle(
      event: StreamEvent,
      transport: ZmqClientTransport,
      config: ClientConfig,
      serverRequestQueue: Queue[ServerRequest]
    ): UIO[ClientState] = {
      event match {
        case StreamEvent.ServerMsg(SessionCreated(sessionId, responseNonce)) =>
          if (nonce == responseNonce) {
            for {
              _ <- ZIO.logInfo(s"Session created: $sessionId")
              now <- Clock.instant
              nextRequestId <- Ref.make(RequestId.zero)
            } yield Connected(
              sessionId = sessionId,
              capabilities = capabilities,
              createdAt = now,
              serverRequestTracker = ServerRequestTracker(),
              nextRequestId = nextRequestId,
              pendingRequests = Map.empty,
              addresses = addresses
            )
          } else {
            ZIO.logWarning("Nonce mismatch, ignoring SessionCreated").as(this)
          }
        
        case StreamEvent.ServerMsg(SessionRejected(reason, responseNonce, leaderId)) =>
          if (nonce == responseNonce) {
            reason match {
              case NotLeader =>
                nextAddress match {
                  case Some((address, newState)) =>
                    for {
                      _ <- ZIO.logInfo(s"Not leader, trying next: $address")
                      newNonce <- Nonce.generate()
                      _ <- transport.sendMessage(CreateSession(capabilities, newNonce)).orDie
                    } yield newState.copy(nonce = newNonce)
                  case None =>
                    for {
                      _ <- ZIO.logInfo("No more addresses, retrying from start")
                      newNonce <- Nonce.generate()
                      _ <- transport.sendMessage(CreateSession(capabilities, newNonce)).orDie
                    } yield copy(currentAddressIndex = 0, nonce = newNonce)
                }
              
              case SessionNotFound =>
                ZIO.dieMessage("Session not found - cannot continue")
              
              case _ =>
                ZIO.logWarning(s"Session rejected: $reason").as(Disconnected)
            }
          } else {
            ZIO.logWarning("Nonce mismatch, ignoring SessionRejected").as(this)
          }
        
        case StreamEvent.ServerMsg(other) =>
          ZIO.logWarning(s"Unexpected message in ConnectingNewSession: ${other.getClass.getSimpleName}").as(this)
        
        case StreamEvent.Action(ClientAction.SubmitCommand(_, promise)) =>
          promise.fail(new IllegalStateException("Not connected")).ignore.as(this)
        
        case StreamEvent.Action(ClientAction.Disconnect) =>
          ZIO.succeed(Disconnected)
        
        case _ =>
          ZIO.succeed(this)
      }
    }
    
    def nextAddress: Option[(String, ConnectingNewSession)] = {
      val nextIndex = currentAddressIndex + 1
      if (nextIndex < addresses.length) {
        Some((addresses(nextIndex), copy(currentAddressIndex = nextIndex)))
      } else {
        None
      }
    }
  }
  
  /**
   * Connecting to resume an EXISTING session (has sessionId).
   */
  case class ConnectingExistingSession(
    sessionId: SessionId,
    capabilities: Map[String, String],
    nonce: Nonce,
    addresses: List[String],
    currentAddressIndex: Int,
    nextRequestId: Ref[RequestId],
    pendingRequests: Map[RequestId, Promise[Throwable, ByteVector]]
  ) extends ClientState {
    override def stateName: String = s"ConnectingExistingSession($sessionId)"
    
    override def handle(
      event: StreamEvent,
      transport: ZmqClientTransport,
      config: ClientConfig,
      serverRequestQueue: Queue[ServerRequest]
    ): UIO[ClientState] = {
      event match {
        case StreamEvent.ServerMsg(SessionContinued(responseNonce)) =>
          if (nonce == responseNonce) {
            for {
              _ <- ZIO.logInfo(s"Session continued: $sessionId")
              now <- Clock.instant
            } yield Connected(
              sessionId = sessionId,
              capabilities = capabilities,
              createdAt = now,
              serverRequestTracker = ServerRequestTracker(),
              nextRequestId = nextRequestId,
              pendingRequests = pendingRequests,
              addresses = addresses
            )
          } else {
            ZIO.logWarning("Nonce mismatch, ignoring SessionContinued").as(this)
          }
        
        case StreamEvent.ServerMsg(SessionRejected(reason, responseNonce, leaderId)) =>
          if (nonce == responseNonce) {
            reason match {
              case NotLeader =>
                nextAddress match {
                  case Some((address, newState)) =>
                    for {
                      _ <- ZIO.logInfo(s"Not leader, trying next: $address")
                      newNonce <- Nonce.generate()
                      _ <- transport.sendMessage(ContinueSession(sessionId, newNonce)).orDie
                    } yield newState.copy(nonce = newNonce)
                  case None =>
                    for {
                      _ <- ZIO.logInfo("No more addresses, retrying from start")
                      newNonce <- Nonce.generate()
                      _ <- transport.sendMessage(ContinueSession(sessionId, newNonce)).orDie
                    } yield copy(currentAddressIndex = 0, nonce = newNonce)
                }
              
              case SessionNotFound =>
                ZIO.dieMessage("Session not found - cannot continue")
              
              case _ =>
                ZIO.logWarning(s"Session rejected: $reason").as(Disconnected)
            }
          } else {
            ZIO.logWarning("Nonce mismatch, ignoring SessionRejected").as(this)
          }
        
        case StreamEvent.ServerMsg(other) =>
          ZIO.logWarning(s"Unexpected message in ConnectingExistingSession: ${other.getClass.getSimpleName}").as(this)
        
        case StreamEvent.Action(ClientAction.SubmitCommand(_, promise)) =>
          promise.fail(new IllegalStateException("Not connected")).ignore.as(this)
        
        case StreamEvent.Action(ClientAction.Disconnect) =>
          ZIO.succeed(Disconnected)
        
        case _ =>
          ZIO.succeed(this)
      }
    }
    
    def nextAddress: Option[(String, ConnectingExistingSession)] = {
      val nextIndex = currentAddressIndex + 1
      if (nextIndex < addresses.length) {
        Some((addresses(nextIndex), copy(currentAddressIndex = nextIndex)))
      } else {
        None
      }
    }
  }
  
  case class Connected(
    sessionId: SessionId,
    capabilities: Map[String, String],
    createdAt: Instant,
    serverRequestTracker: ServerRequestTracker,
    nextRequestId: Ref[RequestId],
    pendingRequests: Map[RequestId, Promise[Throwable, ByteVector]],
    addresses: List[String]
  ) extends ClientState {
    override def stateName: String = s"Connected($sessionId)"
    
    override def handle(
      event: StreamEvent,
      transport: ZmqClientTransport,
      config: ClientConfig,
      serverRequestQueue: Queue[ServerRequest]
    ): UIO[ClientState] = {
      event match {
        case StreamEvent.Action(ClientAction.Disconnect) =>
          for {
            _ <- transport.sendMessage(CloseSession(ClientShutdown)).orDie
            _ <- ZIO.logInfo("Disconnected")
          } yield Disconnected
        
        case StreamEvent.Action(ClientAction.SubmitCommand(payload, promise)) =>
          for {
            requestId <- nextRequestId.getAndUpdate(_.next)
            now <- Clock.instant
            request = ClientRequest(requestId, payload, now)
            _ <- transport.sendMessage(request).orDie
          } yield copy(pendingRequests = pendingRequests.updated(requestId, promise))
        
        case StreamEvent.ServerMsg(ClientResponse(requestId, result)) =>
          pendingRequests.get(requestId) match {
            case Some(promise) =>
              promise.succeed(result).ignore.as(
                copy(pendingRequests = pendingRequests.removed(requestId))
              )
            case None =>
              ZIO.logWarning(s"Response for unknown request: $requestId").as(this)
          }
        
        case StreamEvent.ServerMsg(KeepAliveResponse(_)) =>
          ZIO.succeed(this)
        
        case StreamEvent.ServerMsg(serverRequest: ServerRequest) =>
          for {
            // Enqueue for user to handle
            _ <- serverRequestQueue.offer(serverRequest).orDie
            // Immediately acknowledge
            _ <- transport.sendMessage(ServerRequestAck(serverRequest.requestId)).orDie
            _ <- ZIO.logDebug(s"Enqueued and acknowledged server request: ${serverRequest.requestId}")
          } yield this
        
        case StreamEvent.ServerMsg(RequestError(reason, leaderId)) =>
          reason match {
            case NotLeaderRequest =>
              for {
                _ <- ZIO.logInfo(s"Not leader, reconnecting")
                nonce <- Nonce.generate()
                _ <- transport.sendMessage(ContinueSession(sessionId, nonce)).orDie
              } yield ConnectingExistingSession(
                sessionId = sessionId,
                capabilities = capabilities,
                nonce = nonce,
                addresses = addresses,
                currentAddressIndex = 0,
                nextRequestId = nextRequestId,
                pendingRequests = pendingRequests
              )
            
            case SessionTerminated =>
              ZIO.logWarning("Session terminated").as(Disconnected)
            
            case _ =>
              ZIO.logWarning(s"Request error: $reason").as(this)
          }
        
        case StreamEvent.ServerMsg(SessionClosed(reason, _)) =>
          ZIO.logInfo(s"Session closed: $reason").as(Disconnected)
        
        case StreamEvent.KeepAliveTick =>
          for {
            now <- Clock.instant
            _ <- transport.sendMessage(KeepAlive(now)).orDie
          } yield this
        
        case _ =>
          ZIO.succeed(this)
      }
    }
  }
}

private sealed trait StreamEvent

private object StreamEvent {
  case class Action(action: ClientAction) extends StreamEvent
  case class ServerMsg(message: ServerMessage) extends StreamEvent
  case object KeepAliveTick extends StreamEvent
}

trait ZmqClientTransport {
  def sendMessage(message: ClientMessage): Task[Unit]
  def incomingMessages: ZStream[Any, Throwable, ServerMessage]
}

private class ZmqClientTransportStub extends ZmqClientTransport {
  override def sendMessage(message: ClientMessage): Task[Unit] = 
    ZIO.logDebug(s"Stub: sending message: ${message.getClass.getSimpleName}")
  
  override def incomingMessages: ZStream[Any, Throwable, ServerMessage] = 
    ZStream.empty
}

class TimeoutException(message: String) extends RuntimeException(message)
