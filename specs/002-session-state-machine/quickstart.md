# Quickstart: Session State Machine Framework

**Feature**: 002-session-state-machine  
**Date**: 2025-10-21  
**Status**: Ready for Implementation

## Introduction

This guide shows how to create a simple distributed KV store using the session state machine framework. You'll learn how to:
1. Extend SessionStateMachine abstract base class (template pattern)
2. Implement 3 protected abstract methods for business logic
3. Define type-safe schema with HMap
4. Implement serialization using your chosen library
5. Handle idempotency automatically via base class
6. Test the implementation

**Time to Complete**: 30 minutes

---

## Prerequisites

- Existing ZIO Raft project
- Scala 3.3+
- ZIO 2.1+
- session-state-machine library
- Serialization library of your choice (example uses scodec)

---

## Step 1: Extend SessionStateMachine

Create a simple KV store by extending the SessionStateMachine base class.

### 1.1 Define Commands (see kvstore/App.scala for real example)

```scala
// kvstore/src/main/scala/zio/kvstore/App.scala
package zio.kvstore

import zio.raft.Command

sealed trait KVCommand extends Command

case class Set(key: String, value: String) extends KVCommand:
  type Response = Unit

case class Get(key: String) extends KVCommand:
  type Response = String
```

### 1.2 Define Your Schema

```scala
// Type-safe schema with prefixes and their value types
type KVSchema = 
  ("kv", Map[String, String]) *:  // "kv" prefix holds Map[String, String]
  EmptyTuple
```

### 1.3 Extend SessionStateMachine (Template Pattern)

```scala
import zio.*
import zio.prelude.State
import zio.raft.sessionstatemachine.*
import zio.raft.{Command, HMap}
import zio.stream.Stream

// Note: ServerRequest type - for KV, we might not use server requests
case class NoServerRequest()  // Placeholder

class KVStateMachine extends SessionStateMachine[KVCommand, NoServerRequest, KVSchema]:
  
  // Implement abstract method: applyCommand
  protected def applyCommand(cmd: KVCommand): State[HMap[KVSchema], (cmd.Response, List[NoServerRequest])] =
    State.modify { state =>
      cmd match
        case Set(key, value) =>
          // Type-safe access: state.get["kv"](...) returns Option[Map[String, String]]
          val kvMap = state.get["kv"]("store").getOrElse(Map.empty[String, String])
          val newMap = kvMap.updated(key, value)
          val newState = state.updated["kv"]("store", newMap)  // Type-checked!
          (newState, ((), Nil))  // Response is Unit, no server requests
        
        case Get(key) =>
          val kvMap = state.get["kv"]("store").getOrElse(Map.empty[String, String])
          val value = kvMap.getOrElse(key, "")
          (state, (value, Nil))  // State unchanged for read
    }
  
  // Implement abstract method: handleSessionCreated
  protected def handleSessionCreated(
    sessionId: SessionId, 
    capabilities: Map[String, String]
  ): State[HMap[KVSchema], List[NoServerRequest]] =
    State.succeed(Nil)  // No per-session initialization needed
  
  // Implement abstract method: handleSessionExpired
  protected def handleSessionExpired(sessionId: SessionId): State[HMap[KVSchema], List[NoServerRequest]] =
    State.succeed(Nil)  // No cleanup needed
  
  // Implement serialization (user chooses library - here using scodec)
  def takeSnapshot(state: HMap[CombinedSchema[KVSchema]]): Stream[Nothing, Byte] = ???  // See Step 1.4
  
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[KVSchema]]] = ???  // See Step 1.4
```

**Key Points**:
- ✅ Extend SessionStateMachine (template pattern)
- ✅ Implement 3 protected abstract methods
- ✅ Type-safe state with HMap[KVSchema] - compiler checks prefix "kv"
- ✅ State.get["kv"]("store") returns Option[Map[String, String]] (compile-time type)
- ✅ Errors in response payload, not exceptions
- ✅ Deterministic - same input always produces same output
- ✅ Users see only HMap[KVSchema] in their methods (state narrowing)
- ✅ Base class handles session management automatically

---

## Step 2: Use Your State Machine

Your state machine is ready to use - it extends StateMachine and can be passed directly to Raft.

### 2.1 Instantiate and Use

```scala
// kvstore/src/main/scala/zio/kvstore/App.scala (updated)
package zio.kvstore

import zio.*
import zio.raft.{Raft, StateMachine}

object KVStoreApp extends ZIOAppDefault:
  
  def run =
    val program =
      for
        // ... setup rpc, stable, logStore, snapshotStore ...
        
        // Instantiate your state machine (extends SessionStateMachine)
        val kvSM = new KVStateMachine()  // No constructor params needed!
        
        // Pass to Raft - it's a StateMachine
        raft <- Raft.make(
          memberId,
          peers,
          stable,
          logStore,
          snapshotStore,
          rpc,
          kvSM  // SessionStateMachine extends StateMachine[HMap[CombinedSchema[KVSchema]], SessionCommand[KVCommand]]
        )
        
        _ <- raft.run.forkScoped
        _ <- ZIO.never
      yield ()
    
    program.exitCode
```

**Key Points**:
- ✅ No manual wiring needed - extends StateMachine directly
- ✅ No composition setup - just instantiate your class
- ✅ State type is HMap[CombinedSchema[KVSchema]] (type-safe)
- ✅ Base class handles all session management automatically

---

## Step 3: Test Your State Machine

### 3.1 Unit Tests (Abstract Methods)

