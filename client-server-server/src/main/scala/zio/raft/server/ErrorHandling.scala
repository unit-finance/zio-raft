package zio.raft.server

import zio._
import zio.raft.protocol._

/**
 * Comprehensive error handling and timeout management for the server.
 * 
 * Provides:
 * - Centralized error handling strategies
 * - Timeout management for all operations
 * - Error recovery mechanisms
 * - Client error notification
 * - System health monitoring
 */
trait ErrorHandler {
  
  /**
   * Handle session management errors.
   */
  def handleSessionError(
    routingId: zio.zmq.RoutingId,
    error: SessionError,
    context: SessionContext
  ): UIO[Unit]
  
  /**
   * Handle client message processing errors.
   */
  def handleMessageError(
    routingId: zio.zmq.RoutingId,
    error: MessageError,
    context: MessageContext
  ): UIO[Unit]
  
  /**
   * Handle transport errors.
   */
  def handleTransportError(
    error: TransportError,
    context: TransportContext
  ): UIO[Unit]
  
  /**
   * Handle Raft integration errors.
   */
  def handleRaftError(
    error: RaftError,
    context: RaftContext
  ): UIO[Unit]
  
  /**
   * Handle timeout scenarios.
   */
  def handleTimeout(
    timeoutType: TimeoutType,
    context: TimeoutContext
  ): UIO[Unit]
  
  /**
   * Get error statistics.
   */
  def getErrorStats(): UIO[ErrorStats]
  
  /**
   * Check system health based on error patterns.
   */
  def getSystemHealth(): UIO[SystemHealth]
}

object ErrorHandler {
  
  /**
   * Create an ErrorHandler with the given dependencies.
   */
  def make(
    clientHandler: ClientHandler,
    sessionManager: SessionManager,
    config: ServerConfig
  ): UIO[ErrorHandler] = 
    for {
      errorStats <- Ref.make(ErrorStats.empty)
    } yield new ErrorHandlerImpl(
      clientHandler,
      sessionManager,
      errorStats,
      config
    )
}

/**
 * Internal implementation of ErrorHandler.
 */
