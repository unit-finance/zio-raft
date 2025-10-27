# Contract: SessionStateMachine Abstract Methods

**Feature**: 002-session-state-machine  
**Type**: Abstract Base Class Contract  
**Status**: Design Complete

**Note**: This contract defines the 3 protected abstract methods users must implement when extending SessionStateMachine

## Existing Interface (zio.raft.StateMachine)

**Note**: SessionStateMachine extends this existing interface.

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

---

## SessionStateMachine Abstract Base Class

**Purpose**: Abstract base class providing session management that users extend to add business logic.

**Package**: `zio.raft.sessionstatemachine`

```scala
package zio.raft.sessionstatemachine

import zio.prelude.State
import zio.raft.{Command, HMap, StateMachine}
import zio.raft.protocol.SessionId

/**
 * Abstract base class for session-aware state machines (template pattern).
 * 
 * Users extend this class and implement 3 protected abstract methods for business logic.
 * The base class handles session management, idempotency, caching automatically.
 * 
 * @tparam UC User command type (extends Command)
 * @tparam SR Server request type (user-defined)
 * @tparam UserSchema User's schema defining their state prefixes and types
 */
abstract class SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple]
  extends StateMachine[HMap[CombinedSchema[UserSchema]], SessionCommand[UC]]:
  
  /**
   * Apply a user command to the state.
   * 
   * MUST be pure and deterministic.
   * MUST NOT throw exceptions (return errors in Response).
   * State is HMap[UserSchema] - only user prefixes are visible (state narrowing).
   * 
   * @param command User command to apply (already decoded)
   * @return State monad with (response, server-initiated requests list)
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
   * @return State monad with list of server requests
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
   * @return State monad with list of server requests
   */
  protected def handleSessionExpired(
    sessionId: SessionId
  ): State[HMap[UserSchema], List[SR]]
  
  // Template method (FINAL) - orchestrates session management
  final def apply(command: SessionCommand[UC]): State[HMap[CombinedSchema[UserSchema]], command.Response]
  
  // Users must implement serialization
  def takeSnapshot(state: HMap[CombinedSchema[UserSchema]]): Stream[Nothing, Byte]
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[UserSchema]]]
```

**Template Pattern Architecture**:
- ✅ **Abstract base class** - users extend SessionStateMachine
- ✅ **3 protected abstract methods** - users implement for their business logic
- ✅ **Final template method** - base class defines session management flow
- ✅ **Type-safe state** - HMap[UserSchema] with compile-time schema validation
- ✅ **State narrowing** - users see only HMap[UserSchema], not session state
- ✅ **Automatic merging** - user state changes merged back to combined state
- ✅ **Session lifecycle** - abstract methods called on session creation/expiration
- ✅ **Returns server requests** - all abstract methods can return List[SR]
- ✅ **Users implement serialization** - choose their own library (no scodec dependency)
- ✅ **Extends StateMachine** - base class implements full StateMachine interface

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

## Usage Pattern (Template Pattern)

```scala
// Step 1: Define your schema
type KVSchema = ("kv", Map[String, String]) *: EmptyTuple

// Step 2: Extend SessionStateMachine
class KVStateMachine extends SessionStateMachine[KVCommand, ServerReq, KVSchema]:
  
  // Step 3: Implement 3 protected abstract methods
  protected def applyCommand(command: KVCommand): State[HMap[KVSchema], (command.Response, List[ServerReq])] = ???
  protected def handleSessionCreated(...): State[HMap[KVSchema], List[ServerReq]] = State.succeed(Nil)
  protected def handleSessionExpired(...): State[HMap[KVSchema], List[ServerReq]] = State.succeed(Nil)
  
  // Step 4: Implement serialization (choose your library)
  def takeSnapshot(state: HMap[CombinedSchema[KVSchema]]): Stream[Nothing, Byte] = ???
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[...]] = ???

// Step 5: Use it
val kvSM = new KVStateMachine()  // Extends StateMachine, ready for Raft
```

**Template Pattern Benefits**:
- ✅ Extend one class, implement 3 methods + serialization
- ✅ Type-safe state with HMap[UserSchema] - compile-time checking
- ✅ No separate trait to implement - just extend base class
- ✅ Template method is final - framework controls session management
- ✅ State narrowing: users see only HMap[UserSchema], not session internals
- ✅ Users choose serialization library (no forced dependency)
- ✅ Commands already decoded (UC extends Command with dependent Response type)
- ✅ Session lifecycle coordination automatic
- ✅ SessionStateMachine extends existing zio.raft.StateMachine (Constitution III)

---

## Abstract Methods Contract Specification

### Postconditions for Abstract Methods

#### PC-1: Determinism (applyCommand)
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

### KV Store State Machine (Template Pattern)

