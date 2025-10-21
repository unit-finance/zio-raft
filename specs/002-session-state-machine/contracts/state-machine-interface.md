# Contract: UserStateMachine Interface

**Feature**: 002-session-state-machine  
**Type**: User-Facing Interface Contract  
**Status**: Design Complete

## Existing Interface (zio.raft.StateMachine)

**Note**: SessionStateMachine extends this existing interface. User state machines use a simplified interface.

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
  
  def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean
```

**Note**: SessionStateMachine extends this existing interface. User state machines use a simpler interface below.

---

## UserStateMachine Interface (New - Simplified)

**Purpose**: Simplified interface for user-defined business logic. Users implement this trait, NOT the full `zio.raft.StateMachine`.

```scala
package zio.raft.statemachine

import zio.prelude.State
import scodec.Codec

/**
 * Simplified state machine interface for user business logic.
 * 
 * Users implement this trait and pass it to SessionStateMachine constructor.
 * SessionStateMachine handles:
 * - Idempotency checking
 * - Response caching
 * - Server-initiated requests
 * - Snapshots (using codecs provided here)
 */
trait UserStateMachine[C, R]:
  
  /**
   * Apply a command to the current state.
   * 
   * MUST be pure and deterministic.
   * MUST NOT throw exceptions (return errors in Response).
   * Command is already deserialized.
   * State is Map[String, Any] - user manages their own keys.
   * 
   * @param command Command to apply (decoded)
   * @return State monad with (Response, List of server-initiated requests)
   */
  def apply(command: C): State[Map[String, Any], (R, List[ServerInitiatedRequest])]
  
  /**
   * Handle session creation event.
   * 
   * Called when a new client session is created.
   * State is Map[String, Any] - user can initialize per-session data.
   * Can return server-initiated requests.
   * 
   * @param sessionId The newly created session ID
   * @param capabilities Client capabilities from CreateSession
   * @return State monad with list of server requests to send
   */
  def onSessionCreated(
    sessionId: SessionId,
    capabilities: Map[String, String]
  ): State[Map[String, Any], List[ServerInitiatedRequest]]
  
  /**
   * Handle session expiration event.
   * 
   * Called when a client session expires or is closed.
   * State is Map[String, Any] - user can cleanup per-session data.
   * Can return server-initiated requests.
   * 
   * @param sessionId The expired session ID
   * @return State monad with list of server requests to send
   */
  def onSessionExpired(
    sessionId: SessionId
  ): State[Map[String, Any], List[ServerInitiatedRequest]]
```

**Key Differences from zio.raft.StateMachine**:
- ❌ No `takeSnapshot` method (SessionStateMachine handles it)
- ❌ No `restoreFromSnapshot` method (SessionStateMachine handles it)
- ❌ No `shouldTakeSnapshot` method (SessionStateMachine decides)
- ❌ No codec methods (SessionStateMachine takes ONE codec for snapshot)
- ❌ No `emptyState` method (SessionStateMachine uses empty Map)
- ❌ No custom state type - uses `Map[String, Any]`
- ✅ Session lifecycle methods can return server-initiated requests
- ✅ `apply` returns (Response, List[ServerInitiatedRequest])
- ✅ Works with decoded types only (commands and responses)
- ✅ Ultra-simple - just 3 methods, no type parameters for state!

---

## SessionCommand Type Hierarchy

```scala
// SessionCommand parameterized with Request and Response types
sealed trait SessionCommand[Request, Response] extends Command:
  type Response = Response  // Dependent type
  
object SessionCommand:
  
  case class ClientRequest[UserCommand, UserResponse](
    sessionId: SessionId,
    requestId: RequestId,
    command: UserCommand  // Already deserialized!
  ) extends SessionCommand[UserCommand, UserResponse]
  
  case class ServerRequestAck(
    sessionId: SessionId,
    requestId: RequestId
  ) extends SessionCommand[Unit, Unit]
  
  case class SessionCreationConfirmed(
    sessionId: SessionId,
    capabilities: Map[String, String]
  ) extends SessionCommand[Unit, Unit]
  
  case class SessionExpired(
    sessionId: SessionId
  ) extends SessionCommand[Unit, Unit]
  
  case class GetRequestsForRetry(
    retryIfLastSentBefore: Instant
  ) extends SessionCommand[Unit, List[PendingServerRequest]]
