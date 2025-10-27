# Contract: Session State Machine Template Method

**Feature**: 002-session-state-machine  
**Type**: Core Component Contract (Base Class)  
**Status**: Design Complete

## Interface Definition

```scala
package zio.raft.sessionstatemachine

import zio.*
import zio.raft.*
import zio.raft.protocol.*
import zio.prelude.State
import java.time.Instant

/**
 * Session state machine handling idempotency, response caching,
 * and server-initiated request management (template pattern).
 * 
 * Abstract base class that users extend to add session management
 * to their business logic per Raft dissertation Chapter 6.3.
 * 
 * Extends zio.raft.StateMachine to integrate with existing Raft infrastructure.
 * 
 * State is HMap[CombinedSchema[UserSchema]] with type-safe prefixes:
 * - SessionSchema prefixes: "metadata", "cache", "serverRequests", "lastServerRequestId"
 * - UserSchema prefixes: user-defined with their own types
 * 
 * @tparam UC User command type (extends Command, has its own Response type)
 * @tparam SR Server request type (user-defined)
 * @tparam UserSchema User's schema (tuple of prefix-type pairs)
 */
abstract class SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple]
  extends StateMachine[HMap[CombinedSchema[UserSchema]], SessionCommand[UC]]:
  
  /**
   * Initial state is empty HMap.
   */
  def emptyState: HMap[CombinedSchema[UserSchema]] = HMap.empty
  
  /**
   * Apply SessionCommand to state (FINAL TEMPLATE METHOD).
   * 
   * For ClientRequest: checks idempotency, narrows state, calls applyCommand, caches response.
   * For ServerRequestAck: removes acknowledged requests (cumulative).
   * For SessionCreationConfirmed: adds session metadata, calls handleSessionCreated.
   * For SessionExpired: calls handleSessionExpired, then removes all session data.
   * 
   * @param command Session-level command (UC is the user command type)
   * @return State monad with new state and command response
   */
  final def apply(command: SessionCommand[UC]): State[HMap[CombinedSchema[UserSchema]], command.Response]
  
  /**
   * Abstract methods - users MUST implement these.
   */
  protected def applyCommand(command: UC): State[HMap[UserSchema], (command.Response, List[SR])]
  protected def handleSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[HMap[UserSchema], List[SR]]
  protected def handleSessionExpired(sessionId: SessionId): State[HMap[UserSchema], List[SR]]
  
  /**
   * Create snapshot of state (users implement with their chosen library).
   * 
   * State contains both session data (SessionSchema prefixes) and user data (UserSchema prefixes).
   * Users serialize HMap's internal Map[String, Any] using their chosen library.
   * 
   * @param state State to snapshot
   * @return Stream of bytes
   */
  def takeSnapshot(state: HMap[CombinedSchema[UserSchema]]): Stream[Nothing, Byte]
  
  /**
   * Restore state from snapshot stream (users implement).
   * 
   * Users deserialize to Map[String, Any] then wrap in HMap.
   * 
   * @param stream Snapshot byte stream
   * @return UIO with restored HMap state
   */
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[UserSchema]]]
  
  /**
   * Determine if snapshot should be taken.
   * 
   * Default implementation: every 1000 log entries.
   * Users can override for custom policy.
   * 
   * @return true if snapshot should be taken
   */
  def shouldTakeSnapshot(
    lastSnapshotIndex: Index,
    lastSnapshotSize: Long,
    commitIndex: Index
  ): Boolean = (commitIndex.value - lastSnapshotIndex.value) > 1000
  
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
  def hasPendingRequests(state: HMap[CombinedSchema[UserSchema]], retryIfLastSentBefore: Instant): Boolean
```

---

## Contract Specification

### Postconditions

#### PC-1: Idempotency (FR-007)
```gherkin
Given state with cached response at key "session/cache/{sessionId}/{requestId}"
When SessionCommand.ClientRequest(sessionId, requestId, _) is applied
Then returns cached response without invoking user state machine
And state remains unchanged
```

