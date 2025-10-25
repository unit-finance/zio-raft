package zio.raft.sessionstatemachine

import zio.{UIO, Chunk}
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
 * - `applyCommand`: Business logic for processing user commands (returns StateWriter)
 * - `handleSessionCreated`: Session initialization logic (returns StateWriter)
 * - `handleSessionExpired`: Session cleanup logic (returns StateWriter)
 * 
 * The StateWriter monad combines State for state transitions with Writer for
 * accumulating server-initiated requests. Users call `.log(serverRequest)` to
 * emit server requests instead of manually collecting them in tuples.
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
 * @tparam UserSchema User-defined schema (tuple of (Prefix, KeyType, ValueType) triples)
 * 
 * ## State Schema
 * 
 * State is HMap[Schema] which concatenates:
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
 * @see Schema for complete state structure (SessionSchema ++ UserSchema)
 * @see StateWriter for the state + writer monad used in abstract methods
 */
abstract class SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple]
  extends StateMachine[HMap[Tuple.Concat[SessionSchema, UserSchema]], SessionCommand[UC, SR]]:
  
  /**
   * Type alias for the complete schema (SessionSchema ++ UserSchema).
   * 
   * This allows using `HMap[Schema]` instead of `HMap[Tuple.Concat[SessionSchema, UserSchema]]`
   * throughout the implementation, making signatures cleaner.
   */
  type Schema = Tuple.Concat[SessionSchema, UserSchema]
  
  // ====================================================================================
  // ABSTRACT METHODS - Users must implement
  // ====================================================================================
  
  /**
   * Apply a user command to the state.
   * 
   * This method receives ONLY the user schema state (UserSchema), not session management state.
   * Session management prefixes are handled by the base class automatically.
   * 
   * Use `.log(serverRequest)` to emit server-initiated requests.
   * Server requests MUST be wrapped in ServerRequestForSession to specify target sessionId.
   * This allows sending requests to ANY session, not just the current one!
   * 
   * @param command The user command to process
   * @param createdAt The timestamp when the command was created (use this instead of adding to command)
   * @return StateWriter monad yielding the response and accumulating server requests via log
   * 
   * @note Must be pure and deterministic
   * @note Must NOT throw exceptions - return errors in response payload
   * @note Only receives and modifies UserSchema state
   * @note Use `.log(ServerRequestForSession(targetSessionId, payload))` to emit server requests
   * 
   * @example
   * {{{
   * protected def applyCommand(command: UC, createdAt: Instant): StateWriter[HMap[Schema], ServerRequestForSession[SR], command.Response] =
   *   for {
   *     state <- StateWriter.get[HMap[Schema]]
   *     result = // ... compute result
   *     newState = state.updated["myPrefix"](key, value)
   *     _ <- StateWriter.set(newState)
   *     _ <- StateWriter.log(ServerRequestForSession(targetSessionId, MyRequest(...)))  // Can target any session!
   *   } yield CommandResponse(result)
   * }}}
   */
  protected def applyCommand(command: UC, createdAt: Instant): StateWriter[HMap[Schema], ServerRequestForSession[SR], command.Response]
  
  /**
   * Handle session creation event.
   * 
   * Called when a CreateSession command is processed. Use this to initialize
   * any per-session state in your user-defined prefixes.
   * 
   * Use `.log(ServerRequestForSession(targetSessionId, payload))` to emit server requests.
   * 
   * @param sessionId The newly created session ID
   * @param capabilities Client capabilities as key-value pairs
   * @return StateWriter monad accumulating server requests via log
   * 
   * @note Receives complete schema state
   * @note Must be pure and deterministic
   * @note Server requests must specify target sessionId (can be different from current session)
   */
  protected def handleSessionCreated(
    sessionId: SessionId,
    capabilities: Map[String, String],
    createdAt: Instant
  ): StateWriter[HMap[Schema], ServerRequestForSession[SR], Unit]
  
  /**
   * Handle session expiration event.
   * 
   * Called when a SessionExpired command is processed. Use this to clean up any
   * per-session state in your user-defined prefixes.
   * 
   * Use `.log(ServerRequestForSession(targetSessionId, payload))` to emit server requests.
   * Server requests can target ANY session, not just the expiring one!
   * 
   * @param sessionId The expired session ID
   * @param capabilities The session capabilities (retrieved from metadata)
   * @return StateWriter monad accumulating final server requests via log
   * 
   * @note Receives complete schema state
   * @note Session metadata and cache are automatically removed by base class
   * @note Must be pure and deterministic
   * @note Server requests can be for OTHER sessions (e.g., notify admin session)
   */
  protected def handleSessionExpired(
    sessionId: SessionId, 
    capabilities: Map[String, String],
    createdAt: Instant
  ): StateWriter[HMap[Schema], ServerRequestForSession[SR], Unit]
  
  // ====================================================================================
  // StateMachine INTERFACE - Implemented by base class
  // ====================================================================================
  
  /**
   * Empty state with no sessions or data.
   */
  def emptyState: HMap[Schema] = 
    HMap.empty[Schema]
  
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
  final def apply(command: SessionCommand[UC, SR]): State[HMap[Schema], command.Response] =
    command match
      case cmd: SessionCommand.ClientRequest[UC, SR] @unchecked =>
        handleClientRequest(cmd).asInstanceOf[State[HMap[Schema], command.Response]]
      
      case cmd: SessionCommand.ServerRequestAck[SR] @unchecked =>
        handleServerRequestAck(cmd).asInstanceOf[State[HMap[Schema], command.Response]]
      
      case cmd: SessionCommand.CreateSession[SR] @unchecked =>
        handleCreateSession(cmd).asInstanceOf[State[HMap[Schema], command.Response]]
      
      case cmd: SessionCommand.SessionExpired[SR] @unchecked =>
        handleSessionExpired_internal(cmd).asInstanceOf[State[HMap[Schema], command.Response]]
      
      case cmd: SessionCommand.GetRequestsForRetry[SR] @unchecked =>
        handleGetRequestsForRetry(cmd).asInstanceOf[State[HMap[Schema], command.Response]]
  
  /**
   * Default snapshot behavior - delegates to user.
   * 
   * Users must implement this in their concrete classes.
   */
  def takeSnapshot(state: HMap[Schema]): Stream[Nothing, Byte]
  
  /**
   * Default restore behavior - delegates to user.
   * 
   * Users must implement this in their concrete classes.
   */
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[Schema]]
  
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
  private def handleClientRequest(cmd: SessionCommand.ClientRequest[UC, SR]): State[HMap[Schema], (cmd.command.Response, List[ServerRequestWithContext[SR]])] =
    State.modify { state =>
      // Check cache (idempotency) using composite key
      state.get["cache"]((cmd.sessionId, cmd.requestId)) match
        case Some(cachedResponse) =>
          // Cache hit - return cached response without calling user method
          // No need to clean cache or update state
          ((cachedResponse.asInstanceOf[cmd.command.Response], Nil), state)
        
        case None =>
          // Cache miss - clean up cache based on lowestRequestId first
          val stateAfterCleanup = cleanupCache(state, cmd.sessionId, cmd.lowestRequestId)
          
          // Execute command (pass createdAt to avoid storing twice)
          val (serverRequestsLog, newState, response) = applyCommand(cmd.command, cmd.createdAt).runStateLog(stateAfterCleanup)
          
          // Add server requests and assign IDs (serverRequestsLog is Chunk, keep it as Chunk)
          val (stateWithRequests, assignedRequests) = addServerRequests(
            cmd.createdAt,
            newState,
            serverRequestsLog
          )
          
          // Cache the response using composite key
          val finalState = stateWithRequests.updated["cache"]((cmd.sessionId, cmd.requestId), response)
          
          ((response, assignedRequests), finalState)
    }
  
  /**
   * Handle ServerRequestAck with cumulative acknowledgment.
   * 
   * Acknowledging request N removes all pending requests with ID ≤ N.
   */
  private def handleServerRequestAck(cmd: SessionCommand.ServerRequestAck[SR]): State[HMap[Schema], Unit] =
    State.update { state =>
      acknowledgeServerRequests(state, cmd.sessionId, cmd.requestId)
    }
  
  /**
   * Handle CreateSession command.
   */
  private def handleCreateSession(cmd: SessionCommand.CreateSession[SR]): State[HMap[Schema], List[ServerRequestWithContext[SR]]] =
    State.modify { state =>
      // Create session metadata using createdAt from command
      val metadata = SessionMetadata(
        capabilities = cmd.capabilities,
        createdAt = cmd.createdAt
      )
      
      val stateWithMetadata = state.updated["metadata"](
        cmd.sessionId,
        metadata
      )
      
      // Initialize lastServerRequestId for all sessions that will receive server requests
      // This is done lazily per session in addServerRequests
      
      // Call user's session created handler (passing capabilities and createdAt)
      val (serverRequestsLog, newState, _) = handleSessionCreated(cmd.sessionId, cmd.capabilities, cmd.createdAt).runStateLog(stateWithMetadata)
      
      // Add any server requests (keep as Chunk, sessionId is inside ServerRequestForSession)
      val (finalState, assignedRequests) = addServerRequests(
        cmd.createdAt,
        newState,
        serverRequestsLog
      )
      
      (assignedRequests, finalState)
    }
  
  /**
   * Handle SessionExpired command (internal name to avoid conflict with abstract method).
   */
  private def handleSessionExpired_internal(cmd: SessionCommand.SessionExpired[SR]): State[HMap[Schema], List[ServerRequestWithContext[SR]]] =
    State.modify { state =>
      // Get capabilities from metadata before expiring
      val capabilities = state.get["metadata"](cmd.sessionId)
        .map(_.capabilities)
        .getOrElse(Map.empty[String, String])
      
      // Call user's session expired handler with capabilities and createdAt
      val (serverRequestsLog, stateWithUserChanges, _) = handleSessionExpired(cmd.sessionId, capabilities, cmd.createdAt).runStateLog(state)
      
      // Add server requests before removing session data (keep as Chunk - they may be for OTHER sessions!)
      val (stateWithRequests, assignedRequests) = addServerRequests(
        cmd.createdAt,
        stateWithUserChanges,
        serverRequestsLog
      )
      
      // Remove all session data for THIS session
      val finalState = expireSession(stateWithRequests, cmd.sessionId)
      
      // Return server requests - they may be for other sessions
      (assignedRequests, finalState)
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
   * Works on ALL sessions, iterating through all pending requests.
   */
  private def handleGetRequestsForRetry(cmd: SessionCommand.GetRequestsForRetry[SR]): State[HMap[Schema], List[PendingServerRequest[SR]]] =
    State.modify { state =>
      // Use foldRight to collect updated requests and build new state
      // This avoids vars and the need to reverse at the end
      state.iterator["serverRequests"].foldRight((List.empty[PendingServerRequest[SR]], state)) {
        case (((sessionId, requestId), pendingAny), (accumulated, currentState)) =>
          val pending = pendingAny.asInstanceOf[PendingServerRequest[SR]]
          
          // Check if this request needs retry (lastSentAt before threshold)
          if pending.lastSentAt.isBefore(cmd.lastSentBefore) then
            // Update lastSentAt and add to accumulated list
            val updatedReq = pending.copy(lastSentAt = cmd.createdAt)
            val updatedState = currentState.updated["serverRequests"]((sessionId, requestId), updatedReq)
            (updatedReq :: accumulated, updatedState)
          else
            (accumulated, currentState)
      }
    }
  
  // ====================================================================================
  // NOTE: State narrowing/merging removed for simplicity
  // User methods receive full HMap[Schema] and should only
  // modify their own UserSchema prefixes
  // ====================================================================================
  
  // ====================================================================================
  // SERVER REQUEST MANAGEMENT
  // ====================================================================================
  
  /**
   * Add server requests to state and assign monotonically increasing IDs.
   * 
   * Each request is stored with a composite key (SessionId, RequestId) for efficiency.
   * sessionId comes from ServerRequestForSession, not as a parameter!
   * 
   * @param createdAt Timestamp when the command was created (for lastSentAt)
   * @param state Current state
   * @param serverRequests Chunk of server requests (each contains target sessionId)
   */
  private def addServerRequests(
    createdAt: Instant,
    state: HMap[Schema],
    serverRequests: Chunk[ServerRequestForSession[SR]]
  ): (HMap[Schema], List[ServerRequestWithContext[SR]]) =
    if serverRequests.isEmpty then
      (state, Nil)
    else
      // Group requests by sessionId for efficient ID assignment
      val groupedBySession = serverRequests.groupBy(_.sessionId)
      
      // Process each session's requests
      val (finalState, allRequestsWithContext) = groupedBySession.foldLeft((state, List.empty[ServerRequestWithContext[SR]])) {
        case ((currentState, accumulated), (sessionId, sessionRequests)) =>
          // Get last assigned ID for this session
          val lastId = currentState.get["lastServerRequestId"](sessionId)
            .getOrElse(RequestId.zero)
          
          // Assign IDs starting from lastId + 1
          val requestsWithIds = sessionRequests.zipWithIndex.map { case (reqWithSession, index) =>
            val newId = RequestId(RequestId.unwrap(lastId) + index + 1)
            (sessionId, newId, PendingServerRequest(
              payload = reqWithSession.payload,
              lastSentAt = createdAt
            ))
          }
          
          // Update lastServerRequestId for this session
          val newLastId = requestsWithIds.last._2
          val stateWithNewId = currentState.updated["lastServerRequestId"](
            sessionId,
            newLastId
          )
          
          // Add all requests with composite keys
          val stateWithRequests = requestsWithIds.foldLeft(stateWithNewId) { 
            case (s, (sid, requestId, pending)) =>
              s.updated["serverRequests"]((sid, requestId), pending)
          }
          
          // Create requests with context
          val requestsWithContext = requestsWithIds.map { case (sid, requestId, pending) =>
            ServerRequestWithContext(
              sessionId = sid,
              requestId = requestId,
              payload = pending.payload
            )
          }.toList
          
          (stateWithRequests, accumulated ++ requestsWithContext)
      }
      
      (finalState, allRequestsWithContext)
  
  /**
   * Acknowledge server requests with cumulative acknowledgment.
   * 
   * Acknowledging request N removes all pending requests with ID ≤ N.
   * Uses range queries to efficiently find and remove acknowledged requests.
   */
  private def acknowledgeServerRequests(
    state: HMap[Schema],
    sessionId: SessionId,
    ackRequestId: RequestId
  ): HMap[Schema] =
    // Find all requests for this session with requestId <= ackRequestId
    // Range is [from, until), so we use (sessionId, RequestId.zero) to (sessionId, ackRequestId + 1)
    val nextRequestId = RequestId(RequestId.unwrap(ackRequestId) + 1)
    val keysToRemove = state.range["serverRequests"](
      (sessionId, RequestId.zero),
      (sessionId, nextRequestId)
    ).map(_._1)
    
    // Remove all acknowledged requests in one efficient operation
    state.removedAll["serverRequests"](keysToRemove)
  
  /**
   * Remove all session data when session expires.
   */
  private def expireSession(
    state: HMap[Schema],
    sessionId: SessionId
  ): HMap[Schema] =
    // Remove all cache entries for this session using range query
    val cacheKeysToRemove = state.range["cache"](
      (sessionId, RequestId.zero),
      (sessionId, RequestId.max)
    ).map(_._1)
    
    // Remove all server requests for this session using range query
    val serverRequestKeysToRemove = state.range["serverRequests"](
      (sessionId, RequestId.zero),
      (sessionId, RequestId.max)
    ).map(_._1)
    
    // Remove all session data in batch
    state
      .removedAll["cache"](cacheKeysToRemove)
      .removedAll["serverRequests"](serverRequestKeysToRemove)
      .removed["metadata"](sessionId)
      .removed["lastServerRequestId"](sessionId)
  
  // ====================================================================================
  // HELPER METHODS
  // ====================================================================================
  
  /**
   * Clean up cached responses based on lowestRequestId (Lowest Sequence Number Protocol).
   * 
   * Removes all cached responses for the session with requestId < lowestRequestId.
   * This allows the client to control cache cleanup by telling the server which
   * responses it no longer needs (Chapter 6.3 of Raft dissertation).
   * 
   * Uses range queries to efficiently find and remove old cache entries.
   */
  private def cleanupCache(
    state: HMap[Schema],
    sessionId: SessionId,
    lowestRequestId: RequestId
  ): HMap[Schema] =
    // Use range to find all cache entries for this session with requestId < lowestRequestId
    // Range is [from, until), so we use (sessionId, RequestId.zero) to (sessionId, lowestRequestId)
    val keysToRemove = state.range["cache"](
      (sessionId, RequestId.zero),
      (sessionId, lowestRequestId)
    ).map(_._1)
    
    // Remove all old cache entries in one efficient operation
    state.removedAll["cache"](keysToRemove)
  
  /**
   * Dirty read helper (FR-027) - check if ANY session has pending requests needing retry.
   * 
   * This method can be called directly (outside Raft consensus) to optimize the
   * retry process. The retry process performs a dirty read, applies policy locally,
   * and only sends GetRequestsForRetry command if retries are needed.
   * 
   * Uses HMap.exists for efficient short-circuit evaluation - stops as soon
   * as it finds ANY request (across all sessions) that needs retry.
   * 
   * @param state Current state (can be stale - dirty read)
   * @param lastSentBefore Retry threshold - check for requests sent before this time
   * @return true if any pending requests (across ALL sessions) have lastSentAt < lastSentBefore
   */
  def hasPendingRequests(
    state: HMap[Schema],
    lastSentBefore: Instant
  ): Boolean =
    state.exists["serverRequests"] { (_, pending) =>
      pending.lastSentAt.isBefore(lastSentBefore)
    }