```

---

## How They Work Together

```scala
// User implements UserStateMachine (just 3 methods!)
class CounterStateMachine extends UserStateMachine[CounterCommand, CounterResponse]:
  
  def apply(command: CounterCommand): State[Map[String, Any], (CounterResponse, List[ServerInitiatedRequest])] =
    State.modify { state =>
      // User manages their own keys in the map
      val counter = state.getOrElse("counter", 0).asInstanceOf[Int]
      
      command match
        case CounterCommand.Increment(by) =>
          val newCounter = counter + by
          val newState = state.updated("counter", newCounter)
          (newState, (CounterResponse.Success(newCounter), Nil))
        
        case CounterCommand.Decrement(by) if counter - by < 0 =>
          (state, (CounterResponse.Error("Cannot go negative"), Nil))
        
        case CounterCommand.Decrement(by) =>
          val newCounter = counter - by
          val newState = state.updated("counter", newCounter)
          (newState, (CounterResponse.Success(newCounter), Nil))
        
        case CounterCommand.GetValue =>
          (state, (CounterResponse.Success(counter), Nil))
    }
  
  def onSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[Map[String, Any], List[ServerInitiatedRequest]] =
    State.succeed(Nil)  // No initialization or server requests needed
  
  def onSessionExpired(sessionId: SessionId): State[Map[String, Any], List[ServerInitiatedRequest]] =
    State.succeed(Nil)  // No cleanup or server requests needed

// SessionStateMachine takes UserStateMachine + codec in constructor
class SessionStateMachine[UserCommand, UserResponse](
  userSM: UserStateMachine[UserCommand, UserResponse],
  stateCodec: Codec[(String, Any)]  // ONE codec for all state
) extends zio.raft.StateMachine[Map[String, Any], SessionCommand[?, ?]]:
  
  def emptyState: Map[String, Any] = Map.empty  // Just empty map!
  
  def apply(command: SessionCommand[?, ?]): State[Map[String, Any], command.Response] =
    State.modify { state =>
      command match
        case req: SessionCommand.ClientRequest[UserCommand, UserResponse] @unchecked =>
          // 1. Check idempotency (state contains session data with "session/" prefix)
          val cachedKey = s"session/cache/${req.sessionId}/${req.requestId}"
          state.get(cachedKey) match
            case Some(cached) =>
              (state, cached.asInstanceOf[UserResponse])  // Cache hit
            
            case None =>
              // 2. Delegate to user SM (command already decoded!)
              val (newState, (userResponse, serverReqs)) = userSM.apply(req.command).run(state)
              
              // 3. Cache response and add server requests to session state
              val stateWithCache = newState.updated(cachedKey, userResponse)
              val stateWithRequests = addServerRequests(stateWithCache, req.sessionId, serverReqs)
              
              (stateWithRequests, userResponse)
        
        case ack: SessionCommand.ServerRequestAck =>
          // Cumulative acknowledgment - remove all requests ≤ ack.requestId
          val newState = acknowledgeRequests(state, ack.sessionId, ack.requestId)
          (newState, ())
        
        case created: SessionCommand.SessionCreationConfirmed =>
          // Add session metadata and forward to user SM
          val stateWithSession = state.updated(
            s"session/metadata/${created.sessionId}",
            SessionMetadata(created.sessionId, created.capabilities, now, now)
          )
          
          val (newState, serverReqs) = userSM.onSessionCreated(created.sessionId, created.capabilities)
            .run(stateWithSession)
          
          val stateWithRequests = addServerRequests(newState, created.sessionId, serverReqs)
          (stateWithRequests, ())
        
        case expired: SessionCommand.SessionExpired =>
          // Forward to user SM first (can return server requests)
          val (stateAfterUser, serverReqs) = userSM.onSessionExpired(expired.sessionId).run(state)
          
          // Add any server requests before removing session data
          val stateWithRequests = addServerRequests(stateAfterUser, expired.sessionId, serverReqs)
          
          // Remove all session data (metadata, cache, pending requests)
          val newState = expireSession(stateWithRequests, expired.sessionId)
          (newState, ())
    }
  
  def takeSnapshot(state: Map[String, Any]): Stream[Nothing, Byte] =
    val entries = state.toList
    val encoded = entries.map { case (k, v) => stateCodec.encode((k, v)).require }
    Stream.fromIterable(encodeList(encoded).toByteArray)
  
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[Map[String, Any]] =
    stream.runCollect.map { bytes =>
      val encodedList = decodeList(ByteVector(bytes.toArray))
      encodedList.map { bits => stateCodec.decode(bits).require.value }.toMap
    }
  
  def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
    (commitIndex.value - lastSnapshotIndex.value) > 1000