**Test**: Unit test with SessionStateMachine(mockUserSM, codec) where mockUserSM counts invocations - verify count = 0 for duplicate

---

#### PC-2: Response Caching (FR-008)
```gherkin
Given state without cached response at key "session/cache/{sessionId}/{requestId}"
When SessionCommand.ClientRequest(sessionId, requestId, command) is applied
Then user SM is invoked via userSM.apply(command)
And response is cached at key "session/cache/{sessionId}/{requestId}"
And subsequent requests return cached response without invoking user SM
```

**Test**: Create SessionStateMachine(counterSM), apply same request twice, verify counterSM called once

---

#### PC-3: Cumulative Acknowledgment (FR-020)
```gherkin
Given pending server requests [1, 2, 3, 5, 7] for session S
When ServerRequestAck(S, 5) is applied
Then removes requests [1, 2, 3, 5]
And keeps requests [7]
```

**Test**: Property test with various request ID lists

---

#### PC-4: Monotonic Request IDs (FR-022)
```gherkin
Given session S with last assigned request ID = N
When new server request is added for session S
Then assigns ID = N + 1
And updates last assigned ID to N + 1
```

**Test**: Add multiple requests, verify IDs increment

---

#### PC-5: Session Expiration Cleanup (FR-024)
```gherkin
Given session S with cached responses and pending requests
When SessionExpired(S) is applied
Then removes all data for session S:
  - Session metadata
  - All cached responses
  - All pending server requests
  - Last assigned request ID
```

**Test**: Create session with data, expire, verify all removed

---

### Invariants

#### INV-1: Cached Response Consistency
```scala
forAll { (sessionId, requestId, payload) =>
  val (state1, response1) = apply(state, ClientRequest(sessionId, requestId, payload))
  val (state2, response2) = apply(state1, ClientRequest(sessionId, requestId, payload))
  
  response1 == response2 // Always same response
}
```

---

#### INV-2: Pending Request Order
```scala
forAll { state: SessionState =>
  state.pendingServerRequests.values.forall { requests =>
    requests == requests.sortBy(_.id) // Always sorted by ID
  }
}
```

---

#### INV-3: Last Request ID Bounds
```scala
forAll { (state: SessionState, sessionId: SessionId) =>
  val lastId = state.lastServerRequestId.getOrElse(sessionId, RequestId(0))
  val maxPending = state.pendingServerRequests
    .get(sessionId)
    .flatMap(_.map(_.id).maxOption)
    .getOrElse(RequestId(0))
  
  lastId >= maxPending // Last ID always >= highest pending ID
}
```

---

## State Transitions

### Client Request Processing

```
[No cache entry] --ClientRequest(s, r, p)-->
  1. Deserialize command from payload
  2. Apply to user state machine
  3. Cache response
  4. Add any server requests to pending
  5. Return response

[Cached entry exists] --ClientRequest(s, r, _)-->
  1. Lookup cached response
  2. Return cached response
  (No state change, user SM not invoked)
```

### Server Request Lifecycle

```
[User SM returns server requests] -->
  1. For each request:
     a. Assign next request ID for session
     b. Add to pendingServerRequests
     c. Update lastServerRequestId
  2. Return requests for user to send

[Client acknowledges] --ServerRequestAck(s, N)-->
  1. Filter pendingServerRequests[s]
  2. Keep only requests where id > N
  3. Update state
```

### Session Lifecycle

```
∅ --SessionCreationConfirmed-->
  SessionMetadata(sessionId, capabilities, now, now)

Active --SessionExpired-->
  ∅ (remove all session data)
```

---

## Contract Tests

### Test Suite: SessionStateMachineSpec

