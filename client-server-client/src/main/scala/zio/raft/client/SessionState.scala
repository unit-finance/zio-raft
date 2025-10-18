package zio.raft.client

import zio._
import zio.raft.protocol._

/**
 * Client-side session state management.
 * 
 * Tracks:
 * - Current session ID and status
 * - Session capabilities and metadata
 * - Session lifecycle operations
 * - Server-initiated request tracking
 */
class SessionState private (
  currentSessionId: Ref[Option[SessionId]],
  capabilities: Ref[Map[String, String]],
  serverRequestTracker: Ref[ServerRequestTracker],
  pendingOperations: Ref[Map[Nonce, SessionOperation]],
  connectionManager: ConnectionManager,
  config: ClientConfig
) {
  
  def getCurrentSessionId(): UIO[Option[SessionId]] = 
    currentSessionId.get
  
  def getSessionCapabilities(): UIO[Map[String, String]] = 
    capabilities.get
  
  def createSession(capabilities: Map[String, String]): Task[SessionId] = 
    for {
      // Validate capabilities
      _ <- validateCapabilities(capabilities)
      
      // Generate nonce for operation tracking
      nonce <- Nonce.generate()
      
      // Create session message
      createMessage = CreateSession(capabilities, nonce)
      
      // Create promise for the operation
      promise <- Promise.make[SessionError, SessionId]
      
      // Track pending operation
      operation = SessionOperation.Create(promise)
      _ <- pendingOperations.update(_.updated(nonce, operation))
      
      // Store capabilities for later use
      _ <- this.capabilities.set(capabilities)
      
      // Submit message through connection manager
      // (This would be handled by the action stream in practice)
      _ <- submitSessionMessage(createMessage)
      
      // Wait for response
      sessionId <- promise.await
      
      // Update current session ID
      _ <- currentSessionId.set(Some(sessionId))
      
    } yield sessionId
  
  def continueSession(sessionId: SessionId): Task[Unit] = 
    for {
      // Generate nonce for operation tracking
      nonce <- Nonce.generate()
      
      // Create continue session message
      continueMessage = ContinueSession(sessionId, nonce)
      
      // Create promise for the operation
      promise <- Promise.make[SessionError, Unit]
      
      // Track pending operation
      operation = SessionOperation.Continue(promise)
      _ <- pendingOperations.update(_.updated(nonce, operation))
      
      // Submit message through connection manager
      _ <- submitSessionMessage(continueMessage)
      
      // Wait for response
      _ <- promise.await
      
      // Update current session ID
      _ <- currentSessionId.set(Some(sessionId))
      
    } yield ()
  
  def isSessionActive(): UIO[Boolean] = 
    for {
      sessionIdOpt <- currentSessionId.get
      state <- connectionManager.currentState
    } yield sessionIdOpt.isDefined && state == Connected
  
  def isSessionActive(sessionId: SessionId): UIO[Boolean] = 
    for {
      currentIdOpt <- currentSessionId.get
      state <- connectionManager.currentState
    } yield currentIdOpt.contains(sessionId) && state == Connected
  
  def closeSession(): Task[Unit] = 
    for {
      sessionIdOpt <- currentSessionId.get
      _ <- sessionIdOpt match {
        case Some(sessionId) =>
          for {
            now <- Clock.instant
            closeMessage = CloseSession(ClientShutdown)
            _ <- submitSessionMessage(closeMessage)
            _ <- currentSessionId.set(None)
            _ <- capabilities.set(Map.empty)
          } yield ()
        case None =>
          ZIO.fail(new IllegalStateException("No active session to close"))
      }
    } yield ()
  
  def handleSessionCreated(sessionId: SessionId, nonce: Nonce): UIO[Unit] = 
    for {
      operationsMap <- pendingOperations.get
      _ <- operationsMap.get(nonce) match {
        case Some(SessionOperation.Create(promise)) =>
          for {
            _ <- promise.succeed(sessionId).ignore
            _ <- pendingOperations.update(_.removed(nonce))
            _ <- currentSessionId.set(Some(sessionId))
          } yield ()
        case _ =>
          ZIO.logWarning(s"Received SessionCreated for unexpected nonce: $nonce")
      }
    } yield ()
  
  def handleSessionContinued(nonce: Nonce): UIO[Unit] = 
    for {
      operationsMap <- pendingOperations.get
      _ <- operationsMap.get(nonce) match {
        case Some(SessionOperation.Continue(promise)) =>
          for {
            _ <- promise.succeed(()).ignore
            _ <- pendingOperations.update(_.removed(nonce))
          } yield ()
        case _ =>
          ZIO.logWarning(s"Received SessionContinued for unexpected nonce: $nonce")
      }
    } yield ()
  
  def handleSessionRejected(rejection: SessionRejected): UIO[Unit] = 
    for {
      operationsMap <- pendingOperations.get
      _ <- operationsMap.get(rejection.nonce) match {
        case Some(SessionOperation.Create(promise)) =>
          for {
            error <- ZIO.succeed(SessionError.fromRejectionReason(rejection.reason))
            _ <- promise.fail(error).ignore
            _ <- pendingOperations.update(_.removed(rejection.nonce))
          } yield ()
        case Some(SessionOperation.Continue(promise)) =>
          for {
            error <- ZIO.succeed(SessionError.fromRejectionReason(rejection.reason))
            _ <- promise.fail(error).ignore
            _ <- pendingOperations.update(_.removed(rejection.nonce))
          } yield ()
        case _ =>
          ZIO.logWarning(s"Received SessionRejected for unexpected nonce: ${rejection.nonce}")
      }
    } yield ()
  
  def handleSessionClosed(closure: SessionClosed): UIO[Unit] = 
    for {
      _ <- currentSessionId.set(None)
      _ <- capabilities.set(Map.empty)
      _ <- ZIO.logInfo(s"Session closed by server: ${closure.reason}")
    } yield ()
  
  def getServerRequestTracker(): UIO[ServerRequestTracker] = 
    serverRequestTracker.get
  
  def acknowledgeServerRequest(requestId: RequestId): UIO[Unit] = 
    for {
      tracker <- serverRequestTracker.get
      _ <- if (tracker.shouldProcess(requestId)) {
        for {
          updatedTracker <- ZIO.succeed(tracker.acknowledge(requestId))
          _ <- serverRequestTracker.set(updatedTracker)
          _ <- ZIO.logDebug(s"Acknowledged server request: $requestId")
        } yield ()
      } else {
        ZIO.logDebug(s"Duplicate or out-of-order server request ignored: $requestId")
      }
    } yield ()
  
  def getSessionMetadata(): UIO[Option[SessionMetadata]] = 
    for {
      sessionIdOpt <- currentSessionId.get
      caps <- capabilities.get
    } yield sessionIdOpt.map { sessionId =>
      SessionMetadata(
        sessionId = sessionId,
        capabilities = caps,
        createdAt = java.time.Instant.now() // Would track actual creation time
      )
    }
  
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
  
  private def submitSessionMessage(message: ClientMessage): Task[Unit] = {
    // This would be handled by the action stream in practice
    ZIO.logDebug(s"Submitting session message: $message")
  }
}

object SessionState {
  
  /**
   * Create a SessionState with the given configuration.
   */
  def make(
    connectionManager: ConnectionManager,
    config: ClientConfig
  ): UIO[SessionState] = 
    for {
      currentSessionId <- Ref.make[Option[SessionId]](None)
      capabilities <- Ref.make[Map[String, String]](Map.empty)
      serverRequestTracker <- Ref.make(ServerRequestTracker())
      pendingOperations <- Ref.make[Map[Nonce, SessionOperation]](Map.empty)
    } yield new SessionState(
      currentSessionId,
      capabilities,
      serverRequestTracker,
      pendingOperations,
      connectionManager,
      config
    )
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
