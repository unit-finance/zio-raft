# Quick Start Guide for AI Agents

**Purpose**: Fast onboarding for AI agents working on ZIO Raft  
**Last Updated**: 2025-10-19 (PR #15 learnings)

---

## 🚀 Before You Start Coding

### 1. **Check These Rules First** (.cursor/rules/)
- **`avoid-premature-abstraction.mdc`** ⭐ **READ FIRST** - Don't create useless traits!
- `stream-architecture-pattern.mdc` - ALL state changes through unified stream
- `distributed-resource-cleanup.mdc` - Multi-layer resource cleanup
- `zio-purity-over-stdlib.mdc` - Use ZIO, not Java stdlib
- `rule_management_standards.mdc` - How to use and track rules

### 2. **Read Architecture Docs** (docs/architecture/)
- `session-lifecycle.md` - Session state machine and resource management
- `message-semantics.md` - Precise meaning of each protocol message

### 3. **Key Constitution Principles**
- ✅ Functional purity (no `Instant.now()`, use `Clock.instant`)
- ✅ Explicit error handling (no exceptions, use `ZIO.fail`)
- ✅ Existing code preservation (extend, don't replace)
- ✅ ZIO ecosystem consistency (ZIO primitives, ZStream, Scope)
- ✅ Test-driven (write tests first)

---

## 🎯 Critical Patterns in This Codebase

### Pattern 1: Unified Event Stream ⭐⭐⭐

**MOST IMPORTANT**: Both RaftClient and RaftServer use unified streams.

```scala
// Merge multiple event sources
val unifiedStream = actionStream
  .merge(messageStream)
  .merge(tickStream)

// Process with pure state machine
unifiedStream.runFoldZIO(initialState) { (state, event) =>
  state.handle(event, deps...)  // Returns new state
}
```

**Rule**: ALL state changes MUST go through the stream. Never:
- ❌ Direct `stateRef.update(...)` calls
- ❌ Synchronous state manipulation
- ✅ Queue actions: `actionQueue.offer(Action.Something)`

### Pattern 2: Resource Cleanup (Multi-Layer)

When cleaning up sessions/connections:

```scala
// 1. Check what resources exist
val oldRoutingId = sessions.getRoutingId(sessionId)

// 2. Clean up each layer
for {
  // Transport layer
  _ <- oldRoutingId.map(transport.disconnect).getOrElse(ZIO.unit)
  
  // Application layer
  newSessions = sessions.removeSession(sessionId, routingId)
  
  // Cluster layer (if permanent)
  _ <- raftActions.offer(RaftAction.ExpireSession(sessionId))
} yield newSessions
```

### Pattern 3: Pure Effects Only

```scala
// ❌ WRONG
val now = Instant.now()
val uuid = UUID.randomUUID()

// ✅ CORRECT
for {
  now <- Clock.instant
  uuid <- Random.nextUUID
} yield (now, uuid)
```

---

## 📦 Project Structure

```
client-server-protocol/    # Protocol messages + codecs (Scala 2.13 + 3)
├── ClientMessages.scala   # Client → Server
├── ServerMessages.scala   # Server → Client
├── Codecs.scala          # scodec serialization
└── package.scala         # Common types (SessionId, RequestId, etc.)

client-server-server/      # Server implementation (Scala 3 only)
└── RaftServer.scala      # Main server + state machine + Sessions

client-server-client/      # Client implementation (Scala 2.13 + 3)
└── RaftClient.scala      # Main client + state machine
```

---

## 🔍 Common Issues and Solutions

### Issue: "Private trait leaks into public signature"
**Cause**: Using `private sealed trait` in public constructor  
**Solution**: Make the trait public or restructure hierarchy

### Issue: "Tests using Instant.now() fail"
**Cause**: Impure operation breaks testability  
**Solution**: Replace with `Clock.instant`

### Issue: "State changes not happening"
**Cause**: Bypassing the unified stream  
**Solution**: Queue action through `actionQueue.offer(...)`

### Issue: "Resource leaks on reconnection"
**Cause**: Not cleaning up old routing ID  
**Solution**: Check for old resources, clean up before establishing new

### Issue: "Pattern match not exhaustive"
**Cause**: New message added to protocol, handlers not updated  
**Solution**: Add case for new message in ALL state handlers

---

## 🧪 Testing Guidelines

### Test Structure
```scala
object MySpec extends ZIOSpecDefault {
  override def spec = suiteAll("My Feature") {
    test("should do something") {
      for {
        // Use ZIO Test services
        now <- Clock.instant
        uuid <- Random.nextUUID
        
        // Test your logic
        result <- myFeature.run(...)
      } yield assertTrue(result == expected)
    }
  }
}
```

### Use Test Services
- `TestClock` - Control time in tests
- `TestRandom` - Deterministic randomness
- `TestConsole` - Capture console output

---

## 📊 Message Quick Reference

### When to Use Each Message

**Client wants to**:
- Start new session → `CreateSession`
- Resume after disconnect → `ContinueSession`
- Keep session alive → `KeepAlive`
- Submit command → `ClientRequest`
- Acknowledge server work → `ServerRequestAck`
- End permanently → `CloseSession`
- Report connection drop → `ConnectionClosed`

**Server wants to**:
- Confirm session created → `SessionCreated`
- Confirm session resumed → `SessionContinued`
- Reject session operation → `SessionRejected`
- Close session → `SessionClosed`
- Acknowledge heartbeat → `KeepAliveResponse`
- Return command result → `ClientResponse`
- Dispatch work to client → `ServerRequest`

---

## 🎓 Lessons from PR #15

### Key Takeaways

1. **Respect the Architecture**
   - Stream-based = everything through stream
   - Don't create shortcuts or direct calls

2. **Resource Cleanup is Complex**
   - Think: Transport + Application + Cluster layers
   - Always check for old resources before creating new

3. **Message Semantics Matter**
   - Permanent vs Temporary is a critical distinction
   - Document what gets cleaned up, what gets preserved

4. **Review Feedback is Gold**
   - Reviewer caught stream architecture bypass
   - Simplification often better than complexity
   - Trust the process

### Evolution in PR #15

```
Initial: 15+ separate abstraction files (SessionManager, ActionStream, ConnectionManager, etc.)
  ↓ "ai try to simplify" commit
  ↓ "ai simplify some more" commit
Current: 2-3 files per module with nested types
  ↓ Feedback: "everything through stream"
Final: Clean, simple, everything in right place
```

**Biggest Lesson**: Start with ~2 files (Main + Config), not 15 abstractions!

### Abstraction Red Flags 🚩

Watch for these warning signs that you're over-engineering:

- 🚩 Creating trait + impl with only one implementation
- 🚩 Many files under 100 lines each
- 🚩 Class names ending in "Manager", "Handler", "Processor"
- 🚩 Wiring code that just delegates to other abstractions
- 🚩 Difficulty explaining why an abstraction exists

**Action**: Consolidate into nested types within one file

---

## ⚡ Quick Wins for Next Task

Before starting implementation:

1. ✅ **DON'T create traits for everything!** (See `avoid-premature-abstraction.mdc`)
2. ✅ Read relevant .mdc rules in `.cursor/rules/`
3. ✅ Check `docs/architecture/` for patterns
4. ✅ Look at existing similar code (RaftClient/RaftServer)
5. ✅ Draw state machine diagram if complex
6. ✅ List all resources and their cleanup requirements
7. ✅ Identify any impure operations and plan replacements
8. ✅ Verify changes go through stream (if stream architecture)
9. ✅ **Keep it simple - one file is often better than many small files**

---

## 🔗 Key Documentation Links

- **Constitution**: `.specify/memory/constitution.md` - Core principles
- **Rules**: `.cursor/rules/*.mdc` - Specific patterns and standards  
- **Architecture**: `docs/architecture/*.md` - Design decisions
- **Specs**: `specs/001-implement-client-server/` - Feature specifications
- **PR #15**: https://github.com/unit-finance/zio-raft/pull/15 - Implementation history

---

## 💡 When in Doubt

1. **Check existing code** - RaftServer and RaftClient are reference implementations
2. **Ask clarifying questions** - Better than guessing
3. **Start simple** - Can always add complexity later
4. **Follow the stream** - If everything else uses it, you should too
5. **Think distributed** - Network fails, connections drop, messages duplicate

---

*Quick Start Guide for AI Agents*  
*Distilled from PR #15 implementation learnings*  
*Keep this updated as new patterns emerge*

