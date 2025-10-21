# Quickstart: Session State Machine Framework

**Feature**: 002-session-state-machine  
**Date**: 2025-10-21  
**Status**: Ready for Implementation

## Introduction

This guide shows how to create a simple distributed counter using the session state machine framework. You'll learn how to:
1. Define a custom state machine
2. Wire it to the client-server library
3. Handle idempotency automatically
4. Test the implementation

**Time to Complete**: 30 minutes

---

## Prerequisites

- Existing ZIO Raft project with client-server library integrated
- Scala 3.3+
- ZIO 2.1+
- scodec for serialization

---

## Step 1: Define Your State Machine

Create a simple counter that supports increment, decrement, and get operations.

### 1.1 Define Commands and Responses

```scala
// src/main/scala/example/CounterCommand.scala
package example

import scodec.*
import scodec.codecs.*

sealed trait CounterCommand

object CounterCommand:
  case class Increment(by: Int) extends CounterCommand
  case class Decrement(by: Int) extends CounterCommand
  case object GetValue extends CounterCommand
  
  // scodec codec for serialization
  val codec: Codec[CounterCommand] = discriminated[CounterCommand].by(uint8)
    .typecase(0, int32.as[Increment])
    .typecase(1, int32.as[Decrement])
    .typecase(2, provide(GetValue))

sealed trait CounterResponse

object CounterResponse:
  case class Success(value: Int) extends CounterResponse
  case class Error(message: String) extends CounterResponse
  
  val codec: Codec[CounterResponse] = discriminated[CounterResponse].by(uint8)
    .typecase(0, int32.as[Success])
    .typecase(1, utf8.as[Error])
```

### 1.2 Implement State Machine

```scala
// src/main/scala/example/CounterStateMachine.scala
package example

import zio.*
import zio.prelude.State
import zio.raft.statemachine.UserStateMachine
import scodec.*
import scodec.bits.ByteVector
import scodec.codecs.*

// Note: ServerRequest type parameter - for counter, we use NotificationRequest
case class NotificationRequest(message: String)

class CounterStateMachine extends UserStateMachine[CounterCommand, CounterResponse, NotificationRequest]:
  
  // Apply command to state (pure function, no side effects!)
  // State is Map[String, Any] - we use key "counter"
  def apply(command: CounterCommand): State[Map[String, Any], (CounterResponse, List[NotificationRequest])] =
    State.modify { state =>
      // Get counter from map (default 0)
      val counter = state.getOrElse("counter", 0).asInstanceOf[Int]
      
      command match
        case CounterCommand.Increment(by) =>
          val newCounter = counter + by
          val newState = state.updated("counter", newCounter)
          
          // Example: send notification when counter reaches milestone
          val notifications = if (newCounter % 10 == 0) 
            List(NotificationRequest(s"Counter reached $newCounter!"))
          else Nil
          
          (newState, (CounterResponse.Success(newCounter), notifications))
        
        case CounterCommand.Decrement(by) if counter - by < 0 =>
          // Error in response, NOT exception; state unchanged
          (state, (CounterResponse.Error("Cannot go negative"), Nil))
        
        case CounterCommand.Decrement(by) =>
          val newCounter = counter - by
          val newState = state.updated("counter", newCounter)
          (newState, (CounterResponse.Success(newCounter), Nil))
        
        case CounterCommand.GetValue =>
          (state, (CounterResponse.Success(counter), Nil))
    }
  
  // Session lifecycle hooks (can return server-initiated requests!)
  def onSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[Map[String, Any], List[NotificationRequest]] =
    State.succeed(Nil)  // Counter doesn't need per-session initialization
  
  def onSessionExpired(sessionId: SessionId): State[Map[String, Any], List[NotificationRequest]] =
    State.succeed(Nil)  // No cleanup needed
```

**Key Points**:
- ✅ Pure function - no side effects
- ✅ Uses ZIO Prelude State monad
- ✅ Errors in response payload, not exceptions
- ✅ Deterministic - same input always produces same output
- ✅ State is always `Map[String, Any]` - user picks keys
- ✅ `apply` returns `(Response, List[ServerInitiatedRequest])`
- ✅ Session lifecycle hooks return `List[ServerInitiatedRequest]`
- ✅ NO codecs, NO emptyState, NO custom state type!
- ✅ Just 3 methods to implement

---

## Step 2: Integrate with Client-Server Library

Wire your state machine to the existing RaftServer infrastructure.

### 2.1 Create Session State Machine (with User SM + Codec)

