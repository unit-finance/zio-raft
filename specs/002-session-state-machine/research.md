# Research: Session State Machine Framework

**Feature**: 002-session-state-machine  
**Date**: 2025-10-21  
**Status**: Complete

## Overview

This document consolidates research and architectural decisions for implementing a session state machine framework that provides linearizable semantics and exactly-once command execution by wrapping user-defined state machines with session management, idempotency checking, and response caching (per Raft dissertation Chapter 6.3).

---

## Key Architectural Decisions

### 1. Shared Dictionary for Snapshots

**Decision**: Use `Map[String, ByteVector]` with key prefixes for combined snapshots

**Rationale**:
- Atomic snapshot capture of both session state and user state
- Session state uses "session/" prefix
- User state uses "user/" prefix  
- Avoids separate snapshot mechanisms
- Aligns with Raft's single-snapshot model

**Alternatives Considered**:
- Separate snapshots: Rejected - complicates restoration order and atomicity
- Nested structures: Rejected - harder to serialize/deserialize generically

**Implementation**: Pass single dictionary to both state machines during snapshot/restore

---

### 2. State Machine Purity (No Side Effects)

**Decision**: State machine is pure function: `(State, RaftAction) => UIO[(State, Response)]`

**Rationale**:
- Aligns with Raft dissertation's deterministic state machine model
- User cannot introduce bugs via side effects
- Testable without mocking
- Errors modeled in response payload (FR-014: no exceptions)

**Alternatives Considered**:
- Allowing ZIO effects: Rejected - breaks determinism, hard to test
- Exception-based errors: Rejected - violates Constitution II

**Implementation**: 
- User state machines return `(newState, response, Option[List[ServerRequest]])`
- Session state machine wraps user SM and adds idempotency layer

---

### 3. Cumulative Acknowledgment for Server-Initiated Requests

**Decision**: When acknowledging request ID N, remove all requests with ID ≤ N

**Rationale**:
- Aligns with TCP ACK semantics
- Enables future protocol optimization (piggyback last ID only)
- Works correctly with current protocol (one ack per request)
- Simplifies implementation (single remove operation)
- Raft ordering guarantees all lower IDs were delivered first

**Alternatives Considered**:
- Individual acknowledgment only: Rejected - requires future protocol change if optimizing
- Out-of-order acks: Rejected - cumulative makes order irrelevant

**Implementation**: `pendingRequests.filter(_.id > acknowledgedId)`

---

### 4. Dirty Read Optimization for Retries

**Decision**: External process performs non-linearizable read before sending GetRequestsForRetry command

**Rationale**:
- Avoids unnecessary Raft log entries when no retries needed
- Safe because command response is authoritative (dirty read just hint)
- Process discards dirty data after decision to skip/send command
- Worst case: one missed retry cycle if stale read

**Alternatives Considered**:
- Always send command: Rejected - creates unnecessary log growth during idle periods
- Maintain external counter: Rejected - adds complexity, can get out of sync

**Implementation**:
```scala
// Dirty read (no ZIO effect needed - direct state access)
val retryThreshold = Instant.now().minusSeconds(retryInterval)
val mayNeedRetry = stateMachine.hasPendingRequests(state, retryThreshold)
if (mayNeedRetry) {
  // Send authoritative command through Raft
  sendCommand(GetRequestsForRetry(retryThreshold))
}
```

---

### 5. HMap Schema Strategy

**Decision**: Use type-safe HMap with schema-defined prefixes instead of string prefixes

**Rationale**:
- **Compile-time safety**: Invalid prefixes or type mismatches caught at compile time
- **Type inference**: state.get["metadata"](key) automatically returns Option[SessionMetadata]
- **Zero runtime overhead**: HMap uses same Map[String, Any] internally with "prefix\key" format
- **Schema composition**: CombinedSchema = Tuple.Concat[SessionSchema, UserSchema] at type level
- **Refactoring safety**: Prefix changes caught by compiler

