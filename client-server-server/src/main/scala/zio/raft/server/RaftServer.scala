package zio.raft.server

import zio._
import zio.stream._
import zio.raft.protocol._

/**
 * Main RaftServer implementation for client-server communication.
 * 
 * Coordinates all server components:
 * - SessionManager for session lifecycle
 * - ActionStream for unified event processing  
 * - ClientHandler for protocol message handling
 * - ZeroMQ transport for network communication
 * - Integration with Raft state machine
 */
trait RaftServer {
  
  /**
   * Start the server and begin processing client connections.
   */
  def start(): Task[Unit]
  
  /**
   * Stop the server gracefully.
   */
  def stop(): UIO[Unit]
  
  /**
   * Check if server is currently running.
   */
  def isRunning: UIO[Boolean]
  
  /**
   * Get current server statistics.
   */
  def getStats(): UIO[ServerStats]
  
  /**
   * Handle leadership change notification from Raft.
   * 
   * @param isLeader True if this server is now the leader
   * @param existingSessions Session metadata from Raft state (if becoming leader)
   */
  def handleLeadershipChange(
    isLeader: Boolean,
    existingSessions: Map[SessionId, SessionMetadata] = Map.empty
  ): UIO[Unit]
  
  /**
   * Get the action stream for Raft integration.
   * Returns a stream of actions that should be forwarded to the Raft state machine.
   */
  def getActionStream(): ZStream[Any, Throwable, ServerAction]
  
  /**
   * Handle client response from Raft state machine.
   * 
   * @param sessionId Target session ID
   * @param response Response to deliver to client
   */
  def handleClientResponse(
    sessionId: SessionId,
    response: ClientResponse
  ): UIO[Unit]
  
  /**
   * Dispatch server-initiated request to client.
   * 
   * @param sessionId Target session ID  
   * @param request Request to deliver
   * @return Success if request was delivered
   */
  def dispatchServerRequest(
    sessionId: SessionId,
    request: ServerRequest
  ): UIO[Boolean]
}

object RaftServer {
  
  /**
   * Create a RaftServer with the given configuration.
   */
  def make(config: ServerConfig): ZIO[Scope, Throwable, RaftServer] = 
    for {
      // Validate configuration
      validatedConfig <- ServerConfig.validated(config).mapError(new IllegalArgumentException(_))
      
      // Create core components
      sessionManager <- SessionManager.make(validatedConfig)
      
      // Create ZeroMQ transport (placeholder - needs actual ZMQ implementation)
      zmqTransport <- createZmqTransport(validatedConfig)
      
      // Create ZeroMQ message stream (placeholder)
      zmqMessageStream = createZmqMessageStream(zmqTransport)
      
      // Create action stream
      actionStream <- ActionStream.make(sessionManager, zmqMessageStream)
      
      // Create client handler
      clientHandler <- ClientHandler.make(sessionManager, zmqTransport, validatedConfig)
      
      // Create server instance
      server <- ZIO.succeed(new RaftServerImpl(
        sessionManager,
        actionStream,
        clientHandler,
        zmqTransport,
        validatedConfig
      ))
      
    } yield server
  
  private def createZmqTransport(config: ServerConfig): UIO[ZmqTransport] = {
    // Placeholder - actual ZMQ implementation would go here
    ZIO.succeed(new ZmqTransportStub())
  }
  
  private def createZmqMessageStream(transport: ZmqTransport): ZStream[Any, Throwable, ZmqMessage] = {
    // Placeholder - actual ZMQ message stream would go here
    ZStream.empty
  }
}

/**
 * Internal implementation of RaftServer.
 */