```scala
import zio.test.*
import zio.test.Assertion.*

object SessionStateMachineSpec extends ZIOSpecDefault:
  
  def spec = suiteAll(
    
    test("PC-1: Idempotency - cached response returned"):
      val sessionId = SessionId("test-session")
      val requestId = RequestId(1)
      val cachedResponse = ByteVector(1, 2, 3)
      
      val state = SessionState.empty.cacheResponse(
        sessionId,
        requestId,
        cachedResponse,
        isError = false
      )
      
      val sessionSM = new SessionStateMachine()
      
      for
        (newState, result) <- sessionSM.apply(
          state,
          RaftAction.ClientRequest(sessionId, requestId, ByteVector(4, 5, 6))
        )
      yield assertTrue(
        result.response.exists(_.payload == cachedResponse) &&
        newState == state // State unchanged
      )
    ,
    
    test("PC-2: Response caching - first request caches"):
      val sessionId = SessionId("test-session")
      val requestId = RequestId(1)
      val payload = ByteVector(1, 2, 3)
      
      val sessionSM = new SessionStateMachine()
      val userSM = new CounterStateMachine() // Example user SM
      
      val initialState = SessionState.empty
      
      for
        // First request
        (state1, result1) <- sessionSM.apply(
          initialState,
          RaftAction.ClientRequest(sessionId, requestId, payload)
        )
        
        // Verify cached
        cached = state1.findCachedResponse(sessionId, requestId)
        
        // Second request
        (state2, result2) <- sessionSM.apply(
          state1,
          RaftAction.ClientRequest(sessionId, requestId, payload)
        )
      yield assertTrue(
        cached.isDefined &&
        result1.response == result2.response &&
        state1 == state2 // State unchanged on second request
      )
    ,
    
    test("PC-3: Cumulative acknowledgment property"):
      check(Gen.listOfN(10)(Gen.int(1, 100)), Gen.int(1, 100)) { (ids, ackId) =>
        val sessionId = SessionId("test")
        val requests = ids.map { id =>
          PendingServerRequest(
            id = RequestId(id),
            sessionId = sessionId,
            payload = ByteVector.empty,
            createdAt = java.time.Instant.now(),
            lastSentAt = None
          )
        }
        
        val state = SessionState.empty.copy(
          pendingServerRequests = Map(sessionId -> requests)
        )
        
        val sessionSM = new SessionStateMachine()
        
        sessionSM.apply(
          state,
          RaftAction.ServerRequestAck(sessionId, RequestId(ackId))
        ).map { (newState, _) =>
          val remaining = newState.pendingServerRequests
            .getOrElse(sessionId, Nil)
            .map(_.id.value)
          
          assertTrue(
            remaining.forall(_ > ackId) // All remaining > ackId
          )
        }
      }
    ,
    
    test("PC-4: Monotonic request IDs"):
      val sessionId = SessionId("test")
      val sessionSM = new SessionStateMachine()
      
      val request1 = ServerInitiatedRequest(sessionId, ByteVector(1, 2, 3))
      val request2 = ServerInitiatedRequest(sessionId, ByteVector(4, 5, 6))
      val request3 = ServerInitiatedRequest(sessionId, ByteVector(7, 8, 9))
      
      val state0 = SessionState.empty
      
      for
        (state1, id1) <- ZIO.succeed(state0.addServerRequest(request1))
        (state2, id2) <- ZIO.succeed(state1.addServerRequest(request2))
        (state3, id3) <- ZIO.succeed(state2.addServerRequest(request3))
      yield assertTrue(
        id1.value == 1 &&
        id2.value == 2 &&
        id3.value == 3
      )
    ,
    
    test("PC-5: Session expiration cleanup"):
      val sessionId = SessionId("test")
      
      val state = SessionState.empty
        .addSession(SessionMetadata(sessionId, Map.empty, now, now))
        .cacheResponse(sessionId, RequestId(1), ByteVector(1, 2, 3), false)
        .copy(
          pendingServerRequests = Map(sessionId -> List(
            PendingServerRequest(RequestId(1), sessionId, ByteVector.empty, now, None)
          )),
          lastServerRequestId = Map(sessionId -> RequestId(5))
        )
      
      val sessionSM = new SessionStateMachine()
      
      for
        (newState, _) <- sessionSM.apply(state, RaftAction.SessionExpired(sessionId))
      yield assertTrue(
        newState.sessions.get(sessionId).isEmpty &&
        newState.responseCache.keys.forall(_.sessionId != sessionId) &&
        newState.pendingServerRequests.get(sessionId).isEmpty &&
        newState.lastServerRequestId.get(sessionId).isEmpty
      )
    ,
    
    test("INV-1: Cached response consistency property"):
      check(Gen.alphaNumericString, Gen.int, Gen.listOf(Gen.byte)) { (sessionStr, reqInt, payloadList) =>
        val sessionId = SessionId(sessionStr)
        val requestId = RequestId(reqInt)
        val payload = ByteVector(payloadList*)
        
        val sessionSM = new SessionStateMachine()
        val state0 = SessionState.empty
        
        for
          // Apply same request twice
          (state1, result1) <- sessionSM.apply(state0, RaftAction.ClientRequest(sessionId, requestId, payload))
          (state2, result2) <- sessionSM.apply(state1, RaftAction.ClientRequest(sessionId, requestId, payload))
        yield assertTrue(result1.response == result2.response)
      }
  )
```