```scala
import zio.*
import zio.prelude.State
import zio.raft.sessionstatemachine.*
import zio.raft.{Command, HMap}
import scodec.*
import scodec.bits.*
import scodec.codecs.*

// Define user's schema
type KVSchema = ("kv", Map[String, String]) *: EmptyTuple

// Extend SessionStateMachine (template pattern)
class KVStateMachine extends SessionStateMachine[KVCommand, ServerReq, KVSchema]:
  
  // Implement abstract method: applyCommand
  protected def applyCommand(command: KVCommand): State[HMap[KVSchema], (command.Response, List[ServerReq])] =
    State.modify { state =>
      command match
        case Set(key, value) =>
          val kvMap = state.get["kv"]("store").getOrElse(Map.empty[String, String])
          val newMap = kvMap.updated(key, value)
          val newState = state.updated["kv"]("store", newMap)
          (newState, ((), Nil))  // Response is Unit, no server requests
        
        case Get(key) =>
          val kvMap = state.get["kv"]("store").getOrElse(Map.empty[String, String])
          val value = kvMap.getOrElse(key, "")
          (state, (value, Nil))  // State unchanged for read, no server requests
    }
  
  // Implement abstract method: handleSessionCreated
  protected def handleSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[HMap[KVSchema], List[ServerReq]] =
    State.succeed(Nil)  // No initialization needed
  
  // Implement abstract method: handleSessionExpired
  protected def handleSessionExpired(sessionId: SessionId): State[HMap[KVSchema], List[ServerReq]] =
    State.succeed(Nil)  // No cleanup needed
  
  // Implement serialization (user chooses library - here using scodec)
  def takeSnapshot(state: HMap[CombinedSchema[KVSchema]]): Stream[Nothing, Byte] =
    // Access internal map, serialize based on prefix
    val entries = state.m.toList
    // ... user's serialization code using scodec or other library
    ???
  
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[KVSchema]]] =
    // ... user's deserialization code
    ???

sealed trait KVCommand extends Command
case class Set(key: String, value: String) extends KVCommand:
  type Response = Unit
case class Get(key: String) extends KVCommand:
  type Response = String
```

---

## Contract Tests

### Test Suite: Abstract Methods Contract

```scala
import zio.test.*
import zio.test.Assertion.*
import zio.raft.sessionstatemachine.*
import zio.raft.HMap

// Test implementation extending SessionStateMachine
type TestSchema = ("counter", Int) *: EmptyTuple

class TestCounterSM extends SessionStateMachine[CounterCmd, ServerReq, TestSchema]:
  protected def applyCommand(cmd: CounterCmd): State[HMap[TestSchema], (cmd.Response, List[ServerReq])] =
    State.modify { state =>
      val counter = state.get["counter"]("value").getOrElse(0)
      cmd match
        case Increment(by) =>
          val newState = state.updated["counter"]("value", counter + by)
          (newState, (counter + by, Nil))
        case Decrement(by) if counter - by < 0 =>
          (state, (-1, Nil))  // Error indicator
        case Decrement(by) =>
          val newState = state.updated["counter"]("value", counter - by)
          (newState, (counter - by, Nil))
    }
  
  protected def handleSessionCreated(...) = State.succeed(Nil)
  protected def handleSessionExpired(...) = State.succeed(Nil)
  def takeSnapshot(state) = ???
  def restoreFromSnapshot(stream) = ???

object TestCounterSMSpec extends ZIOSpecDefault:
  
  val counter = new TestCounterSM()
  
  def spec = suiteAll(
    
    test("PC-1: Determinism - same input produces same output"):
      val state0 = HMap.empty[TestSchema]
      val (state1, result1) = counter.applyCommand(Increment(5)).run(state0)
      val (state2, result2) = counter.applyCommand(Increment(5)).run(state0)
      assertTrue(result1 == result2)
    ,
    
    test("PC-2: Purity - original state unchanged"):
      val state0 = HMap.empty[TestSchema].updated["counter"]("value", 42)
      val (newState, _) = counter.applyCommand(Increment(1)).run(state0)
      assertTrue(
        state0.get["counter"]("value") == Some(42) && // Original unchanged
        newState.get["counter"]("value") == Some(43)   // New state correct
      )
    ,
    
    test("PC-4: Error handling - no exceptions"):
      val state0 = HMap.empty[TestSchema].updated["counter"]("value", 5)
      val (state1, (result, _)) = counter.applyCommand(Decrement(10)).run(state0)
      assertTrue(
        state1.get["counter"]("value") == Some(5) && // State unchanged on error
        result == -1  // Error indicator
      )
  )
```

---

## Integration Points

### How Base Class Calls Abstract Methods

```scala
// Inside SessionStateMachine (base class implementation)
abstract class SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple]
  extends StateMachine[HMap[CombinedSchema[UserSchema]], SessionCommand[UC]]:
  
  // Template method (final) - defines the flow
  final def apply(command: SessionCommand[UC]): State[HMap[CombinedSchema[UserSchema]], command.Response] =
    State.modify { state =>
      command match
        case req: SessionCommand.ClientRequest[UC] @unchecked =>
          // 1. Check idempotency (base class logic)
          val cacheKey = s"${req.sessionId}/${req.requestId}"
          state.get["cache"](cacheKey) match
            case Some(cached) =>
              (state, cached.asInstanceOf[req.command.Response])
            
            case None =>
              // 2. Narrow state and call abstract method
              val userState: HMap[UserSchema] = state.narrowTo[UserSchema]
              val (userStateAfter, (response, serverReqs)) = applyCommand(req.command).run(userState)
              
              // 3. Merge, cache, and add server requests (base class logic)
              val stateWithUser = mergeUserState(state, userStateAfter)
              val stateWithCache = stateWithUser.updated["cache"](cacheKey, response)
              (addServerRequests(stateWithCache, req.sessionId, serverReqs), response)
        
        case created: SessionCommand.SessionCreationConfirmed =>
          // Narrow and call abstract method
          val userState: HMap[UserSchema] = state.narrowTo[UserSchema]
          val (userStateAfter, serverReqs) = handleSessionCreated(created.sessionId, created.capabilities).run(userState)
          (mergeAndAddRequests(...), ())
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


