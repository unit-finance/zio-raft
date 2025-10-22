# Data Model: Session State Machine Framework

**Feature**: 002-session-state-machine  
**Date**: 2025-10-21  
**Status**: Design Complete

## Overview

This document defines the core data structures for the **session state machine framework**. This framework provides a base class that users extend to add session management to their state machines.

**Framework Purpose**: Provide session management with idempotency checking and response caching per Raft dissertation Chapter 6.3.

**Key Design (Template Pattern)**:
- State is `HMap[Schema]` - type-safe heterogeneous map with compile-time prefix validation
- Schema defines allowed prefixes and their value types at the type level
- Session data uses prefixes like "metadata", "cache", "serverRequests"
- User data uses custom prefixes defined in extended schema
- **SessionStateMachine** is an abstract base class that users extend
- Users implement 3 abstract methods for business logic (apply, onSessionCreated, onSessionExpired)
- Users handle their own serialization/deserialization (no scodec dependency in library)
- SessionStateMachine provides the session management skeleton, users fill in specifics
- All types are immutable following ZIO Raft constitutional principles

---

## Core State Machine Types

### StateMachine[S, A <: Command] (Existing)

**Purpose**: Base trait for all Raft state machines (already exists in `zio.raft` package)

```scala
// From raft/src/main/scala/zio/raft/StateMachine.scala
package zio.raft

import zio.UIO
import zio.stream.Stream
import zio.prelude.State

trait StateMachine[S, A <: Command]:
  def emptyState: S
  def apply(command: A): State[S, command.Response]
  def takeSnapshot(state: S): Stream[Nothing, Byte]
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[S]
  def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean
```

**Properties** (from existing interface):
- Uses ZIO Prelude's `State` monad for pure state transitions
- Commands extend `Command` trait with dependent `Response` type
- Snapshots use `Stream[Nothing, Byte]` for streaming serialization
- Pure functions (no side effects)

**Constitution Compliance**: Per Constitution III, we MUST use this existing interface.

---

### SessionStateMachine (Abstract Base Class with Template Pattern)

**Purpose**: Abstract base class providing session management that users extend with their business logic

First, define the session state schema:

```scala
package zio.raft.statemachine

import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

// Session schema defines the session management state structure
type SessionSchema = 
  ("metadata", SessionMetadata) *:          // Session metadata by sessionId key
  ("cache", Any) *:                         // Cached responses (Any = response type from user command)
  ("serverRequests", PendingServerRequest[?]) *:  // Pending server requests
  ("lastServerRequestId", RequestId) *:     // Last assigned server request ID
  EmptyTuple
```

Now the SessionStateMachine abstract base class:

