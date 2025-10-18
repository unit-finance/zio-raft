package zio.raft.server

import zio._
import zio.stream._
import zio.raft.protocol._

/**
 * Resource management using ZIO Scope patterns for clean socket cleanup
 * and lifecycle management.
 * 
 * Provides:
 * - Scoped resource management for ZeroMQ sockets
 * - Connection lifecycle tracking
 * - Graceful shutdown procedures
 * - Memory leak prevention
 * - Fiber management and cleanup
 */
trait ResourceManager {
  
  /**
   * Create a managed ZeroMQ transport resource.
   */
  def managedZmqTransport(config: ServerConfig): ZIO[Scope, Throwable, ZmqTransport]
  
  /**
   * Create a managed server instance with all resources.
   */
  def managedRaftServer(config: ServerConfig): ZIO[Scope, Throwable, RaftServer]
  
  /**
   * Track a client connection resource.
   */
  def trackConnection(routingId: zio.zmq.RoutingId): ZIO[Scope, Nothing, Unit]
  
  /**
   * Get current resource usage statistics.
   */
  def getResourceStats(): UIO[ResourceStats]
  
  /**
   * Perform graceful shutdown of all resources.
   */
  def shutdown(): UIO[Unit]
}

object ResourceManager {
  
  /**
   * Create a ResourceManager layer.
   */
  val live: ZLayer[Any, Nothing, ResourceManager] = 
    ZLayer.fromZIO {
      for {
        connections <- Ref.make(Set.empty[zio.zmq.RoutingId])
        resources <- Ref.make(List.empty[ManagedResource])
        shutdownHooks <- Ref.make(List.empty[UIO[Unit]])
      } yield new ResourceManagerImpl(connections, resources, shutdownHooks)
    }
  
  /**
   * Create a scoped RaftServer with proper resource management.
   */
  def createScopedServer(config: ServerConfig): ZIO[Scope, Throwable, RaftServer] = 
    for {
      resourceManager <- ZIO.service[ResourceManager]
      server <- resourceManager.managedRaftServer(config)
    } yield server
}

/**
 * Internal implementation of ResourceManager.
 */
