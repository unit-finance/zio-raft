package zio.raft.sessionstatemachine

import zio.raft.protocol.SessionId
import java.time.Instant

/**
 * Metadata associated with a client session.
 * 
 * This tracks session-level information for the session state machine
 * framework. Created when a session is established and persists until
 * session expiration.
 * 
 * @param sessionId Unique identifier for the session
 * @param capabilities Key-value pairs describing client capabilities
 * @param createdAt Timestamp when the session was created (from ZIO Clock service)
 * 
 * @note Immutable case class - use copy() to create modified versions
 * @note createdAt must come from ZIO Clock service, not Instant.now() (Constitution IV)
 */
case class SessionMetadata(
  sessionId: SessionId,
  capabilities: Map[String, String],
  createdAt: Instant
)