```scala
import zio.raft.*
import zio.prelude.State
import java.time.Instant

// Combined schema merges session and user schemas
type CombinedSchema[UserSchema <: Tuple] = Tuple.Concat[SessionSchema, UserSchema]

/**
 * Abstract base class for session-aware state machines.
 * 
 * Users extend this class and implement 3 abstract methods for their business logic.
 * The base class handles session management, idempotency, caching, and server requests.
 * 
 * @tparam UC User command type (extends Command)
 * @tparam SR Server request type
 * @tparam UserSchema User's schema defining their state prefixes and types
 */
abstract class SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple]
  extends StateMachine[HMap[CombinedSchema[UserSchema]], SessionCommand[UC]]:
  
  // Template method - defines skeleton, calls abstract methods
  final def apply(command: SessionCommand[UC]): State[HMap[CombinedSchema[UserSchema]], command.Response] =
    State.modify { state =>
      command match
        case req: SessionCommand.ClientRequest[UC] @unchecked =>
          // 1. Check idempotency cache with type-safe prefix
          val cacheKey = s"${req.sessionId}/${req.requestId}"
          state.get["cache"](cacheKey) match
            case Some(cached) =>
              // Cache hit - return cached response without invoking user logic
              (state, cached.asInstanceOf[req.command.Response])
            
            case None =>
              // 2. Apply user command (calls abstract method!)
              // Narrow state to UserSchema for user logic
              val userState: HMap[UserSchema] = state.narrowTo[UserSchema]
              val (userStateAfter, (userResponse, serverReqs)) = applyCommand(req.command).run(userState)
              
              // 3. Merge user state changes back and cache response
              val stateWithUser = mergeUserState(state, userStateAfter)
              val stateWithCache = stateWithUser.updated["cache"](cacheKey, userResponse)
              
              // 4. Add server-initiated requests to pending
              val finalState = addServerRequests(stateWithCache, req.sessionId, serverReqs)
              
              (finalState, userResponse)
        
        case ack: SessionCommand.ServerRequestAck =>
          // Cumulative acknowledgment - remove all requests ≤ ack.requestId
          val newState = acknowledgeRequests(state, ack.sessionId, ack.requestId)
          (newState, ())
        
        case created: SessionCommand.SessionCreationConfirmed =>
          // Add session metadata with type-safe prefix
          val stateWithSession = state.updated["metadata"](
            created.sessionId.toString,
            SessionMetadata(created.sessionId, created.capabilities, now)
          )
          
          // Forward to user logic (calls abstract method!)
          val userState: HMap[UserSchema] = stateWithSession.narrowTo[UserSchema]
          val (userStateAfter, serverReqs) = handleSessionCreated(
            created.sessionId,
            created.capabilities
          ).run(userState)
          
          // Merge user state changes and add server requests
          val stateWithUser = mergeUserState(stateWithSession, userStateAfter)
          val finalState = addServerRequests(stateWithUser, created.sessionId, serverReqs)
          (finalState, ())
        
        case expired: SessionCommand.SessionExpired =>
          // Forward to user logic first (calls abstract method!)
          val userState: HMap[UserSchema] = state.narrowTo[UserSchema]
          val (userStateAfter, serverReqs) = handleSessionExpired(expired.sessionId).run(userState)
          
          // Merge user state changes and add server requests before removing session data
          val stateWithUser = mergeUserState(state, userStateAfter)
          val stateWithRequests = addServerRequests(stateWithUser, expired.sessionId, serverReqs)
          
          // Remove all session data (metadata, cache, pending requests)
          val finalState = expireSession(stateWithRequests, expired.sessionId)
          (finalState, ())
    }
  
  // ===== Abstract methods - users MUST implement these =====
  
  /**
   * Apply a user command to the state.
   * 
   * MUST be pure and deterministic.
   * MUST NOT throw exceptions (return errors in Response).
   * Command is already deserialized.
   * State is HMap[UserSchema] - only user prefixes are visible.
   * 
   * @param command User command to apply
   * @return State monad with (response, server-initiated requests)
   */
  protected def applyCommand(command: UC): State[HMap[UserSchema], (command.Response, List[SR])]
  
  /**
   * Handle session creation event.
   * 
   * Called when a new client session is created.
   * State is HMap[UserSchema] - initialize per-session data.
   * Can return server-initiated requests.
   * 
   * @param sessionId The newly created session ID
   * @param capabilities Client capabilities
   * @return State monad with list of server-initiated requests
   */
  protected def handleSessionCreated(
    sessionId: SessionId,
    capabilities: Map[String, String]
  ): State[HMap[UserSchema], List[SR]]
  
  /**
   * Handle session expiration event.
   * 
   * Called when a client session expires or is closed.
   * State is HMap[UserSchema] - cleanup per-session data.
   * Can return server-initiated requests.
   * 
   * @param sessionId The expired session ID
   * @return State monad with list of server-initiated requests
   */
  protected def handleSessionExpired(
    sessionId: SessionId
  ): State[HMap[UserSchema], List[SR]]
  
  // ===== Helper methods for session management =====
  
  // Helper to merge user state changes back into combined state
  private def mergeUserState(
    combined: HMap[CombinedSchema[UserSchema]], 
    userState: HMap[UserSchema]
  ): HMap[CombinedSchema[UserSchema]] =
    // Implementation would iterate through user state and update combined state
    // The HMap internal storage is still Map[String, Any], so we can access it
    HMap[CombinedSchema[UserSchema]](userState.m)
  
  // Snapshot methods - users must implement (typically using scodec or similar)
  // These are inherited from StateMachine trait and must be implemented
  // The library does NOT provide serialization - users choose their own approach
  
  /**
   * Serialize state to snapshot stream.
   * 
   * Users must implement this using their chosen serialization library.
   * The state contains both session data (with SessionSchema prefixes) and
   * user data (with UserSchema prefixes).
   * 
   * Recommendation: Use scodec or similar to encode the HMap's internal Map[String, Any]
   */
  def takeSnapshot(state: HMap[CombinedSchema[UserSchema]]): Stream[Nothing, Byte]
  
  /**
   * Restore state from snapshot stream.
   * 
   * Users must implement this using their chosen serialization library.
   * Must reconstruct the HMap with all session and user data.
   * 
   * Recommendation: Use scodec or similar to decode into Map[String, Any],
   * then wrap in HMap[CombinedSchema[UserSchema]]
   */
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[UserSchema]]]
  
  /**
   * Determine if a snapshot should be taken.
   * 
   * Default implementation: snapshot every 1000 commits.
   * Users can override for custom snapshot policy.
   */
  def shouldTakeSnapshot(
    lastSnapshotIndex: Index,
    lastSnapshotSize: Long,
    commitIndex: Index
  ): Boolean =
    (commitIndex.value - lastSnapshotIndex.value) > 1000
  
  /**
   * Check if there are any pending server requests that need retry.
   * 
   * This is a query method that can be called directly on the state
   * (dirty read - no Raft consensus needed) to determine if the
   * retry fiber should check for requests that need to be resent.
   * 
   * Only returns true if there are requests with lastSentAt before the threshold.
   * 
   * @param state The current HMap state
   * @param retryIfLastSentBefore Time threshold - only consider requests sent before this time
   * @return true if there are any pending server requests needing retry
   */
  def hasPendingRequests(
    state: HMap[CombinedSchema[UserSchema]], 
    retryIfLastSentBefore: Instant
  ): Boolean =
    // Access internal map to find pending requests
    state.m.collect {
      case (key, value: PendingServerRequest[?]) if key.startsWith("serverRequests\\") =>
        value.lastSentAt.isBefore(retryIfLastSentBefore)
    }.exists(identity)
```

