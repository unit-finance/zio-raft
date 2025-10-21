# Final Architecture: Session State Machine Framework

**Feature**: 002-session-state-machine  
**Date**: 2025-10-21  
**Status**: ✅ FINAL - Ready for Implementation

---

## What This Is

**SESSION STATE MACHINE FRAMEWORK** - Provides session management with idempotency checking and response caching (Raft dissertation Chapter 6.3).

**NOT** a "composable framework" for multiple state machines - it's about wrapping ONE user state machine with session management infrastructure.

---

## Ultra-Simplified Architecture

### UserStateMachine[C, R] - Just 3 Methods!

```scala
trait UserStateMachine[C, R]:
  
  // Apply command, return (response, server requests)
  def apply(command: C): State[Map[String, Any], (R, List[ServerInitiatedRequest])]
  
  // Session created, return server requests
  def onSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[Map[String, Any], List[ServerInitiatedRequest]]
  
  // Session expired, return server requests
  def onSessionExpired(sessionId: SessionId): State[Map[String, Any], List[ServerInitiatedRequest]]
```

**Key Points**:
- ❌ No custom state type parameter - always `Map[String, Any]`
- ❌ No `emptyState` method - SessionStateMachine uses empty Map
- ❌ No codec methods - SessionStateMachine takes ONE codec
- ✅ User manages their own keys in the Map
- ✅ All methods can return server-initiated requests
- ✅ Commands and responses already decoded

---

### SessionStateMachine[UserCommand, UserResponse]

```scala
class SessionStateMachine[UserCommand, UserResponse](
  userSM: UserStateMachine[UserCommand, UserResponse],
  stateCodec: Codec[(String, Any)]  // ONE codec for snapshots
) extends zio.raft.StateMachine[Map[String, Any], SessionCommand[?, ?]]
```

**Constructor**:
- `userSM`: User state machine
- `stateCodec`: Codec for serializing Map entries (user provides)

**State**: `Map[String, Any]` with key prefixes:
- `"session/"` - Session management data (metadata, cache, pending requests)
- User keys - Whatever user wants (e.g., `"counter"`, `"users/123"`, etc.)

**Extends**: `zio.raft.StateMachine` (existing trait - Constitution III)

---

## Complete Example

```scala
// 1. User implements UserStateMachine
class CounterStateMachine extends UserStateMachine[CounterCommand, CounterResponse]:
  
  def apply(command: CounterCommand): State[Map[String, Any], (CounterResponse, List[ServerInitiatedRequest])] =
    State.modify { state =>
      val counter = state.getOrElse("counter", 0).asInstanceOf[Int]
      
      command match
        case CounterCommand.Increment(by) =>
          val newCounter = counter + by
          val newState = state.updated("counter", newCounter)
          (newState, (CounterResponse.Success(newCounter), Nil))
        
        case CounterCommand.GetValue =>
          (state, (CounterResponse.Success(counter), Nil))
    }
  
  def onSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[Map[String, Any], List[ServerInitiatedRequest]] =
    State.succeed(Nil)
  
  def onSessionExpired(sessionId: SessionId): State[Map[String, Any], List[ServerInitiatedRequest]] =
    State.succeed(Nil)

// 2. User provides codec for Map serialization
val stateCodec: Codec[(String, Any)] = ??? // User implementation

// 3. Create SessionStateMachine
val counterSM = new CounterStateMachine()
val sessionSM = new SessionStateMachine(counterSM, stateCodec)

// 4. Use with Raft (sessionSM extends zio.raft.StateMachine)
val raftNode = RaftNode(sessionSM, ...)
```

---

## Key Simplifications

### Before (Complex):
- UserStateMachine[S, C, R] with custom state type
- emptyState method
- 3 codec methods (stateCodec, commandCodec, responseCodec)
- CombinedState wrapper type
- Complex serialization logic

### After (Ultra-Simple):
- UserStateMachine[C, R] - no state type parameter!
- State always `Map[String, Any]`
- No emptyState - just empty Map
- No codecs in UserStateMachine
- SessionStateMachine takes ONE `Codec[(String, Any)]`
- No CombinedState - just Map with key prefixes
- User manages their own keys

---

## SessionCommand Types

```scala
sealed trait SessionCommand[Request, Response] extends Command

object SessionCommand:
  case class ClientRequest[UserCommand, UserResponse](
    sessionId: SessionId,
    requestId: RequestId,
    command: UserCommand  // Already decoded!
  ) extends SessionCommand[UserCommand, UserResponse]
  
  case class ServerRequestAck(sessionId: SessionId, requestId: RequestId)
    extends SessionCommand[Unit, Unit]
  
  case class SessionCreationConfirmed(sessionId: SessionId, capabilities: Map[String, String])
    extends SessionCommand[Unit, Unit]
  
  case class SessionExpired(sessionId: SessionId)
    extends SessionCommand[Unit, Unit]
```

---

## State Layout (Map[String, Any])

### Session Keys (prefix "session/"):
```
session/metadata/{sessionId} → SessionMetadata
session/cache/{sessionId}/{requestId} → Response (decoded!)
session/serverRequests/{sessionId}/{requestId} → PendingServerRequest
session/lastServerRequestId/{sessionId} → RequestId
```

### User Keys (no prefix required):
```
counter → Int
users/{userId} → UserData
queue/{itemId} → QueueItem
... whatever user wants ...
```

---

## Benefits

✅ **Ultra-Simple for Users**: Just 3 methods, no type parameters for state  
✅ **No Serialization in SM**: Works with decoded types, serialization in integration  
✅ **Flexible State Management**: User picks their own keys  
✅ **Session Lifecycle**: Can initialize/cleanup and produce server requests  
✅ **Constitution Compliant**: Extends existing `zio.raft.StateMachine`  
✅ **Single Codec**: User provides `Codec[(String, Any)]` for snapshots  

---

## Responsibilities

### SessionStateMachine (Framework)
- Idempotency checking
- Response caching
- Server-initiated request tracking
- Cumulative acknowledgments
- Session metadata management
- Snapshot serialization (using provided codec)
- Forwarding session lifecycle events to user SM

### UserStateMachine (User Code)
- Business logic in `apply`
- Return server-initiated requests when needed
- Manage their own keys in `Map[String, Any]`
- Handle session lifecycle (optional - can return empty lists)

### Integration Layer (User Code)
- Deserialize commands from `RaftAction.ClientRequest` payloads
- Create `SessionCommand.ClientRequest` with decoded command
- Serialize responses before sending to clients
- Provide `Codec[(String, Any)]` for state snapshots
- Wire SessionStateMachine to Raft infrastructure

---

**Status**: ✅ FINAL ARCHITECTURE - No more changes expected


