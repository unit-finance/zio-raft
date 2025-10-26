package zio.raft.sessionstatemachine

import zio.{UIO, Chunk}
import zio.prelude.State
import zio.raft.{Command, HMap, StateMachine, Index}
import zio.raft.protocol.{SessionId, RequestId}
import zio.raft.protocol.RequestId.RequestIdSyntax
import zio.stream.Stream
import java.time.Instant

/** Abstract base class for session-aware state machines using the template pattern.
  *
  * This class implements Chapter 6.3 of the Raft dissertation (Implementing linearizable semantics) by providing
  * automatic session management, idempotency checking, and response caching.
  *
  * ## Template Pattern
  *
  * Users extend this class and implement 3 protected abstract methods:
  *   - `applyCommand`: Business logic for processing user commands (returns StateWriter)
  *   - `handleSessionCreated`: Session initialization logic (returns StateWriter)
  *   - `handleSessionExpired`: Session cleanup logic (returns StateWriter)
  *
  * The StateWriter monad combines State for state transitions with Writer for accumulating server-initiated requests.
  * Users call `.log(serverRequest)` to emit server requests instead of manually collecting them in tuples.
  *
  * The base class provides the final `apply` template method that orchestrates:
  *   - Idempotency checking via (sessionId, requestId) pairs
  *   - Response caching for duplicate requests
  *   - Server-initiated request management with cumulative acknowledgment
  *   - Session lifecycle coordination
  *   - State narrowing (users see only HMap[UserSchema])
  *   - State merging (user changes merged back to combined state)
  *
  * ## Type Parameters
  *
  * @tparam UC
  *   User command type (extends Command with dependent Response type)
  * @tparam R
  *   Response marker type - a sealed trait or type that encompasses all possible command responses. This enables proper
  *   serialization of cached responses. Each command's Response type must be a subtype of R (enforced via intersection
  *   type: command.Response & R). Example: sealed trait MyResponse; case class GetResponse(value: String) extends
  *   MyResponse
  * @tparam SR
  *   Server-initiated request payload type
  * @tparam UserSchema
  *   User-defined schema (tuple of (Prefix, KeyType, ValueType) triples)
  *
  * ## State Schema
  *
  * State is HMap[Schema] which concatenates:
  *   - SessionSchema: 4 fixed prefixes for session management
  *   - UserSchema: User-defined prefixes for business logic
  *
  * Users' abstract methods receive HMap[UserSchema] (narrowed), changes are merged back.
  *
  * ## Constitution Compliance
  *
  *   - Pure functions only (Principle I)
  *   - No exceptions - errors in response payload (Principle II)
  *   - Extends existing StateMachine trait (Principle III)
  *   - Uses ZIO Clock for timestamps (Principle IV)
  *
  * @see
  *   SessionCommand for commands this state machine accepts
  * @see
  *   SessionSchema for session management state structure
  * @see
  *   Schema for complete state structure (SessionSchema ++ UserSchema)
  * @see
  *   StateWriter for the state + writer monad used in abstract methods
  */
