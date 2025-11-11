package zio.raft.sessionstatemachine

import zio.raft.Command
import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

/** Sealed trait representing all commands that the SessionStateMachine accepts.
  *
  * These commands are internal to the session state machine framework. Users convert RaftAction events from the
  * client-server library into SessionCommand instances and apply them to their SessionStateMachine.
  *
  * @tparam UC
  *   The user command type (extends Command with dependent Response type)
  * @tparam SR
  *   Server-initiated request payload type
  */
sealed trait SessionCommand[+UC <: Command, SR, +E] extends Command

object SessionCommand:

  /** A client request containing a user command to execute.
    *
    * This is the primary command type. The SessionStateMachine base class:
    *   1. Updates highestLowestRequestIdSeen if lowestRequestId is higher 2. Checks if requestId <=
    *      highestLowestRequestIdSeen AND not in cache → return Left(RequestError.ResponseEvicted) 3. Checks if
    *      (sessionId, requestId) is already in the cache (idempotency) 4. If cached, returns the cached response 5. If
    *      not cached, narrows state to UserSchema and calls user's applyCommand method 6. Caches the response and
    *      returns it
    *
    * @param sessionId
    *   The client session ID
    * @param requestId
    *   The request ID (for idempotency checking)
    * @param lowestRequestId
    *   The lowest request ID for which client hasn't received response (for cache cleanup). Client is saying "I have
    *   received all responses for requestIds <= this value (inclusive)"
    * @param command
    *   The user's command to execute
    *
    * @note
    *   Response type is Either[RequestError, (command.Response, List[ServerRequestEnvelope[SR]])] Left: error (e.g.,
    *   response evicted) Right: (user command response, server request envelopes)
    * @note
    *   lowestRequestId enables the "Lowest Sequence Number Protocol" from Raft dissertation Ch. 6.3
    * @note
    *   ResponseEvicted error indicates client must create a new session (Raft dissertation Ch. 6.3)
    * @note
    *   Requests can arrive out of order. Eviction detection uses lowestRequestId, not requestId ordering
    */
  case class ClientRequest[UC <: Command, SR, E](
    createdAt: Instant,
    sessionId: SessionId,
    requestId: RequestId,
    lowestPendingRequestId: RequestId,
    command: UC
  ) extends SessionCommand[UC, SR, E]:
    // Response type can be an error or the user command's response with server request envelopes
    type Response = Either[RequestError[E], (command.Response, List[ServerRequestEnvelope[SR]])]

  /** Acknowledgment from a client for a server-initiated request.
    *
    * The SessionStateMachine base class uses cumulative acknowledgment: acknowledging request N removes all pending
    * requests with ID ≤ N.
    *
    * @param sessionId
    *   The client session ID
    * @param requestId
    *   The server request ID being acknowledged
    */
  case class ServerRequestAck[SR](
    createdAt: Instant,
    sessionId: SessionId,
    requestId: RequestId
  ) extends SessionCommand[Nothing, SR, Nothing]:
    type Response = Unit

  /** Create a new session.
    *
    * The SessionStateMachine base class:
    *   1. Creates SessionMetadata and stores it 2. Calls user's handleSessionCreated method 3. Returns any server
    *      requests
    *
    * @param sessionId
    *   The newly created session ID
    * @param capabilities
    *   Client capabilities as key-value pairs
    */
  case class CreateSession[SR](
    createdAt: Instant,
    sessionId: SessionId,
    capabilities: Map[String, String]
  ) extends SessionCommand[Nothing, SR, Nothing]:
    type Response = List[ServerRequestEnvelope[SR]] // server request envelopes

  /** Notification that a session has expired.
    *
    * The SessionStateMachine base class:
    *   1. Narrows state to UserSchema 2. Calls user's handleSessionExpired method 3. Removes all session data
    *      (metadata, cache, pending requests) 4. Returns any final server requests
    *
    * @param sessionId
    *   The expired session ID
    */
  case class SessionExpired[SR](
    createdAt: Instant,
    sessionId: SessionId
  ) extends SessionCommand[Nothing, SR, Nothing]:
    type Response = List[ServerRequestEnvelope[SR]] // server request envelopes

  /** Command to atomically retrieve requests needing retry and update lastSentAt.
    *
    * Used by external retry process (FR-027 dirty read optimization):
    *   1. Process does dirty read of unacknowledged requests 2. Applies retry policy locally (cheap, no Raft consensus)
    *      3. If policy says retries needed, sends this command 4. State machine atomically identifies requests, updates
    *      lastSentAt, returns list 5. Process discards dirty read data, uses command response (authoritative)
    *
    * Benefits: Single Raft log entry, atomic operation, responses stay in state (not log)
    *
    * @param lastSentBefore
    *   Only return requests where lastSentAt is before this time (retry threshold)
    */
  case class GetRequestsForRetry[SR](
    createdAt: Instant,
    lastSentBefore: Instant
  ) extends SessionCommand[Nothing, SR, Nothing]:
    type Response = List[ServerRequestEnvelope[SR]]
end SessionCommand