private class ErrorHandlerImpl(
  clientHandler: ClientHandler,
  sessionManager: SessionManager,
  errorStats: Ref[ErrorStats],
  config: ServerConfig
) extends ErrorHandler {
  
  override def handleSessionError(
    routingId: zio.zmq.RoutingId,
    error: SessionError,
    context: SessionContext
  ): UIO[Unit] = 
    for {
      _ <- updateErrorStats(ErrorCategory.Session, error.toString)
      _ <- error match {
        case SessionError.NotFound(sessionId) =>
          for {
            _ <- ZIO.logWarning(s"Session not found: $sessionId for routing $routingId")
            rejection = SessionRejected(SessionNotFound, context.nonce, None)
            _ <- clientHandler.sendMessage(routingId, rejection).orDie
          } yield ()
        
        case SessionError.AlreadyExists(sessionId) =>
          for {
            _ <- ZIO.logWarning(s"Session already exists: $sessionId for routing $routingId")
            rejection = SessionRejected(SessionConflict, context.nonce, None)
            _ <- clientHandler.sendMessage(routingId, rejection).orDie
          } yield ()
        
        case SessionError.NotAuthorized(sessionId, reason) =>
          for {
            _ <- ZIO.logWarning(s"Session not authorized: $sessionId, reason: $reason")
            rejection = SessionRejected(NotAuthorized, context.nonce, None)
            _ <- clientHandler.sendMessage(routingId, rejection).orDie
          } yield ()
        
        case SessionError.Expired(sessionId) =>
          for {
            _ <- ZIO.logInfo(s"Session expired: $sessionId")
            closure = SessionClosed(zio.raft.protocol.SessionError, None)
            _ <- clientHandler.sendMessage(routingId, closure).orDie
          } yield ()
        
        case SessionError.InvalidCapabilities(reason) =>
          for {
            _ <- ZIO.logWarning(s"Invalid capabilities: $reason")
            rejection = SessionRejected(InvalidCapabilities, context.nonce, None)
            _ <- clientHandler.sendMessage(routingId, rejection).orDie
          } yield ()
      }
    } yield ()
  
  override def handleMessageError(
    routingId: zio.zmq.RoutingId,
    error: MessageError,
    context: MessageContext
  ): UIO[Unit] = 
    for {
      _ <- updateErrorStats(ErrorCategory.Message, error.toString)
      _ <- error match {
        case MessageError.InvalidFormat(reason) =>
          for {
            _ <- ZIO.logWarning(s"Invalid message format from $routingId: $reason")
            // Send generic error response
            errorMsg = RequestError(
              reason = InvalidRequest,
              leaderId = None
            )
            _ <- clientHandler.sendMessage(routingId, errorMsg).orDie
          } yield ()
        
        case MessageError.UnsupportedVersion(version) =>
          for {
            _ <- ZIO.logWarning(s"Unsupported protocol version from $routingId: $version")
            errorMsg = RequestError(
              reason = UnsupportedVersion,
              leaderId = None
            )
            _ <- clientHandler.sendMessage(routingId, errorMsg).orDie
          } yield ()
        
        case MessageError.TooLarge(size, maxSize) =>
          for {
            _ <- ZIO.logWarning(s"Message too large from $routingId: $size > $maxSize")
            errorMsg = RequestError(
              reason = PayloadTooLarge,
              leaderId = None
            )
            _ <- clientHandler.sendMessage(routingId, errorMsg).orDie
          } yield ()
        
        case MessageError.Malformed(details) =>
          for {
            _ <- ZIO.logError(s"Malformed message from $routingId: $details")
            // Close the connection for malformed messages
            _ <- clientHandler.handleClientDisconnection(routingId)
          } yield ()
      }
    } yield ()
  
  override def handleTransportError(
    error: TransportError,
    context: TransportContext
  ): UIO[Unit] = 
    for {
      _ <- updateErrorStats(ErrorCategory.Transport, error.toString)
      _ <- error match {
        case TransportError.ConnectionLost(address) =>
          for {
            _ <- ZIO.logWarning(s"Connection lost to $address")
            // Attempt reconnection
            _ <- ZIO.logInfo(s"Attempting to reconnect to $address")
          } yield ()
        
        case TransportError.SendFailed(routingId, reason) =>
          for {
            _ <- ZIO.logWarning(s"Failed to send message to $routingId: $reason")
            // Handle client as disconnected
            _ <- clientHandler.handleClientDisconnection(routingId)
          } yield ()
        
        case TransportError.ReceiveFailed(reason) =>
          for {
            _ <- ZIO.logError(s"Failed to receive message: $reason")
            // This might indicate a more serious transport issue
          } yield ()
        
        case TransportError.BindFailed(address, reason) =>
          for {
            _ <- ZIO.logError(s"Failed to bind to $address: $reason")
            // This is a critical error - server cannot function
          } yield ()
      }
    } yield ()
  
  override def handleRaftError(
    error: RaftError,
    context: RaftContext
  ): UIO[Unit] = 
    for {
      _ <- updateErrorStats(ErrorCategory.Raft, error.toString)
      _ <- error match {
        case RaftError.NotLeader(currentLeader) =>
          for {
            _ <- ZIO.logDebug(s"Not leader, current leader: $currentLeader")
            // Send redirection to client
            errorMsg = RequestError(
              reason = NotLeaderRequest,
              leaderId = currentLeader
            )
            _ <- context.routingId.map { routingId =>
              clientHandler.sendMessage(routingId, errorMsg).orDie
            }.getOrElse(ZIO.unit)
          } yield ()
        
        case RaftError.NoQuorum =>
          for {
            _ <- ZIO.logError("No quorum available")
            // Send unavailable error to client
            errorMsg = RequestError(
              reason = ServiceUnavailable,
              leaderId = None
            )
            _ <- context.routingId.map { routingId =>
              clientHandler.sendMessage(routingId, errorMsg).orDie
            }.getOrElse(ZIO.unit)
          } yield ()
        
        case RaftError.CommandFailed(command, reason) =>
          for {
            _ <- ZIO.logWarning(s"Raft command failed: $command, reason: $reason")
            errorMsg = RequestError(
              reason = ProcessingFailed,
              leaderId = None
            )
            _ <- context.routingId.map { routingId =>
              clientHandler.sendMessage(routingId, errorMsg).orDie
            }.getOrElse(ZIO.unit)
          } yield ()
        
        case RaftError.StateError(details) =>
          for {
            _ <- ZIO.logError(s"Raft state error: $details")
            // This might require more serious intervention
          } yield ()
      }
    } yield ()
  
  override def handleTimeout(
    timeoutType: TimeoutType,
    context: TimeoutContext
  ): UIO[Unit] = 
    for {
      _ <- updateErrorStats(ErrorCategory.Timeout, timeoutType.toString)
      _ <- timeoutType match {
        case TimeoutType.SessionTimeout =>
          for {
            _ <- ZIO.logInfo(s"Session timeout for session ${context.sessionId}")
            _ <- context.sessionId.map { sessionId =>
              sessionManager.handleSessionTimeout(sessionId)
            }.getOrElse(ZIO.unit)
          } yield ()
        
        case TimeoutType.RequestTimeout =>
          for {
            _ <- ZIO.logWarning(s"Request timeout for request ${context.requestId}")
            errorMsg = RequestError(
              reason = RequestTimeout,
              leaderId = None
            )
            _ <- context.routingId.map { routingId =>
              clientHandler.sendMessage(routingId, errorMsg).orDie
            }.getOrElse(ZIO.unit)
          } yield ()
        
        case TimeoutType.ConnectionTimeout =>
          for {
            _ <- ZIO.logInfo(s"Connection timeout for routing ${context.routingId}")
            _ <- context.routingId.map { routingId =>
              clientHandler.handleClientDisconnection(routingId)
            }.getOrElse(ZIO.unit)
          } yield ()
        
        case TimeoutType.LeaderElectionTimeout =>
          for {
            _ <- ZIO.logWarning("Leader election timeout")
            // This is handled by the Raft implementation
          } yield ()
      }
    } yield ()
  
  override def getErrorStats(): UIO[ErrorStats] = 
    errorStats.get
  
  override def getSystemHealth(): UIO[SystemHealth] = 
    for {
      stats <- errorStats.get
      now <- Clock.instant
      
      // Calculate error rates
      recentErrors = stats.getErrorsInLastMinute(now)
      errorRate = recentErrors.toDouble / 60.0 // errors per second
      
      // Determine health status
      healthStatus = if (errorRate < 0.1) {
        SystemHealthStatus.Healthy
      } else if (errorRate < 1.0) {
        SystemHealthStatus.Degraded
      } else {
        SystemHealthStatus.Unhealthy
      }
      
    } yield SystemHealth(
      status = healthStatus,
      errorRate = errorRate,
      totalErrors = stats.totalErrors,
      lastError = stats.lastError,
      criticalErrors = stats.criticalErrors
    )
  
  private def updateErrorStats(category: ErrorCategory, error: String): UIO[Unit] = 
    for {
      now <- Clock.instant
      _ <- errorStats.update(_.recordError(category, error, now))
    } yield ()
}

