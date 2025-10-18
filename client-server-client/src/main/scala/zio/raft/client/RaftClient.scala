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
      now <- Clock.instant
      initialState <- createInitialState(validatedConfig, nonce, now)
      
      // Connect to first address and send CreateSession
      _ <- zmqTransport.connect(validatedConfig.clusterAddresses.head)
      _ <- zmqTransport.sendMessage(CreateSession(validatedConfig.capabilities, nonce))
      
      // Start main loop with initial state
      _ <- startMainLoop(zmqTransport, validatedConfig, actionQueue, serverRequestQueue, initialState).fork
      
    } yield client
  
  private def createZmqTransport(config: ClientConfig): UIO[ZmqClientTransport] = 
    ZIO.succeed(new ZmqClientTransportStub())
  
  /**
   * Create initial state with proper nonce and timestamp.
   */
  private def createInitialState(config: ClientConfig, nonce: Nonce, now: Instant): UIO[ClientState] =
    ZIO.succeed(
      ClientState.ConnectingNewSession(
        capabilities = config.capabilities,
        nonce = nonce,
        addresses = config.clusterAddresses,
        currentAddressIndex = 0,
        createdAt = now
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
    
    // Timeout check every 100ms
    val timeoutStream = ZStream.tick(100.millis)
      .map(_ => StreamEvent.TimeoutCheck)
    
    val unifiedStream = actionStream
      .merge(messageStream)
      .merge(keepAliveStream)
      .merge(timeoutStream)
    
    // Pure functional state machine: state handles events
    unifiedStream
      .runFold(initialState) { (state, event) =>
        // State handles everything - just pass dependencies
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
            now <- Clock.instant
            address = config.clusterAddresses.head
            _ <- transport.connect(address).orDie
            _ <- transport.sendMessage(CreateSession(config.capabilities, nonce)).orDie
          } yield ConnectingNewSession(
            capabilities = config.capabilities,
            nonce = nonce,
            addresses = config.clusterAddresses,
            currentAddressIndex = 0,
            createdAt = now
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
    currentAddressIndex: Int,
    createdAt: Instant
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
              pendingRequests = PendingRequests.empty,
              addresses = addresses,
              currentAddressIndex = currentAddressIndex
            )
          } else {
            ZIO.logWarning("Nonce mismatch, ignoring SessionCreated").as(this)
          }
        
        case StreamEvent.ServerMsg(SessionRejected(reason, responseNonce, leaderId)) =>
          if (nonce == responseNonce) {
            reason match {
              case NotLeader =>
                // Always cycle to next address using modulo
                val nextIndex = (currentAddressIndex + 1) % addresses.length
                val nextAddr = addresses(nextIndex)
                for {
                  _ <- ZIO.logInfo(s"Not leader, trying next: $nextAddr")
                  _ <- transport.disconnect().orDie
                  _ <- transport.connect(nextAddr).orDie
                  newNonce <- Nonce.generate()
                  _ <- transport.sendMessage(CreateSession(capabilities, newNonce)).orDie
                  now <- Clock.instant
                } yield copy(nonce = newNonce, currentAddressIndex = nextIndex, createdAt = now)
              
              case SessionNotFound =>
                ZIO.dieMessage("Session not found - cannot continue")
              
              case _ =>
                ZIO.logWarning(s"Session rejected: $reason").as(Disconnected)
            }
          } else {
            ZIO.logWarning("Nonce mismatch, ignoring SessionRejected").as(this)
          }
        
        case StreamEvent.TimeoutCheck =>
          // Check if connection attempt has timed out
          for {
            now <- Clock.instant
            elapsed = java.time.Duration.between(createdAt, now)
            nextState <- if (elapsed.compareTo(java.time.Duration.ofSeconds(config.connectionTimeout.toSeconds)) > 0) {
              // Timeout - try next address
              val nextIndex = (currentAddressIndex + 1) % addresses.length
              val nextAddr = addresses(nextIndex)
              for {
                _ <- ZIO.logWarning(s"Connection timeout, trying next address: $nextAddr")
                _ <- transport.disconnect().orDie
                _ <- transport.connect(nextAddr).orDie
                newNonce <- Nonce.generate()
                _ <- transport.sendMessage(CreateSession(capabilities, newNonce)).orDie
                now <- Clock.instant
              } yield copy(nonce = newNonce, currentAddressIndex = nextIndex, createdAt = now)
            } else {
              ZIO.succeed(this)
            }
          } yield nextState
        
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
    createdAt: Instant,
    serverRequestTracker: ServerRequestTracker,
    nextRequestId: Ref[RequestId],
    pendingRequests: PendingRequests
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
              serverRequestTracker = serverRequestTracker, // Resume previous tracker
              nextRequestId = nextRequestId,
              pendingRequests = pendingRequests,
              addresses = addresses,
              currentAddressIndex = currentAddressIndex
            )
          } else {
            ZIO.logWarning("Nonce mismatch, ignoring SessionContinued").as(this)
          }
        
        case StreamEvent.ServerMsg(SessionRejected(reason, responseNonce, leaderId)) =>
          if (nonce == responseNonce) {
            reason match {
              case NotLeader =>
                // Always cycle to next address using modulo
                val nextIndex = (currentAddressIndex + 1) % addresses.length
                val nextAddr = addresses(nextIndex)
                for {
                  _ <- ZIO.logInfo(s"Not leader, trying next: $nextAddr")
                  _ <- transport.disconnect().orDie
                  _ <- transport.connect(nextAddr).orDie
                  newNonce <- Nonce.generate()
                  _ <- transport.sendMessage(ContinueSession(sessionId, newNonce)).orDie
                  now <- Clock.instant
                } yield copy(nonce = newNonce, currentAddressIndex = nextIndex, createdAt = now)
              
              case SessionNotFound =>
                ZIO.dieMessage("Session not found - cannot continue")
              
              case _ =>
                ZIO.logWarning(s"Session rejected: $reason").as(Disconnected)
            }
          } else {
            ZIO.logWarning("Nonce mismatch, ignoring SessionRejected").as(this)
          }
        
        case StreamEvent.TimeoutCheck =>
          // Check if connection attempt has timed out
          for {
            now <- Clock.instant
            elapsed = java.time.Duration.between(createdAt, now)
            nextState <- if (elapsed.compareTo(java.time.Duration.ofSeconds(config.connectionTimeout.toSeconds)) > 0) {
              // Timeout - try next address
              val nextIndex = (currentAddressIndex + 1) % addresses.length
              val nextAddr = addresses(nextIndex)
              for {
                _ <- ZIO.logWarning(s"Connection timeout, trying next address: $nextAddr")
                _ <- transport.disconnect().orDie
                _ <- transport.connect(nextAddr).orDie
                newNonce <- Nonce.generate()
                _ <- transport.sendMessage(ContinueSession(sessionId, newNonce)).orDie
                now <- Clock.instant
              } yield copy(nonce = newNonce, currentAddressIndex = nextIndex, createdAt = now)
            } else {
              ZIO.succeed(this)
            }
          } yield nextState
        
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
  }
  
  case class Connected(
    sessionId: SessionId,
    capabilities: Map[String, String],
    createdAt: Instant,
    serverRequestTracker: ServerRequestTracker,
    nextRequestId: Ref[RequestId],
    pendingRequests: PendingRequests,
    addresses: List[String],
    currentAddressIndex: Int
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
            newPending = pendingRequests.add(requestId, promise)
          } yield copy(pendingRequests = newPending)
        
        case StreamEvent.ServerMsg(ClientResponse(requestId, result)) =>
          pendingRequests.complete(requestId, result) match {
            case Some(newPending) =>
              ZIO.succeed(copy(pendingRequests = newPending))
            case None =>
              ZIO.logWarning(s"Response for unknown request: $requestId").as(this)
          }
        
        case StreamEvent.ServerMsg(KeepAliveResponse(_)) =>
          ZIO.succeed(this)
        
        case StreamEvent.ServerMsg(serverRequest: ServerRequest) =>
          // Check with tracker
          if (serverRequestTracker.shouldProcess(serverRequest.requestId)) {
            for {
              // Add to queue for user to handle
              _ <- serverRequestQueue.offer(serverRequest).orDie
              // Update tracker
              newTracker = serverRequestTracker.acknowledge(serverRequest.requestId)
              // Send acknowledgment
              _ <- transport.sendMessage(ServerRequestAck(serverRequest.requestId)).orDie
              _ <- ZIO.logDebug(s"Enqueued and acknowledged server request: ${serverRequest.requestId}")
            } yield copy(serverRequestTracker = newTracker)
          } else if (serverRequestTracker.lastAcknowledgedRequestId >= serverRequest.requestId) {
            // Already processed - just ack again
            for {
              _ <- transport.sendMessage(ServerRequestAck(serverRequest.requestId)).orDie
              _ <- ZIO.logDebug(s"Re-acknowledged already processed request: ${serverRequest.requestId}")
            } yield this
          } else {
            // Out of order - drop it
            ZIO.logWarning(s"Dropping out-of-order server request: ${serverRequest.requestId}, expected: ${serverRequestTracker.lastAcknowledgedRequestId.next}").as(this)
          }
        
        case StreamEvent.ServerMsg(RequestError(reason, leaderId)) =>
          reason match {
            case NotLeaderRequest =>
              // Transition to ConnectingExistingSession to find new leader
              for {
                _ <- ZIO.logInfo(s"Not leader, reconnecting")
                nonce <- Nonce.generate()
                val nextIndex = (currentAddressIndex + 1) % addresses.length
                val nextAddr = addresses(nextIndex)
                _ <- transport.disconnect().orDie
                _ <- transport.connect(nextAddr).orDie
                _ <- transport.sendMessage(ContinueSession(sessionId, nonce)).orDie
                now <- Clock.instant
              } yield ConnectingExistingSession(
                sessionId = sessionId,
                capabilities = capabilities,
                nonce = nonce,
                addresses = addresses,
                currentAddressIndex = nextIndex,
                createdAt = now,
                serverRequestTracker = serverRequestTracker,
                nextRequestId = nextRequestId,
                pendingRequests = pendingRequests
              )
            
            case SessionTerminated =>
              // Transition to Connecting to create new session
              for {
                _ <- ZIO.logWarning("Session terminated, creating new session")
                nonce <- Nonce.generate()
                _ <- transport.sendMessage(CreateSession(capabilities, nonce)).orDie
                now <- Clock.instant
              } yield ConnectingNewSession(
                capabilities = capabilities,
                nonce = nonce,
                addresses = addresses,
                currentAddressIndex = currentAddressIndex,
                createdAt = now
              )
            
            case _ =>
              ZIO.logWarning(s"Request error: $reason").as(this)
          }
        
        case StreamEvent.ServerMsg(SessionClosed(reason, _)) =>
          for {
            _ <- ZIO.logInfo(s"Session closed: $reason")
            nonce <- Nonce.generate()
            _ <- transport.sendMessage(CreateSession(capabilities, nonce)).orDie
            now <- Clock.instant
          } yield ConnectingNewSession(
            capabilities = capabilities,
            nonce = nonce,
            addresses = addresses,
            currentAddressIndex = currentAddressIndex,
            createdAt = now
          )
        
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

