# Data Model: Session State Machine Framework

**Feature**: 002-session-state-machine  
**Date**: 2025-10-21  
**Status**: Design Complete

## Overview

This document defines the core data structures for the **session state machine framework**. This is NOT about composing multiple state machines - it's about wrapping ONE user state machine with session management infrastructure.

**Framework Purpose**: Provide session management with idempotency checking and response caching per Raft dissertation Chapter 6.3.

**Key Design**:
- State is `Map[String, Any]` (single map for everything!)
- Session data uses "session/" key prefix
- User data uses their own keys (no prefix)
- UserStateMachine[C, R, SR] - just 3 methods
- SessionStateMachine takes UserStateMachine + ONE Codec[(String, Any)]
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

### UserStateMachine[UC <: Command, ServerRequest] (New Simplified Trait)

**Purpose**: Simplified interface for user-defined business logic (no serialization, no snapshots)

```scala
package zio.raft.statemachine

import zio.prelude.State
import zio.raft.protocol.SessionId
import zio.raft.Command

trait UserStateMachine[UC <: Command, SR]:
  /**
   * Apply a command to the current state.
   * 
   * MUST be pure and deterministic.
   * MUST NOT throw exceptions (return errors in Response).
   * Command is already deserialized and is a Command subtype (has its own Response type).
   * State is always Map[String, Any] - user manages their own keys.
   * 
   * @param command Command to apply (decoded, UC is a Command subtype)
   * @return State monad with (response, server-initiated requests)
   *         Response type comes from the command itself (command.Response)
   */
  def apply(command: UC): State[Map[String, Any], (command.Response, List[SR])]
  
  /**
   * Handle session creation event.
   * 
   * Called when a new client session is created.
   * State is Map[String, Any] - user can initialize per-session data.
   * Can return server-initiated requests.
   * 
   * @param sessionId The newly created session ID
   * @param capabilities Client capabilities
   * @return State monad with list of server-initiated requests
   */
  def onSessionCreated(
    sessionId: SessionId,
    capabilities: Map[String, String]
  ): State[Map[String, Any], List[SR]]
  
  /**
   * Handle session expiration event.
   * 
   * Called when a client session expires or is closed.
   * State is Map[String, Any] - user can cleanup per-session data.
   * Can return server-initiated requests.
   * 
   * @param sessionId The expired session ID
   * @return State monad with list of server-initiated requests
   */
  def onSessionExpired(
    sessionId: SessionId
  ): State[Map[String, Any], List[SR]]
```

**Note**: 
- Just 3 methods - ultra-simple!
- State always `Map[String, Any]` - no custom state type
- UC (UserCommand) is a Command subtype, so it has its own Response type
- Response type comes from the command itself (command.Response)
- No codec methods - SessionStateMachine takes ONE codec
- No snapshot methods - SessionStateMachine handles snapshots
- No emptyState - SessionStateMachine uses empty Map
- Works with decoded types only
- All methods can return server-initiated requests

---

### SessionStateMachine (Session Management Implementation)

**Purpose**: Session management state machine that wraps a UserStateMachine

```scala
package zio.raft.statemachine

import zio.raft.*
import zio.prelude.State
import scodec.Codec
import java.time.Instant

class SessionStateMachine[UC <: Command, ServerRequest](
  userSM: UserStateMachine[UC, ServerRequest],
  stateCodec: Codec[(String, Any)]  // ONE codec for all state serialization
) extends StateMachine[Map[String, Any], SessionCommand[UC]]:
  
  def emptyState: Map[String, Any] = Map.empty  // Just empty map!
  
  def apply(command: SessionCommand[UC]): State[Map[String, Any], command.Response] =
    State.modify { state =>
      command match
        case req: SessionCommand.ClientRequest[UC] @unchecked =>
          // 1. Check idempotency cache
          val cacheKey = s"session/cache/${req.sessionId}/${req.requestId}"
          state.get(cacheKey) match
            case Some(cached) =>
              // Cache hit - return cached response without invoking user SM
              (state, cached.asInstanceOf[req.command.Response])
            
            case None =>
              // 2. Apply to user SM (command already decoded!)
              val (stateAfterUser, (userResponse, serverReqs)) = userSM.apply(req.command).run(state)
              
              // 3. Cache response
              val stateWithCache = stateAfterUser.updated(cacheKey, userResponse)
              
              // 4. Add server-initiated requests to pending
              val finalState = addServerRequests(stateWithCache, req.sessionId, serverReqs)
              
              (finalState, userResponse)
        
        case ack: SessionCommand.ServerRequestAck =>
          // Cumulative acknowledgment - remove all requests ≤ ack.requestId
          val newState = acknowledgeRequests(state, ack.sessionId, ack.requestId)
          (newState, ())
        
        case created: SessionCommand.SessionCreationConfirmed =>
          // Add session metadata
          val stateWithSession = state.updated(
            s"session/metadata/${created.sessionId}",
            SessionMetadata(created.sessionId, created.capabilities, now)
          )
          
          // Forward to user SM (can return server requests!)
          val (stateAfterUser, serverReqs) = userSM.onSessionCreated(
            created.sessionId,
            created.capabilities
          ).run(stateWithSession)
          
          // Add server requests
          val finalState = addServerRequests(stateAfterUser, created.sessionId, serverReqs)
          (finalState, ())
        
        case expired: SessionCommand.SessionExpired =>
          // Forward to user SM first (can return server requests!)
          val (stateAfterUser, serverReqs) = userSM.onSessionExpired(expired.sessionId).run(state)
          
          // Add server requests before removing session data
          val stateWithRequests = addServerRequests(stateAfterUser, expired.sessionId, serverReqs)
          
          // Remove all session data (metadata, cache, pending requests)
          val finalState = expireSession(stateWithRequests, expired.sessionId)
          (finalState, ())
    }
  
  // Snapshot serialization using provided codec
  def takeSnapshot(state: Map[String, Any]): Stream[Nothing, Byte] =
    val entries = state.toList
    val encoded = entries.map { case (k, v) => 
      stateCodec.encode((k, v)).require  
    }
    Stream.fromIterable(encodeList(encoded).toByteArray)
  
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[Map[String, Any]] =
    stream.runCollect.map { bytes =>
      val encodedList = decodeList(ByteVector(bytes.toArray))
      encodedList.map { bits => 
        stateCodec.decode(bits).require.value 
      }.toMap
    }
  
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
   * @param state The current state map
   * @param retryIfLastSentBefore Time threshold - only consider requests sent before this time
   * @return true if there are any pending server requests needing retry
   */
  def hasPendingRequests(state: Map[String, Any], retryIfLastSentBefore: Instant): Boolean =
    state.collect {
      case (key, value: PendingServerRequest[?]) if key.startsWith("session/serverRequests/") =>
        value.lastSentAt.isBefore(retryIfLastSentBefore)
    }.exists(identity)
```

