package zio.raft.client

import zio._
import zio.stream._
import zio.raft.protocol._
import scodec.bits.ByteVector

/**
 * Main RaftClient implementation for client-server communication.
 * 
 * Provides high-level API for:
 * - Session management (create, continue, close)
 * - Command submission with leader redirection
 * - Server-initiated request handling
 * - Connection state management
 * - Automatic reconnection and retry logic
 */
trait RaftClient {
  
  /**
   * Create a new session with the cluster.
   * 
   * @return Session ID for the created session
   */
  def createSession(): Task[SessionId]
  
  /**
   * Continue an existing session after reconnection.
   * 
   * @param sessionId Session ID to continue
   */
  def continueSession(sessionId: SessionId): Task[Unit]
  
  /**
   * Check if a session is currently active.
   */
  def isSessionActive(sessionId: SessionId): UIO[Boolean]
  
  /**
   * Close a session gracefully.
   */
  def closeSession(sessionId: SessionId): Task[Boolean]
  
  /**
   * Submit a command to the Raft cluster.
   * 
   * @param sessionId Session ID for the command
   * @param payload Command payload
   * @param timeout Optional timeout override
   * @return Command response payload
   */
  def submitCommand(
    sessionId: SessionId,
    payload: ByteVector,
    timeout: Duration = Duration.Infinity
  ): Task[ByteVector]
  
  /**
   * Submit a read query to the Raft cluster.
   * 
   * @param sessionId Session ID for the query
   * @param payload Query payload
   * @param timeout Optional timeout override
   * @return Query response payload
   */
  def submitQuery(
    sessionId: SessionId,
    payload: ByteVector,
    timeout: Duration = Duration.Infinity
  ): Task[ByteVector]
  
  /**
   * Submit a command asynchronously.
   * 
   * @param payload Command payload
   * @return Promise that will be completed with the response
   */
  def submitCommandAsync(payload: ByteVector): UIO[Task[ByteVector]]
  
  /**
   * Get the next server-initiated request.
   * This method blocks until a request is available.
   */
  def getNextServerRequest(): Task[Option[ServerRequest]]
  
  /**
   * Acknowledge a server-initiated request.
   * 
   * @param requestId Request ID to acknowledge
   */
  def acknowledgeServerRequest(requestId: RequestId): Task[Boolean]
  
  /**
   * Get current connection state.
   */
  def getConnectionState(): UIO[ClientConnectionState]
  
  /**
   * Get current session capabilities.
   */
  def getSessionCapabilities(sessionId: SessionId): UIO[Map[String, String]]
  
  /**
   * Get current leader information.
   */
  def getCurrentLeaderInfo(): UIO[Option[MemberId]]
  
  /**
   * Connect to the cluster (async).
   */
  def connect(): Task[Unit]
  
  /**
   * Disconnect from the cluster.
   */
  def disconnect(): UIO[Unit]
  
  /**
   * Reconnect to the cluster.
   */
  def reconnect(): Task[Unit]
  
  /**
   * Force disconnect for testing.
   */
  def forceDisconnect(): UIO[Unit]
  
  /**
   * Check if client is currently connected.
   */
  def isConnected(): UIO[Boolean]
}

object RaftClient {
  
  /**
   * Create and connect a RaftClient with the given configuration.
   */
  def connect(config: ClientConfig): ZIO[Scope, Throwable, RaftClient] = 
    for {
      // Validate configuration
      validatedConfig <- ClientConfig.validated(config).mapError(new IllegalArgumentException(_))
      
      // Create core components
      connectionManager <- ConnectionManager.make(validatedConfig)
      sessionState <- SessionState.make(connectionManager, validatedConfig)
      retryManager <- RetryManager.make(validatedConfig)
      
      // Create ZeroMQ transport (placeholder)
      zmqTransport <- createZmqTransport(validatedConfig)
      
      // Create ZeroMQ message stream (placeholder)
      zmqMessageStream = createZmqMessageStream(zmqTransport)
      
      // Create action stream
      actionStream <- ActionStream.make(connectionManager, zmqMessageStream, validatedConfig)
      
      // Create client instance
      client <- ZIO.succeed(new RaftClientImpl(
        connectionManager,
        sessionState,
        retryManager,
        actionStream,
        zmqTransport,
        validatedConfig
      ))
      
      // Start the client
      _ <- client.connect()
      
    } yield client
  
  private def createZmqTransport(config: ClientConfig): UIO[ZmqClientTransport] = {
    // Placeholder - actual ZMQ implementation would go here
    ZIO.succeed(new ZmqClientTransportStub())
  }
  
  private def createZmqMessageStream(transport: ZmqClientTransport): ZStream[Any, Throwable, ServerMessage] = {
    // Placeholder - actual ZMQ message stream would go here
    ZStream.empty
  }
}