// Usage - MUCH simpler!
val counterSM = new CounterStateMachine()
val codec: Codec[(String, Any)] = ???  // User provides
val sessionSM = new SessionStateMachine(counterSM, codec)
```

**Architecture Benefits**:
- ✅ Users implement simple UserStateMachine (just 3 methods!)
- ✅ No custom state type - uses `Map[String, Any]`
- ✅ No codecs in UserStateMachine - SessionStateMachine takes ONE codec for snapshots
- ✅ No emptyState in UserStateMachine - SessionStateMachine uses empty Map
- ✅ Commands and responses already decoded (no serialization in SM)
- ✅ Session lifecycle events can produce server-initiated requests
- ✅ `apply` returns (Response, List[ServerInitiatedRequest])
- ✅ SessionStateMachine extends existing zio.raft.StateMachine (Constitution III)
- ✅ Ultra-simple: user manages keys in Map, SessionStateMachine handles rest

---

## UserStateMachine Contract Specification

### Postconditions for UserStateMachine

#### PC-1: Determinism
```gherkin
Given any state S and command C
When apply(S, C) is called multiple times
Then all calls return the same (newState, response)
```

**Test**: Property-based test with random states and commands

---

#### PC-2: Purity (No Side Effects)
```gherkin
Given any state S and command C
When apply(S, C) is called
Then no observable side effects occur (no I/O, no mutation, no exceptions)
```

**Test**: Run in ZIO TestClock, verify no external calls

---

#### PC-3: Serialization Round-Trip
```gherkin
Given any state S
When deserialize(serialize(S)) is called
Then result equals Right(S)
```

**Test**: Property-based test with generated states

---

#### PC-4: Error Handling
```gherkin
Given any state S and invalid command C
When apply(S, C) is called
Then returns UIO with error encoded in Response (not exception)
```

**Test**: Unit test with invalid commands

---

### Preconditions

#### PR-1: Valid State
```gherkin
Given state S passed to apply()
Then S must be a valid instance (no null, no partially initialized)
```

**Enforcement**: Type system (case classes)

---

#### PR-2: Valid Command
```gherkin
Given command C passed to apply()
Then C must be deserializable from ByteVector
```

**Enforcement**: scodec codec validation

---

### Invariants

#### INV-1: State Immutability
```scala
forAll { (state: State, command: Command) =>
  val (newState, _) = stateMachine.apply(state, command).run
  state == state // Original state unchanged
}
```

---

#### INV-2: Type Safety
```scala
// Compile-time guarantee via sealed traits
sealed trait Command
case class Increment(by: Int) extends Command
case class GetValue() extends Command

// Cannot pass invalid command types
```

---

## Example Implementation

### Counter State Machine

```scala
import zio.*
import scodec.*
import scodec.bits.*
import scodec.codecs.*

class CounterStateMachine extends StateMachine[Int, CounterCommand, CounterResponse]:
  
  def apply(state: Int, command: CounterCommand): UIO[(Int, CounterResponse)] =
    command match
      case CounterCommand.Increment(by) =>
        val newState = state + by
        ZIO.succeed((newState, CounterResponse.Success(newState)))
      
      case CounterCommand.Decrement(by) if state - by < 0 =>
        // Error in response, not exception
        ZIO.succeed((state, CounterResponse.Error("Cannot go negative")))
      
      case CounterCommand.Decrement(by) =>
        val newState = state - by
        ZIO.succeed((newState, CounterResponse.Success(newState)))
      
      case CounterCommand.GetValue =>
        ZIO.succeed((state, CounterResponse.Success(state)))
  
  def serialize(state: Int): Attempt[ByteVector] =
    int32.encode(state)
  
  def deserialize(bytes: ByteVector): Attempt[Int] =
    int32.decode(bytes.bits).map(_.value)

sealed trait CounterCommand
object CounterCommand:
  case class Increment(by: Int) extends CounterCommand
  case class Decrement(by: Int) extends CounterCommand
  case object GetValue extends CounterCommand

sealed trait CounterResponse
object CounterResponse:
  case class Success(value: Int) extends CounterResponse
  case class Error(message: String) extends CounterResponse
