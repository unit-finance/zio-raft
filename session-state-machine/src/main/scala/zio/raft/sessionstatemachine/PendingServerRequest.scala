package zio.raft.sessionstatemachine

import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

/**
 * Represents a server-initiated request that has been sent to a client
 * and is awaiting acknowledgment.
 * 
 * These requests are tracked in the session state and managed by the
 * SessionStateMachine base class. When a client sends a ServerRequestAck,
 * the base class uses cumulative acknowledgment: acknowledging request N
 * removes all pending requests with ID ≤ N.
 * 
 * @param id Unique identifier for this server-initiated request (monotonic per session)
 * @param sessionId The session this request was sent to
 * @param payload The actual request data (generic type SR)
 * @param lastSentAt Timestamp when this request was last sent (required, not optional)
 * 
 * @tparam SR The type of the server-initiated request payload
 * 
 * @note Immutable case class
 * @note lastSentAt must come from ZIO Clock service, not Instant.now() (Constitution IV)
 * @note lastSentAt is NOT optional - it's always present and required
 */
case class PendingServerRequest[SR](
  id: RequestId,
  sessionId: SessionId,
  payload: SR,
  lastSentAt: Instant
)