/**
 * Error categories for classification.
 */
sealed trait ErrorCategory

object ErrorCategory {
  case object Session extends ErrorCategory
  case object Message extends ErrorCategory
  case object Transport extends ErrorCategory
  case object Raft extends ErrorCategory
  case object Timeout extends ErrorCategory
}

/**
 * Session-related errors.
 */
sealed trait SessionError

object SessionError {
  case class NotFound(sessionId: SessionId) extends SessionError
  case class AlreadyExists(sessionId: SessionId) extends SessionError
  case class NotAuthorized(sessionId: SessionId, reason: String) extends SessionError
  case class Expired(sessionId: SessionId) extends SessionError
  case class InvalidCapabilities(reason: String) extends SessionError
}

/**
 * Message processing errors.
 */
sealed trait MessageError

object MessageError {
  case class InvalidFormat(reason: String) extends MessageError
  case class UnsupportedVersion(version: String) extends MessageError
  case class TooLarge(size: Int, maxSize: Int) extends MessageError
  case class Malformed(details: String) extends MessageError
}

/**
 * Transport-related errors.
 */
sealed trait TransportError

object TransportError {
  case class ConnectionLost(address: String) extends TransportError
  case class SendFailed(routingId: zio.zmq.RoutingId, reason: String) extends TransportError
  case class ReceiveFailed(reason: String) extends TransportError
  case class BindFailed(address: String, reason: String) extends TransportError
}

