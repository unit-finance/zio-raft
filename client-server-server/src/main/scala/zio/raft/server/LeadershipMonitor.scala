package zio.raft.server

import zio._
import zio.raft.protocol._

/**
 * Monitor Raft leadership state and handle leadership transitions.
 * 
 * Provides:
 * - Continuous monitoring of Raft leadership status
 * - Leadership change notifications
 * - Session cleanup and initialization during transitions
 * - Leader information for client redirections
 */
trait LeadershipMonitor {
  
  /**
   * Check if this server is currently the leader.
   */
  def isLeader(): UIO[Boolean]
  
  /**
   * Get the current leader member ID.
   */
  def getCurrentLeader(): UIO[Option[MemberId]]
  
  /**
   * Start monitoring leadership state.
   * Returns a fiber that continuously monitors and handles leadership changes.
   */
  def startMonitoring(): UIO[Fiber.Runtime[Nothing, Unit]]
  
  /**
   * Stop monitoring leadership state.
   */
  def stopMonitoring(): UIO[Unit]
  
  /**
   * Register a callback for leadership change events.
   * 
   * @param callback Function called when leadership changes (isLeader, leaderId)
   */
  def onLeadershipChange(callback: (Boolean, Option[MemberId]) => UIO[Unit]): UIO[Unit]
}

object LeadershipMonitor {
  
  /**
   * Create a LeadershipMonitor instance.
   * 
   * @param raftIntegration Raft integration for checking leadership status
   * @param sessionManager Session manager for handling session transitions
   * @param config Server configuration
   * @return LeadershipMonitor instance
   */
  def make(
    raftIntegration: RaftIntegration,
    sessionManager: SessionManager,
    config: ServerConfig
  ): UIO[LeadershipMonitor] = 
    for {
      isLeaderRef <- Ref.make(false)
      currentLeaderRef <- Ref.make[Option[MemberId]](None)
      monitoringFiber <- Ref.make[Option[Fiber.Runtime[Nothing, Unit]]](None)
      callbacks <- Ref.make[List[(Boolean, Option[MemberId]) => UIO[Unit]]](List.empty)
      
      monitor = new LeadershipMonitorImpl(
        raftIntegration,
        sessionManager,
        config,
        isLeaderRef,
        currentLeaderRef,
        monitoringFiber,
        callbacks
      )
    } yield monitor
  
  /**
   * Internal implementation of LeadershipMonitor.
   */
  private class LeadershipMonitorImpl(
    raftIntegration: RaftIntegration,
    sessionManager: SessionManager,
    config: ServerConfig,
    isLeaderRef: Ref[Boolean],
    currentLeaderRef: Ref[Option[MemberId]],
    monitoringFiber: Ref[Option[Fiber.Runtime[Nothing, Unit]]],
    callbacks: Ref[List[(Boolean, Option[MemberId]) => UIO[Unit]]]
  ) extends LeadershipMonitor {
    
    override def isLeader(): UIO[Boolean] = 
      isLeaderRef.get
    
    override def getCurrentLeader(): UIO[Option[MemberId]] = 
      currentLeaderRef.get
    
    override def startMonitoring(): UIO[Fiber.Runtime[Nothing, Unit]] = {
      val monitoringLoop = 
        ZIO.logInfo("Starting leadership monitoring") *>
        monitorLeadership()
          .repeat(Schedule.spaced(config.leaderTimeout / 2)) // Check twice per timeout period
          .unit
          .catchAll(err => 
            ZIO.logError(s"Error in leadership monitoring: ${err.getMessage}") *>
            ZIO.sleep(config.leaderTimeout)
          )
          .forever
      
      for {
        fiber <- monitoringLoop.forkDaemon
        _ <- monitoringFiber.set(Some(fiber))
        _ <- ZIO.logInfo("Leadership monitoring started")
      } yield fiber
    }
    
    override def stopMonitoring(): UIO[Unit] = 
      for {
        fiberOpt <- monitoringFiber.get
        _ <- fiberOpt match {
          case Some(fiber) =>
            ZIO.logInfo("Stopping leadership monitoring") *>
            fiber.interrupt.unit
          case None =>
            ZIO.unit
        }
        _ <- monitoringFiber.set(None)
      } yield ()
    
    override def onLeadershipChange(
      callback: (Boolean, Option[MemberId]) => UIO[Unit]
    ): UIO[Unit] = 
      callbacks.update(callback :: _)
    
    /**
     * Monitor leadership state and handle changes.
     */
    private def monitorLeadership(): UIO[Unit] = {
      for {
        // Check current leadership from Raft
        newIsLeader <- raftIntegration.isLeader()
        newLeaderId <- raftIntegration.getCurrentLeader()
        
        // Get previous state
        oldIsLeader <- isLeaderRef.get
        oldLeaderId <- currentLeaderRef.get
        
        // Detect changes
        leadershipChanged = newIsLeader != oldIsLeader
        leaderIdChanged = newLeaderId != oldLeaderId
        
        // Handle leadership changes
        _ <- if (leadershipChanged || leaderIdChanged) {
          for {
            _ <- ZIO.logInfo(s"Leadership change detected: isLeader=$newIsLeader, leaderId=$newLeaderId")
            
            // Update stored state
            _ <- isLeaderRef.set(newIsLeader)
            _ <- currentLeaderRef.set(newLeaderId)
            
            // Handle leadership transition
            _ <- handleLeadershipTransition(oldIsLeader, newIsLeader, newLeaderId)
            
            // Notify callbacks
            callbackList <- callbacks.get
            _ <- ZIO.foreachDiscard(callbackList)(cb => cb(newIsLeader, newLeaderId))
            
          } yield ()
        } else {
          ZIO.unit
        }
        
      } yield ()
    }
    
    /**
     * Handle leadership transition logic.
     */
    private def handleLeadershipTransition(
      wasLeader: Boolean,
      isLeader: Boolean,
      newLeaderId: Option[MemberId]
    ): UIO[Unit] = {
      (wasLeader, isLeader) match {
        case (false, true) =>
          // Became leader
          for {
            _ <- ZIO.logInfo("Became leader: initializing session tracking from Raft state")
            
            // Get active sessions from Raft
            activeSessions <- raftIntegration.getActiveSessions()
            
            // Initialize session manager from Raft state
            _ <- sessionManager.initializeFromRaftState(activeSessions)
            
            _ <- ZIO.logInfo(s"Initialized ${activeSessions.size} sessions from Raft state")
            
          } yield ()
        
        case (true, false) =>
          // Lost leadership
          for {
            _ <- ZIO.logInfo("Lost leadership: sessions will expire via timeout mechanism")
            
            // Note: Sessions will naturally expire through the timeout mechanism
            // Clients will need to reconnect to the new leader
            
          } yield ()
        
        case (false, false) if newLeaderId.isDefined =>
          // Follower with new leader information
          ZIO.logInfo(s"Following new leader: $newLeaderId")
        
        case _ =>
          // No significant change
          ZIO.unit
      }
    }
  }
}
