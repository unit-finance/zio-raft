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

### 5. Key Prefix Strategy

**Decision**: Use simple string prefixes for dictionary keys

**Rationale**:
- "session/" for all session state machine keys
- "user/" for all user state machine keys
- Simple to implement and debug
- Clear separation of concerns
- No risk of key collisions

**Key Format Examples**:
```
session/sessions/{sessionId}
session/responses/{sessionId}/{requestId}
session/serverRequests/{sessionId}/{requestId}
session/lastServerRequestId/{sessionId}
user/{custom-key-from-user-sm}
```

**Alternatives Considered**:
- Binary prefixes: Rejected - harder to debug, minimal space savings
- No prefixes with namespaced types: Rejected - requires complex type system

---

### 6. SessionStateMachine Composition via Constructor

**Decision**: SessionStateMachine takes UserStateMachine in constructor and extends `zio.raft.StateMachine`

**Rationale**:
- **Simpler**: No separate ComposableStateMachine class needed
- **Constitution III**: Extends existing `zio.raft.StateMachine` trait
- **Composition**: Via constructor dependency injection
- **Encapsulation**: SessionStateMachine handles all composition logic internally
- **User-Friendly**: Users just pass their SM to SessionStateMachine constructor

**Architecture**:
```scala
// Simplified UserStateMachine trait (no snapshot methods, NO codecs!)
trait UserStateMachine[S, C, R]:
  def apply(command: C): State[S, R]
  def emptyState: S
  def onSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[S, Unit]
  def onSessionExpired(sessionId: SessionId): State[S, Unit]

// SessionCommand parameterized with decoded types
sealed trait SessionCommand[Request, Response] extends Command

object SessionCommand:
  case class ClientRequest[UserCommand, UserResponse](
    sessionId: SessionId,
    requestId: RequestId,
    command: UserCommand  // Already deserialized!
  ) extends SessionCommand[UserCommand, UserResponse]

// SessionStateMachine composes via constructor
class SessionStateMachine[UserState, UserCommand, UserResponse](
  userSM: UserStateMachine[UserState, UserCommand, UserResponse]
) extends StateMachine[CombinedState[UserState], SessionCommand[?, ?]]:
  
  def apply(command: SessionCommand[?, ?]): State[CombinedState[UserState], command.Response] =
    State.modify { combined =>
      command match
        case req: SessionCommand.ClientRequest[UserCommand, UserResponse] @unchecked =>
          // Check cache first (no deserialization needed!)
          combined.session.findCachedResponse(req.sessionId, req.requestId) match
            case Some(cached) => (combined, cached.response.asInstanceOf[UserResponse])
            case None =>
              // Command already decoded - just apply!
              val (newUser, resp) = userSM.apply(req.command).run(combined.user)
              val newSession = combined.session.cacheResponse(req.sessionId, req.requestId, resp)
              (CombinedState(newSession, newUser), resp)
        
        case created: SessionCommand.SessionCreationConfirmed =>
          // Forward to both
          val newSession = combined.session.addSession(...)
          val newUser = userSM.onSessionCreated(created.sessionId, created.capabilities).run(combined.user)._1
          (CombinedState(newSession, newUser), ())
        
        case expired: SessionCommand.SessionExpired =>
          // Forward to both
          val newSession = combined.session.expireSession(expired.sessionId)
          val newUser = userSM.onSessionExpired(expired.sessionId).run(combined.user)._1
          (CombinedState(newSession, newUser), ())
    }
  
  def takeSnapshot(state: CombinedState[UserState]): Stream[Nothing, Byte] =
    // User provides serialization in integration layer
    ???
```

**Benefits**:
- User doesn't implement snapshot methods OR codec methods
- Commands already decoded when reaching state machine
- Responses cached as-is (decoded, not ByteVector)
- Session lifecycle events forwarded to user SM
- SessionStateMachine handles all infrastructure
- Clear separation: UserStateMachine = business logic only
- Serialization completely outside state machine (integration layer)

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

**Decision**: Use scodec for type-safe binary serialization

**Rationale**:
- Already used in client-server-protocol module
- Provides compile-time schema checking
- Efficient binary format
- Explicit error handling via `Attempt[A]`

**Key Codecs Needed**:
- `SessionId`, `RequestId` (already exist in protocol)
- `SessionState` (sessions, response cache, pending server requests)
- `ResponseCacheEntry` (sessionId, requestId, response payload)
- `ServerRequest` with ID and lastSentAt

**Alternative**: Protocol Buffers - Rejected for consistency with existing codebase

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
state-machine/
├── src/
│   ├── main/
│   │   └── scala/
│   │       └── zio/
│   │           └── raft/
│   │               └── statemachine/
│   │                   ├── StateMachine.scala         # Core trait
│   │                   ├── ComposableStateMachine.scala # Session + User composition
│   │                   ├── SessionStateMachine.scala   # Idempotency logic
│   │                   ├── model/
│   │                   │   ├── SessionState.scala
│   │                   │   ├── ResponseCache.scala
│   │                   │   └── ServerRequest.scala
│   │                   └── codec/
│   │                       └── StateCodecs.scala
│   └── test/
│       └── scala/
│           └── zio/
│               └── raft/
│                   └── statemachine/
│                       ├── StateMachineSpec.scala
│                       ├── IdempotencySpec.scala
│                       ├── CumulativeAckSpec.scala
│                       └── SnapshotSpec.scala
```

---

## Dependencies

**Required**:
- ZIO 2.1+ (already in project)
- scodec-core (already in project via client-server-protocol)
- client-server-protocol (for SessionId, RequestId types)

**No New Dependencies Needed**

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
1. Shared Dictionary for Snapshots (with key prefixes)
2. State Machine Purity (no side effects)
3. Cumulative Acknowledgment for Server-Initiated Requests
4. Dirty Read Optimization for Retries
5. Key Prefix Strategy ("session/", "user/")
6. **SessionStateMachine Composition via Constructor** (takes UserStateMachine parameter; extends zio.raft.StateMachine)
7. No Modifications to Client-Server Library (clean boundaries)
8. Idempotency Cache Management (unbounded initially, future eviction)
9. Server-Initiated Request ID Assignment (monotonic, persisted)

**Architecture Simplification**:
- NO separate ComposableStateMachine class
- SessionStateMachine takes UserStateMachine in constructor
- UserStateMachine trait is simplified (no snapshot methods)
- SessionStateMachine handles all: idempotency, caching, server requests, snapshots

**No New External Dependencies**: All required libraries (ZIO, scodec) already in project

## Next Steps (Phase 1)

1. ✅ Design data model (SessionState, ResponseCache, etc.)
2. ✅ Use existing StateMachine trait (Constitution III)
3. ✅ Create API contracts (event types, response types)
4. ✅ Generate property-based test cases
5. ✅ Create quickstart example (simple counter state machine)