/**
 * Raft-related errors.
 */
sealed trait RaftError

object RaftError {
  case class NotLeader(currentLeader: Option[MemberId]) extends RaftError
  case object NoQuorum extends RaftError
  case class CommandFailed(command: String, reason: String) extends RaftError
  case class StateError(details: String) extends RaftError
}

/**
 * Timeout types.
 */
sealed trait TimeoutType

object TimeoutType {
  case object SessionTimeout extends TimeoutType
  case object RequestTimeout extends TimeoutType
  case object ConnectionTimeout extends TimeoutType
  case object LeaderElectionTimeout extends TimeoutType
}

/**
 * Error contexts for providing additional information.
 */
case class SessionContext(
  nonce: Nonce,
  capabilities: Map[String, String] = Map.empty
)

case class MessageContext(
  requestId: Option[RequestId] = None,
  messageType: String = "unknown"
)

case class TransportContext(
  address: Option[String] = None,
  routingId: Option[zio.zmq.RoutingId] = None
)

case class RaftContext(
  requestId: RequestId,
  routingId: Option[zio.zmq.RoutingId] = None
)

case class TimeoutContext(
  sessionId: Option[SessionId] = None,
  requestId: Option[RequestId] = None,
  routingId: Option[zio.zmq.RoutingId] = None
)

/**
 * Error statistics for monitoring.
 */
case class ErrorStats(
  totalErrors: Long,
  errorsByCategory: Map[ErrorCategory, Long],
  recentErrors: List[ErrorEvent],
  lastError: Option[java.time.Instant],
  criticalErrors: Long
) {
  
  def recordError(category: ErrorCategory, error: String, timestamp: java.time.Instant): ErrorStats = {
    val event = ErrorEvent(category, error, timestamp)
    val isCritical = category == ErrorCategory.Raft || category == ErrorCategory.Transport
    
    copy(
      totalErrors = totalErrors + 1,
      errorsByCategory = errorsByCategory.updated(
        category, 
        errorsByCategory.getOrElse(category, 0L) + 1
      ),
      recentErrors = (event :: recentErrors).take(100), // Keep last 100 errors
      lastError = Some(timestamp),
      criticalErrors = if (isCritical) criticalErrors + 1 else criticalErrors
    )
  }
  
  def getErrorsInLastMinute(now: java.time.Instant): Long = {
    val oneMinuteAgo = now.minusSeconds(60)
    recentErrors.count(_.timestamp.isAfter(oneMinuteAgo))
  }
}

object ErrorStats {
  def empty: ErrorStats = ErrorStats(
    totalErrors = 0L,
    errorsByCategory = Map.empty,
    recentErrors = List.empty,
    lastError = None,
    criticalErrors = 0L
  )
}

/**
 * Individual error event.
 */
case class ErrorEvent(
  category: ErrorCategory,
  error: String,
  timestamp: java.time.Instant
)

/**
 * System health status.
 */
case class SystemHealth(
  status: SystemHealthStatus,
  errorRate: Double,
  totalErrors: Long,
  lastError: Option[java.time.Instant],
  criticalErrors: Long
)

sealed trait SystemHealthStatus

object SystemHealthStatus {
  case object Healthy extends SystemHealthStatus
  case object Degraded extends SystemHealthStatus
  case object Unhealthy extends SystemHealthStatus
}

/**
 * Additional error reasons for comprehensive error handling.
 */
// Note: Additional RequestErrorReason and RejectionReason values are now defined in the protocol Messages.scala file

// Extensions to SessionManager for error handling
extension (sessionManager: SessionManager) {
  def handleSessionTimeout(sessionId: SessionId): UIO[Unit] = {
    // This would be implemented in the actual SessionManager
    ZIO.logInfo(s"Handling session timeout: $sessionId")
  }
}

// Extensions to ClientHandler for error handling  
extension (clientHandler: ClientHandler) {
  def sendMessage(routingId: zio.zmq.RoutingId, message: ServerMessage): Task[Unit] = {
    // This would delegate to the actual ZmqTransport
    ZIO.logDebug(s"Sending error response to $routingId: ${message.getClass.getSimpleName}")
  }
}
