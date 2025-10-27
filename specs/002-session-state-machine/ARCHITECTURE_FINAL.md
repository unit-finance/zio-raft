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

### UserStateMachine[UC <: Command, SR] - Just 3 Methods!

```scala
import zio.raft.Command

trait UserStateMachine[UC <: Command, SR]:
  
  // Apply command, return (response, server requests)
  // Response type comes from the command itself (command.Response)
  def apply(command: UC): State[Map[String, Any], (command.Response, List[SR])]
  
  // Session created, return server requests
  def onSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[Map[String, Any], List[SR]]
  
  // Session expired, return server requests
  def onSessionExpired(sessionId: SessionId): State[Map[String, Any], List[SR]]
```

**Key Points**:
- ❌ No custom state type parameter - always `Map[String, Any]`
- ❌ No `emptyState` method - SessionStateMachine uses empty Map
- ❌ No codec methods - SessionStateMachine takes ONE codec
- ✅ UC (UserCommand) is a Command subtype with its own Response type
- ✅ Response type comes from the command itself (no separate R parameter)
- ✅ User manages their own keys in the Map
- ✅ All methods can return server-initiated requests
- ✅ Commands and responses already decoded

---

### SessionStateMachine[UC <: Command, SR]

```scala
class SessionStateMachine[UC <: Command, SR](
  userSM: UserStateMachine[UC, SR],
  stateCodec: Codec[(String, Any)]  // ONE codec for snapshots
) extends zio.raft.StateMachine[Map[String, Any], SessionCommand[UC]]
```

**Constructor**:
- `userSM`: User state machine (UC is the user's Command subtype)
- `stateCodec`: Codec for serializing Map entries (user provides)

**Type Parameters**:
- `UC <: Command`: User command type (extends Command, has its own Response type)
- `SR`: Server request type (user-defined)

**State**: `Map[String, Any]` with key prefixes:
- `"session/"` - Session management data (metadata, cache, pending requests)
- User keys - Whatever user wants (e.g., `"counter"`, `"users/123"`, etc.)

**Extends**: `zio.raft.StateMachine` (existing trait - Constitution III)

---

## Complete Example

```scala
import zio.raft.Command

// 1. User defines their Command type (extends Command with dependent Response)
sealed trait CounterCommand extends Command

object CounterCommand:
  case class Increment(by: Int) extends CounterCommand:
    type Response = Int
  
  case object GetValue extends CounterCommand:
    type Response = Int

// Define server request type (empty for this example)
sealed trait CounterServerRequest

// 2. User implements UserStateMachine
class CounterStateMachine extends UserStateMachine[CounterCommand, CounterServerRequest]:
  
  def apply(command: CounterCommand): State[Map[String, Any], (command.Response, List[CounterServerRequest])] =
    State.modify { state =>
      val counter = state.getOrElse("counter", 0).asInstanceOf[Int]
      
      command match
        case Increment(by) =>
          val newCounter = counter + by
          val newState = state.updated("counter", newCounter)
          (newState, (newCounter, Nil))  // Response type is Int (from command)
        
        case GetValue =>
          (state, (counter, Nil))  // Response type is Int (from command)
    }
  
  def onSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[Map[String, Any], List[CounterServerRequest]] =
    State.succeed(Nil)
  
  def onSessionExpired(sessionId: SessionId): State[Map[String, Any], List[CounterServerRequest]] =
    State.succeed(Nil)

// 3. User provides codec for Map serialization
val stateCodec: Codec[(String, Any)] = ??? // User implementation

// 4. Create SessionStateMachine
val counterSM = new CounterStateMachine()
val sessionSM = new SessionStateMachine[CounterCommand, CounterServerRequest](counterSM, stateCodec)

// 5. Use with Raft (sessionSM extends zio.raft.StateMachine)
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
- UserStateMachine[UC <: Command, SR] - no state type parameter, no response type parameter!
- UC extends Command, so it has its own Response type
- State always `Map[String, Any]`
- No emptyState - just empty Map
- No codecs in UserStateMachine
- SessionStateMachine takes ONE `Codec[(String, Any)]`
- No CombinedState - just Map with key prefixes
- User manages their own keys

---

## SessionCommand Types

**Note**: Following the core `zio.raft.Command` trait pattern where `Response` is a type member (not a type parameter).

```scala
// From raft/src/main/scala/zio/raft/Types.scala
trait Command:
  type Response  // Type member, not type parameter

sealed trait SessionCommand[UC <: Command] extends Command
  // Response type is defined by each case class using type members

object SessionCommand:
  case class ClientRequest[UC <: Command](
    sessionId: SessionId,
    requestId: RequestId,
    lowestPendingRequestId: RequestId,  // For cache cleanup (Lowest Sequence Number Protocol, exclusive: evict < this)
    command: UC  // Already decoded! UC is a Command subtype
  ) extends SessionCommand[UC]:
    type Response = (command.Response, List[Any])  // (user response, server requests)
  
  case class ServerRequestAck(sessionId: SessionId, requestId: RequestId)
    extends SessionCommand[Nothing]:
    type Response = Unit
  
  case class CreateSession(sessionId: SessionId, capabilities: Map[String, String])
    extends SessionCommand[Nothing]:
    type Response = List[Any]  // server requests
  
  case class SessionExpired(sessionId: SessionId)
    extends SessionCommand[Nothing]:
    type Response = List[Any]  // final server requests
  
  case class GetRequestsForRetry(sessionId: SessionId, currentTime: java.time.Instant)
    extends SessionCommand[Nothing]:
    type Response = List[PendingServerRequest[Any]]
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


