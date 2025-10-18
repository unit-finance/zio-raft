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
  actionQueue: Queue[ClientAction]
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
}

object RaftClient {
  
  def make(addresses: List[String], capabilities: Map[String, String]): ZIO[Scope, Throwable, RaftClient] = 
    for {
      config = ClientConfig.make(addresses, capabilities)
      validatedConfig <- ClientConfig.validated(config).mapError(new IllegalArgumentException(_))
      
      zmqTransport <- createZmqTransport(validatedConfig)
      actionQueue <- Queue.unbounded[ClientAction]
      
      client = new RaftClient(zmqTransport, validatedConfig, actionQueue)
      
      _ <- startMainLoop(client, zmqTransport, validatedConfig, actionQueue).fork
      
      // Send initial CreateSession
      nonce <- Nonce.generate()
      _ <- zmqTransport.sendMessage(CreateSession(validatedConfig.capabilities, nonce))
      
    } yield client
  
  private def createZmqTransport(config: ClientConfig): UIO[ZmqClientTransport] = 
    ZIO.succeed(new ZmqClientTransportStub())
  
  private def startMainLoop(
    client: RaftClient,
    transport: ZmqClientTransport,
    config: ClientConfig,
    actionQueue: Queue[ClientAction]
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
    
    val initialState: ClientState = ClientState.Connecting(
      capabilities = config.capabilities,
      nonce = None,
      addresses = config.clusterAddresses,
      currentAddressIndex = 0,
      retryManager = RetryManager.empty(config)
    )
    
    // Pure functional state machine: state handles events
    unifiedStream
      .runFold(initialState) { (state, event) =>
        state.handle(event, transport, config, client)
      }
      .unit
  }
  
  private def generateRequestId(): UIO[RequestId] = 
    Random.nextLong.map(RequestId.fromLong)
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
    client: RaftClient
  ): UIO[ClientState]
}

object ClientState {
  
  case object Disconnected extends ClientState {
    override def stateName: String = "Disconnected"
    
    override def handle(
      event: StreamEvent,
      transport: ZmqClientTransport,
      config: ClientConfig,
      client: RaftClient
    ): UIO[ClientState] = {
      event match {
        case StreamEvent.Action(ClientAction.Connect) =>
          // TODO: implement reconnection
          ZIO.succeed(this)
        
        case StreamEvent.ServerMsg(message) =>
          ZIO.logWarning(s"Received message while disconnected: ${message.getClass.getSimpleName}").as(this)
        
        case _ =>
          ZIO.succeed(this)
      }
    }
  }
  
  case class Connecting(
    capabilities: Map[String, String],
    nonce: Option[Nonce],
    addresses: List[String],
    currentAddressIndex: Int,
    retryManager: RetryManager
  ) extends ClientState {
    override def stateName: String = "Connecting"
    
    override def handle(
      event: StreamEvent,
      transport: ZmqClientTransport,
      config: ClientConfig,
      client: RaftClient
    ): UIO[ClientState] = {
      event match {
        case StreamEvent.ServerMsg(SessionCreated(sessionId, responseNonce)) =>
          nonce match {
            case Some(expected) if expected != responseNonce =>
              ZIO.logWarning("Nonce mismatch, ignoring SessionCreated").as(this)
            case _ =>
              for {
                _ <- ZIO.logInfo(s"Session created: $sessionId")
                now <- Clock.instant
              } yield Connected(
                sessionId = sessionId,
                capabilities = capabilities,
                createdAt = now,
                serverRequestTracker = ServerRequestTracker(),
                retryManager = retryManager,
                pendingRequests = Map.empty
              )
          }
        
        case StreamEvent.ServerMsg(SessionContinued(responseNonce)) =>
          nonce match {
            case Some(expected) if expected != responseNonce =>
              ZIO.logWarning("Nonce mismatch, ignoring SessionContinued").as(this)
            case _ =>
              ZIO.logInfo("Session continued").as(this)
          }
        
        case StreamEvent.ServerMsg(SessionRejected(reason, responseNonce, leaderId)) =>
          nonce match {
            case Some(expected) if expected != responseNonce =>
              ZIO.logWarning("Nonce mismatch, ignoring SessionRejected").as(this)
            case _ =>
              reason match {
                case NotLeader =>
                  nextAddress match {
                    case Some((address, newState)) =>
                      for {
                        _ <- ZIO.logInfo(s"Not leader, trying next: $address")
                        nonce <- Nonce.generate()
                        _ <- transport.sendMessage(CreateSession(capabilities, nonce)).orDie
                      } yield newState.copy(nonce = Some(nonce))
                    case None =>
                      for {
                        _ <- ZIO.logInfo("No more addresses, retrying from start")
                        nonce <- Nonce.generate()
                        _ <- transport.sendMessage(CreateSession(capabilities, nonce)).orDie
                      } yield copy(currentAddressIndex = 0, nonce = Some(nonce))
                  }
                
                case SessionNotFound =>
                  ZIO.dieMessage("Session not found - cannot continue")
                
                case _ =>
                  ZIO.logWarning(s"Session rejected: $reason").as(Disconnected)
              }
          }
        
        case StreamEvent.ServerMsg(other) =>
          ZIO.logWarning(s"Unexpected message in Connecting: ${other.getClass.getSimpleName}").as(this)
        
        case StreamEvent.Action(ClientAction.SubmitCommand(_, promise)) =>
          promise.fail(new IllegalStateException("Not connected")).ignore.as(this)
        
        case StreamEvent.Action(ClientAction.Disconnect) =>
          ZIO.succeed(Disconnected)
        
        case _ =>
          ZIO.succeed(this)
      }
    }
    
    def nextAddress: Option[(String, Connecting)] = {
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
    retryManager: RetryManager,
    pendingRequests: Map[RequestId, Promise[Throwable, ByteVector]]
  ) extends ClientState {
    override def stateName: String = s"Connected($sessionId)"
    
    override def handle(
      event: StreamEvent,
      transport: ZmqClientTransport,
      config: ClientConfig,
      client: RaftClient
    ): UIO[ClientState] = {
      event match {
        case StreamEvent.Action(ClientAction.Disconnect) =>
          for {
            _ <- transport.sendMessage(CloseSession(ClientShutdown)).orDie
            _ <- ZIO.logInfo("Disconnected")
          } yield Disconnected
        
        case StreamEvent.Action(ClientAction.SubmitCommand(payload, promise)) =>
          for {
            requestId <- Random.nextLong.map(RequestId.fromLong)
            now <- Clock.instant
            request = ClientRequest(requestId, payload, now)
            _ <- transport.sendMessage(request).orDie
            newState = copy(pendingRequests = pendingRequests.updated(requestId, promise))
          } yield newState
        
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
            _ <- transport.sendMessage(ServerRequestAck(serverRequest.requestId)).orDie
            _ <- ZIO.logDebug(s"Acknowledged server request: ${serverRequest.requestId}")
          } yield this
        
        case StreamEvent.ServerMsg(RequestError(reason, leaderId)) =>
          reason match {
            case NotLeaderRequest =>
              for {
                _ <- ZIO.logInfo(s"Not leader, reconnecting")
                nonce <- Nonce.generate()
                _ <- transport.sendMessage(ContinueSession(sessionId, nonce)).orDie
              } yield Connecting(
                capabilities = capabilities,
                nonce = Some(nonce),
                addresses = config.clusterAddresses,
                currentAddressIndex = 0,
                retryManager = retryManager
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
