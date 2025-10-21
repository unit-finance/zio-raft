# Contract: Session State Machine

**Feature**: 002-session-state-machine  
**Type**: Core Component Contract  
**Status**: Design Complete

## Interface Definition

```scala
package zio.raft.statemachine

import zio.*
import zio.raft.*
import zio.raft.protocol.*
import zio.prelude.State
import scodec.bits.ByteVector

/**
 * Session state machine handling idempotency, response caching,
 * and server-initiated request management.
 * 
 * Wraps user state machine (passed in constructor) to provide 
 * linearizable semantics per Raft dissertation Chapter 6.3.
 * 
 * Extends zio.raft.StateMachine to integrate with existing Raft infrastructure.
 */
class SessionStateMachine[UserState, UserCommand, UserResponse](
  userSM: UserStateMachine[UserState, UserCommand, UserResponse]
) extends StateMachine[CombinedState[UserState], SessionCommand]:
  
  /**
   * Initial state combining empty session state and user's empty state.
   */
  def emptyState: CombinedState[UserState] = 
    CombinedState(SessionState.empty, userSM.emptyState)
  
  /**
   * Apply SessionCommand to combined state.
   * 
   * For ClientRequest: checks idempotency, delegates to user SM if needed, caches response.
   * For ServerRequestAck: removes acknowledged requests (cumulative).
   * For SessionCreationConfirmed: adds new session metadata.
   * For SessionExpired: removes all session data.
   * 
   * @param command Session-level command
   * @return State monad with new combined state and command response
   */
  def apply(command: SessionCommand): State[CombinedState[UserState], command.Response]
  
  /**
   * Create snapshot of both session state and user state.
   * 
   * Serializes to shared dictionary with key prefixes:
   * - "session/" for session state
   * - "user/" for user state
   * 
   * Uses userSM.stateCodec to serialize user state.
   * 
   * @param state Combined state to snapshot
   * @return Stream of bytes
   */
  def takeSnapshot(state: CombinedState[UserState]): Stream[Nothing, Byte]
  
  /**
   * Restore both state machines from snapshot stream.
   * 
   * Deserializes shared dictionary and splits by prefix.
   * Uses userSM.stateCodec to deserialize user state.
   * 
   * @param stream Snapshot byte stream
   * @return UIO with restored combined state
   */
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[CombinedState[UserState]]
  
  /**
   * Determine if snapshot should be taken.
   * 
   * Default policy: every 1000 log entries.
   * 
   * @return true if snapshot should be taken
   */
  def shouldTakeSnapshot(
    lastSnapshotIndex: Index,
    lastSnapshotSize: Long,
    commitIndex: Index
  ): Boolean
```

---

## Contract Specification

### Postconditions

#### PC-1: Idempotency (FR-007)
```gherkin
Given combined state with cached response for (sessionId, requestId)
When SessionCommand.ClientRequest(sessionId, requestId, _) is applied
Then returns cached response without invoking user state machine
And both session and user states remain unchanged
```

**Test**: Unit test with SessionStateMachine(mockUserSM) where mockUserSM counts invocations - verify count = 0 for duplicate

---

#### PC-2: Response Caching (FR-008)
```gherkin
Given combined state without cached response for (sessionId, requestId)
When SessionCommand.ClientRequest(sessionId, requestId, payload) is applied
Then user SM is invoked via userSM.apply()
And response is cached with key (sessionId, requestId)
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

### With User State Machine

```scala
// Step 1: User creates their state machine
class CounterStateMachine extends UserStateMachine[Int, CounterCommand, CounterResponse]:
  def emptyState: Int = 0
  
  def apply(command: CounterCommand): State[Int, CounterResponse] =
    State.modify { counter =>
      command match
        case CounterCommand.Increment(by) =>
          (counter + by, CounterResponse.Success(counter + by))
        case CounterCommand.GetValue =>
          (counter, CounterResponse.Success(counter))
    }
  
  def stateCodec: Codec[Int] = int32
  def commandCodec: Codec[CounterCommand] = CounterCommand.codec
  def responseCodec: Codec[CounterResponse] = CounterResponse.codec

// Step 2: Pass to SessionStateMachine constructor
val counterSM = new CounterStateMachine()
val sessionSM = new SessionStateMachine(counterSM)

// sessionSM now extends zio.raft.StateMachine and can be used with Raft

// Step 3: Use in application
for
  currentState <- stateRef.get
  
  // Apply session command
  (newState, response) = sessionSM.apply(
    SessionCommand.ClientRequest(sessionId, requestId, payload)
  ).run(currentState)
  
  _ <- stateRef.set(newState)
  
  // Send response to client
  _ <- sendToClient(response)
yield ()
```

**Key Points**:
- ✅ SessionStateMachine takes userSM in constructor
- ✅ Manages CombinedState[UserState] internally
- ✅ Handles idempotency, caching, snapshots automatically
- ✅ User just implements simple UserStateMachine trait

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