```scala
// src/main/scala/example/CounterApp.scala
package example

import zio.*
import zio.raft.statemachine.*
import zio.raft.server.*
import zio.raft.protocol.*
import scodec.*

class CounterApp(raftServer: RaftServer):
  
  // Create user state machine
  val userSM = new CounterStateMachine()
  
  // Provide codec for Map[(String, Any)] serialization
  val stateCodec: Codec[(String, Any)] = ??? // User implementation
  
  // Create session state machine (composition via constructor!)
  val sessionSM = new SessionStateMachine(userSM, stateCodec)
  
  // State reference (just Map[String, Any]!)
  val stateRef = Ref.make(Map.empty[String, Any])
  
  // Wire RaftActions from server to state machine
  def processSessionCommand(command: SessionCommand): Task[Unit] =
    for
      currentState <- stateRef.get
      
      // Apply command to session state machine (which delegates to user SM as needed)
      (newState, response) = sessionSM.apply(command).run(currentState)
      
      // Update state
      _ <- stateRef.set(newState)
      
      // Send response based on command type
      _ <- command match
        case req: SessionCommand.ClientRequest =>
          // Send response to client
          raftServer.sendResponse(
            ClientResponse(req.sessionId, req.requestId, response, false)
          )
        case _ =>
          ZIO.unit
    yield ()
```

### 2.2 Connect to Raft Server Event Stream

```scala
  // In your main application setup
  def run: Task[Unit] =
    raftServer.raftActionsStream
      .mapZIO(processRaftAction)
      .runDrain
```

---

## Step 3: Test Your State Machine

### 3.1 Unit Tests (State Machine Logic)

```scala
// src/test/scala/example/CounterStateMachineSpec.scala
package example

import zio.test.*
import zio.test.Assertion.*

object CounterStateMachineSpec extends ZIOSpecDefault:
  
  val counter = new CounterStateMachine()
  
  def spec = suiteAll(
    
    test("Increment increases counter"):
      for
        (newState, response) <- counter.apply(0, CounterCommand.Increment(5))
      yield assertTrue(
        newState == 5 &&
        response == CounterResponse.Success(5)
      )
    ,
    
    test("Decrement decreases counter"):
      for
        (newState, response) <- counter.apply(10, CounterCommand.Decrement(3))
      yield assertTrue(
        newState == 7 &&
        response == CounterResponse.Success(7)
      )
    ,
    
    test("Decrement below zero returns error"):
      for
        (newState, response) <- counter.apply(5, CounterCommand.Decrement(10))
      yield assertTrue(
        newState == 5 && // State unchanged
        response.isInstanceOf[CounterResponse.Error]
      )
    ,
    
    test("GetValue returns current state"):
      for
        (newState, response) <- counter.apply(42, CounterCommand.GetValue)
      yield assertTrue(
        newState == 42 && // State unchanged
        response == CounterResponse.Success(42)
      )
    ,
    
    test("Serialization round-trip"):
      check(Gen.int) { value =>
        val serialized = counter.serialize(value)
        val deserialized = serialized.flatMap(counter.deserialize)
        assertTrue(deserialized.toOption.contains(value))
      }
  )
```

### 3.2 Integration Tests (With Idempotency)

```scala
// src/test/scala/example/CounterIntegrationSpec.scala
package example

import zio.test.*
import zio.test.Assertion.*
import zio.raft.protocol.*

object CounterIntegrationSpec extends ZIOSpecDefault:
  
  def spec = suiteAll(
    
    test("Idempotency - same request returns cached response"):
      for
        sessionSM <- ZIO.succeed(new SessionStateMachine())
        userSM <- ZIO.succeed(new CounterStateMachine())
        composable <- ZIO.succeed(new ComposableStateMachine(userSM, sessionSM))
        
        sessionState0 = SessionState.empty.addSession(
          SessionMetadata(SessionId("test"), Map.empty, now, now)
        )
        counterState0 = 0
        
        // First request
        (sessionState1, counterState1, result1) <- composable.apply(
          sessionState0,
          counterState0,
          RaftAction.ClientRequest(
            SessionId("test"),
            RequestId(1),
            encodeCommand(CounterCommand.Increment(5))
          )
        )
        
        // Second request (duplicate)
        (sessionState2, counterState2, result2) <- composable.apply(
          sessionState1,
          counterState1,
          RaftAction.ClientRequest(
            SessionId("test"),
            RequestId(1),
            encodeCommand(CounterCommand.Increment(5))
          )
        )
      yield assertTrue(
        counterState1 == 5 &&
        counterState2 == 5 && // Not incremented twice!
        result1.response == result2.response && // Same response
        sessionState1 == sessionState2 // Session state unchanged
      )
    ,
    
    test("Different requests processed independently"):
      for
        sessionSM <- ZIO.succeed(new SessionStateMachine())
        userSM <- ZIO.succeed(new CounterStateMachine())
        composable <- ZIO.succeed(new ComposableStateMachine(userSM, sessionSM))
        
        sessionState0 = SessionState.empty.addSession(
          SessionMetadata(SessionId("test"), Map.empty, now, now)
        )
        counterState0 = 0
        
        // Request 1
        (sessionState1, counterState1, _) <- composable.apply(
          sessionState0,
          counterState0,
          RaftAction.ClientRequest(
            SessionId("test"),
            RequestId(1),
            encodeCommand(CounterCommand.Increment(5))
          )
        )
        
        // Request 2 (different request ID)
        (sessionState2, counterState2, _) <- composable.apply(
          sessionState1,
          counterState1,
          RaftAction.ClientRequest(
            SessionId("test"),
            RequestId(2),
            encodeCommand(CounterCommand.Increment(3))
          )
        )
      yield assertTrue(
        counterState1 == 5 &&
        counterState2 == 8 // Both increments applied
      )
  )
```

