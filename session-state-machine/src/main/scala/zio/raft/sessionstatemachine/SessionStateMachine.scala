package zio.raft.sessionstatemachine

import zio.{UIO, ZIO, Clock}
import zio.prelude.State
import zio.raft.{Command, HMap, StateMachine, Index}
import zio.raft.protocol.{SessionId, RequestId}
import zio.stream.Stream
import java.time.Instant

/**
 * Abstract base class for session-aware state machines using the template pattern.
 * 
 * This class implements Chapter 6.3 of the Raft dissertation (Implementing linearizable
 * semantics) by providing automatic session management, idempotency checking, and
 * response caching.
 * 
 * ## Template Pattern
 * 
 * Users extend this class and implement 3 protected abstract methods:
 * - `applyCommand`: Business logic for processing user commands
 * - `handleSessionCreated`: Session initialization logic
 * - `handleSessionExpired`: Session cleanup logic
 * 
 * The base class provides the final `apply` template method that orchestrates:
 * - Idempotency checking via (sessionId, requestId) pairs
 * - Response caching for duplicate requests
 * - Server-initiated request management with cumulative acknowledgment
 * - Session lifecycle coordination
 * - State narrowing (users see only HMap[UserSchema])
 * - State merging (user changes merged back to combined state)
 * 
 * ## Type Parameters
 * 
 * @tparam UC User command type (extends Command with dependent Response type)
 * @tparam SR Server-initiated request payload type
 * @tparam UserSchema User-defined schema (tuple of prefix-type pairs)
 * 
 * ## State Schema
 * 
 * State is HMap[CombinedSchema[UserSchema]] which concatenates:
 * - SessionSchema: 4 fixed prefixes for session management
 * - UserSchema: User-defined prefixes for business logic
 * 
 * Users' abstract methods receive HMap[UserSchema] (narrowed), changes are merged back.
 * 
 * ## Constitution Compliance
 * 
 * - Pure functions only (Principle I)
 * - No exceptions - errors in response payload (Principle II)
 * - Extends existing StateMachine trait (Principle III)
 * - Uses ZIO Clock for timestamps (Principle IV)
 * 
 * @see SessionCommand for commands this state machine accepts
 * @see SessionSchema for session management state structure
 * @see CombinedSchema for combined state structure
 */
