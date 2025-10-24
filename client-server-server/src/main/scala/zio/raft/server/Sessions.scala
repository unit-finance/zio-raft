package zio.raft.server

import zio.*
import zio.raft.protocol.*
import zio.zmq.RoutingId
import java.time.Instant

/** Connection state for a session.
  */
case class SessionConnection(
  routingId: Option[RoutingId],
  expiresAt: Instant
)

/** Metadata about a confirmed session.
  */
case class SessionMetadata(
  capabilities: Map[String, String],
  createdAt: Instant
)

/** A session that has been created but not yet confirmed by Raft.
  */
case class PendingSession(
  routingId: RoutingId,
  nonce: Nonce,
  capabilities: Map[String, String],
  createdAt: Instant
)

/** Manages all session state for the server.
  */
case class Sessions(
  metadata: Map[SessionId, SessionMetadata],
  connections: Map[SessionId, SessionConnection],
  routingToSession: Map[RoutingId, SessionId],
  pendingSessions: Map[SessionId, PendingSession]
):
  def addPending(
    sessionId: SessionId,
    routingId: RoutingId,
    nonce: Nonce,
    capabilities: Map[String, String],
    now: Instant
  ): Sessions =
    copy(
      pendingSessions = pendingSessions.updated(sessionId, PendingSession(routingId, nonce, capabilities, now))
    )

  def confirmSession(
    sessionId: SessionId,
    now: Instant,
    config: ServerConfig
  ): Option[(RoutingId, Nonce, Sessions)] =
    pendingSessions.get(sessionId).map { pending =>
      val expiresAt = now.plus(config.sessionTimeout)
      val newSessions = copy(
        metadata = metadata.updated(sessionId, SessionMetadata(pending.capabilities, pending.createdAt)),
        connections = connections.updated(sessionId, SessionConnection(Some(pending.routingId), expiresAt)),
        routingToSession = routingToSession.updated(pending.routingId, sessionId),
        pendingSessions = pendingSessions.removed(sessionId)
      )
      (pending.routingId, pending.nonce, newSessions)
    }

  def reconnect(sessionId: SessionId, routingId: RoutingId, now: Instant, config: ServerConfig): Sessions =
    connections.get(sessionId) match
      case Some(conn) =>
        val expiresAt = now.plus(config.sessionTimeout)
        // Remove old routing mapping if it exists
        val cleanedRouting = conn.routingId.map(old => routingToSession.removed(old)).getOrElse(routingToSession)
        copy(
          connections = connections.updated(sessionId, conn.copy(routingId = Some(routingId), expiresAt = expiresAt)),
          routingToSession = cleanedRouting.updated(routingId, sessionId)
        )
      case None => this

  def disconnect(sessionId: SessionId, routingId: RoutingId): Sessions =
    copy(
      connections = connections.updatedWith(sessionId)(_.map(_.copy(routingId = None))),
      routingToSession = routingToSession.removed(routingId)
    )

  def removeSession(sessionId: SessionId, routingId: RoutingId): Sessions =
    copy(
      metadata = metadata.removed(sessionId),
      connections = connections.removed(sessionId),
      routingToSession = routingToSession.removed(routingId),
      pendingSessions = pendingSessions.removed(sessionId)
    )

  def updateExpiry(sessionId: SessionId, now: Instant, config: ServerConfig): Sessions =
    connections.get(sessionId) match
      case Some(conn) =>
        val expiresAt = now.plus(config.sessionTimeout)
        copy(connections = connections.updated(sessionId, conn.copy(expiresAt = expiresAt)))
      case None => this

  def removeExpired(now: Instant): (List[SessionId], Sessions) =
    val expired = connections.filter { case (_, conn) => conn.expiresAt.isBefore(now) }.keys.toList
    val newMetadata = metadata -- expired
    val newConnections = connections -- expired
    val newRouting = routingToSession.filter { case (_, sid) => !expired.contains(sid) }
    val newPendingSessions = pendingSessions -- expired
    (expired, Sessions(newMetadata, newConnections, newRouting, newPendingSessions))

  def getRoutingId(sessionId: SessionId): Option[RoutingId] =
    connections.get(sessionId).flatMap(_.routingId)

  def getMetadata(sessionId: SessionId): Option[SessionMetadata] =
    metadata.get(sessionId)

  def findSessionByRouting(routingId: RoutingId): Option[SessionId] =
    routingToSession.get(routingId)

  def shutdown(transport: ServerTransport): UIO[Unit] =
    ZIO.foreachDiscard(connections.values.flatMap(_.routingId)) { routingId =>
      transport.sendMessage(routingId, SessionClosed(SessionCloseReason.Shutdown, None)).orDie
    }

  def stepDown(transport: ServerTransport, leaderId: Option[MemberId]): UIO[Unit] =
    ZIO.foreachDiscard(connections.values.flatMap(_.routingId)) { routingId =>
      transport.sendMessage(routingId, SessionClosed(SessionCloseReason.NotLeaderAnymore, leaderId)).orDie
    }
end Sessions

object Sessions:
  def empty: Sessions = Sessions(Map.empty, Map.empty, Map.empty, Map.empty)

  def fromMetadata(metadata: Map[SessionId, SessionMetadata], config: ServerConfig, now: Instant): Sessions =
    val expiresAt = now.plusSeconds(config.sessionTimeout.toSeconds)
    val connections = metadata.map { case (sid, _) =>
      sid -> SessionConnection(None, expiresAt)
    }
    Sessions(metadata, connections, Map.empty, Map.empty)