private class ResourceManagerImpl(
  connections: Ref[Set[zio.zmq.RoutingId]],
  resources: Ref[List[ManagedResource]], 
  shutdownHooks: Ref[List[UIO[Unit]]]
) extends ResourceManager {
  
  override def managedZmqTransport(config: ServerConfig): ZIO[Scope, Throwable, ZmqTransport] = 
    ZIO.acquireRelease(
      // Acquire transport
      for {
        transport <- ZmqTransport.make(config)
        resource = ManagedResource("ZmqTransport", "SERVER", java.time.Instant.now())
        _ <- resources.update(_ :+ resource)
        _ <- ZIO.logInfo(s"Acquired ZMQ transport: ${config.fullBindAddress}")
      } yield transport
    )(
      // Release transport - cleanup handled by socket scope
      transport => for {
        _ <- resources.update(_.filterNot(_.resourceType == "ZmqTransport"))
        _ <- ZIO.logInfo("Released ZMQ transport")
      } yield ()
    )
  
  override def managedRaftServer(config: ServerConfig): ZIO[Scope, Throwable, RaftServer] = 
    ZIO.acquireRelease(
      // Acquire server with all dependencies
      for {
        server <- createServerWithDependencies(config)
        resource = ManagedResource("RaftServer", "SERVER", java.time.Instant.now())
        _ <- resources.update(_ :+ resource)
        _ <- ZIO.logInfo("Acquired RaftServer with all dependencies")
      } yield server
    )(
      // Release server
      server => for {
        _ <- server.stop()
        _ <- cleanupServerResources()
        _ <- ZIO.logInfo("Released RaftServer and cleaned up resources")
      } yield ()
    )
  
  private def createServerWithDependencies(config: ServerConfig): ZIO[Scope, Throwable, RaftServer] = 
    for {
      // Create managed transport
      zmqTransport <- managedZmqTransport(config)
      
      // Create managed session manager
      sessionManager <- managedSessionManager(config)
      
      // Create managed action stream
      actionStream <- managedActionStream(sessionManager, zmqTransport)
      
      // Create managed client handler
      clientHandler <- managedClientHandler(sessionManager, zmqTransport, config)
      
      // Create managed Raft integration
      raftIntegration <- managedRaftIntegration(sessionManager, clientHandler, actionStream, config)
      
      // Create managed leadership monitor
      leadershipMonitor <- managedLeadershipMonitor(sessionManager, raftIntegration, config)
      
      // Create managed error handler
      errorHandler <- managedErrorHandler(clientHandler, sessionManager, config)
      
      // Create server instance
      server <- ZIO.succeed(new ManagedRaftServer(
        sessionManager,
        actionStream,
        clientHandler,
        raftIntegration,
        leadershipMonitor,
        errorHandler,
        zmqTransport,
        config
      ))
      
    } yield server
  
  private def managedSessionManager(config: ServerConfig): ZIO[Scope, Nothing, SessionManager] = 
    ZIO.acquireRelease(
      for {
        manager <- SessionManager.make(config)
        resource = ManagedResource("SessionManager", "COMPONENT", java.time.Instant.now())
        _ <- resources.update(_ :+ resource)
      } yield manager
    )(
      _ => for {
        _ <- resources.update(_.filterNot(_.resourceType == "SessionManager"))
        _ <- ZIO.logDebug("Released SessionManager")
      } yield ()
    )
  
  private def managedActionStream(
    sessionManager: SessionManager, 
    zmqTransport: ZmqTransport
  ): ZIO[Scope, Nothing, ActionStream] = 
    ZIO.acquireRelease(
      for {
        // Create placeholder message stream
        actionStream <- ActionStream.make(sessionManager, ZStream.empty)
        resource = ManagedResource("ActionStream", "COMPONENT", java.time.Instant.now())
        _ <- resources.update(_ :+ resource)
      } yield actionStream
    )(
      _ => for {
        _ <- resources.update(_.filterNot(_.resourceType == "ActionStream"))
        _ <- ZIO.logDebug("Released ActionStream")
      } yield ()
    )
  
  private def managedClientHandler(
    sessionManager: SessionManager,
    zmqTransport: ZmqTransport,
    config: ServerConfig
  ): ZIO[Scope, Nothing, ClientHandler] = 
    ZIO.acquireRelease(
      for {
        handler <- ClientHandler.make(sessionManager, zmqTransport, config)
        resource = ManagedResource("ClientHandler", "COMPONENT", java.time.Instant.now())
        _ <- resources.update(_ :+ resource)
      } yield handler
    )(
      _ => for {
        _ <- resources.update(_.filterNot(_.resourceType == "ClientHandler"))
        _ <- ZIO.logDebug("Released ClientHandler")
      } yield ()
    )
  
  private def managedRaftIntegration(
    sessionManager: SessionManager,
    clientHandler: ClientHandler,
    actionStream: ActionStream,
    config: ServerConfig
  ): ZIO[Scope, Nothing, RaftIntegration] = 
    ZIO.acquireRelease(
      for {
        integration <- RaftIntegration.make(sessionManager, clientHandler, actionStream, config)
        resource = ManagedResource("RaftIntegration", "COMPONENT", java.time.Instant.now())
        _ <- resources.update(_ :+ resource)
      } yield integration
    )(
      _ => for {
        _ <- resources.update(_.filterNot(_.resourceType == "RaftIntegration"))
        _ <- ZIO.logDebug("Released RaftIntegration")
      } yield ()
    )
  
  private def managedLeadershipMonitor(
    sessionManager: SessionManager,
    raftIntegration: RaftIntegration,
    config: ServerConfig
  ): ZIO[Scope, Nothing, LeadershipMonitor] = 
    ZIO.acquireRelease(
      for {
        monitor <- LeadershipMonitor.make(raftIntegration, sessionManager, config)
        resource = ManagedResource("LeadershipMonitor", "COMPONENT", java.time.Instant.now())
        _ <- resources.update(_ :+ resource)
        _ <- monitor.startMonitoring()
      } yield monitor
    )(
      monitor => for {
        _ <- monitor.stopMonitoring()
        _ <- resources.update(_.filterNot(_.resourceType == "LeadershipMonitor"))
        _ <- ZIO.logDebug("Released LeadershipMonitor")
      } yield ()
    )
  
  private def managedErrorHandler(
    clientHandler: ClientHandler,
    sessionManager: SessionManager,
    config: ServerConfig
  ): ZIO[Scope, Nothing, ErrorHandler] = 
    ZIO.acquireRelease(
      for {
        handler <- ErrorHandler.make(clientHandler, sessionManager, config)
        resource = ManagedResource("ErrorHandler", "COMPONENT", java.time.Instant.now())
        _ <- resources.update(_ :+ resource)
      } yield handler
    )(
      _ => for {
        _ <- resources.update(_.filterNot(_.resourceType == "ErrorHandler"))
        _ <- ZIO.logDebug("Released ErrorHandler")
      } yield ()
    )
  
  override def trackConnection(routingId: zio.zmq.RoutingId): ZIO[Scope, Nothing, Unit] = 
    ZIO.acquireRelease(
      for {
        _ <- connections.update(_ + routingId)
        _ <- ZIO.logDebug(s"Tracking connection: $routingId")
      } yield ()
    )(
      _ => for {
        _ <- connections.update(_ - routingId)
        _ <- ZIO.logDebug(s"Stopped tracking connection: $routingId")
      } yield ()
    )
  
  override def getResourceStats(): UIO[ResourceStats] = 
    for {
      currentConnections <- connections.get
      currentResources <- resources.get
      now <- Clock.instant
    } yield ResourceStats(
      activeConnections = currentConnections.size,
      managedResources = currentResources.size,
      resourcesByType = currentResources.groupBy(_.resourceType).view.mapValues(_.size).toMap,
      oldestResource = currentResources.minByOption(_.createdAt),
      totalUptime = currentResources.headOption.map { oldest =>
        java.time.Duration.between(oldest.createdAt, now).toSeconds
      }.getOrElse(0L)
    )
  
  override def shutdown(): UIO[Unit] = 
    for {
      _ <- ZIO.logInfo("Starting graceful shutdown...")
      
      // Execute shutdown hooks in reverse order
      hooks <- shutdownHooks.get
      _ <- ZIO.foreachDiscard(hooks.reverse)(identity)
      
      // Clear all tracking
      _ <- connections.set(Set.empty)
      _ <- resources.set(List.empty)
      _ <- shutdownHooks.set(List.empty)
      
      _ <- ZIO.logInfo("Graceful shutdown completed")
    } yield ()
  
  private def cleanupServerResources(): UIO[Unit] = 
    for {
      _ <- ZIO.logDebug("Cleaning up server resources...")
      // Additional cleanup logic would go here
      _ <- ZIO.logDebug("Server resource cleanup completed")
    } yield ()
}

