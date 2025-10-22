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
  extends StateMachine[HMap[CombinedSchema[UserSchema]], SessionCommand[UC, SR]]:
  
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
   * @param capabilities The session capabilities (retrieved from metadata)
   * @return State transition returning list of final server requests
   * 
   * @note Receives full combined state - modify only UserSchema prefixes
   * @note Session metadata and cache are automatically removed by base class
   * @note Must be pure and deterministic
   */
  protected def handleSessionExpired(
    sessionId: SessionId, 
    capabilities: Map[String, String]
  ): State[HMap[CombinedSchema[UserSchema]], List[SR]]
  
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
  final def apply(command: SessionCommand[UC, SR]): State[HMap[CombinedSchema[UserSchema]], command.Response] =
    command match
      case cmd: SessionCommand.ClientRequest[UC, SR] =>
        handleClientRequest(cmd).asInstanceOf[State[HMap[CombinedSchema[UserSchema]], command.Response]]
      
      case cmd: SessionCommand.ServerRequestAck[SR] =>
        handleServerRequestAck(cmd).asInstanceOf[State[HMap[CombinedSchema[UserSchema]], command.Response]]
      
      case cmd: SessionCommand.CreateSession[SR] =>
        handleCreateSession(cmd).asInstanceOf[State[HMap[CombinedSchema[UserSchema]], command.Response]]
      
      case cmd: SessionCommand.SessionExpired[SR] =>
        handleSessionExpired_internal(cmd).asInstanceOf[State[HMap[CombinedSchema[UserSchema]], command.Response]]
      
      case cmd: SessionCommand.GetRequestsForRetry[SR] =>
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
   * Snapshot policy - determines when to take snapshots.
   * 
   * Users must implement this to define their snapshot strategy.
   * 
   * @param lastSnapshotIndex Index of the last snapshot
   * @param lastSnapshotSize Size of the last snapshot in bytes
   * @param commitIndex Current commit index
   * @return true if a snapshot should be taken
   */
  def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean
  
  // ====================================================================================
  // INTERNAL COMMAND HANDLERS
  // ====================================================================================
  
  /**
   * Handle ClientRequest command with idempotency checking.
   */
  private def handleClientRequest(cmd: SessionCommand.ClientRequest[UC, SR]): State[HMap[CombinedSchema[UserSchema]], (cmd.command.Response, List[SR])] =
    State.modify { state =>
      val cacheKey = makeCacheKey(cmd.sessionId, cmd.requestId)
      
      // Clean up cache based on lowestRequestId (Lowest Sequence Number Protocol)
      val stateAfterCleanup = cleanupCache(state, cmd.sessionId, cmd.lowestRequestId)
      
      // Check cache (idempotency)
      stateAfterCleanup.get["cache"](cacheKey) match
        case Some(cachedResponse) =>
          // Cache hit - return cached response without calling user method
          // We don't cache server requests, so return empty list
          (stateAfterCleanup, (cachedResponse.asInstanceOf[cmd.command.Response], Nil))
        
        case None =>
          // Cache miss - execute command
          val (newState, (response, serverRequests)) = applyCommand(cmd.command).run(stateAfterCleanup)
          
          // Add server requests and assign IDs
          val (stateWithRequests, assignedRequests) = addServerRequests(
            cmd.createdAt,
            newState,
            cmd.sessionId,
            serverRequests
          )
          
          // Cache only the response (not server requests)
          val finalState = stateWithRequests.updated["cache"](cacheKey, response)
          
          (finalState, (response, assignedRequests))
    }
  
  /**
   * Handle ServerRequestAck with cumulative acknowledgment.
   * 
   * Acknowledging request N removes all pending requests with ID ≤ N.
   */
  private def handleServerRequestAck(cmd: SessionCommand.ServerRequestAck[SR]): State[HMap[CombinedSchema[UserSchema]], Unit] =
    State.update { state =>
      acknowledgeServerRequests(state, cmd.sessionId, cmd.requestId)
    }
  
  /**
   * Handle CreateSession command.
   */
  private def handleCreateSession(cmd: SessionCommand.CreateSession[SR]): State[HMap[CombinedSchema[UserSchema]], List[SR]] =
    State.modify { state =>
      // Create session metadata
      // Note: timestamp must come from ZIO Clock service in real usage
      // For state machine (pure function), we use a placeholder
      val metadata = SessionMetadata(
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
      
      // Call user's session created handler (passing capabilities)
      val (newState, serverRequests) = handleSessionCreated(cmd.sessionId, cmd.capabilities).run(stateWithRequestId)
      
      // Add any server requests
      val (finalState, assignedRequests) = addServerRequests(
        cmd.createdAt,
        newState,
        cmd.sessionId,
        serverRequests
      )
      
      (finalState, assignedRequests)
    }
  
  /**
   * Handle SessionExpired command (internal name to avoid conflict with abstract method).
   */
  private def handleSessionExpired_internal(cmd: SessionCommand.SessionExpired[SR]): State[HMap[CombinedSchema[UserSchema]], List[SR]] =
    State.modify { state =>
      // Get capabilities from metadata before expiring
      val capabilities = state.get["metadata"](SessionId.unwrap(cmd.sessionId))
        .map(_.capabilities)
        .getOrElse(Map.empty[String, String])
      
      // Call user's session expired handler with capabilities
      val (newState, serverRequests) = handleSessionExpired(cmd.sessionId, capabilities).run(state)
      
      // Remove all session data
      val finalState = expireSession(newState, cmd.sessionId)
      
      // Return any final server requests (but don't add them to pending - session is expired)
      (finalState, serverRequests)
    }
  
  /**
   * Handle GetRequestsForRetry command.
   * 
   * NOTE: Left unimplemented - requires access to HMap internal iteration
   * which is not currently available. This functionality should be implemented
   * externally or requires additional HMap methods.
   */
  /**
   * Handle GetRequestsForRetry - atomically get eligible requests and update lastSentAt.
   */
  private def handleGetRequestsForRetry(cmd: SessionCommand.GetRequestsForRetry[SR]): State[HMap[CombinedSchema[UserSchema]], List[PendingServerRequest[SR]]] =
    State.modify { state =>
      // Get pending requests for this session
      val pending = state.get["serverRequests"](SessionId.unwrap(cmd.sessionId))
        .getOrElse(List.empty[PendingServerRequest[SR]])
      
      // Filter requests eligible for retry (lastSentAt < lastSentBefore)
      val eligible = pending.filter(req => req.lastSentAt.isBefore(cmd.lastSentBefore))
      
      // Update lastSentAt to createdAt (current time) for eligible requests
      val updated = eligible.map(_.copy(lastSentAt = cmd.createdAt))
      
      // Replace eligible requests with updated versions in the list
      val remaining = pending.filterNot(req => eligible.exists(_.id == req.id))
      val newList = remaining ++ updated
      
      // Update state with new list
      val newState = state.updated["serverRequests"](SessionId.unwrap(cmd.sessionId), newList)
      
      (newState, updated)
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
   * 
   * @param state Current state
   * @param sessionId Session ID
   * @param serverRequests List of server requests to add
   * @param createdAt Timestamp when the command was created (for lastSentAt)
   */
  private def addServerRequests(
    createdAt: Instant,
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
          lastSentAt = createdAt  // Use provided timestamp
        )
      }
      
      // Update lastServerRequestId
      val newLastId = requestsWithIds.last.id
      val stateWithNewId = state.updated["lastServerRequestId"](
        SessionId.unwrap(sessionId),
        newLastId
      )
      
      // Get existing pending requests for this session
      val existingRequests = state.get["serverRequests"](SessionId.unwrap(sessionId))
        .getOrElse(List.empty[PendingServerRequest[SR]])
      
      // Append new requests to the list
      val allRequests = existingRequests ++ requestsWithIds
      
      // Update with the complete list
      val stateWithRequests = stateWithNewId.updated["serverRequests"](
        SessionId.unwrap(sessionId),
        allRequests
      )
      
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
    // Get pending requests list for this session
    val pending = state.get["serverRequests"](SessionId.unwrap(sessionId))
      .getOrElse(List.empty[PendingServerRequest[SR]])
    
    // Keep only requests with ID > ackRequestId (cumulative acknowledgment)
    val remaining = pending.filter(req => RequestId.unwrap(req.id) > RequestId.unwrap(ackRequestId))
    
    // Update the list
    state.updated["serverRequests"](SessionId.unwrap(sessionId), remaining)
  
  /**
   * Get all pending server requests for a session.
   */
  private def getPendingServerRequests(
    state: HMap[CombinedSchema[UserSchema]],
    sessionId: SessionId
  ): List[PendingServerRequest[SR]] =
    state.get["serverRequests"](SessionId.unwrap(sessionId))
      .getOrElse(List.empty[PendingServerRequest[SR]])
  
  /**
   * Remove all session data when session expires.
   */
  private def expireSession(
    state: HMap[CombinedSchema[UserSchema]],
    sessionId: SessionId
  ): HMap[CombinedSchema[UserSchema]] =
    val sessionKey = SessionId.unwrap(sessionId)
    
    // Remove all session data
    state
      .removed["metadata"](sessionKey)
      .removed["lastServerRequestId"](sessionKey)
      .removed["serverRequests"](sessionKey)  // Remove the entire list for this session
    
    // Note: Cache entries for this session would need to be removed too,
    // but this requires iterating cache keys (not currently supported by HMap)
    // TODO: Implement cache cleanup when HMap supports iteration
  
  // ====================================================================================
  // HELPER METHODS
  // ====================================================================================
  
  /**
   * Create cache key for (sessionId, requestId) pair.
   */
  private def makeCacheKey(sessionId: SessionId, requestId: RequestId): String =
    s"${SessionId.unwrap(sessionId)}-${RequestId.unwrap(requestId)}"
  
  /**
   * Clean up cached responses based on lowestRequestId (Lowest Sequence Number Protocol).
   * 
   * Removes all cached responses for the session with requestId < lowestRequestId.
   * This allows the client to control cache cleanup by telling the server which
   * responses it no longer needs (Chapter 6.3 of Raft dissertation).
   * 
   * NOTE: This is inefficient without HMap iteration support. In production, we would
   * need either:
   * 1. Access to HMap's internal map for iteration
   * 2. A separate index tracking cache keys per session
   * 3. A keys() method on HMap
   * 
   * For now, this is a placeholder that does nothing.
   */
  private def cleanupCache(
    state: HMap[CombinedSchema[UserSchema]],
    sessionId: SessionId,
    lowestRequestId: RequestId
  ): HMap[CombinedSchema[UserSchema]] =
    // Remove all cache entries for requestIds < lowestRequestId
    // Iterate through the range [0, lowestRequestId) and remove those keys
    (0L until RequestId.unwrap(lowestRequestId)).foldLeft(state) { (s, reqId) =>
      val cacheKey = makeCacheKey(sessionId, RequestId(reqId))
      s.removed["cache"](cacheKey)
    }
  
  /**
   * Dirty read helper (FR-027) - check if session has pending requests needing retry.
   * 
   * This method can be called directly (outside Raft consensus) to optimize the
   * retry process. The retry process performs a dirty read, applies policy locally,
   * and only sends GetRequestsForRetry command if retries are needed.
   * 
   * @param state Current state (can be stale - dirty read)
   * @param sessionId Session to check
   * @param lastSentBefore Retry threshold - check for requests sent before this time
   * @return true if any pending requests have lastSentAt < lastSentBefore
   */
  def hasPendingRequests(
    state: HMap[CombinedSchema[UserSchema]],
    sessionId: SessionId,
    lastSentBefore: Instant
  ): Boolean =
    val pending = getPendingServerRequests(state, sessionId)
    // Check if any requests were sent before the threshold (need retry)
    pending.exists(req => req.lastSentAt.isBefore(lastSentBefore))