trait SessionStateMachine[UC <: Command, R, SR, UserSchema <: Tuple]
    extends StateMachine[HMap[Tuple.Concat[SessionSchema[R, SR], UserSchema]], SessionCommand[UC, SR]]:

  /** Type alias for the complete schema (SessionSchema[R, SR] ++ UserSchema).
    *
    * This allows using `HMap[Schema]` instead of `HMap[Tuple.Concat[SessionSchema[R, SR], UserSchema]]` throughout the
    * implementation, making signatures cleaner.
    */
  type Schema = Tuple.Concat[SessionSchema[R, SR], UserSchema]

  // ====================================================================================
  // ABSTRACT METHODS - Users must implement
  // ====================================================================================

  /** Apply a user command to the state.
    *
    * This method receives ONLY the user schema state (UserSchema), not session management state. Session management
    * prefixes are handled by the base class automatically.
    *
    * Use `.log(serverRequest)` to emit server-initiated requests. Server requests MUST be wrapped in
    * ServerRequestForSession to specify target sessionId. This allows sending requests to ANY session, not just the
    * current one!
    *
    * @param command
    *   The user command to process
    * @param createdAt
    *   The timestamp when the command was created (use this instead of adding to command)
    * @return
    *   StateWriter monad yielding the response (must be subtype of R) and accumulating server requests via log
    *
    * @note
    *   Must be pure and deterministic
    * @note
    *   Must NOT throw exceptions - return errors in response payload
    * @note
    *   Return type is intersection: command.Response & R This ensures command response is compatible with response
    *   marker type R for serialization
    * @note
    *   Use `.log(ServerRequestForSession(targetSessionId, payload))` to emit server requests
    *
    * @example
    *   {{{
    * sealed trait MyResponse
    * case class GetResponse(value: String) extends MyResponse
    *
    * case class GetCmd(key: String) extends Command:
    *   type Response = GetResponse  // Must be subtype of MyResponse!
    *
    * protected def applyCommand(cmd: UC, createdAt: Instant): StateWriter[HMap[Schema], ServerRequestForSession[SR], cmd.Response & MyResponse] =
    *   for {
    *     state <- StateWriter.get[HMap[Schema]]
    *     result = GetResponse(state.get["kv"](key))  // Response is GetResponse & MyResponse
    *     _ <- StateWriter.log(ServerRequestForSession(targetSessionId, notification))
    *   } yield result
    *   }}}
    */
  protected def applyCommand(
    command: UC,
    createdAt: Instant
  ): StateWriter[HMap[Schema], ServerRequestForSession[SR], command.Response & R]

  /** Handle session creation event.
    *
    * Called when a CreateSession command is processed. Use this to initialize any per-session state in your
    * user-defined prefixes.
    *
    * Use `.log(ServerRequestForSession(targetSessionId, payload))` to emit server requests.
    *
    * @param sessionId
    *   The newly created session ID
    * @param capabilities
    *   Client capabilities as key-value pairs
    * @return
    *   StateWriter monad accumulating server requests via log
    *
    * @note
    *   Receives complete schema state
    * @note
    *   Must be pure and deterministic
    * @note
    *   Server requests must specify target sessionId (can be different from current session)
    */
  protected def handleSessionCreated(
    sessionId: SessionId,
    capabilities: Map[String, String],
    createdAt: Instant
  ): StateWriter[HMap[Schema], ServerRequestForSession[SR], Unit]

  /** Handle session expiration event.
    *
    * Called when a SessionExpired command is processed. Use this to clean up any per-session state in your user-defined
    * prefixes.
    *
    * Use `.log(ServerRequestForSession(targetSessionId, payload))` to emit server requests. Server requests can target
    * ANY session, not just the expiring one!
    *
    * @param sessionId
    *   The expired session ID
    * @param capabilities
    *   The session capabilities (retrieved from metadata)
    * @return
    *   StateWriter monad accumulating final server requests via log
    *
    * @note
    *   Receives complete schema state
    * @note
    *   Session metadata and cache are automatically removed by base class
    * @note
    *   Must be pure and deterministic
    * @note
    *   Server requests can be for OTHER sessions (e.g., notify admin session)
    */
  protected def handleSessionExpired(
    sessionId: SessionId,
    capabilities: Map[String, String],
    createdAt: Instant
  ): StateWriter[HMap[Schema], ServerRequestForSession[SR], Unit]

  // ====================================================================================
  // StateMachine INTERFACE - Implemented by base class
  // ====================================================================================

  /** Empty state with no sessions or data.
    */
  def emptyState: HMap[Schema] =
    HMap.empty[Schema]

  /** Template method (FINAL) - orchestrates session management.
    *
    * This is the core of the template pattern. Users cannot override this method. It handles all session management
    * automatically and calls the abstract methods at the appropriate times.
    *
    * Flow for ClientRequest:
    *   1. Check cache for (sessionId, requestId) 2. If cached, return cached response 3. If not cached:
    *      a. Narrow state to HMap[UserSchema] b. Call applyCommand (abstract method) c. Merge user state changes back
    *         d. Add server requests and assign IDs e. Cache the response f. Return response and server requests
    *
    * @param command
    *   The session command to process
    * @return
    *   State transition with command-specific response
    *
    * @note
    *   \@unchecked is needed because SessionCommand is a GADT with dependent types. Each case class has its own
    *   Response type, and the compiler cannot verify exhaustiveness due to type erasure. This is safe because we handle
    *   all sealed trait cases explicitly.
    */
  final def apply(command: SessionCommand[UC, SR]): State[HMap[Schema], command.Response] =
    command match
      case cmd: SessionCommand.ClientRequest[UC, SR] @unchecked =>
        handleClientRequest(cmd).map(_.asResponseType(command, cmd))

      case cmd: SessionCommand.ServerRequestAck[SR] @unchecked =>
        handleServerRequestAck(cmd).map(_.asResponseType(command, cmd))

      case cmd: SessionCommand.CreateSession[SR] @unchecked =>
        handleCreateSession(cmd).map(_.asResponseType(command, cmd))

      case cmd: SessionCommand.SessionExpired[SR] @unchecked =>
        handleSessionExpired_internal(cmd).map(_.asResponseType(command, cmd))

      case cmd: SessionCommand.GetRequestsForRetry[SR] @unchecked =>
        handleGetRequestsForRetry(cmd).map(_.asResponseType(command, cmd))

  /** Snapshot behavior - users must implement.
    *
    * Users can implement custom serialization or use ScodecSerialization mixin trait.
    */
  def takeSnapshot(state: HMap[Schema]): Stream[Nothing, Byte]

  /** Restore behavior - users must implement.
    *
    * Users can implement custom deserialization or use ScodecSerialization mixin trait.
    */
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[Schema]]

  /** Snapshot policy - determines when to take snapshots.
    *
    * Users must implement this to define their snapshot strategy.
    *
    * @param lastSnapshotIndex
    *   Index of the last snapshot
    * @param lastSnapshotSize
    *   Size of the last snapshot in bytes
    * @param commitIndex
    *   Current commit index
    * @return
    *   true if a snapshot should be taken
    */
  def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean

  // ====================================================================================
  // INTERNAL COMMAND HANDLERS
  // ====================================================================================

  /** Handle ClientRequest command with idempotency checking and eviction detection.
    *
    * Implementation of Raft dissertation Chapter 6.3 session management protocol:
    *   1. Check cache for (sessionId, requestId) 2. If cache hit, return cached response 3. If cache miss, check if
    *      requestId <= highestLowestRequestIdSeen → response was evicted, return error 4. If cache miss + requestId >
    *      highestLowestRequestIdSeen, execute command and update highestLowestRequestIdSeen
    *
    * This correctly handles out-of-order requests. The lowestRequestId from the client tells us which responses have
    * been acknowledged (inclusive) and can be evicted. We only update highestLowestRequestIdSeen for requests we
    * actually process.
    */
  private def handleClientRequest(cmd: SessionCommand.ClientRequest[UC, SR])
    : State[HMap[Schema], Either[RequestError, (cmd.command.Response, List[ServerRequestEnvelope[SR]])]] =
    for
      highestLowestSeen <- getHighestLowestRequestIdSeen(cmd.sessionId)
      cachedOpt <- getCachedResponse((cmd.sessionId, cmd.requestId))
      result <- cachedOpt match
        case Some(cachedResponse) =>
          // Cache hit - return cached response without calling user method
          State.succeed(Right((cachedResponse.asInstanceOf[cmd.command.Response], Nil)))

        case None =>
          // Cache miss - check if response was evicted
          // If requestId <= highestLowestRequestIdSeen, client has acknowledged receiving this response
          if cmd.requestId.isLowerOrEqual(highestLowestSeen) then
            // Client said "I have responses for all requestIds <= highestLowest", so this was evicted
            State.succeed(Left(RequestError.ResponseEvicted(cmd.sessionId, cmd.requestId)))
          else
            // requestId >= highestLowestRequestIdSeen
            // This is a valid request (not yet acknowledged), execute the command
            for
              // Update highestLowestRequestIdSeen ONLY when actually executing a new request
              _ <- updateHighestLowestRequestIdSeen(cmd.sessionId, cmd.lowestRequestId)
              _ <- cleanupCache(cmd.sessionId, cmd.lowestRequestId)
              (serverRequestsLog, response) <- applyCommand(cmd.command, cmd.createdAt).withLog
              assignedRequests <- addServerRequests(cmd.createdAt, serverRequestsLog)
              _ <- cacheResponse((cmd.sessionId, cmd.requestId), response)
            yield Right((response, assignedRequests))
    yield result

  /** Get cached response for a composite key.
    */
  private def getCachedResponse(key: (SessionId, RequestId)): State[HMap[Schema], Option[Any]] =
    State.get.map(_.get["cache"](key))

  /** Cache a response at the given composite key.
    */
  private def cacheResponse(
    key: (SessionId, RequestId),
    response: R
  ): State[HMap[Schema], Unit] =
    State.update(_.updated["cache"](key, response))

  /** Get the highest lowestRequestId seen from the client for a session.
    *
    * This tracks the highest value of lowestRequestId that the client has sent, indicating which responses the client
    * has acknowledged receiving.
    *
    * Returns RequestId.zero if no lowestRequestId has been seen yet (no requests have been acknowledged).
    */
  private def getHighestLowestRequestIdSeen(sessionId: SessionId): State[HMap[Schema], RequestId] =
    State.get.map(_.get["highestLowestRequestIdSeen"](sessionId).getOrElse(RequestId.zero))

  /** Update the highest lowestRequestId seen from the client (only if lowestRequestId > current highest).
    *
    * The lowestRequestId from the client indicates "I have received all responses for requestIds <= this value
    * (inclusive)". We track the highest such value to detect evicted responses.
    */
  private def updateHighestLowestRequestIdSeen(
    sessionId: SessionId,
    lowestRequestId: RequestId
  ): State[HMap[Schema], Unit] =
    State.update(state =>
      state.get["highestLowestRequestIdSeen"](sessionId) match
        case Some(current) if !lowestRequestId.isGreaterThan(current) =>
          state // Don't update if lowestRequestId is not higher
        case _ =>
          state.updated["highestLowestRequestIdSeen"](sessionId, lowestRequestId)
    )

  /** Handle ServerRequestAck with cumulative acknowledgment.
    *
    * Acknowledging request N removes all pending requests with ID ≤ N.
    */
  private def handleServerRequestAck(cmd: SessionCommand.ServerRequestAck[SR]): State[HMap[Schema], Unit] =
    State.update { state =>
      // Find all requests for this session with requestId <= ackRequestId
      // Range is [from, until), so we use (sessionId, RequestId.zero) to (sessionId, ackRequestId + 1)
      val upperBoundExclusive = cmd.requestId.next
      val keysToRemove = state.range["serverRequests"](
        (cmd.sessionId, RequestId.zero),
        (cmd.sessionId, upperBoundExclusive)
      ).map((key, _) => key)

      // Remove all acknowledged requests in one efficient operation
      state.removedAll["serverRequests"](keysToRemove)
    }

  /** Handle CreateSession command.
    */
  private def handleCreateSession(cmd: SessionCommand.CreateSession[SR])
    : State[HMap[Schema], List[ServerRequestEnvelope[SR]]] =
    for
      _ <- createSessionMetadata(cmd.sessionId, cmd.capabilities, cmd.createdAt)
      (serverRequestsLog, _) <- handleSessionCreated(cmd.sessionId, cmd.capabilities, cmd.createdAt).withLog
      assignedRequests <- addServerRequests(cmd.createdAt, serverRequestsLog)
    yield assignedRequests

  /** Create session metadata entry.
    */
  private def createSessionMetadata(
    sessionId: SessionId,
    capabilities: Map[String, String],
    createdAt: Instant
  ): State[HMap[Schema], Unit] =
    State.update(_.updated["metadata"](sessionId, SessionMetadata(capabilities, createdAt)))

  /** Handle SessionExpired command (internal name to avoid conflict with abstract method).
    */
  private def handleSessionExpired_internal(cmd: SessionCommand.SessionExpired[SR])
    : State[HMap[Schema], List[ServerRequestEnvelope[SR]]] =
    for
      capabilities <- getSessionCapabilities(cmd.sessionId)
      (serverRequestsLog, _) <- handleSessionExpired(cmd.sessionId, capabilities, cmd.createdAt).withLog
      assignedRequests <- addServerRequests(cmd.createdAt, serverRequestsLog)
      _ <- expireSession(cmd.sessionId)
    yield assignedRequests

  /** Get session capabilities from metadata (returns empty if not found).
    */
  private def getSessionCapabilities(sessionId: SessionId): State[HMap[Schema], Map[String, String]] =
    State.get.map(
      _.get["metadata"](sessionId)
        .map(_.capabilities)
        .getOrElse(Map.empty[String, String])
    )

  /** Handle GetRequestsForRetry - atomically get eligible requests and update lastSentAt. Works on ALL sessions,
    * iterating through all pending requests.
    */
  private def handleGetRequestsForRetry(cmd: SessionCommand.GetRequestsForRetry[SR])
    : State[HMap[Schema], List[PendingServerRequest[SR]]] =
    State.modify { state =>
      // Use foldRight to collect updated requests and build new state
      // This avoids vars and the need to reverse at the end
      state.iterator["serverRequests"].foldRight((List.empty[PendingServerRequest[SR]], state)) {
        case (((sessionId, requestId), pending), (accumulated, currentState)) =>
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
  // SERVER REQUEST MANAGEMENT
  // ====================================================================================

  /** Add server requests to state and assign monotonically increasing IDs.
    *
    * Each request is stored with a composite key (SessionId, RequestId) for efficiency. sessionId comes from
    * ServerRequestForSession, not as a parameter!
    *
    * @param createdAt
    *   Timestamp when the command was created (for lastSentAt)
    * @param serverRequests
    *   Chunk of server requests (each contains target sessionId)
    */
  private def addServerRequests(
    createdAt: Instant,
    serverRequests: Chunk[ServerRequestForSession[SR]]
  ): State[HMap[Schema], List[ServerRequestEnvelope[SR]]] =
    if serverRequests.isEmpty then
      State.succeed(Nil)
    else
      State.modify { state =>
        // Group requests by sessionId for efficient ID assignment
        val groupedBySession = serverRequests.groupBy(_.sessionId)

        // Process each session's requests
        val (finalState, allEnvelopes) =
          groupedBySession.foldLeft((state, List.empty[ServerRequestEnvelope[SR]])) {
            case ((currentState, accumulated), (sessionId, sessionRequests)) =>
              // Get last assigned ID for this session
              val lastId = currentState.get["lastServerRequestId"](sessionId)
                .getOrElse(RequestId.zero)

              // Calculate new last ID: lastId + number of requests
              val newLastId = lastId.increaseBy(sessionRequests.length)
              val stateWithNewId = currentState.updated["lastServerRequestId"](
                sessionId,
                newLastId
              )

              // Add all requests with composite keys and create envelopes in one pass
              val (stateWithRequests, envelopes) =
                sessionRequests.zipWithIndex.foldLeft((stateWithNewId, List.empty[ServerRequestEnvelope[SR]])) {
                  case ((s, envs), (reqWithSession, index)) =>
                    val requestId = lastId.increaseBy(index + 1)
                    val pending = PendingServerRequest(
                      payload = reqWithSession.payload,
                      lastSentAt = createdAt
                    )
                    val updatedState = s.updated["serverRequests"]((sessionId, requestId), pending)
                    val envelope = ServerRequestEnvelope(
                      sessionId = sessionId,
                      requestId = requestId,
                      payload = reqWithSession.payload
                    )
                    (updatedState, envelope :: envs)
                }

              // Reverse to maintain order (since we prepended)
              val orderedEnvelopes = envelopes.reverse

              (stateWithRequests, accumulated ++ orderedEnvelopes)
          }

        (allEnvelopes, finalState)
      }

  /** Remove all session data when session expires.
    */
  private def expireSession(sessionId: SessionId): State[HMap[Schema], Unit] =
    State.update { state =>
      // Remove all cache entries for this session using range query
      val cacheKeysToRemove = state.range["cache"](
        (sessionId, RequestId.zero),
        (sessionId, RequestId.max)
      ).map((key, _) => key)

      // Remove all server requests for this session using range query
      val serverRequestKeysToRemove = state.range["serverRequests"](
        (sessionId, RequestId.zero),
        (sessionId, RequestId.max)
      ).map((key, _) => key)

      // Remove all session data in batch
      state
        .removedAll["cache"](cacheKeysToRemove)
        .removedAll["serverRequests"](serverRequestKeysToRemove)
        .removed["metadata"](sessionId)
        .removed["lastServerRequestId"](sessionId)
        .removed["highestLowestRequestIdSeen"](sessionId)
    }

  // ====================================================================================
  // HELPER METHODS - All return State[HMap[Schema], A] for composition
  // ====================================================================================

  /** Clean up cached responses based on lowestRequestId (Lowest Sequence Number Protocol).
    *
    * Removes all cached responses for the session with requestId <= lowestRequestId. This allows the client to control
    * cache cleanup by telling the server which responses it no longer needs (Chapter 6.3 of Raft dissertation).
    *
    * The client sends lowestRequestId to indicate "I have received all responses up to and including this ID".
    *
    * Uses range queries to efficiently find and remove old cache entries.
    */
  private def cleanupCache(
    sessionId: SessionId,
    lowestRequestId: RequestId
  ): State[HMap[Schema], Unit] =
    State.update { state =>
      // Use range to find all cache entries for this session with requestId <= lowestRequestId
      // Range is [from, until), so to include lowestRequestId, we use lowestRequestId.next as upper bound
      val keysToRemove = state.range["cache"](
        (sessionId, RequestId.zero),
        (sessionId, lowestRequestId.next)
      ).map((key, _) => key)

      // Remove all old cache entries in one efficient operation
      state.removedAll["cache"](keysToRemove)
    }

  /** Dirty read helper - check if ANY session has pending requests needing retry.
    *
    * This method can be called directly (outside Raft consensus) to optimize the retry process. The retry process
    * performs a dirty read, applies policy locally, and only sends GetRequestsForRetry command if retries are needed.
    *
    * Uses HMap.exists for efficient short-circuit evaluation - stops as soon as it finds ANY request (across all
    * sessions) that needs retry.
    *
    * @param state
    *   Current state (can be stale - dirty read)
    * @param lastSentBefore
    *   Retry threshold - check for requests sent before this time
    * @return
    *   true if any pending requests (across ALL sessions) have lastSentAt < lastSentBefore
    */
  def hasPendingRequests(
    state: HMap[Schema],
    lastSentBefore: Instant
  ): Boolean =
    state.exists["serverRequests"] { (_, pending) =>
      pending.lastSentAt.isBefore(lastSentBefore)
    }
end SessionStateMachine