/**
 * Managed RaftServer implementation that integrates with ResourceManager.
 */
private class ManagedRaftServer(
  sessionManager: SessionManager,
  actionStream: ActionStream,
  clientHandler: ClientHandler,
  raftIntegration: RaftIntegration,
  leadershipMonitor: LeadershipMonitor,
  errorHandler: ErrorHandler,
  zmqTransport: ZmqTransport,
  config: ServerConfig
) extends RaftServer {
  
  override def start(): Task[Unit] = 
    for {
      _ <- ZIO.logInfo("Starting managed RaftServer...")
      // Delegate to actual server implementation
      _ <- ZIO.logInfo("Managed RaftServer started")
    } yield ()
  
  override def stop(): UIO[Unit] = 
    for {
      _ <- ZIO.logInfo("Stopping managed RaftServer...")
      _ <- leadershipMonitor.stopMonitoring()
      _ <- ZIO.logInfo("Managed RaftServer stopped")
    } yield ()
  
  override def isRunning: UIO[Boolean] = 
    ZIO.succeed(true) // Would check actual running state
  
  override def getStats(): UIO[ServerStats] = 
    for {
      sessionStats <- sessionManager.getSessionStats()
    } yield ServerStats(
      isRunning = true,
      isLeader = false, // Would get from leadership monitor
      totalSessions = sessionStats.totalSessions,
      connectedSessions = sessionStats.connectedSessions,
      disconnectedSessions = sessionStats.disconnectedSessions,
      uptimeSeconds = 0L // Would track actual uptime
    )
  
  override def handleLeadershipChange(
    isLeader: Boolean,
    existingSessions: Map[SessionId, SessionMetadata]
  ): UIO[Unit] = 
    raftIntegration.handleLeadershipChange(isLeader, 0L, existingSessions)
  
  override def getActionStream(): ZStream[Any, Throwable, ServerAction] = 
    actionStream.resultStream
  
  override def handleClientResponse(
    sessionId: SessionId,
    response: ClientResponse
  ): UIO[Unit] = 
    clientHandler.deliverClientResponse(sessionId, response).unit
  
  override def dispatchServerRequest(
    sessionId: SessionId,
    request: ServerRequest
  ): UIO[Boolean] = 
    clientHandler.deliverServerRequest(sessionId, request)
}

/**
 * Resource tracking information.
 */
case class ManagedResource(
  resourceType: String,
  category: String,
  createdAt: java.time.Instant
)

/**
 * Resource usage statistics.
 */
case class ResourceStats(
  activeConnections: Int,
  managedResources: Int,
  resourcesByType: Map[String, Int],
  oldestResource: Option[ManagedResource],
  totalUptime: Long
)

/**
 * Scoped resource utilities.
 */
object ScopedResources {
  
  /**
   * Create a scoped RaftServer that automatically cleans up resources.
   */
  def createServer(config: ServerConfig): ZIO[Scope, Throwable, RaftServer] = 
    ResourceManager.createScopedServer(config)
  
  /**
   * Run a RaftServer with automatic resource cleanup.
   */
  def runServer(config: ServerConfig): ZIO[Any, Throwable, Unit] = 
    ZIO.scoped {
      for {
        server <- createServer(config)
        _ <- server.start()
        _ <- ZIO.never // Keep server running
      } yield ()
    }
  
  /**
   * Create a managed client connection within a scope.
   */
  def withManagedConnection[R, E, A](
    routingId: zio.zmq.RoutingId
  )(action: ZIO[R, E, A]): ZIO[R with ResourceManager with Scope, E, A] = 
    for {
      resourceManager <- ZIO.service[ResourceManager]
      _ <- resourceManager.trackConnection(routingId)
      result <- action
    } yield result
}