```

---

## Contract Tests

### Test Suite: StateMachineContract

```scala
import zio.test.*
import zio.test.Assertion.*

object CounterStateMachineSpec extends ZIOSpecDefault:
  
  val counter = new CounterStateMachine()
  
  def spec = suiteAll(
    
    test("PC-1: Determinism - same input produces same output"):
      for
        result1 <- counter.apply(0, CounterCommand.Increment(5))
        result2 <- counter.apply(0, CounterCommand.Increment(5))
      yield assertTrue(result1 == result2)
    ,
    
    test("PC-2: Purity - original state unchanged"):
      val originalState = 42
      for
        (newState, _) <- counter.apply(originalState, CounterCommand.Increment(1))
      yield assertTrue(
        originalState == 42 && // Original unchanged
        newState == 43         // New state correct
      )
    ,
    
    test("PC-3: Serialization round-trip"):
      check(Gen.int) { state =>
        val serialized = counter.serialize(state)
        val deserialized = serialized.flatMap(counter.deserialize)
        assertTrue(deserialized == Attempt.successful(state))
      }
    ,
    
    test("PC-4: Error handling - no exceptions"):
      for
        (state, response) <- counter.apply(5, CounterCommand.Decrement(10))
      yield assertTrue(
        state == 5 && // State unchanged on error
        response.isInstanceOf[CounterResponse.Error]
      )
    ,
    
    test("INV-1: State immutability property"):
      check(Gen.int, Gen.int) { (state, increment) =>
        val originalState = state
        counter.apply(state, CounterCommand.Increment(increment)).map { (newState, _) =>
          assertTrue(state == originalState) // Immutability verified
        }
      }
  )
```

---

## Integration Points

### With Session State Machine

```scala
class ComposableStateMachine[State, Command, Response](
  sessionSM: SessionStateMachine,
  userSM: StateMachine[State, Command, Response]
):
  
  def apply(
    sessionState: SessionState,
    userState: State,
    action: RaftAction.ClientRequest
  ): UIO[(SessionState, State, Option[Response])] =
    
    // 1. Check idempotency
    sessionState.findCachedResponse(action.sessionId, action.requestId) match
      case Some(cached) =>
        // Return cached response, no state change
        ZIO.succeed((sessionState, userState, Some(deserializeResponse(cached))))
      
      case None =>
        // 2. Apply to user state machine
        for
          command <- deserializeCommand(action.payload)
          (newUserState, response) <- userSM.apply(userState, command)
          
          // 3. Cache response
          newSessionState = sessionState.cacheResponse(
            action.sessionId,
            action.requestId,
            serializeResponse(response)
          )
        yield (newSessionState, newUserState, Some(response))
```

---

## Failure Modes

### FM-1: Serialization Failure
**Symptom**: `serialize()` returns `Attempt.failure`  
**Cause**: State contains non-serializable data  
**Handling**: Return error to user at snapshot time  
**Prevention**: Property tests for all state types

---

### FM-2: Non-Determinism
**Symptom**: Same input produces different outputs  
**Cause**: User SM uses randomness, time, or external state  
**Handling**: Detected by property tests  
**Prevention**: Code review, constitution enforcement

---

### FM-3: Exception Thrown
**Symptom**: UIO fails (should be impossible)  
**Cause**: User SM violates contract (throws exception)  
**Handling**: Caught at composition layer, logged as critical error  
**Prevention**: Contract tests, documentation, code review

---

## Performance Characteristics

### Time Complexity
- `apply()`: O(1) for state machine itself + O(user logic)
- `serialize()`: O(size of state)
- `deserialize()`: O(size of bytes)

### Space Complexity
- No additional space beyond return values
- State is immutable (no hidden accumulation)

### Benchmarks
```scala
test("apply performance - 1M operations"):
  val counter = new CounterStateMachine()
  val start = System.nanoTime()
  var state = 0
  (1 to 1_000_000).foreach { i =>
    val (newState, _) = counter.apply(state, CounterCommand.Increment(1)).run
    state = newState
  }
  val duration = (System.nanoTime() - start) / 1_000_000
  assertTrue(duration < 1000) // < 1 second for 1M ops
```

---

## Status

- [x] Interface defined
- [x] Contract specified
- [x] Example implementation provided
- [x] Test suite defined
- [ ] Implementation complete (Phase 3)
- [ ] Tests passing (Phase 4)