```scala
// kvstore/src/test/scala/zio/kvstore/KVStateMachineSpec.scala
package zio.kvstore

import zio.test.*
import zio.test.Assertion.*
import zio.raft.HMap

object KVStateMachineSpec extends ZIOSpecDefault:
  
  val kvSM = new KVStateMachine()
  
  def spec = suiteAll(
    
    test("Set stores value"):
      val state0 = HMap.empty[KVSchema]
      val (newState, (response, serverReqs)) = kvSM.applyCommand(Set("key1", "value1")).run(state0)
      assertTrue(
        newState.get["kv"]("store").flatMap(_.get("key1")) == Some("value1") &&
        response == () &&
        serverReqs.isEmpty
      )
    ,
    
    test("Get retrieves value"):
      val state0 = HMap.empty[KVSchema]
        .updated["kv"]("store", Map("key1" -> "value1"))
      val (newState, (value, serverReqs)) = kvSM.applyCommand(Get("key1")).run(state0)
      assertTrue(
        newState == state0 && // State unchanged
        value == "value1" &&
        serverReqs.isEmpty
      )
    ,
    
    test("Get missing key returns empty string"):
      val state0 = HMap.empty[KVSchema]
      val (newState, (value, _)) = kvSM.applyCommand(Get("missing")).run(state0)
      assertTrue(
        newState == state0 &&
        value == ""
      )
    ,
    
    test("Session lifecycle methods work"):
      val state0 = HMap.empty[KVSchema]
      val (state1, serverReqs1) = kvSM.handleSessionCreated(SessionId("test"), Map.empty).run(state0)
      val (state2, serverReqs2) = kvSM.handleSessionExpired(SessionId("test")).run(state1)
      assertTrue(
        serverReqs1.isEmpty &&
        serverReqs2.isEmpty
      )
  )
```

### 3.2 Integration Tests (Template Method with Idempotency)

```scala
// kvstore/src/test/scala/zio/kvstore/KVIntegrationSpec.scala
package zio.kvstore

import zio.test.*
import zio.test.Assertion.*
import zio.raft.sessionstatemachine.*
import zio.raft.HMap

object KVIntegrationSpec extends ZIOSpecDefault:
  
  def spec = suiteAll(
    
    test("Idempotency - same request returns cached response"):
      val kvSM = new KVStateMachine()
      val state0 = HMap.empty[CombinedSchema[KVSchema]]
      
      // First request
      val cmd1 = SessionCommand.ClientRequest(SessionId("test"), RequestId(1), Set("key1", "val1"))
      val (state1, response1) = kvSM.apply(cmd1).run(state0)
      
      // Second request (duplicate - same sessionId and requestId)
      val cmd2 = SessionCommand.ClientRequest(SessionId("test"), RequestId(1), Set("key1", "OTHER"))
      val (state2, response2) = kvSM.apply(cmd2).run(state1)
      
      assertTrue(
        response1 == response2 &&  // Same cached response
        state2 == state1 &&  // State unchanged (applyCommand NOT called again)
        state2.get["kv"]("store").flatMap(_.get("key1")) == Some("val1")  // Original value, not "OTHER"
      )
    ,
    
    test("Different requests processed independently"):
      val kvSM = new KVStateMachine()
      val state0 = HMap.empty[CombinedSchema[KVSchema]]
      
      // Request 1
      val cmd1 = SessionCommand.ClientRequest(SessionId("test"), RequestId(1), Set("k1", "v1"))
      val (state1, _) = kvSM.apply(cmd1).run(state0)
      
      // Request 2 (different request ID)
      val cmd2 = SessionCommand.ClientRequest(SessionId("test"), RequestId(2), Set("k2", "v2"))
      val (state2, _) = kvSM.apply(cmd2).run(state1)
      
      assertTrue(
        state2.get["kv"]("store").flatMap(_.get("k1")) == Some("v1") &&
        state2.get["kv"]("store").flatMap(_.get("k2")) == Some("v2")  // Both applied
      )
  )
```

---

## Step 4: Deploy and Run

See the `kvstore/` project for a complete working example. Your state machine is a StateMachine implementation that can be passed directly to Raft.make().

### 4.1 Using with Raft

```scala
// kvstore/src/main/scala/zio/kvstore/App.scala
package zio.kvstore

import zio.*
import zio.raft.Raft

object KVStoreApp extends ZIOAppDefault:
  
  def run =
    val program =
      for
        // ... setup dependencies ...
        
        // Your state machine (extends SessionStateMachine)
        val kvSM = new KVStateMachine()
        
        // Pass directly to Raft
        raft <- Raft.make(memberId, peers, stable, logStore, snapshotStore, rpc, kvSM)
        
        _ <- raft.run.forkScoped
        _ <- ZIO.never
      yield ()
    
    program.exitCode
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

## Resources

- **Raft Dissertation Chapter 6**: https://github.com/ongardie/dissertation
- **ZIO Documentation**: https://zio.dev
- **scodec Guide**: https://scodec.org
- **Project README**: ../README.md

---

## Validation Checklist

Use this checklist to verify your implementation:

- [ ] Extended SessionStateMachine successfully
- [ ] Implemented 3 protected abstract methods
- [ ] Defined UserSchema with type-safe prefixes
- [ ] Implemented serialization (takeSnapshot/restoreFromSnapshot)
- [ ] Abstract methods are pure (no side effects)
- [ ] Errors in response payload, not exceptions
- [ ] HMap type safety working (compile-time checks)
- [ ] Idempotency test passes (template method caching works)
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Snapshot/restore tested

---

## Reference

**Complete Example**: See `kvstore/` project for real implementation using template pattern with HMap

**Status**: ✅ Ready for implementation - This quickstart reflects the template pattern architecture