---

## Integration Examples

### Extending SessionStateMachine (Template Pattern)

```scala
// Step 1: Define schema
type KVSchema = ("kv", Map[String, String]) *: EmptyTuple

// Step 2: Extend SessionStateMachine
class KVStateMachine extends SessionStateMachine[KVCommand, ServerReq, KVSchema]:
  
  // Step 3: Implement 3 abstract methods
  protected def applyCommand(cmd: KVCommand): State[HMap[KVSchema], (cmd.Response, List[ServerReq])] =
    State.modify { state =>
      cmd match
        case Set(k, v) =>
          val kvMap = state.get["kv"]("store").getOrElse(Map.empty)
          val newState = state.updated["kv"]("store", kvMap.updated(k, v))
          (newState, ((), Nil))
        case Get(k) =>
          val kvMap = state.get["kv"]("store").getOrElse(Map.empty)
          (state, (kvMap.getOrElse(k, ""), Nil))
    }
  
  protected def handleSessionCreated(...) = State.succeed(Nil)
  protected def handleSessionExpired(...) = State.succeed(Nil)
  
  // Step 4: Implement serialization
  def takeSnapshot(state: HMap[CombinedSchema[KVSchema]]): Stream[Nothing, Byte] = ???
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[...]] = ???

// Step 5: Use it
val kvSM = new KVStateMachine()  // Ready for Raft

// Step 6: Use in application
for
  currentState <- stateRef.get
  
  // Apply session command (template method handles everything)
  (newState, response) = kvSM.apply(
    SessionCommand.ClientRequest(sessionId, requestId, command)
  ).run(currentState)
  
  _ <- stateRef.set(newState)
  _ <- sendToClient(response)
yield ()
```

**Key Points**:
- ✅ Users extend SessionStateMachine (template pattern)
- ✅ Implement 3 methods + serialization
- ✅ Base class manages HMap[CombinedSchema[UserSchema]] internally
- ✅ Handles idempotency, caching, state narrowing/merging automatically
- ✅ Type-safe state access with compile-time checking

---

## Performance Characteristics

### Time Complexity
- Idempotency check: O(1) via Map lookup
- Cache insert: O(1)
- Cumulative ack: O(n) where n = pending requests for session (typically < 100)
- Session expiration: O(r + p) where r = cached responses, p = pending requests

### Space Complexity
- Per session: ~108 bytes (base)
- Per cached response: 48 bytes + payload
- Per pending request: 40 bytes + payload
- Total: Unbounded (no eviction initially per FR-015)

---

## Status

- [x] Contract defined
- [x] State transitions documented
- [x] Test suite specified
- [ ] Implementation complete (Phase 3)
- [ ] Tests passing (Phase 4)