---

## Step 4: Deploy and Run

### 4.1 Create Main Application

```scala
// src/main/scala/example/Main.scala
package example

import zio.*
import zio.raft.server.RaftServer

object Main extends ZIOAppDefault:
  
  def run =
    ZIO.scoped {
      for
        // Initialize Raft server (existing client-server infrastructure)
        raftServer <- RaftServer.make(config)
        
        // Create counter app
        counterApp <- ZIO.succeed(new CounterApp(raftServer))
        
        // Start processing events
        _ <- counterApp.run.fork
        
        // Keep running
        _ <- ZIO.never
      yield ()
    }
```

### 4.2 Run Tests

```bash
# Run all tests
sbt test

# Run specific test suite
sbt "testOnly example.CounterStateMachineSpec"

# Run with coverage
sbt clean coverage test coverageReport
```

---

## Step 5: Client Usage

### 5.1 Send Commands from Client

```scala
// Client code (using existing RaftClient)
import zio.raft.client.RaftClient

val client = RaftClient.connect(serverAddress)

for
  // Create session
  sessionId <- client.createSession(capabilities = Map.empty)
  
  // Increment counter
  response1 <- client.sendCommand(
    sessionId,
    requestId = RequestId(1),
    payload = encodeCommand(CounterCommand.Increment(5))
  )
  
  // Get value
  response2 <- client.sendCommand(
    sessionId,
    requestId = RequestId(2),
    payload = encodeCommand(CounterCommand.GetValue)
  )
  
  _ <- Console.printLine(s"Counter value: ${decodeResponse(response2)}")
yield ()
```

### 5.2 Retry Safety

```scala
// If client doesn't receive response, it can safely retry
for
  response1 <- client.sendCommand(sessionId, requestId, payload)
    .timeout(5.seconds)
    .catchAll { _ =>
      // Timeout - retry with SAME request ID
      client.sendCommand(sessionId, requestId, payload)
    }
yield ()
```

**Idempotency Guarantee**: Same `(sessionId, requestId)` always returns the same response, even if the command was already executed.

---

## Troubleshooting

### Issue: "State machine called twice for same request"

**Cause**: Not checking idempotency cache before applying to user SM

**Solution**: Ensure ComposableStateMachine checks cache first:
```scala
sessionState.findCachedResponse(sessionId, requestId) match
  case Some(cached) => return cached
  case None => // apply to user SM
```

---

### Issue: "Counter goes negative despite check"

**Cause**: Exception thrown instead of returning error in response

**Solution**: Always return errors in response payload:
```scala
// ❌ Wrong
if (state - by < 0) throw new IllegalArgumentException("...")

// ✅ Correct
if (state - by < 0) 
  ZIO.succeed((state, CounterResponse.Error("Cannot go negative")))
```

---

### Issue: "Snapshot restore fails"

**Cause**: Serialization codec mismatch

**Solution**: Verify codecs are consistent:
```scala
test("Serialization consistency"):
  val value = 42
  val bytes = counter.serialize(value).require
  val restored = counter.deserialize(bytes).require
  assertTrue(value == restored)
```

---

## Next Steps

### Advanced Features

1. **Server-Initiated Requests**: Return requests from user SM for push notifications
2. **Complex State**: Use case classes instead of primitives
3. **Multiple State Machines**: Compose multiple user SMs with routing logic
4. **Retry Process**: Implement external process with dirty reads for optimization

### Production Considerations

1. **Response Cache Eviction**: Implement lowest-sequence-number protocol (see Known Gaps)
2. **Monitoring**: Add metrics for cache hit rate, pending requests
3. **Performance**: Benchmark with realistic workloads
4. **Testing**: Add property-based tests for invariants

---

## Complete Example

See `examples/counter-state-machine/` for the full working example with:
- Complete implementation
- Comprehensive test suite
- Client integration
- Deployment scripts

---

## Resources

- **Raft Dissertation Chapter 6**: https://github.com/ongardie/dissertation
- **ZIO Documentation**: https://zio.dev
- **scodec Guide**: https://scodec.org
- **Project README**: ../README.md

---

## Validation Checklist

Use this checklist to verify your implementation:

- [ ] State machine is pure (no side effects)
- [ ] Returns UIO (cannot fail, errors in response)
- [ ] Serialization round-trip works
- [ ] Idempotency test passes
- [ ] Integration with RaftServer complete
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Client can send commands successfully
- [ ] Retry safety verified
- [ ] Snapshot/restore tested

---

**Status**: ✅ Ready for implementation - This quickstart can be used once Phase 3 (implementation) is complete.