/**
 * Internal implementation of RaftClient.
 */
private class RaftClientImpl(
  connectionManager: ConnectionManager,
  sessionState: SessionState,
  retryManager: RetryManager,
  actionStream: ActionStream,
  zmqTransport: ZmqClientTransport,
  config: ClientConfig
) extends RaftClient {
  
  // Using Unsafe instance for class-level initialization
  import zio.Unsafe
  private implicit val unsafe: Unsafe = Unsafe.unsafe(identity)
  
  private val isConnectedRef = Ref.unsafe.make(false)
  private val currentLeader = Ref.unsafe.make[Option[MemberId]](None)
  private val clientFiber = Ref.unsafe.make[Option[Fiber[Throwable, Unit]]](None)
  
  override def createSession(): Task[SessionId] = 
    for {
      connected <- isConnectedRef.get
      _ <- if (!connected) {
        ZIO.fail(new IllegalStateException("Client not connected"))
      } else {
        ZIO.unit
      }
      sessionId <- sessionState.createSession(config.capabilities)
    } yield sessionId
  
  override def continueSession(sessionId: SessionId): Task[Unit] = 
    for {
      connected <- isConnectedRef.get
      _ <- if (!connected) {
        ZIO.fail(new IllegalStateException("Client not connected"))
      } else {
        ZIO.unit
      }
      _ <- sessionState.continueSession(sessionId)
    } yield ()
  
  override def isSessionActive(sessionId: SessionId): UIO[Boolean] = 
    sessionState.isSessionActive(sessionId)
  
  override def closeSession(sessionId: SessionId): Task[Boolean] = 
    sessionState.closeSession().as(true)
  
  override def submitCommand(
    sessionId: SessionId,
    payload: ByteVector,
    timeout: Duration
  ): Task[ByteVector] = 
    for {
      // Check session is active
      active <- isSessionActive(sessionId)
      _ <- if (!active) {
        ZIO.fail(new IllegalStateException(s"Session $sessionId is not active"))
      } else {
        ZIO.unit
      }
      
      // Generate request ID
      requestId <- generateRequestId()
      now <- Clock.instant
      
      // Create request
      request = ClientRequest(requestId, payload, now)
      
      // Create promise for response
      promise <- Promise.make[RequestErrorReason, ByteVector]
      
      // Submit through connection manager
      action <- connectionManager.submitRequest(request, promise)
      
      // Handle based on action
      _ <- action match {
        case QueueAndSendRequest =>
          // Send immediately
          zmqTransport.sendMessage(request)
        case QueueRequest =>
          // Just queued, will be sent when connected
          ZIO.unit
        case RejectRequest =>
          ZIO.fail(new IllegalStateException("Request rejected"))
      }
      
      // Wait for response with timeout
      effectiveTimeout = if (timeout == Duration.Infinity) config.requestTimeout else timeout
      response <- promise.await.mapError {
        case reason: RequestErrorReason => new RuntimeException(s"Request failed: $reason")
      }.timeoutFail(new TimeoutException("Request timed out"))(effectiveTimeout)
    } yield response
  
  override def submitQuery(
    sessionId: SessionId,
    payload: ByteVector,
    timeout: Duration
  ): Task[ByteVector] = 
    // Queries use the same mechanism as commands in this implementation
    submitCommand(sessionId, payload, timeout)
  
  override def submitCommandAsync(payload: ByteVector): UIO[Task[ByteVector]] = 
    for {
      sessionIdOpt <- sessionState.getCurrentSessionId()
      result <- sessionIdOpt match {
        case Some(sessionId) =>
          ZIO.succeed(submitCommand(sessionId, payload))
        case None =>
          ZIO.succeed(ZIO.fail(new IllegalStateException("No active session")))
      }
    } yield result
  
  override def getNextServerRequest(): Task[Option[ServerRequest]] = 
    for {
      requestOpt <- actionStream.serverInitiatedRequestStream.take(1).runCollect.map(_.headOption)
    } yield requestOpt
  
  override def acknowledgeServerRequest(requestId: RequestId): Task[Boolean] = 
    for {
      _ <- sessionState.acknowledgeServerRequest(requestId)
      ackMessage = ServerRequestAck(requestId)
      _ <- zmqTransport.sendMessage(ackMessage)
    } yield true
  
  override def getConnectionState(): UIO[ClientConnectionState] = 
    connectionManager.currentState
  
  override def getSessionCapabilities(sessionId: SessionId): UIO[Map[String, String]] = 
    sessionState.getSessionCapabilities()
  
  override def getCurrentLeaderInfo(): UIO[Option[MemberId]] = 
    currentLeader.get
  
  override def connect(): Task[Unit] = 
    for {
      _ <- ZIO.logInfo("Connecting RaftClient...")
      
      // Start connection process
      _ <- connectionManager.startConnection()
      
      // Start the main client fiber
      fiber <- startClientFiber().fork
      
      // Update state
      _ <- isConnectedRef.set(true)
      _ <- clientFiber.set(Some(fiber))
      
      _ <- ZIO.logInfo("RaftClient connected successfully")
    } yield ()
  
  private def startClientFiber(): Task[Unit] = {
    val actionProcessing = processActionStream()
    val keepAliveProcessing = runKeepAliveTask()
    val retryProcessing = runRetryTask()
    
    // Run all processing concurrently
    actionProcessing.race(keepAliveProcessing).race(retryProcessing).unit
  }
  
  private def processActionStream(): Task[Unit] = {
    actionStream.unifiedStream
      .foreach { action =>
        for {
          result <- actionStream.processAction(action)
          _ <- result match {
            case ActionResult.SendRequired(message) =>
              zmqTransport.sendMessage(message)
            case _ =>
              ZIO.unit
          }
        } yield ()
      }
  }
  
  private def runKeepAliveTask(): Task[Unit] = {
    ZStream.tick(config.keepAliveInterval)
      .foreach { _ =>
        for {
          state <- connectionManager.currentState
          _ <- if (state == Connected) {
            for {
              now <- Clock.instant
              keepAlive = KeepAlive(now)
              _ <- zmqTransport.sendMessage(keepAlive)
            } yield ()
          } else {
            ZIO.unit
          }
        } yield ()
      }
  }
  
  private def runRetryTask(): Task[Unit] = {
    ZStream.tick(config.requestTimeout * 0.5) // Check every half timeout period
      .foreach { _ =>
        for {
          now <- Clock.instant
          timedOut <- retryManager.getTimedOutRequests(now, config.requestTimeout)
          _ <- ZIO.foreachDiscard(timedOut) { requestId =>
            for {
              resent <- retryManager.resendRequest(requestId, now)
              _ <- if (resent) {
                ZIO.logDebug(s"Resending timed out request: $requestId")
                // Would resend the actual message here
              } else {
                ZIO.unit
              }
            } yield ()
          }
        } yield ()
      }
  }
  
  override def disconnect(): UIO[Unit] = 
    for {
      _ <- ZIO.logInfo("Disconnecting RaftClient...")
      
      // Stop the client fiber
      fiberOpt <- clientFiber.get
      _ <- fiberOpt match {
        case Some(fiber) =>
          fiber.interrupt
        case None =>
          ZIO.unit
      }
      
      // Disconnect connection manager
      _ <- connectionManager.disconnect()
      
      // Update state
      _ <- isConnectedRef.set(false)
      _ <- clientFiber.set(None)
      
      _ <- ZIO.logInfo("RaftClient disconnected")
    } yield ()
  
  override def reconnect(): Task[Unit] = 
    for {
      _ <- disconnect()
      _ <- connect()
    } yield ()
  
  override def forceDisconnect(): UIO[Unit] = 
    for {
      _ <- connectionManager.handleConnectionFailure(new RuntimeException("Force disconnect"))
    } yield ()
  
  override def isConnected(): UIO[Boolean] = 
    isConnectedRef.get
  
  private def generateRequestId(): UIO[RequestId] = {
    // Generate unique request ID
    Random.nextLong.map(RequestId.fromLong)
  }
}

