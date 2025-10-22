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
 * @param capabilities Key-value pairs describing client capabilities
 * @param createdAt Timestamp when the session was created (from ZIO Clock service)
 * 
 * @note Immutable case class - use copy() to create modified versions
 * @note createdAt must come from ZIO Clock service, not Instant.now() (Constitution IV)
 * @note sessionId is NOT stored here - it's the key in the HMap, storing it would waste memory
 */
case class SessionMetadata(
  capabilities: Map[String, String],
  createdAt: Instant
)
