package zio.raft.server

import zio._
import zio.stream._
import zio.raft.protocol._
import java.time.Instant

/**
 * Server-side session management with leader awareness.
 * 
 * Manages durable client sessions with:
 * - Session creation and continuation
 * - Connection state tracking (Connected/Disconnected)
 * - Session expiration and cleanup
 * - Routing ID to session ID mapping
 * - Leader-aware operation validation
 */
class SessionManager private (
  sessions: Ref[Map[SessionId, ConnectionState]],
  routingToSession: Ref[Map[zio.zmq.RoutingId, SessionId]],
  isLeaderRef: Ref[Boolean],
  config: ServerConfig
) {
  
  def isLeader: UIO[Boolean] = 
    isLeaderRef.get
  
  def createSession(
    routingId: zio.zmq.RoutingId,
    capabilities: Map[String, String],
    nonce: Nonce
  ): UIO[Either[RejectionReason, SessionId]] = 
    for {
      leader <- isLeader
      result <- if (!leader) {
        ZIO.succeed(Left(NotLeader))
      } else {
        doCreateSession(routingId, capabilities, nonce)
      }
    } yield result
    
  private def doCreateSession(
    routingId: zio.zmq.RoutingId,
    capabilities: Map[String, String],
    nonce: Nonce
  ): UIO[Either[RejectionReason, SessionId]] = 
    for {
      sessionId <- SessionId.generate()
      now <- Clock.instant
      expiredAt = now.plusSeconds(config.sessionTimeout.toSeconds)
      
      connected = Connected(
        routingId = routingId,
        capabilities = capabilities,
        createdAt = now,
        expiredAt = expiredAt
      )
      
      _ <- sessions.update(_.updated(sessionId, connected))
      _ <- routingToSession.update(_.updated(routingId, sessionId))
    } yield Right(sessionId)
  
  def continueSession(
    routingId: zio.zmq.RoutingId,
    sessionId: SessionId,
    nonce: Nonce
  ): UIO[Either[RejectionReason, Unit]] = 
    for {
      sessionsMap <- sessions.get
      result <- sessionsMap.get(sessionId) match {
        case Some(disconnected @ Disconnected(capabilities, createdAt, _)) =>
          // Session exists and is disconnected - can continue
          doContinueSession(routingId, sessionId, disconnected)
        case Some(_: Connected) =>
          // Session is already connected (duplicate continue?)
          ZIO.succeed(Left(SessionConflict))
        case None =>
          // Session doesn't exist
          ZIO.succeed(Left(SessionNotFound))
      }
    } yield result
    
  private def doContinueSession(
    routingId: zio.zmq.RoutingId,
    sessionId: SessionId,
    disconnected: Disconnected
  ): UIO[Either[RejectionReason, Unit]] = 
    for {
      now <- Clock.instant
      expiredAt = now.plusSeconds(config.sessionTimeout.toSeconds)
      
      connected = Connected(
        routingId = routingId,
        capabilities = disconnected.capabilities,
        createdAt = disconnected.createdAt,
        expiredAt = expiredAt
      )
      
      _ <- sessions.update(_.updated(sessionId, connected))
      _ <- routingToSession.update(_.updated(routingId, sessionId))
    } yield Right(())
  
  def handleDisconnection(routingId: zio.zmq.RoutingId): UIO[Option[SessionId]] = 
    for {
      routing <- routingToSession.get
      sessionIdOpt = routing.get(routingId)
      _ <- sessionIdOpt match {
        case Some(sessionId) =>
          for {
            sessionsMap <- sessions.get
            _ <- sessionsMap.get(sessionId) match {
              case Some(connected: Connected) =>
                // Transform Connected -> Disconnected
                val disconnected = Disconnected(
                  capabilities = connected.capabilities,
                  createdAt = connected.createdAt,
                  expiredAt = connected.expiredAt
                )
                sessions.update(_.updated(sessionId, disconnected)) *>
                routingToSession.update(_.removed(routingId))
              case _ =>
                // Session not in connected state, just remove routing
                routingToSession.update(_.removed(routingId))
            }
          } yield ()
        case None =>
          ZIO.unit
      }
    } yield sessionIdOpt
  
  def updateKeepAlive(routingId: zio.zmq.RoutingId): UIO[Boolean] = 
    for {
      routing <- routingToSession.get
      result <- routing.get(routingId) match {
        case Some(sessionId) =>
          for {
            now <- Clock.instant
            expiredAt = now.plusSeconds(config.sessionTimeout.toSeconds)
            updated <- sessions.modify { sessionsMap =>
              sessionsMap.get(sessionId) match {
                case Some(connected: Connected) =>
                  val updated = connected.copy(expiredAt = expiredAt)
                  (true, sessionsMap.updated(sessionId, updated))
                case _ =>
                  (false, sessionsMap)
              }
            }
          } yield updated
        case None =>
          ZIO.succeed(false)
      }
    } yield result
  
  def getSessionId(routingId: zio.zmq.RoutingId): UIO[Option[SessionId]] = 
    routingToSession.get.map(_.get(routingId))
  
  def removeExpiredSessions(): UIO[List[SessionId]] = 
    for {
      now <- Clock.instant
      expired <- sessions.modify { sessionsMap =>
        val (expired, active) = sessionsMap.partition { case (_, state) =>
          state.expiredAt.isBefore(now)
        }
        (expired.keys.toList, active)
      }
      // Remove routing mappings for expired sessions  
      _ <- ZIO.foreachDiscard(expired) { sessionId =>
        routingToSession.update { routing =>
          routing.filter { case (_, sid) => sid != sessionId }
        }
      }
    } yield expired
  
  def getSessionStats(): UIO[SessionStats] = 
    for {
      sessionsMap <- sessions.get
      routing <- routingToSession.get
      connected = sessionsMap.values.count(_.isInstanceOf[Connected])
      disconnected = sessionsMap.values.count(_.isInstanceOf[Disconnected])
    } yield SessionStats(
      totalSessions = sessionsMap.size,
      connectedSessions = connected,
      disconnectedSessions = disconnected,
      routingMappings = routing.size
    )
  
  def initializeFromRaftState(existingSessions: Map[SessionId, SessionMetadata]): UIO[Unit] = 
    for {
      now <- Clock.instant
      // Convert Raft session metadata to local connection states (all start as Disconnected)
      disconnectedStates = existingSessions.map { case (sessionId, metadata) =>
        val expiredAt = now.plusSeconds(config.sessionTimeout.toSeconds) // Fresh timeout
        sessionId -> Disconnected(
          capabilities = metadata.capabilities,
          createdAt = metadata.createdAt,
          expiredAt = expiredAt
        )
      }
      _ <- sessions.set(disconnectedStates)
      _ <- routingToSession.set(Map.empty) // No routing mappings initially
      _ <- isLeaderRef.set(true) // Mark as leader
    } yield ()
}

