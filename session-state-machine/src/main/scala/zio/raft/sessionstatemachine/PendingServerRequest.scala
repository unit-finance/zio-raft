package zio.raft.sessionstatemachine

import java.time.Instant

/** Represents a server-initiated request that has been sent to a client and is awaiting acknowledgment.
  *
  * These requests are tracked in the session state using composite keys (SessionId, RequestId) in the HMap. When a
  * client sends a ServerRequestAck, the base class uses cumulative acknowledgment: acknowledging request N removes all
  * pending requests with ID ≤ N.
  *
  * The id and sessionId are stored in the HMap key, not duplicated here, for efficiency.
  *
  * @param payload
  *   The actual request data (generic type SR)
  * @param lastSentAt
  *   Timestamp when this request was last sent (required, not optional)
  *
  * @tparam SR
  *   The type of the server-initiated request payload
  *
  * @note
  *   Immutable case class
  * @note
  *   lastSentAt must come from ZIO Clock service, not Instant.now() (Constitution IV)
  * @note
  *   lastSentAt is NOT optional - it's always present and required
  */
case class PendingServerRequest[SR](
  payload: SR,
  lastSentAt: Instant
)