private class RaftServerImpl(
  sessionManager: SessionManager,
  actionStream: ActionStream,
  clientHandler: ClientHandler,
  zmqTransport: ZmqTransport,
  config: ServerConfig
) extends RaftServer {
  
  // Using Unsafe instance for class-level initialization
  import zio.Unsafe
  private implicit val unsafe: Unsafe = Unsafe.unsafe(identity)
  
  private val isRunningRef = Ref.unsafe.make(false)
  private val serverFiber = Ref.unsafe.make[Option[Fiber[Throwable, Unit]]](None)
  
  override def start(): Task[Unit] = 
    for {
      running <- isRunningRef.get
      _ <- if (running) {
        ZIO.fail(new IllegalStateException("Server is already running"))
      } else {
        doStart()
      }
    } yield ()
  
  private def doStart(): Task[Unit] = 
    for {
      _ <- ZIO.logInfo(s"Starting RaftServer on ${config.fullBindAddress}")
      
      // Start the main server fiber
      fiber <- startServerFiber().fork
      
      // Update state
      _ <- isRunningRef.set(true)
      _ <- serverFiber.set(Some(fiber))
      
      _ <- ZIO.logInfo("RaftServer started successfully")
    } yield ()
  
  private def startServerFiber(): Task[Unit] = {
    val messageProcessing = processIncomingMessages()
    val actionProcessing = processActionStream()
    val cleanupProcessing = runCleanupTask()
    
    // Run all processing concurrently
    messageProcessing.race(actionProcessing).race(cleanupProcessing).unit
  }
  
  private def processIncomingMessages(): Task[Unit] = {
    // Placeholder - actual ZMQ message processing would go here
    ZStream.fromIterable(List.empty[ZmqMessage])
      .foreach { msg =>
        for {
          responseOpt <- clientHandler.processClientMessage(msg.routingId, msg.content)
          _ <- responseOpt match {
            case Some(response) =>
              zmqTransport.sendMessage(msg.routingId, response)
            case None =>
              ZIO.unit
          }
        } yield ()
      }
  }
  
  private def processActionStream(): Task[Unit] = {
    actionStream.resultStream
      .foreach { serverAction =>
        for {
          _ <- ZIO.logDebug(s"Processing server action: $serverAction")
          // Actions would be forwarded to Raft state machine here
          _ <- forwardToRaftStateMachine(serverAction)
        } yield ()
      }
  }
  
  private def runCleanupTask(): Task[Unit] = {
    ZStream.tick(config.cleanupInterval)
      .foreach { _ =>
        for {
          expiredSessions <- sessionManager.removeExpiredSessions()
          _ <- if (expiredSessions.nonEmpty) {
            ZIO.logInfo(s"Cleaned up ${expiredSessions.size} expired sessions")
          } else {
            ZIO.unit
          }
        } yield ()
      }
  }
  
  override def stop(): UIO[Unit] = 
    for {
      running <- isRunningRef.get
      _ <- if (running) {
        doStop()
      } else {
        ZIO.logInfo("Server is not running")
      }
    } yield ()
  
  private def doStop(): UIO[Unit] = 
    for {
      _ <- ZIO.logInfo("Stopping RaftServer...")
      
      // Stop the server fiber
      fiberOpt <- serverFiber.get
      _ <- fiberOpt match {
        case Some(fiber) =>
          fiber.interrupt
        case None =>
          ZIO.unit
      }
      
      // Update state
      _ <- isRunningRef.set(false)
      _ <- serverFiber.set(None)
      
      _ <- ZIO.logInfo("RaftServer stopped")
    } yield ()
  
  override def isRunning: UIO[Boolean] = 
    isRunningRef.get
  
  override def getStats(): UIO[ServerStats] = 
    for {
      running <- isRunning
      sessionStats <- sessionManager.getSessionStats()
    } yield ServerStats(
      isRunning = running,
      isLeader = false, // Would be updated from Raft integration
      totalSessions = sessionStats.totalSessions,
      connectedSessions = sessionStats.connectedSessions,
      disconnectedSessions = sessionStats.disconnectedSessions,
      uptimeSeconds = 0 // Would track actual uptime
    )
  
  override def handleLeadershipChange(
    isLeader: Boolean,
    existingSessions: Map[SessionId, SessionMetadata]
  ): UIO[Unit] = 
    for {
      _ <- ZIO.logInfo(s"Leadership change: isLeader=$isLeader, existingSessions=${existingSessions.size}")
      _ <- if (isLeader) {
        sessionManager.initializeFromRaftState(existingSessions)
      } else {
        ZIO.unit // Non-leader doesn't manage sessions
      }
    } yield ()
  
  override def getActionStream(): ZStream[Any, Throwable, ServerAction] = 
    actionStream.resultStream
  
  override def handleClientResponse(
    sessionId: SessionId,
    response: ClientResponse
  ): UIO[Unit] = 
    for {
      delivered <- clientHandler.deliverClientResponse(sessionId, response)
      _ <- if (!delivered) {
        ZIO.logWarning(s"Failed to deliver response to session $sessionId")
      } else {
        ZIO.unit
      }
    } yield ()
  
  override def dispatchServerRequest(
    sessionId: SessionId,
    request: ServerRequest
  ): UIO[Boolean] = 
    clientHandler.deliverServerRequest(sessionId, request)
  
  private def forwardToRaftStateMachine(action: ServerAction): UIO[Unit] = {
    // Placeholder - actual Raft integration would go here
    ZIO.logDebug(s"Forwarding to Raft: $action")
  }
}

/**
 * Server statistics for monitoring.
 */
case class ServerStats(
  isRunning: Boolean,
  isLeader: Boolean,
  totalSessions: Int,
  connectedSessions: Int,
  disconnectedSessions: Int,
  uptimeSeconds: Long
)
/**
 * Stub implementation of ZmqTransport for compilation.
 */
private class ZmqTransportStub extends ZmqTransport {
  override def sendMessage(routingId: zio.zmq.RoutingId, message: ServerMessage): Task[Unit] = 
    ZIO.logDebug(s"Stub: sending message to $routingId: $message")
}