**Key Points**:
- Takes `UserStateMachine[UC, SR]` + `Codec[(String, Any)]` in constructor
- Type parameters: UC <: Command (user command type), SR (server request type)
- State is just `Map[String, Any]` - NO CombinedState!
- Session data uses "session/" key prefix
- User data uses their own keys (no prefix required)
- UC is a Command subtype, so it has its own Response type
- Commands and responses already decoded (no serialization in SM)
- Handles idempotency checking before delegating to user SM
- Forwards session lifecycle events to user SM (can produce server requests)
- Adds server-initiated requests from user SM to pending list
- Snapshot serialization using provided codec
- Provides `hasPendingRequests(state, retryIfLastSentBefore)` query method for dirty reads (retry logic optimization)

---

## Session State Management

**Note**: There is NO separate SessionState type! Everything is in the single `Map[String, Any]` with key prefixes.

### State Keys (in Map[String, Any])

**Session Metadata**:
```
session/metadata/{sessionId} → SessionMetadata
```

**Response Cache** (for idempotency):
```
session/cache/{sessionId}/{requestId} → Response (type R, decoded)
```

**Pending Server Requests**:
```
session/serverRequests/{sessionId}/{requestId} → PendingServerRequest[SR]
```

**Last Assigned Request ID**:
```
session/lastServerRequestId/{sessionId} → RequestId
```

**User Data** (no prefix - user picks keys):
```
counter → Int
users/{userId} → UserData
... whatever user wants ...
```

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
- Payload type SR matches UserStateMachine[C, R, SR] type parameter

**Ordering**: Stored in Map with compound key, sorted by `id` (ascending) for cumulative acknowledgment

**Stored At**: Key `session/serverRequests/{sessionId}/{requestId}` in the Map[String, Any]

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

**Note**: Serialization is handled in the **integration layer** (user code), NOT in the state machine.

**Approach**:
- State machine works with decoded types only
- User provides codecs when wiring to Raft/client-server
- Snapshot serialization implemented by user in integration layer

**Example** (user integration code):
```scala
// User provides serialization when integrating
def serializeState(combined: CombinedState[Int]): Stream[Nothing, Byte] =
  val dict = Map.newBuilder[String, ByteVector]
  
  // Serialize session state
  dict ++= serializeSessionState(combined.session)
  
  // Serialize user state (user provides codec)
  val userBytes = counterCodec.encode(combined.user).require
  dict += ("user/state" -> userBytes)
  
  Stream.fromIterable(serializeDict(dict.result()).toByteArray)
```

**Benefits**:
- State machine pure (no serialization concerns)
- User controls serialization format
- Can change codecs without modifying state machine

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

1. **Idempotency**: `∀ sessionId, requestId: state.get(s"session/cache/$sessionId/$requestId") returns same response on repeated ClientRequest`

2. **Cumulative Ack**: `∀ sessionId, N: acknowledgeRequests(sessionId, N) removes all requests with id ≤ N`

3. **Monotonic IDs**: `∀ sessionId: state.get(s"session/lastServerRequestId/$sessionId") ≥ max(pending request IDs for session)`

4. **Snapshot Fidelity**: `restoreFromSnapshot(takeSnapshot(state)) = state`

5. **No Duplicate Request IDs**: All pending server requests for a session have unique IDs

6. **Key Prefix Separation**: Session keys start with "session/", user keys don't

---

## Summary

**Ultra-Simplified Data Model**:

**Core Types** (just 4!):
1. `UserStateMachine[UC <: Command, SR]` - Trait with 3 methods (UC has its own Response type)
2. `SessionStateMachine[UC <: Command, SR]` - Wraps UserStateMachine, extends zio.raft.StateMachine
3. `SessionMetadata` - Per-session info (3 fields)
4. `PendingServerRequest[SR]` - Awaiting ack (4 fields)

**Removed**:
- ❌ SessionState type (just use Map with key prefixes)
- ❌ CombinedState type (just use Map)
- ❌ ResponseCacheEntry type (store response directly in Map)
- ❌ ResponseCacheKey type (use string keys)
- ❌ lastActivityAt from SessionMetadata
- ❌ cachedAt from response cache
- ❌ createdAt from PendingServerRequest (use lastSentAt)
- ❌ Option from lastSentAt (always has value)

**State**: Single `Map[String, Any]` with key prefixes for organization

---

## Next Steps

- [x] Define ultra-simplified types
- [x] Document Map[String, Any] approach
- [x] Define state transitions  
- [x] Removed unnecessary types
- [ ] Create contract tests (Phase 1 next)
- [ ] Generate quickstart example