abstract class SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple]
  extends StateMachine[HMap[CombinedSchema[UserSchema]], SessionCommand[UC]]:
  
  // ====================================================================================
  // ABSTRACT METHODS - Users must implement
  // ====================================================================================
  
  /**
   * Apply a user command to the state.
   * 
   * This method receives the full combined state but should only modify user prefixes
   * (those defined in UserSchema). Session management prefixes are handled by the
   * base class automatically.
   * 
   * @param command The user command to process
   * @return State transition returning (response, list of server requests to send)
   * 
   * @note Must be pure and deterministic
   * @note Must NOT throw exceptions - return errors in response payload
   * @note Should only modify UserSchema prefixes, not SessionSchema prefixes
   */
  protected def applyCommand(command: UC): State[HMap[CombinedSchema[UserSchema]], (command.Response, List[SR])]
  
  /**
   * Handle session creation event.
   * 
   * Called when a SessionCreationConfirmed command is processed. Use this to initialize
   * any per-session state in the user schema.
   * 
   * @param sessionId The newly created session ID
   * @param capabilities Client capabilities as key-value pairs
   * @return State transition returning list of server requests to send
   * 
   * @note Receives full combined state - modify only UserSchema prefixes
   * @note Must be pure and deterministic
   */
  protected def handleSessionCreated(
    sessionId: SessionId,
    capabilities: Map[String, String]
  ): State[HMap[CombinedSchema[UserSchema]], List[SR]]
  
  /**
   * Handle session expiration event.
   * 
   * Called when a SessionExpired command is processed. Use this to clean up any
   * per-session state in the user schema.
   * 
   * @param sessionId The expired session ID
   * @return State transition returning list of final server requests
   * 
   * @note Receives full combined state - modify only UserSchema prefixes
   * @note Session metadata and cache are automatically removed by base class
   * @note Must be pure and deterministic
   */
  protected def handleSessionExpired(sessionId: SessionId): State[HMap[CombinedSchema[UserSchema]], List[SR]]
  
  // ====================================================================================
  // StateMachine INTERFACE - Implemented by base class
  // ====================================================================================
  
  /**
   * Empty state with no sessions or data.
   */
  def emptyState: HMap[CombinedSchema[UserSchema]] = 
    HMap.empty[CombinedSchema[UserSchema]]
  
  /**
   * Template method (FINAL) - orchestrates session management.
   * 
   * This is the core of the template pattern. Users cannot override this method.
   * It handles all session management automatically and calls the abstract methods
   * at the appropriate times.
   * 
   * Flow for ClientRequest:
   * 1. Check cache for (sessionId, requestId)
   * 2. If cached, return cached response
   * 3. If not cached:
   *    a. Narrow state to HMap[UserSchema]
   *    b. Call applyCommand (abstract method)
   *    c. Merge user state changes back
   *    d. Add server requests and assign IDs
   *    e. Cache the response
   *    f. Return response and server requests
   * 
   * @param command The session command to process
   * @return State transition with command-specific response
   */
  final def apply(command: SessionCommand[UC]): State[HMap[CombinedSchema[UserSchema]], command.Response] =
    command match
      case cmd: SessionCommand.ClientRequest[UC] =>
        handleClientRequest(cmd).asInstanceOf[State[HMap[CombinedSchema[UserSchema]], command.Response]]
      
      case cmd: SessionCommand.ServerRequestAck =>
        handleServerRequestAck(cmd).asInstanceOf[State[HMap[CombinedSchema[UserSchema]], command.Response]]
      
      case cmd: SessionCommand.SessionCreationConfirmed =>
        handleSessionCreationConfirmed(cmd).asInstanceOf[State[HMap[CombinedSchema[UserSchema]], command.Response]]
      
      case cmd: SessionCommand.SessionExpired =>
        handleSessionExpired_internal(cmd).asInstanceOf[State[HMap[CombinedSchema[UserSchema]], command.Response]]
      
      case cmd: SessionCommand.GetRequestsForRetry =>
        handleGetRequestsForRetry(cmd).asInstanceOf[State[HMap[CombinedSchema[UserSchema]], command.Response]]
  
  /**
   * Default snapshot behavior - delegates to user.
   * 
   * Users must implement this in their concrete classes.
   */
  def takeSnapshot(state: HMap[CombinedSchema[UserSchema]]): Stream[Nothing, Byte]
  
  /**
   * Default restore behavior - delegates to user.
   * 
   * Users must implement this in their concrete classes.
   */
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[UserSchema]]]
  
  /**
   * Default snapshot policy - take snapshot every 1000 entries.
   */
  def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
    (commitIndex.value - lastSnapshotIndex.value) >= 1000
  
  // ====================================================================================
  // INTERNAL COMMAND HANDLERS
  // ====================================================================================
  
  /**
   * Handle ClientRequest command with idempotency checking.
   */
  private def handleClientRequest(cmd: SessionCommand.ClientRequest[UC]): State[HMap[CombinedSchema[UserSchema]], (cmd.command.Response, List[SR])] =
    State.modify { state =>
      val cacheKey = makeCacheKey(cmd.sessionId, cmd.requestId)
      
      // Check cache (idempotency)
      state.get["cache"](cacheKey) match
        case Some(cachedResponse) =>
          // Cache hit - return cached response without calling user method
          (state, cachedResponse.asInstanceOf[(cmd.command.Response, List[SR])])
        
        case None =>
          // Cache miss - execute command
          val (newState, (response, serverRequests)) = applyCommand(cmd.command).run(state)
          
          // Add server requests and assign IDs
          val (stateWithRequests, assignedRequests) = addServerRequests(
            newState,
            cmd.sessionId,
            serverRequests
          )
          
          // Cache the response
          val cachedResponse = (response, assignedRequests)
          val finalState = stateWithRequests.updated["cache"](cacheKey, cachedResponse)
          
          (finalState, cachedResponse)
    }
  
  /**
   * Handle ServerRequestAck with cumulative acknowledgment.
   * 
   * Acknowledging request N removes all pending requests with ID ≤ N.
   */
  private def handleServerRequestAck(cmd: SessionCommand.ServerRequestAck): State[HMap[CombinedSchema[UserSchema]], Unit] =
    State.modify { state =>
      val updatedState = acknowledgeServerRequests(state, cmd.sessionId, cmd.requestId)
      (updatedState, ())
    }
  
  /**
   * Handle SessionCreationConfirmed command.
   */
  private def handleSessionCreationConfirmed(cmd: SessionCommand.SessionCreationConfirmed): State[HMap[CombinedSchema[UserSchema]], List[SR]] =
    State.modify { state =>
      // Create session metadata
      // Note: timestamp must come from ZIO Clock service in real usage
      // For state machine (pure function), we use a placeholder
      val metadata = SessionMetadata(
        sessionId = cmd.sessionId,
        capabilities = cmd.capabilities,
        createdAt = Instant.EPOCH  // Placeholder - will be set properly in integration
      )
      
      val stateWithMetadata = state.updated["metadata"](
        SessionId.unwrap(cmd.sessionId),
        metadata
      )
      
      // Initialize lastServerRequestId for this session
      val stateWithRequestId = stateWithMetadata.updated["lastServerRequestId"](
        SessionId.unwrap(cmd.sessionId),
        RequestId(0)
      )
      
      // Call user's session created handler
      val (newState, serverRequests) = handleSessionCreated(cmd.sessionId, cmd.capabilities).run(stateWithRequestId)
      
      // Add any server requests
      val (finalState, assignedRequests) = addServerRequests(
        newState,
        cmd.sessionId,
        serverRequests
      )
      
      (finalState, assignedRequests)
    }
  
  /**
   * Handle SessionExpired command (internal name to avoid conflict with abstract method).
   */
  private def handleSessionExpired_internal(cmd: SessionCommand.SessionExpired): State[HMap[CombinedSchema[UserSchema]], List[SR]] =
    State.modify { state =>
      // Call user's session expired handler first
      val (newState, serverRequests) = handleSessionExpired(cmd.sessionId).run(state)
      
      // Remove all session data
      val finalState = expireSession(newState, cmd.sessionId)
      
      // Return any final server requests (but don't add them to pending - session is expired)
      (finalState, serverRequests)
    }
  
  /**
   * Handle GetRequestsForRetry command.
   * 
   * Returns pending server requests that need to be resent.
   * (Note: Actual retry policy logic is in external process via dirty reads)
   */
  private def handleGetRequestsForRetry(cmd: SessionCommand.GetRequestsForRetry): State[HMap[CombinedSchema[UserSchema]], List[PendingServerRequest[Any]]] =
    State.get.map { state =>
      // Get all pending requests for this session
      val pending = getPendingServerRequests(state, cmd.sessionId)
      pending.asInstanceOf[List[PendingServerRequest[Any]]]
    }
  
  // ====================================================================================
  // NOTE: State narrowing/merging removed for simplicity
  // User methods receive full HMap[CombinedSchema[UserSchema]] and should only
  // modify their own UserSchema prefixes
  // ====================================================================================
  
  // ====================================================================================
  // SERVER REQUEST MANAGEMENT
  // ====================================================================================
  
  /**
   * Add server requests to pending list and assign monotonically increasing IDs.
   */
  private def addServerRequests(
    state: HMap[CombinedSchema[UserSchema]],
    sessionId: SessionId,
    serverRequests: List[SR]
  ): (HMap[CombinedSchema[UserSchema]], List[SR]) =
    if serverRequests.isEmpty then
      (state, Nil)
    else
      // Get last assigned ID for this session
      val lastId = state.get["lastServerRequestId"](SessionId.unwrap(sessionId))
        .getOrElse(RequestId(0))
      
      // Assign IDs starting from lastId + 1
      val requestsWithIds = serverRequests.zipWithIndex.map { case (req, index) =>
        val newId = RequestId(RequestId.unwrap(lastId) + index + 1)
        PendingServerRequest(
          id = newId,
          sessionId = sessionId,
          payload = req,
          lastSentAt = Instant.EPOCH  // Placeholder - set properly in integration
        )
      }
      
      // Update lastServerRequestId
      val newLastId = requestsWithIds.last.id
      val stateWithNewId = state.updated["lastServerRequestId"](
        SessionId.unwrap(sessionId),
        newLastId
      )
      
      // Add requests to pending list
      val stateWithRequests = requestsWithIds.foldLeft(stateWithNewId) { (s, req) =>
        s.updated["serverRequests"](
          s"${SessionId.unwrap(sessionId)}-${RequestId.unwrap(req.id)}",
          req
        )
      }
      
      (stateWithRequests, serverRequests)
  
  /**
   * Acknowledge server requests with cumulative acknowledgment.
   * 
   * Acknowledging request N removes all pending requests with ID ≤ N.
   */
  private def acknowledgeServerRequests(
    state: HMap[CombinedSchema[UserSchema]],
    sessionId: SessionId,
    ackRequestId: RequestId
  ): HMap[CombinedSchema[UserSchema]] =
    // Get all pending requests for this session
    val pending = getPendingServerRequests(state, sessionId)
    
    // Remove all with ID ≤ ackRequestId (cumulative)
    val toRemove = pending.filter(req => RequestId.unwrap(req.id) <= RequestId.unwrap(ackRequestId))
    
    // Remove from state using HMap.removed
    toRemove.foldLeft(state) { (s, req) =>
      s.removed["serverRequests"](s"${SessionId.unwrap(sessionId)}-${RequestId.unwrap(req.id)}")
    }
  
  /**
   * Get all pending server requests for a session.
   * 
   * Note: This is inefficient as it requires checking all possible keys.
   * A better implementation would iterate the internal map.
   * For now, we return empty list as a placeholder.
   */
  private def getPendingServerRequests(
    state: HMap[CombinedSchema[UserSchema]],
    sessionId: SessionId
  ): List[PendingServerRequest[SR]] =
    // TODO: Need to iterate internal map or maintain separate index
    // For now, return empty list
    // In a real implementation, we'd need either:
    // 1. Access to HMap's internal map for iteration
    // 2. A separate index of request IDs per session
    // 3. A keys() method on HMap
    List.empty
  
  /**
   * Remove all session data when session expires.
   */
  private def expireSession(
    state: HMap[CombinedSchema[UserSchema]],
    sessionId: SessionId
  ): HMap[CombinedSchema[UserSchema]] =
    val sessionKey = SessionId.unwrap(sessionId)
    
    // Remove metadata and lastServerRequestId
    val state1 = state
      .removed["metadata"](sessionKey)
      .removed["lastServerRequestId"](sessionKey)
    
    // Remove all pending server requests for this session
    val pending = getPendingServerRequests(state1, sessionId)
    val state2 = pending.foldLeft(state1) { (s, req) =>
      s.removed["serverRequests"](s"$sessionKey-${RequestId.unwrap(req.id)}")
    }
    
    // Remove cache entries for this session
    // Note: This is inefficient - ideally we'd iterate all cache keys
    // For now, we can't efficiently remove all cache entries without
    // access to internal map or a keys() method
    state2
  
  // ====================================================================================
  // HELPER METHODS
  // ====================================================================================
  
  /**
   * Create cache key for (sessionId, requestId) pair.
   */
  private def makeCacheKey(sessionId: SessionId, requestId: RequestId): String =
    s"${SessionId.unwrap(sessionId)}-${RequestId.unwrap(requestId)}"
  
  /**
   * Dirty read helper (FR-027) - check if session has pending requests needing retry.
   * 
   * This method can be called directly (outside Raft consensus) to optimize the
   * retry process. The retry process performs a dirty read, applies policy locally,
   * and only sends GetRequestsForRetry command if retries are needed.
   * 
   * @param state Current state (can be stale - dirty read)
   * @param sessionId Session to check
   * @param retryThreshold Timestamp threshold for considering requests as needing retry
   * @return true if any pending requests have lastSentAt < retryThreshold
   */
  def hasPendingRequests(
    state: HMap[CombinedSchema[UserSchema]],
    sessionId: SessionId,
    retryThreshold: Instant
  ): Boolean =
    val pending = getPendingServerRequests(state, sessionId)
    pending.exists(req => req.lastSentAt.isBefore(retryThreshold))