/**
 * ZeroMQ client transport abstraction.
 */
trait ZmqClientTransport {
  def sendMessage(message: ClientMessage): Task[Unit]
}

/**
 * Stub implementation of ZmqClientTransport for compilation.
 */
private class ZmqClientTransportStub extends ZmqClientTransport {
  override def sendMessage(message: ClientMessage): Task[Unit] = 
    ZIO.logDebug(s"Stub: sending client message: $message")
}

/**
 * Exception thrown when operations timeout.
 */
class TimeoutException(message: String) extends RuntimeException(message)

/**
 * Exception thrown for leader redirection scenarios.
 */
class LeaderRedirectionException(val leaderId: Option[MemberId]) extends RuntimeException(
  s"Not leader, redirect to: ${leaderId.getOrElse("unknown")}"
)

/**
 * Exception thrown when session is not found.
 */
class SessionNotFoundException(sessionId: SessionId) extends RuntimeException(
  s"Session not found: $sessionId"
)

/**
 * Exception thrown when session has expired.
 */
class SessionExpiredException(sessionId: SessionId) extends RuntimeException(
  s"Session expired: $sessionId"
)

/**
 * Exception thrown for cluster quorum issues.
 */
class NoQuorumException extends RuntimeException(
  "Cluster does not have quorum"
)

/**
 * Exception thrown for oversized payloads.
 */
class PayloadTooLargeException(size: Int, maxSize: Int) extends RuntimeException(
  s"Payload too large: $size bytes > $maxSize bytes maximum"
)
