# Final Summary: Session State Machine Framework

**Feature**: 002-session-state-machine  
**Branch**: `002-composable-raft-state` (unchanged)  
**Directory**: `specs/002-session-state-machine/`  
**Date**: 2025-10-21  
**Status**: ✅ Planning Complete - Ready for `/tasks`

---

## What This Feature Is

**SESSION STATE MACHINE FRAMEWORK** - Wraps ONE user state machine with session management infrastructure per Raft dissertation Chapter 6.3.

**NOT** a "composable framework" for multiple state machines!

**Purpose**: Provide linearizable semantics and exactly-once command execution through:
- Idempotency checking via (sessionId, requestId) pairs
- Response caching for duplicate detection  
- Server-initiated request management with cumulative acks
- Session lifecycle event forwarding

---

## Ultra-Simplified Architecture (FINAL)

### UserStateMachine[C, R, SR] - Just 3 Methods!

```scala
trait UserStateMachine[C, R, SR]:
  def apply(command: C): State[Map[String, Any], (R, List[SR])]
  def onSessionCreated(sessionId, capabilities): State[Map[String, Any], List[SR]]
  def onSessionExpired(sessionId): State[Map[String, Any], List[SR]]
```

**Removed**:
- ❌ State type parameter (always `Map[String, Any]`)
- ❌ `emptyState` method (SessionStateMachine uses empty Map)
- ❌ All codec methods (SessionStateMachine takes ONE codec)

**Added**:
- ✅ ServerRequest type parameter (SR)
- ✅ All methods return `List[SR]` (server-initiated requests)
- ✅ `apply` returns `(R, List[SR])`

### SessionStateMachine[C, R, SR]

```scala
class SessionStateMachine[C, R, SR](
  userSM: UserStateMachine[C, R, SR],
  stateCodec: Codec[(String, Any)]  // ONE codec for all!
) extends zio.raft.StateMachine[Map[String, Any], SessionCommand[?, ?]]
```

**Constructor**:
- User state machine
- ONE codec for serializing Map entries

**State**: `Map[String, Any]` with key prefixes:
- `session/metadata/{sessionId}` → SessionMetadata
- `session/cache/{sessionId}/{requestId}` → Response (type R)
- `session/serverRequests/{sessionId}/{requestId}` → PendingServerRequest[SR]
- `session/lastServerRequestId/{sessionId}` → RequestId
- User keys (no prefix) → User data

---

## Core Types (Just 4!)

### 1. UserStateMachine[C, R, SR] (Trait)
- 3 methods
- No state type parameter
- No codecs
- No emptyState

### 2. SessionStateMachine[C, R, SR] (Class)
- Extends `zio.raft.StateMachine[Map[String, Any], SessionCommand[?, ?]]`
- Takes UserStateMachine + Codec in constructor
- Handles all session management

### 3. SessionMetadata (Case Class)
```scala
case class SessionMetadata(
  sessionId: SessionId,
  capabilities: Map[String, String],
  createdAt: Instant
)
```
**Removed**: `lastActivityAt` (unnecessary)

### 4. PendingServerRequest[SR] (Case Class)
```scala
case class PendingServerRequest[SR](
  id: RequestId,
  sessionId: SessionId,
  payload: SR,  // Type parameter, not ByteVector!
  lastSentAt: Instant  // NOT Option, initially equals creation time
)
```
**Removed**: 
- `createdAt` (use `lastSentAt`)
- `Option` from `lastSentAt`
- `ByteVector` for payload (use type parameter)

---

## Types REMOVED (Simplified Away)

1. ❌ **SessionState** - Just use Map with "session/" prefix
2. ❌ **CombinedState** - Just use Map
3. ❌ **ResponseCacheEntry** - Store response directly in Map
4. ❌ **ResponseCacheKey** - Use string keys
5. ❌ **ComposableStateMachine** - Composition in SessionStateMachine constructor

---

## Complete Example

```scala
// Define server request type
case class Notification(message: String)

// Implement UserStateMachine (3 methods!)
class CounterSM extends UserStateMachine[CounterCmd, CounterResp, Notification]:
  def apply(cmd: CounterCmd): State[Map[String, Any], (CounterResp, List[Notification])] =
    State.modify { state =>
      val counter = state.getOrElse("counter", 0).asInstanceOf[Int]
      val newCounter = counter + 1
      val notifications = if (newCounter % 10 == 0)
        List(Notification(s"Milestone: $newCounter"))
      else Nil
      (state.updated("counter", newCounter), (CounterResp.Success(newCounter), notifications))
    }
  
  def onSessionCreated(sid, caps): State[Map[String, Any], List[Notification]] =
    State.succeed(Nil)
  
  def onSessionExpired(sid): State[Map[String, Any], List[Notification]] =
    State.succeed(Nil)

// Provide codec
val codec: Codec[(String, Any)] = ???

// Create session state machine
val sessionSM = new SessionStateMachine(new CounterSM(), codec)

// Use with Raft
val raftNode = RaftNode(sessionSM, ...)
```

---

## Key Corrections Made

1. ✅ UserStateMachine[C, R, **SR**] - Added ServerRequest type parameter
2. ✅ onSessionCreated/onSessionExpired return `List[SR]`
3. ✅ apply returns `(R, List[SR])`
4. ✅ SessionStateMachine takes `Codec[(String, Any)]`
5. ✅ Example shows server-initiated requests
6. ✅ Removed CombinedState - just Map[String, Any]
7. ✅ Removed SessionState type - just Map with prefixes
8. ✅ Removed lastActivityAt from SessionMetadata
9. ✅ Response cache stores type R directly, not Any
10. ✅ Removed cachedAt
11. ✅ lastSentAt is not Option, removed createdAt
12. ✅ PendingServerRequest[SR] uses type parameter

---

## Metrics

**Code**: ~600-800 LOC (ultra-simplified!)
- Down from 1200-1500 LOC
- Down from 1500-2000 LOC (original)

**Types**: Just 4 core types
- Down from 10+ types

**UserStateMachine**: Just 3 methods
- Down from 5 methods  
- Down from 8+ methods (original)

**Constitution**: ✅ All checks passed

---

## Next Step

Run `/tasks` to generate implementation tasks from this ultra-simplified architecture!


