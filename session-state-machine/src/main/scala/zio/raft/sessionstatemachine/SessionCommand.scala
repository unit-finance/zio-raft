package zio.raft.sessionstatemachine

import zio.raft.{Command, HMap}
import zio.raft.protocol.{SessionId, RequestId}

/**
 * Sealed trait representing all commands that the SessionStateMachine accepts.
 * 
 * These commands are internal to the session state machine framework.
 * Users convert RaftAction events from the client-server library into
 * SessionCommand instances and apply them to their SessionStateMachine.
 * 
 * @tparam UC The user command type (extends Command with dependent Response type)
 */
sealed trait SessionCommand[UC <: Command] extends Command

object SessionCommand {
  
  /**
   * A client request containing a user command to execute.
   * 
   * This is the primary command type. The SessionStateMachine base class:
   * 1. Checks if (sessionId, requestId) is already in the cache (idempotency)
   * 2. If cached, returns the cached response
   * 3. If not cached, narrows state to UserSchema and calls user's applyCommand method
   * 4. Caches the response and returns it
   * 
   * @param sessionId The client session ID
   * @param requestId The request ID (for idempotency checking)
   * @param lowestRequestId The lowest request ID for which client hasn't received response (for cache cleanup)
   * @param command The user's command to execute
   * 
   * @note Response type is the user command's Response type (dependent types)
   * @note lowestRequestId enables the "Lowest Sequence Number Protocol" from Raft dissertation Ch. 6.3
   */
  case class ClientRequest[UC <: Command](
    sessionId: SessionId,
    requestId: RequestId,
    lowestRequestId: RequestId,
    command: UC
  ) extends SessionCommand[UC]:
    // Response type matches the user command's Response type
    type Response = (command.Response, List[Any])  // (response, server requests)
  
  /**
   * Acknowledgment from a client for a server-initiated request.
   * 
   * The SessionStateMachine base class uses cumulative acknowledgment:
   * acknowledging request N removes all pending requests with ID ≤ N.
   * 
   * @param sessionId The client session ID
   * @param requestId The server request ID being acknowledged
   */
  case class ServerRequestAck(
    sessionId: SessionId,
    requestId: RequestId
  ) extends SessionCommand[Nothing]:
    type Response = Unit
  
  /**
   * Create a new session.
   * 
   * The SessionStateMachine base class:
   * 1. Creates SessionMetadata and stores it
   * 2. Calls user's handleSessionCreated method
   * 3. Returns any server requests
   * 
   * @param sessionId The newly created session ID
   * @param capabilities Client capabilities as key-value pairs
   */
  case class CreateSession(
    sessionId: SessionId,
    capabilities: Map[String, String]
  ) extends SessionCommand[Nothing]:
    type Response = List[Any]  // server requests
  
  /**
   * Notification that a session has expired.
   * 
   * The SessionStateMachine base class:
   * 1. Narrows state to UserSchema
   * 2. Calls user's handleSessionExpired method
   * 3. Removes all session data (metadata, cache, pending requests)
   * 4. Returns any final server requests
   * 
   * @param sessionId The expired session ID
   */
  case class SessionExpired(
    sessionId: SessionId
  ) extends SessionCommand[Nothing]:
    type Response = List[Any]  // server requests
  
  /**
   * Command to atomically retrieve requests needing retry and update lastSentAt.
   * 
   * Used by external retry process (FR-027 dirty read optimization):
   * 1. Process does dirty read of unacknowledged requests
   * 2. Applies retry policy locally (cheap, no Raft consensus)
   * 3. If policy says retries needed, sends this command
   * 4. State machine atomically identifies requests, updates lastSentAt, returns list
   * 5. Process discards dirty read data, uses command response (authoritative)
   * 
   * Benefits: Single Raft log entry, atomic operation, responses stay in state (not log)
   * 
   * @param sessionId The session to check for retry-eligible requests
   * @param currentTime The current time for determining retry eligibility and updating lastSentAt
   */
  case class GetRequestsForRetry(
    sessionId: SessionId,
    currentTime: java.time.Instant
  ) extends SessionCommand[Nothing]:
    type Response = List[PendingServerRequest[Any]]
}
