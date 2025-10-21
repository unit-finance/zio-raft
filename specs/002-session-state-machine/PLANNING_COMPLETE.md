# Planning Complete: Session State Machine Framework

**Feature**: 002-session-state-machine  
**Branch**: `002-composable-raft-state` (unchanged)  
**Spec Directory**: `002-session-state-machine`  
**Date**: 2025-10-21  
**Status**: ✅ Ready for `/tasks`

## 🎯 FINAL ARCHITECTURE (Ultra-Simplified)

This is a **SESSION STATE MACHINE FRAMEWORK** - not a "composable" framework. Focus is on session management with idempotency checking and response caching per Raft dissertation Chapter 6.3.

**UserStateMachine[C, R]** - Just 3 methods:
1. `apply(cmd)` → `(Response, List[ServerRequests])`
2. `onSessionCreated(...)` → `List[ServerRequests]`
3. `onSessionExpired(...)` → `List[ServerRequests]`

**State**: Always `Map[String, Any]` - user picks keys, no custom types!

**SessionStateMachine** constructor:
- Takes `UserStateMachine`
- Takes ONE `Codec[(String, Any)]` for snapshots
- Extends existing `zio.raft.StateMachine`

**No**: CombinedState, codecs in UserSM, emptyState, custom state types, serialization in SM!

---

## Execution Summary

### Phases Completed

✅ **Phase 0: Research** - research.md created
- 9 architectural decisions
- Critical: Use existing `zio.raft.StateMachine` trait (Constitution III)
- No new external dependencies

✅ **Phase 1: Design & Contracts** - data-model.md, contracts/, quickstart.md created
- Complete type system (10 core types)
- 2 interface contracts (UserStateMachine, SessionStateMachine)
- 11 postconditions + 6 invariants = 17 test specifications
- Simplified architecture via constructor composition

✅ **Phase 2: Task Planning** - Strategy documented in plan.md
- 24-28 estimated tasks
- TDD ordering defined
- Parallel execution opportunities marked

---

## Architecture (Simplified)

```
┌─────────────────────────────────────────────────────────┐
│  SessionStateMachine[UserState, UserCmd, UserResp]     │
│                                                         │
│  Constructor: userSM: UserStateMachine[...]            │
│                                                         │
│  Extends: zio.raft.StateMachine[CombinedState[...],   │
│                                  SessionCommand]        │
│                                                         │
│  Responsibilities:                                      │
│  - Idempotency checking                                │
│  - Response caching                                    │
│  - Server-initiated request management                 │
│  - Snapshot serialization (both session + user state)  │
│  - Cumulative acknowledgments                          │
└─────────────────────────────────────────────────────────┘
                      ▲
                      │ composed via
                      │ constructor
                      │
┌─────────────────────────────────────────────────────────────┐
│      UserStateMachine[Command, Response]  ← NO State!      │
│                                                             │
│  Ultra-simple trait - just 3 methods:                      │
│  - apply(cmd: C): State[Map[String,Any], (R, List[Srv]))  │
│  - onSessionCreated(...): State[Map[String,Any], List[S]]  │
│  - onSessionExpired(...): State[Map[String,Any], List[S]]  │
│                                                             │
│  State always Map[String, Any] - user picks keys!          │
│  NO codecs, NO emptyState, NO custom state type!           │
│  ALL methods can return server requests!                   │
└─────────────────────────────────────────────────────────────┘
```

**Usage**:
```scala
val counterSM = new CounterStateMachine()              // Just 3 methods!
val codec: Codec[(String, Any)] = ???                  // User provides ONE codec
val sessionSM = new SessionStateMachine(counterSM, codec)
// sessionSM extends zio.raft.StateMachine[Map[String, Any], SessionCommand[?, ?]]
```

---

## Key Decisions

1. **SessionStateMachine takes UserStateMachine in constructor** (composition via dependency injection)
2. **UserStateMachine = simplified trait** (no snapshot methods - only 5 methods)
3. **Extends existing zio.raft.StateMachine** (Constitution III compliance)
4. **Shared dictionary snapshots** with key prefixes ("session/", "user/")
5. **Cumulative acknowledgments** for server-initiated requests (ack N removes all ≤ N)
6. **Dirty read optimization** for retry process (safe - command response is authoritative)
7. **No modifications to client-server library** (clean boundaries)
8. **User responsible for integration glue** (wiring events, sending responses)

---

## Artifacts Generated

### Planning Documents
- ✅ `spec.md` - Feature specification (27 functional requirements, 11 acceptance scenarios)
- ✅ `plan.md` - Implementation plan (486 lines)
- ✅ `research.md` - 9 architectural decisions  
- ✅ `data-model.md` - Complete type system (Map[String, Any] based)
- ✅ `quickstart.md` - Developer tutorial
- ✅ `ARCHITECTURE_FINAL.md` - Final architecture summary

### Contracts
- ✅ `contracts/state-machine-interface.md` - UserStateMachine trait contract
- ✅ `contracts/session-state-machine.md` - SessionStateMachine contract

### Configuration
- ✅ `.cursor/rules/specify-rules.mdc` - Updated agent context

**Total Documentation**: ~3,500+ lines across 9 files
**Directory**: `/specs/002-session-state-machine/`

---

## Constitution Compliance

### ✅ All Checks Passed

**I. Functional Purity & Type Safety**
- Immutable data structures (case classes)
- ZIO Prelude State monad
- No unsafe operations

**II. Explicit Error Handling**
- Errors in response payload (FR-014: no exceptions)
- Scodec Attempt for serialization errors
- UIO types where appropriate

**III. Existing Code Preservation** (NON-NEGOTIABLE)
- ✅ **EXTENDS existing zio.raft.StateMachine trait**
- ✅ No modifications to client-server library
- ✅ Purely additive new module

**IV. ZIO Ecosystem Consistency**
- ZIO Prelude State monad
- ZStream for snapshots
- No external libraries

**V. Test-Driven Maintenance**
- 17 test specifications defined
- Property-based tests for invariants
- TDD approach (tests before implementation)

---

## Metrics

### Estimated Scope
- **Module**: New `state-machine` module
- **Code**: ~1200-1500 LOC
- **Tests**: 17 specifications
- **Tasks**: 24-28 (to be generated by `/tasks`)

### Time Complexity
- Idempotency check: O(1)
- Cumulative ack: O(n) where n = pending requests/session
- Snapshot: O(s + r + p + user) where s=sessions, r=responses, p=pending

### Space Complexity
- Per session: ~108 bytes base
- Per cached response: 48 bytes + payload (unbounded initially)
- Per pending request: 40 bytes + payload

---

## Next Step

### Run `/tasks` Command

This will:
1. Load task generation template
2. Generate 24-28 numbered, ordered tasks
3. Map acceptance scenarios to implementation tasks
4. Define success criteria for each task
5. Mark parallel execution opportunities [P]
6. Create `tasks.md` in this directory

---

## Known Gaps (Future Work)

Documented in spec.md "Known Gaps in Client-Server Implementation":

1. **Query Message** (read-only queries) - Chapter 6.4 optimization
2. **Lowest Sequence Number Protocol** - Client-driven cache eviction
3. **RequestError Response** - Handle evicted cache entries
4. **Cumulative ServerRequestAck** - Protocol optimization (piggyback acks)

These will be addressed in follow-up features after this implementation is complete.

---

**Status**: ✅ Planning phase complete. Ready for task generation and implementation.