**Schema Definition**:
```scala
// Session management prefixes (library-defined)
type SessionSchema = 
  ("metadata", SessionMetadata) *:
  ("cache", Any) *:
  ("serverRequests", PendingServerRequest[?]) *:
  ("lastServerRequestId", RequestId) *:
  EmptyTuple

// User defines their schema
type KVSchema = ("kv", Map[String, String]) *: EmptyTuple

// Combined at type level
type CombinedSchema[U <: Tuple] = Tuple.Concat[SessionSchema, U]
```

**Alternatives Considered**:
- String prefixes with Map[String, Any]: Rejected - no compile-time safety
- Separate state objects: Rejected - harder to snapshot atomically

---

### 6. Template Pattern with Abstract Base Class

**Decision**: SessionStateMachine is abstract base class that users extend (template pattern), not composition via constructor

**Rationale**:
- **Simpler API**: No separate UserStateMachine trait - just extend one class
- **Constitution III**: Extends existing `zio.raft.StateMachine` trait
- **Framework Control**: Base class ensures session management is always correct
- **User Focus**: Users implement only 3 protected methods for business logic
- **Type Safety**: HMap with compile-time schema validation
- **No Serialization Dependency**: Users implement serialization, library stays agnostic

**Architecture**:
```scala
// SessionStateMachine is abstract base class
abstract class SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple]
  extends StateMachine[HMap[CombinedSchema[UserSchema]], SessionCommand[UC]]:
  
  // Final template method - orchestrates session management
  final def apply(command: SessionCommand[UC]): State[HMap[CombinedSchema[UserSchema]], command.Response] =
    State.modify { state =>
      command match
        case req: SessionCommand.ClientRequest[UC] @unchecked =>
          // Check cache with type-safe prefix
          state.get["cache"](s"${req.sessionId}/${req.requestId}") match
            case Some(cached) => (state, cached.asInstanceOf[req.command.Response])
            case None =>
              // Narrow to UserSchema and call abstract method
              val userState: HMap[UserSchema] = state.narrowTo[UserSchema]
              val (userStateAfter, (response, serverReqs)) = applyCommand(req.command).run(userState)
              
              // Merge back and cache
              val stateWithUser = mergeUserState(state, userStateAfter)
              val stateWithCache = stateWithUser.updated["cache"](cacheKey, response)
              (addServerRequests(stateWithCache, req.sessionId, serverReqs), response)
        
        case created: SessionCommand.SessionCreationConfirmed =>
          // Add metadata, narrow state, call abstract method
          val userState: HMap[UserSchema] = state.narrowTo[UserSchema]
          val (userStateAfter, serverReqs) = handleSessionCreated(created.sessionId, created.capabilities).run(userState)
          (mergeAndAddRequests(...), ())
        
        case expired: SessionCommand.SessionExpired =>
          // Call abstract method, then cleanup session data
          val userState: HMap[UserSchema] = state.narrowTo[UserSchema]
          val (userStateAfter, serverReqs) = handleSessionExpired(expired.sessionId).run(userState)
          (cleanupSession(...), ())
    }
  
  // Abstract methods - users implement
  protected def applyCommand(command: UC): State[HMap[UserSchema], (command.Response, List[SR])]
  protected def handleSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[HMap[UserSchema], List[SR]]
  protected def handleSessionExpired(sessionId: SessionId): State[HMap[UserSchema], List[SR]]
  
  // Users implement serialization
  def takeSnapshot(state: HMap[CombinedSchema[UserSchema]]): Stream[Nothing, Byte]
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[UserSchema]]]
```

**Benefits**:
- User extends one class, implements 3 methods + serialization
- Template method is final - framework controls session management flow
- Type-safe state via HMap with compile-time checking
- State narrowing: users see only HMap[UserSchema], not session state
- No serialization library dependency - users choose their own
- Clear separation: base class = session management, user methods = business logic
- Serialization responsibility clear (user implements, not library)

---

### 7. No Modifications to Client-Server Library

**Decision**: State machine library does NOT modify client-server-server module