object SessionManager {
  
  /**
   * Create a SessionManager with the given configuration.
   */
  def make(config: ServerConfig): UIO[SessionManager] = 
    for {
      sessions <- Ref.make(Map.empty[SessionId, ConnectionState])
      routingToSession <- Ref.make(Map.empty[zio.zmq.RoutingId, SessionId])
      isLeaderRef <- Ref.make(false)
    } yield new SessionManager(sessions, routingToSession, isLeaderRef, config)
}

/**
 * Session statistics for monitoring.
 */
case class SessionStats(
  totalSessions: Int,
  connectedSessions: Int,
  disconnectedSessions: Int,
  routingMappings: Int
)

/**
 * Session metadata stored in Raft state machine.
 */
case class SessionMetadata(
  capabilities: Map[String, String],
  createdAt: Instant
)

/**
 * Connection state discriminated union.
 */
sealed trait ConnectionState {
  def capabilities: Map[String, String]
  def createdAt: Instant  
  def expiredAt: Instant
}

case class Connected(
  routingId: zio.zmq.RoutingId,
  capabilities: Map[String, String],
  createdAt: Instant,
  expiredAt: Instant
) extends ConnectionState

case class Disconnected(
  capabilities: Map[String, String], 
  createdAt: Instant,
  expiredAt: Instant
) extends ConnectionState

/**
 * Additional rejection reasons for SessionManager.
 */
// Note: RejectionReason values are defined in the protocol Messages.scala file