/**
 * Manages pending requests (renamed from RetryManager).
 */
case class PendingRequests(
  requests: Map[RequestId, Promise[Throwable, ByteVector]]
) {
  def add(requestId: RequestId, promise: Promise[Throwable, ByteVector]): PendingRequests =
    copy(requests = requests.updated(requestId, promise))
  
  def complete(requestId: RequestId, result: ByteVector): Option[PendingRequests] =
    requests.get(requestId).map { promise =>
      promise.succeed(result).ignore
      copy(requests = requests.removed(requestId))
    }
}

object PendingRequests {
  def empty: PendingRequests = PendingRequests(Map.empty)
}

private sealed trait StreamEvent

private object StreamEvent {
  case class Action(action: ClientAction) extends StreamEvent
  case class ServerMsg(message: ServerMessage) extends StreamEvent
  case object KeepAliveTick extends StreamEvent
  case object TimeoutCheck extends StreamEvent
}

trait ZmqClientTransport {
  def connect(address: String): Task[Unit]
  def disconnect(): Task[Unit]
  def sendMessage(message: ClientMessage): Task[Unit]
  def incomingMessages: ZStream[Any, Throwable, ServerMessage]
}

private class ZmqClientTransportStub extends ZmqClientTransport {
  override def connect(address: String): Task[Unit] =
    ZIO.logDebug(s"Stub: connecting to $address")
  
  override def disconnect(): Task[Unit] =
    ZIO.logDebug("Stub: disconnecting")
  
  override def sendMessage(message: ClientMessage): Task[Unit] = 
    ZIO.logDebug(s"Stub: sending message: ${message.getClass.getSimpleName}")
  
  override def incomingMessages: ZStream[Any, Throwable, ServerMessage] = 
    ZStream.empty
}

class TimeoutException(message: String) extends RuntimeException(message)