**Rationale**:
- Preserves Constitution III (existing code preservation)
- Creates clean separation: state machine logic vs transport logic
- Users responsible for wiring events (clear boundaries)
- Easier to test state machine in isolation

**Integration Pattern**:
```scala
// User code (NOT in state machine library)
raftActionsStream
  .mapZIO { action =>
    stateMachine.apply(action).flatMap { case (newState, response, serverReqs) =>
      for {
        _ <- stateMachine.set(newState)
        _ <- sendResponse(response) // via ServerAction.SendResponse
        _ <- ZIO.foreachDiscard(serverReqs)(sendServerRequest) // via ServerAction.SendServerRequest
      } yield ()
    }
  }
```

---

### 8. Idempotency Cache Management

**Decision**: No size limits initially; document future need for lowest-sequence-number protocol

**Rationale**:
- Simplifies initial implementation (FR-015)
- Known gap documented in spec (Feature #2)
- When implemented, client will send `lowestPendingSequenceNumber` field
- Server can then deterministically evict responses

**Alternatives Considered**:
- Time-based eviction: Rejected - non-deterministic
- LRU eviction: Rejected - can violate exactly-once if client retries evicted request

**Future Implementation**: See spec "Known Gaps in Client-Server Implementation" section

---

### 9. Server-Initiated Request ID Assignment

**Decision**: Monotonically increasing IDs starting at 1 per session, tracked even when list empty

**Rationale**:
- Simple to implement (counter per session)
- Enables cumulative acknowledgment
- Persisted in snapshot for restart safety
- No risk of ID collisions across restarts

**Implementation**:
```scala
case class SessionState(
  lastServerRequestId: RequestId, // Persisted even when pendingRequests empty
  pendingRequests: List[ServerRequest] // Sorted by ID
)

def assignRequest(req: ServerRequest, sessionId: SessionId): SessionState = {
  val nextId = lastServerRequestId + 1
  copy(
    lastServerRequestId = nextId,
    pendingRequests = pendingRequests :+ req.copy(id = nextId)
  )
}
```

---

## Serialization Strategy

**Decision**: Library has NO serialization dependencies - users implement their own

**Rationale**:
- **Simpler Library**: Fewer dependencies, focused on session management logic only
- **User Flexibility**: Users choose best serialization for their needs (scodec, protobuf, JSON, etc.)
- **No Coupling**: Library doesn't force specific serialization approach
- **Clear Responsibility**: Users implement takeSnapshot/restoreFromSnapshot methods

**User Implementation Approach**:
Users extend SessionStateMachine and implement serialization methods:
- Access HMap's internal Map[String, Any] via `.m` property
- Serialize each entry based on its prefix (schema-aware)
- Can use any serialization library (scodec recommended for type safety)

**Example Libraries Users Might Choose**:
- scodec: Type-safe binary (recommended, already in project)
- Protocol Buffers: Cross-language compatibility
- JSON: Human-readable, debugging
- Custom binary: Performance-optimized

**No Library Dependency**: Library code has NO imports of serialization frameworks

---

## Testing Strategy

### Unit Tests (ZIO Test)
- Session state machine logic
- User state machine composition
- Idempotency checking
- Cumulative acknowledgment
- Snapshot serialization/deserialization

### Property-Based Tests
- **Idempotency Invariant**: Same (sessionId, requestId) always returns same response
- **Cumulative Ack Invariant**: Ack N removes all ≤ N, regardless of order
- **Snapshot Invariant**: Restore(Snapshot(state)) = state

### Integration Tests
- Full flow: RaftAction → State Machine → Response
- Server-initiated requests lifecycle
- Retry process with dirty reads
- Snapshot + restore with both session and user state

---

## Module Structure

```
session-state-machine/
├── src/
│   ├── main/
│   │   └── scala/
│   │       └── zio/
│   │           └── raft/
│   │               └── sessionstatemachine/
│   │                   ├── SessionStateMachine.scala     # Abstract base class (template pattern)
│   │                   ├── SessionCommand.scala          # Command ADT
│   │                   ├── SessionMetadata.scala         # Session info
│   │                   ├── PendingServerRequest.scala    # Server request tracking
│   │                   └── package.scala                 # SessionSchema, CombinedSchema types
│   └── test/
│       └── scala/
│           └── zio/
│               └── raft/
│                   └── sessionstatemachine/
│                       ├── SessionStateMachineTemplateSpec.scala
│                       ├── IdempotencySpec.scala
│                       ├── CumulativeAckSpec.scala
│                       ├── StateNarrowingSpec.scala
│                       └── InvariantSpec.scala
```

---

## Dependencies

**Required**:
- ZIO 2.1+ (already in project)
- ZIO Prelude (already in project - for State monad)
- Core raft module (for HMap, Command, StateMachine trait)

**No New Dependencies Needed** - Library is serialization-agnostic (no scodec dependency)

---

## Performance Considerations

### Idempotency Check: O(1)
- Map lookup for (sessionId, requestId) → response
- No iteration needed

### Cumulative Ack: O(n) where n = pending requests for session
- Filter operation on list
- Typically small n (< 100 requests pending)
- Can optimize to O(k) by tracking lowest pending ID

### Snapshot Size
- Session state: ~190 bytes per session (from spec analysis)
- Response cache: unbounded initially (documented limitation)
- Server requests: ~200 bytes per pending request
- User state: depends on user SM (outside control)

### Dirty Read Overhead
- Direct memory access (no Raft consensus)
- Negligible compared to Raft log append
- Saves log entries when nothing needs retry

---

## Risk Mitigation

### Risk: Response Cache Unbounded Growth
**Mitigation**: Documented in spec as known gap (Feature #2); will implement lowest-sequence-number protocol in follow-up

### Risk: User State Machine Complexity
**Mitigation**: Keep interface simple (pure function); provide examples in quickstart

### Risk: Snapshot Corruption
**Mitigation**: scodec provides version checking; property tests verify snapshot invariants

### Risk: Dirty Read Staleness
**Mitigation**: Safe by design - command response is authoritative; worst case is one missed retry cycle

---

## References

1. **Raft Dissertation Chapter 6.3**: Implementing linearizable semantics
   - https://github.com/ongardie/dissertation/blob/master/clients/clients.tex
   - Sessions, sequence numbers, response caching

2. **Existing Client-Server Implementation**:
   - `client-server-server/src/main/scala/zio/raft/server/RaftServer.scala`
   - RaftAction and ServerAction event types
   - Session management already implemented

3. **ZIO Raft Constitution v1.0.0**:
   - `.specify/memory/constitution.md`
   - Functional purity, explicit error handling, existing code preservation

---

## Summary of Research

**9 Key Architectural Decisions Made**:
1. Type-Safe HMap for State (with schema-defined prefixes)
2. State Machine Purity (no side effects)
3. Cumulative Acknowledgment for Server-Initiated Requests
4. Dirty Read Optimization for Retries
5. HMap Schema Strategy (SessionSchema + UserSchema → CombinedSchema)
6. **Template Pattern with Abstract Base Class** (users extend SessionStateMachine; implement 3 abstract methods)
7. No Modifications to Client-Server Library (clean boundaries)
8. Idempotency Cache Management (unbounded initially, future eviction)
9. Server-Initiated Request ID Assignment (monotonic, persisted)

**Architecture (Template Pattern)**:
- SessionStateMachine is abstract base class
- Users extend and implement 3 protected methods + serialization
- NO separate UserStateMachine trait
- Base class provides final template method for session management
- Users see only HMap[UserSchema] in their methods (state narrowing)
- Library handles idempotency, caching, server requests automatically

**No New External Dependencies**: Only ZIO 2.1+ and core raft (for HMap). No serialization dependencies.

## Next Steps (Phase 1)

1. ✅ Design data model (SessionState, ResponseCache, etc.)
2. ✅ Use existing StateMachine trait (Constitution III)
3. ✅ Create API contracts (event types, response types)
4. ✅ Generate property-based test cases
5. ✅ Create quickstart example (simple counter state machine)