**Key Points (Template Pattern)**:
- **Abstract base class** - users extend and implement 3 protected abstract methods
- Type parameters: UC <: Command (user command type), SR (server request type), UserSchema (user's schema)
- State is `HMap[CombinedSchema[UserSchema]]` - type-safe heterogeneous map!
- Schema combines SessionSchema and UserSchema at the type level
- Compile-time type checking for all prefix access (e.g., `state.get["cache"](key)`)
- UC is a Command subtype, so it has its own Response type
- Commands and responses already decoded (no serialization in library)
- **Template method** `apply` is final - defines session management skeleton
- Calls abstract methods `applyCommand`, `handleSessionCreated`, `handleSessionExpired`
- Handles idempotency checking before calling user's `applyCommand`
- Forwards session lifecycle events to user's handlers (can produce server requests)
- Uses schema narrowing to pass only user state to user methods (`state.narrowTo[UserSchema]`)
- **Users implement their own serialization** (takeSnapshot/restoreFromSnapshot)
- Provides `hasPendingRequests(state, retryIfLastSentBefore)` query method for dirty reads
- Type safety enforced at compile time - invalid prefixes or mismatched types cause compilation errors
- **No scodec dependency** - library is serialization-agnostic

---

## Session State Management

**Note**: State is stored in `HMap[CombinedSchema[UserSchema]]` with type-safe prefixes!

### Schema Structure

The `SessionSchema` defines session management state:
```scala
type SessionSchema = 
  ("metadata", SessionMetadata) *:          
  ("cache", Any) *:                         
  ("serverRequests", PendingServerRequest[?]) *:
  ("lastServerRequestId", RequestId) *:     
  EmptyTuple
```

User defines their own schema:
```scala
// Example: Counter state machine
type CounterSchema = 
  ("counter", Int) *:
  EmptyTuple
```

Combined schema merges both:
```scala
type CombinedSchema[UserSchema] = Tuple.Concat[SessionSchema, UserSchema]
// Results in: ("metadata", SessionMetadata) *: ("cache", Any) *: ... *: ("counter", Int) *: EmptyTuple
```

### State Access Patterns

**Session Metadata** (compile-time type-checked!):
```scala
val metadata: Option[SessionMetadata] = state.get["metadata"](sessionId.toString)
val updated = state.updated["metadata"](sessionId.toString, SessionMetadata(...))
```

**Response Cache** (type is Any to support different response types):
```scala
val cacheKey = s"$sessionId/$requestId"
val cached: Option[Any] = state.get["cache"](cacheKey)
val withCache = state.updated["cache"](cacheKey, response)
```

**Pending Server Requests** (type-safe PendingServerRequest):
```scala
val requestKey = s"$sessionId/$requestId"
val pending: Option[PendingServerRequest[?]] = state.get["serverRequests"](requestKey)
val withRequest = state.updated["serverRequests"](requestKey, PendingServerRequest(...))
```

**Last Assigned Request ID** (type-safe RequestId):
```scala
val lastId: Option[RequestId] = state.get["lastServerRequestId"](sessionId.toString)
val withNewId = state.updated["lastServerRequestId"](sessionId.toString, RequestId(42))
```

**User Data** (user-defined prefixes with their own types):
```scala
// User defined: ("counter", Int) *: EmptyTuple
val count: Option[Int] = state.get["counter"]("value")  // Compile-time type: Option[Int]
val updated = state.updated["counter"]("value", 42)     // Type-checked: must be Int
```

**Benefits of HMap**:
- Compile-time type safety: `state.get["metadata"](key)` returns `Option[SessionMetadata]`
- Invalid prefixes caught at compile time: `state.get["invalid"](key)` → compilation error
- Type mismatches caught at compile time: `state.updated["counter"]("x", "string")` → compilation error
- Same internal storage as Map[String, Any] (keys are "prefix\key" strings)
- No runtime overhead compared to Map[String, Any]

---

### SessionMetadata

**Purpose**: Per-session information

```scala
case class SessionMetadata(
  sessionId: SessionId,
  capabilities: Map[String, String],
  createdAt: Instant
)
```

**Fields**:
- `sessionId`: Unique session identifier
- `capabilities`: Client capabilities (from CreateSession)
- `createdAt`: When session was created

**Lifecycle**: Created on SessionCreationConfirmed, removed on SessionExpired

**Stored At**: Key `session/metadata/{sessionId}` in the Map[String, Any]

---

### Response Caching (No Separate Type!)

**Purpose**: Cache responses for idempotency checking

**Storage**: Response stored directly in Map at key `session/cache/{sessionId}/{requestId}`

**Type**: Response type R (from UserStateMachine[C, R, SR])

**Example**:
```scala
// Cache a response
val cacheKey = s"session/cache/$sessionId/$requestId"
val newState = state.updated(cacheKey, userResponse)  // userResponse type R

// Lookup cached response
state.get(cacheKey) match
  case Some(cached) => cached.asInstanceOf[R]  // Cache hit
  case None => // Cache miss, apply to user SM
```

**Note**:
- No ResponseCacheEntry wrapper needed
- Response stored as-is (decoded type R)
- No cachedAt timestamp (not needed initially per FR-015)
- Key format encodes both sessionId and requestId
- Type-safe via SessionCommand parameterization

**Size**: Response object size (user-dependent, unbounded initially per FR-015)

---

### PendingServerRequest[SR]

**Purpose**: Server-initiated request awaiting acknowledgment

```scala
case class PendingServerRequest[SR](
  id: RequestId,          // Monotonically increasing per session
  sessionId: SessionId,
  payload: SR,            // Server request payload (decoded, not ByteVector!)
  lastSentAt: Instant     // For retry logic (initially set when created, no Option)
)
```

**Fields**:
- `id`: Unique within session, assigned by session state machine
- `sessionId`: Target session
- `payload`: Server request data (type SR from UserStateMachine)
- `lastSentAt`: Last time sent to client (initially set to creation time, updated on retry)

**Note**:
- No `createdAt` - waste of memory, use `lastSentAt` instead
- `lastSentAt` is NOT Optional - always has a value (initially equals creation time)
- Payload type SR matches UserStateMachine type parameter

**Ordering**: Stored in HMap with compound key, sorted by `id` (ascending) for cumulative acknowledgment

**Stored At**: HMap with prefix "serverRequests" and key `{sessionId}/{requestId}`
```scala
state.get["serverRequests"](s"$sessionId/$requestId")  // Returns Option[PendingServerRequest[?]]
```

---

## Command Types

### SessionCommand[UC <: Command]

**Purpose**: Commands for SessionStateMachine (using dependent type pattern from Command trait)

```scala
sealed trait SessionCommand[UC <: Command] extends Command
  // Response type is defined by each case class

object SessionCommand:
  
  case class ClientRequest[UC <: Command](
    sessionId: SessionId,
    requestId: RequestId,
    command: UC  // Already deserialized! UC is a Command subtype
  ) extends SessionCommand[UC]:
    type Response = command.Response  // Response from the UserCommand itself
  
  case class ServerRequestAck(
    sessionId: SessionId,
    requestId: RequestId
  ) extends SessionCommand[Nothing]:
    type Response = Unit
  
  case class SessionCreationConfirmed(
    sessionId: SessionId,
    capabilities: Map[String, String]
  ) extends SessionCommand[Nothing]:
    type Response = Unit
  
  case class SessionExpired(
    sessionId: SessionId
  ) extends SessionCommand[Nothing]:
    type Response = Unit
  
  case class GetRequestsForRetry(
    retryIfLastSentBefore: Instant
  ) extends SessionCommand[Nothing]:
    type Response = List[PendingServerRequest[?]]
```

**Note**: 
- Commands contain decoded types, not ByteVector. Integration layer handles serialization.
- `UserCommand` (UC) is itself a Command subtype, so it has its own Response type
- ClientRequest's Response type comes from the UserCommand itself
- Non-ClientRequest commands use Nothing as the UC type parameter

---

### RaftAction (Integration Layer - Out of Scope)

**Purpose**: Events from client-server library (contains ByteVector payloads)

**Note**: User's integration code converts RaftAction → SessionCommand by deserializing payloads.

Example:
```scala
// Integration layer (user code)
raftAction match
  case RaftAction.ClientRequest(sessionId, requestId, payload) =>
    val decodedCommand = commandCodec.decode(payload).require.value
    SessionCommand.ClientRequest(sessionId, requestId, decodedCommand)
```

---

## Response Types

### StateMachineResult

**Purpose**: Output from state machine processing

```scala
case class StateMachineResult(
  response: Option[ClientResponse],           // To send via ServerAction.SendResponse
  serverRequests: List[ServerInitiatedRequest], // To send via ServerAction.SendServerRequest
  newState: ByteVector                        // Serialized combined state for snapshot
)
```

**Fields**:
- `response`: Response to send back to client (None for acks/admin commands)
- `serverRequests`: New server-initiated requests to send (empty list if none)
- `newState`: Updated state (library doesn't maintain state, returns it for user to persist)

---

### ClientResponse

**Purpose**: Response to client request

```scala
case class ClientResponse(
  sessionId: SessionId,
  requestId: RequestId,
  payload: ByteVector,
  isError: Boolean
)
```

**Note**: This type likely exists in client-server-protocol

---

### ServerInitiatedRequest

**Purpose**: Request to send from server to client

```scala
case class ServerInitiatedRequest(
  sessionId: SessionId,
  requestId: RequestId,  // Assigned by session state machine
  payload: ByteVector
)
```

**Note**: This type likely exists in client-server-protocol

---

## Snapshot Types

### SnapshotDictionary

**Purpose**: Combined state for both session and user state machines

```scala
type SnapshotDictionary = Map[String, ByteVector]
```

**Key Prefixes**:
- `"session/"` - Session state machine keys
- `"user/"` - User state machine keys

**Example**:
```scala
val snapshot: SnapshotDictionary = Map(
  "session/sessions/abc123" → encode(sessionMetadata),
  "session/responses/abc123/1" → encode(responseEntry),
  "session/serverRequests/abc123/5" → encode(pendingRequest),
  "session/lastServerRequestId/abc123" → encode(RequestId(10)),
  "user/counter" → encode(42)  // User state machine key
)
```

---

## Validation Rules

### From Functional Requirements

1. **FR-022**: Server request IDs start at 1 per session
   ```scala
   def assignRequestId(sessionId: SessionId): RequestId =
     lastServerRequestId.getOrElse(sessionId, RequestId(0)) + 1
   ```

2. **FR-020**: Cumulative acknowledgment removes all ≤ acknowledged ID
   ```scala
   def acknowledgeRequests(sessionId: SessionId, upTo: RequestId): SessionState =
     val updated = pendingServerRequests.get(sessionId).map { requests =>
       requests.filter(_.id > upTo)
     }.getOrElse(Nil)
     copy(pendingServerRequests = pendingServerRequests.updated(sessionId, updated))
   ```

3. **FR-014**: No exceptions - errors in response payload
   ```scala
   // User state machine contract
   def apply(state: State, command: Command): UIO[(State, Response, List[ServerRequest])]
   // ^ Cannot throw - return error in Response
   ```

4. **FR-024**: Drop pending requests when session expires
   ```scala
   def expireSession(sessionId: SessionId): SessionState =
     copy(
       sessions = sessions.removed(sessionId),
       responsesCache = responseCache.filterNot(_._1.sessionId == sessionId),
       pendingServerRequests = pendingServerRequests.removed(sessionId),
       lastServerRequestId = lastServerRequestId.removed(sessionId)
     )
   ```

---

## State Transitions

### Session Creation
```
∅ --[SessionCreationConfirmed]→ SessionMetadata(new session)
```

### Command Processing
```
State --[ClientRequest]→ 
  1. Check responseCache for (sessionId, requestId)
  2a. If found: return cached response (no state change)
  2b. If not found:
      - Apply to user state machine
      - Cache response
      - Add any server requests to pending
      - Return response
```

### Server Request Acknowledgment
```
State --[ServerRequestAck(sessionId, N)]→
  Remove all pending requests where id ≤ N for sessionId
```

### Session Expiration
```
SessionMetadata --[SessionExpired]→ ∅
(Also removes all responses and pending requests for session)
```

### Retry Query
```
State --[GetRequestsForRetry(threshold)]→
  1. Filter pending requests where lastSentAt.forall(_ < threshold)
  2. Update lastSentAt = now for filtered requests
  3. Return filtered requests
```

---

## Serialization Strategy

**Approach**: User-provided serialization (library is serialization-agnostic)

**HMap Serialization**:
- HMap internally stores data as `Map[String, Any]` with keys formatted as "prefix\key"
- Users must implement `takeSnapshot` and `restoreFromSnapshot` methods
- Users choose their own serialization library (scodec, protobuf, JSON, etc.)
- State contains both session data (SessionSchema prefixes) and user data (UserSchema prefixes)

**Example User Implementation** (using scodec):
```scala
class CounterStateMachine extends SessionStateMachine[CounterCmd, ServerReq, CounterSchema]:
  
  // User implements abstract methods
  protected def applyCommand(cmd: CounterCmd): State[HMap[CounterSchema], (cmd.Response, List[ServerReq])] = ???
  protected def handleSessionCreated(...): State[HMap[CounterSchema], List[ServerReq]] = ???
  protected def handleSessionExpired(...): State[HMap[CounterSchema], List[ServerReq]] = ???
  
  // User implements serialization using their chosen library
  def takeSnapshot(state: HMap[CombinedSchema[CounterSchema]]): Stream[Nothing, Byte] =
    // Access HMap's internal Map[String, Any]
    val entries = state.m.toList
    
    // Encode each entry based on prefix
    val encoded = entries.map { case (key, value) =>
      val Array(prefix, actualKey) = key.split("\\\\", 2)
      prefix match
        case "metadata" => encodeEntry(key, sessionMetadataCodec.encode(value.asInstanceOf[SessionMetadata]))
        case "cache" => encodeEntry(key, cacheCodec.encode(value))
        case "serverRequests" => encodeEntry(key, pendingRequestCodec.encode(value.asInstanceOf[PendingServerRequest[?]]))
        case "lastServerRequestId" => encodeEntry(key, requestIdCodec.encode(value.asInstanceOf[RequestId]))
        case "counter" => encodeEntry(key, intCodec.encode(value.asInstanceOf[Int]))  // User prefix
    }
    
    Stream.fromIterable(encodeAll(encoded).toByteArray)
  
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[CounterSchema]]] =
    stream.runCollect.map { bytes =>
      val decoded = decodeAll(ByteVector(bytes))
      val entries = decoded.map { case (key, bits) =>
        val Array(prefix, actualKey) = key.split("\\\\", 2)
        val value = prefix match
          case "metadata" => sessionMetadataCodec.decode(bits).require.value
          case "cache" => cacheCodec.decode(bits).require.value
          case "serverRequests" => pendingRequestCodec.decode(bits).require.value
          case "lastServerRequestId" => requestIdCodec.decode(bits).require.value
          case "counter" => intCodec.decode(bits).require.value
        (key, value)
      }.toMap
      HMap[CombinedSchema[CounterSchema]](entries)
    }
```

**Benefits**:
- **No library dependencies** on serialization frameworks
- Users choose best serialization for their needs (performance, size, compatibility)
- Library focuses on session management logic only
- Users can change serialization without updating library
- Simpler library with fewer dependencies
- Type safety from schema helps guide serialization implementation

---

## Size Estimates

### Per Session
- SessionMetadata: ~92 bytes (sessionId, capabilities map, createdAt - removed lastActivityAt)
- Last Request ID: ~8 bytes
- **Total per session base**: ~100 bytes (down from 108 - removed lastActivityAt)

### Per Cached Response
- Stored directly in Map at key (no wrapper type)
- Response payload: **unbounded** (user-dependent, type R)
- **Total per response**: Just the response object size

### Per Pending Server Request
- PendingServerRequest[SR] metadata: ~32 bytes (id, sessionId, lastSentAt)
- Payload: user-dependent (type SR)
- **Total per request**: 32 bytes + payload (down from 40 - removed createdAt)

### Overall
- 1000 sessions with 10 cached responses each: ~0.5 MB (excluding payloads)
- Growing unbounded without eviction protocol (documented limitation)

---

## Property Invariants

1. **Idempotency**: `∀ sessionId, requestId: state.get["cache"](s"$sessionId/$requestId") returns same response on repeated ClientRequest`

2. **Cumulative Ack**: `∀ sessionId, N: acknowledgeRequests(sessionId, N) removes all requests with id ≤ N`

3. **Monotonic IDs**: `∀ sessionId: state.get["lastServerRequestId"](sessionId.toString) ≥ max(pending request IDs for session)`

4. **Snapshot Fidelity**: `restoreFromSnapshot(takeSnapshot(state)) = state` (up to HMap internal representation)

5. **No Duplicate Request IDs**: All pending server requests for a session have unique IDs

6. **Schema Type Safety**: All state access is type-checked at compile time via the schema

7. **Prefix Isolation**: SessionSchema prefixes ("metadata", "cache", etc.) are distinct from UserSchema prefixes

---

## Summary

**Type-Safe Template Pattern with HMap**:

**Core Types** (just 3!):
1. `SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple]` - Abstract base class, users extend and implement 3 methods
2. `SessionMetadata` - Per-session info (3 fields)
3. `PendingServerRequest[SR]` - Awaiting ack (4 fields)

**Schema-Driven Design**:
- ✅ `SessionSchema` - Fixed schema for session management state (4 prefixes)
- ✅ `UserSchema` - User-defined schema with their own prefixes and types
- ✅ `CombinedSchema[UserSchema]` - Type-level concatenation of both schemas
- ✅ `HMap[CombinedSchema[UserSchema]]` - Type-safe heterogeneous map for state

**Template Pattern**:
- ✅ `SessionStateMachine` is abstract base class (not trait to compose with)
- ✅ Users extend and implement 3 protected methods: `applyCommand`, `handleSessionCreated`, `handleSessionExpired`
- ✅ Base class defines `apply` as final template method - calls user methods
- ✅ Users implement their own serialization (no scodec dependency in library)
- ✅ Library provides session management skeleton, users fill in business logic

**Removed Complexity**:
- ❌ Separate UserStateMachine trait (methods are abstract in SessionStateMachine)
- ❌ Composition (constructor taking UserStateMachine) - use inheritance instead
- ❌ Separate SessionState type (use HMap with SessionSchema prefixes)
- ❌ Separate CombinedState wrapper type (schema concatenation at type level)
- ❌ ResponseCacheEntry type (store response directly with "cache" prefix)
- ❌ ResponseCacheKey type (use string keys within prefix)
- ❌ Library-provided serialization (users implement their own)
- ❌ scodec dependency (library is serialization-agnostic)
- ❌ lastActivityAt from SessionMetadata
- ❌ cachedAt from response cache
- ❌ createdAt from PendingServerRequest (use lastSentAt)
- ❌ Option from lastSentAt (always has value)

**State**: `HMap[CombinedSchema[UserSchema]]` with compile-time type checking

**Key Benefits**:
1. **Compile-time safety**: Invalid prefixes or type mismatches → compilation errors
2. **Zero runtime overhead**: HMap uses same internal storage as Map[String, Any]
3. **Template pattern clarity**: Base class defines flow, users fill in specifics
4. **No serialization coupling**: Users choose their own serialization library
5. **Simpler library**: Fewer dependencies, focused on session management logic
6. **Schema evolution**: User can extend their schema without touching session code
7. **Type inference**: Return types automatically inferred from schema
8. **Refactoring safety**: Rename/remove prefixes caught by compiler

---

## Next Steps

- [x] Define ultra-simplified types
- [x] Document Map[String, Any] approach
- [x] Define state transitions  
- [x] Removed unnecessary types
- [ ] Create contract tests (Phase 1 next)
- [ ] Generate quickstart example

