# Tasks: Session State Machine Framework

**Input**: Design documents from `/specs/002-session-state-machine/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md
**Architecture**: Template pattern with HMap for type-safe state management

## Execution Flow Summary
```
1. Setup project structure with new session-state-machine library
2. Create core types (SessionMetadata, PendingServerRequest, etc.)
3. Define SessionSchema and CombinedSchema type aliases
4. Create SessionCommand ADT with dependent types
5. Create abstract SessionStateMachine base class (template pattern)
6. Write contract tests BEFORE implementation (TDD)
7. Implement SessionStateMachine with session management logic
8. Update kvstore to use SessionStateMachine (convert KVStateMachine)
9. Unit tests for all state machine functionality
10. Documentation and polish
```

## Path Conventions
- New library: `session-state-machine/` (separate from core raft)
- Library code: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/`
- Tests: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/`
- Example usage: `kvstore/` (existing project will be updated to use SessionStateMachine)

---

## Phase 3.1: Setup

- [ ] **T001** Create new `session-state-machine` library project
  - **Files**: 
    - `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/`
    - `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/`
  - **Success**: Library project structure exists as separate module

- [ ] **T002** Add session-state-machine module to build.sbt
  - **Files**: `build.sbt`
  - **Changes**: 
    - Add `lazy val sessionStateMachine = project.in(file("session-state-machine"))`
    - Configure dependencies: ZIO 2.1+, ZIO Prelude, ZIO Test
    - Add dependency on core raft module (for HMap, Command, StateMachine trait)
  - **Note**: Library has NO scodec dependency - users provide their own serialization
  - **Success**: Module compiles, can reference raft core types

- [ ] **T003** [P] Configure compilation flags for strict type checking
  - **Files**: `build.sbt` (if needed)
  - **Flags**: `-Wunused:imports`, `-Wvalue-discard`, `-Xfatal-warnings`
  - **Success**: Compilation is strict

---

## Phase 3.2: Core Types (Data Model) - Tests First! ⚠️

### Contract Tests (Write FIRST - Must Fail)

- [ ] **T004** [P] Contract test for SessionMetadata immutability
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SessionMetadataSpec.scala`
  - **Tests**: Creation, field access, immutability
  - **Status**: MUST FAIL (type doesn't exist yet)

- [ ] **T005** [P] Contract test for PendingServerRequest
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/PendingServerRequestSpec.scala`
  - **Tests**: Creation, lastSentAt non-optional, immutability
  - **Status**: MUST FAIL

- [ ] **T006** [P] Contract test for SessionCommand ADT with dependent types
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SessionCommandSpec.scala`
  - **Tests**: ClientRequest response type, other command types, pattern matching
  - **Status**: MUST FAIL

- [ ] **T007** [P] Schema type safety tests for SessionSchema
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SchemaSpec.scala`
  - **Tests**: Compile-time type checking, prefix validation, CombinedSchema concatenation
  - **Status**: MUST FAIL

### Implementation (After Tests Fail)

- [ ] **T008** [P] Implement SessionMetadata case class
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionMetadata.scala`
  - **Fields**: `sessionId: SessionId`, `capabilities: Map[String, String]`, `createdAt: Instant`
  - **Constitution Note**: `createdAt` timestamp must come from ZIO Clock service, not `Instant.now()` (Constitution IV)
  - **Success**: T004 passes

- [ ] **T009** [P] Implement PendingServerRequest[SR] case class
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/PendingServerRequest.scala`
  - **Fields**: `id: RequestId`, `sessionId: SessionId`, `payload: SR`, `lastSentAt: Instant` (NOT Optional)
  - **Constitution Note**: `lastSentAt` timestamp must come from ZIO Clock service, not `Instant.now()` (Constitution IV)
  - **Success**: T005 passes

- [ ] **T010** Define SessionSchema and CombinedSchema type aliases
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/package.scala`
  - **Content**:
    ```scala
    type SessionSchema = 
      ("metadata", SessionMetadata) *:
      ("cache", Any) *:
      ("serverRequests", PendingServerRequest[?]) *:
      ("lastServerRequestId", RequestId) *:
      EmptyTuple
    
    type CombinedSchema[UserSchema <: Tuple] = Tuple.Concat[SessionSchema, UserSchema]
    ```
  - **Note**: T010 and T011 combined - both modify same file (package.scala)
  - **Success**: T007 passes, schema types compile

- [ ] **T011** Implement SessionCommand[UC <: Command] ADT
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionCommand.scala`
  - **Cases**: ClientRequest, ServerRequestAck, SessionCreationConfirmed, SessionExpired, GetRequestsForRetry
  - **Note**: Each case defines its own Response type (dependent types)
  - **Success**: T006 passes, all core types compile

---

## Phase 3.3: Abstract Base Class (SessionStateMachine) - Tests First! ⚠️

### Contract Tests (Write FIRST - Must Fail)

- [ ] **T012** [P] Contract test for template method (apply) behavior
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SessionStateMachineTemplateSpec.scala`
  - **Tests**: Template method is final, calls abstract methods in correct order
  - **Status**: MUST FAIL

- [ ] **T014** [P] Contract test for idempotency checking (PC-1)
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/IdempotencySpec.scala`
  - **Tests**: Cache hit returns cached response, user method NOT called on duplicate
  - **Mocking**: Use test implementation extending SessionStateMachine
  - **Status**: MUST FAIL

- [ ] **T015** [P] Contract test for response caching (PC-2)
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/ResponseCachingSpec.scala`
  - **Tests**: First request caches, second request returns cached
  - **Status**: MUST FAIL

- [ ] **T016** [P] Contract test for cumulative acknowledgment (PC-3)
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/CumulativeAckSpec.scala`
  - **Tests**: Property-based test - ack N removes all ≤ N
  - **Status**: MUST FAIL

- [ ] **T017** [P] Contract test for state narrowing and merging
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/StateNarrowingSpec.scala`
  - **Tests**: User methods receive HMap[UserSchema], changes merged back correctly
  - **Status**: MUST FAIL

- [ ] **T018** [P] Property-based invariant tests
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/InvariantSpec.scala`
  - **Tests**: INV-1 (idempotency consistency), INV-3 (monotonic IDs), INV-6 (schema type safety), INV-7 (prefix isolation)
  - **Status**: MUST FAIL

### Implementation (After Tests Fail)

- [ ] **T019** Create abstract SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple] base class
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Extends**: `StateMachine[HMap[CombinedSchema[UserSchema]], SessionCommand[UC]]`
  - **Structure**:
    - Define 3 protected abstract methods
    - Implement final template method `apply`
    - Mark snapshot methods as abstract (users implement)
    - Implement `emptyState` returning empty HMap
  - **Success**: Compiles, abstract methods defined

- [ ] **T020** Implement template method skeleton (apply)
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Logic**: Match on SessionCommand, define flow for each case
  - **Note**: Mark as `final def apply` - users cannot override
  - **Success**: Template structure complete, calls to abstract methods

- [ ] **T021** Implement idempotency checking logic in template method
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Logic**: 
    ```scala
    state.get["cache"](cacheKey) match
      case Some(cached) => return cached
      case None => call applyCommand(...)
    ```
  - **Success**: T014, T015 pass

- [ ] **T022** Implement state narrowing helper (narrowTo[UserSchema])
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Logic**: Use HMap's `narrowTo` method to pass subset to user methods
  - **Success**: User methods receive correct HMap type

- [ ] **T023** Implement state merging helper (mergeUserState)
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Logic**: Merge user state changes back into combined state
  - **Note**: Access HMap internal Map via `.m` property
  - **Success**: T017 passes

- [ ] **T024** Implement cumulative acknowledgment logic
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Logic**: For ServerRequestAck, remove all requests where `id <= ackId`
  - **Success**: T016 passes

- [ ] **T025** Implement session creation handling
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Logic**: 
    1. Add session metadata to state
    2. Narrow to UserSchema
    3. Call `handleSessionCreated` abstract method
    4. Merge results and add server requests
  - **Success**: SessionCreationConfirmed handled correctly

- [ ] **T026** Implement session expiration handling
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Logic**:
    1. Narrow to UserSchema
    2. Call `handleSessionExpired` abstract method
    3. Merge results and add server requests
    4. Remove all session data (metadata, cache, pending requests)
  - **Success**: SessionExpired handled correctly

- [ ] **T027** Implement server request helpers (addServerRequests, acknowledgeRequests)
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Methods**:
    - `addServerRequests`: Add to pending, assign IDs, update lastServerRequestId
    - `acknowledgeRequests`: Cumulative removal
    - `expireSession`: Remove all session data
  - **Success**: Helper methods work correctly

- [ ] **T027** Implement hasPendingRequests query method (dirty read for FR-027)
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Logic**: Check for pending requests with `lastSentAt < threshold`
  - **Purpose**: **Dirty read optimization** - allows retry process to query state without Raft consensus
  - **Note**: This enables FR-027 dirty read optimization - external process can check if retries needed before sending GetRequestsForRetry command
  - **Success**: Query method works, template method tests pass

---

## Phase 3.4: KVStore Implementation - Tests First! ⚠️

### Unit Tests (Write FIRST - Must Fail)

- [ ] **T029** [P] Unit test for KVStateMachine with SessionStateMachine
  - **File**: `kvstore/src/test/scala/zio/kvstore/KVStateMachineSpec.scala`
  - **Tests**: Set, Get operations, idempotency checking
  - **Status**: MUST FAIL (SessionStateMachine integration doesn't exist)

- [ ] **T030** [P] Unit test for KV with session lifecycle
  - **File**: `kvstore/src/test/scala/zio/kvstore/KVSessionSpec.scala`
  - **Tests**: Session creation, expiration, state isolation
  - **Status**: MUST FAIL

### Implementation (After Tests Fail)

- [ ] **T031** Define KVSchema type alias
  - **File**: `kvstore/src/main/scala/zio/kvstore/App.scala`
  - **Content**: `type KVSchema = ("kv", Map[String, String]) *: EmptyTuple`
  - **Success**: Schema defined for KV store

- [ ] **T032** Update KVStateMachine to extend SessionStateMachine
  - **File**: `kvstore/src/main/scala/zio/kvstore/App.scala`
  - **Changes**: 
    - Change from `extends StateMachine[Map[String, String], KVCommand]`
    - To `extends SessionStateMachine[KVCommand, ServerReq, KVSchema]`
  - **Note**: Need to add dependency on session-state-machine library
  - **Success**: Compiles with new base class

- [ ] **T033** Implement 3 protected abstract methods in KVStateMachine
  - **File**: `kvstore/src/main/scala/zio/kvstore/App.scala`
  - **Methods**:
    - `applyCommand(command: KVCommand): State[HMap[KVSchema], (command.Response, List[ServerReq])]`
    - `handleSessionCreated(sessionId, capabilities): State[HMap[KVSchema], List[ServerReq]]`
    - `handleSessionExpired(sessionId): State[HMap[KVSchema], List[ServerReq]]`
  - **Note**: Convert existing logic to use HMap[KVSchema] with "kv" prefix
  - **Success**: T029 passes

- [ ] **T034** Implement serialization methods in KVStateMachine
  - **File**: `kvstore/src/main/scala/zio/kvstore/App.scala`
  - **Methods**: `takeSnapshot`, `restoreFromSnapshot`
  - **Note**: Keep existing scodec-based serialization, adapt for HMap
  - **Success**: Snapshot/restore works with HMap

- [ ] **T035** Update build.sbt to add session-state-machine dependency
  - **File**: `build.sbt`
  - **Changes**: Add kvstore `.dependsOn(sessionStateMachine)`
  - **Success**: kvstore compiles with session-state-machine

- [ ] **T036** Update KVStoreApp to work with SessionStateMachine
  - **File**: `kvstore/src/main/scala/zio/kvstore/App.scala`
  - **Changes**: Ensure Raft.make works with updated KVStateMachine
  - **Note**: No API changes needed - still extends StateMachine
  - **Success**: T030 passes, application runs

---

## Phase 3.5: Additional Tests & Polish

### Additional Unit Tests

- [ ] **T037** Snapshot/restore unit test
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SnapshotSpec.scala`
  - **Tests**: Take snapshot, restore, verify state consistency (unit level, no Raft integration)
  - **Note**: Uses test implementation of SessionStateMachine with user-provided serialization
  - **Success**: Snapshot/restore works correctly

### Documentation

- [ ] **T038** [P] Add scaladoc to SessionStateMachine
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Content**: Class doc, method docs, usage examples
  - **Success**: API documentation complete

- [ ] **T039** [P] Add scaladoc to core types
  - **Files**: SessionMetadata.scala, PendingServerRequest.scala, SessionCommand.scala (in session-state-machine/)
  - **Content**: Type descriptions, field documentation
  - **Success**: All types documented

- [ ] **T040** [P] Create library README
  - **File**: `session-state-machine/README.md`
  - **Content**: Overview, architecture, usage guide, quick start
  - **Success**: README complete

- [ ] **T041** [P] Create user guide
  - **File**: `docs/session-state-machine-guide.md`
  - **Content**: How to extend SessionStateMachine, serialization guide, best practices
  - **Success**: User guide complete

### Polish

- [ ] **T042** Constitution compliance verification
  - **Checklist**: Review all constitution points from plan.md
  - **Verify**:
    - No unsafe operations (Principle I)
    - HMap provides type safety (Principle I)
    - No exceptions in business logic (Principle II)
    - Extends existing StateMachine trait (Principle III)
    - Pure functions only (Principle I)
    - No serialization library dependency (clean architecture)
    - **ZIO Clock service used for all time operations** - verify Instant values come from ZIO Clock, not Instant.now() or System.currentTimeMillis() (Principle IV)
    - **No java.util.Random or UUID.randomUUID()** - only ZIO Random service if needed (Principle IV)
  - **Success**: All constitutional requirements met

- [ ] **T043** Code review and cleanup
  - **Review**: Template pattern implementation, HMap usage, abstract methods
  - **Cleanup**: Remove unused imports, format code, fix warnings
  - **Success**: Code clean and review-ready

- [ ] **T044** [P] Update design documentation for final architecture
  - **Files**: spec.md, research.md, contracts/ (verify consistency with implementation)
  - **Purpose**: Ensure all design documents reflect actual implementation
  - **Note**: May already be done if implementation matches current docs
  - **Success**: All docs consistent with code

---

## Dependencies

```
Setup (T001-T003)
  ↓
Core Types Tests (T004-T007) [P]
  ↓
Core Types Implementation (T008-T011) [P]
  ↓
SessionStateMachine Tests (T012-T017) [P]
  ↓
SessionStateMachine Implementation (T018-T027)
  ↓
KVStore Tests (T028-T029) [P]
  ↓
KVStore Implementation (T030-T035)
  ↓
Tests & Polish (T036-T044)
```

**Critical Path**: T001 → T003 → (T004-T007 parallel) → (T008-T011 parallel) → (T012-T017 parallel) → (T018-T027 sequential) → (T028-T029 parallel) → (T030-T035 sequential) → (T036) → (T037-T044 parallel for docs/polish)

**Note**: Task numbers renumbered after combining T010 and T011 (both modified package.scala). Added T044 for documentation consistency verification.

---

## Parallel Execution Examples

### Phase 3.2 - Core Types Tests (All Parallel)
```bash
# Can run simultaneously
sbt "testOnly *SessionMetadataSpec"
sbt "testOnly *PendingServerRequestSpec"
sbt "testOnly *SessionCommandSpec"
sbt "testOnly *SchemaSpec"
```

### Phase 3.2 - Core Types Implementation (All Parallel)
Different files, no dependencies:
- T008: SessionMetadata.scala
- T009: PendingServerRequest.scala
- T010: package.scala (SessionSchema + CombinedSchema - combined task)
- T011: SessionCommand.scala

### Phase 3.3 - Contract Tests (All Parallel)
```bash
# T012-T017 can run simultaneously
sbt "testOnly *SessionStateMachineTemplateSpec"
sbt "testOnly *IdempotencySpec"
sbt "testOnly *ResponseCachingSpec"
sbt "testOnly *CumulativeAckSpec"
sbt "testOnly *StateNarrowingSpec"
sbt "testOnly *InvariantSpec"
```

### Phase 3.5 - Documentation (All Parallel)
- T037-T041: Documentation tasks can run in parallel
- T042-T043: Constitution compliance and code review

---

## Task Estimation

| Phase | Tasks | Estimated Time | Parallelizable |
|-------|-------|----------------|----------------|
| Setup | T001-T003 | 1 hour | T003 |
| Core Types Tests | T004-T007 | 3 hours | All |
| Core Types Impl | T008-T012 | 4 hours | All |
| SessionSM Tests | T013-T018 | 6 hours | All |
| SessionSM Impl | T019-T028 | 12 hours | Sequential |
| KVStore Tests | T029-T030 | 2 hours | Both |
| KVStore Impl | T031-T036 | 4 hours | Sequential |
| Unit Tests | T037 | 2 hours | N/A |
| Documentation | T038-T041 | 3 hours | All |
| Polish | T042-T043 | 2 hours | Sequential |
| **Total** | **42 tasks** | **38 hours** | **60% parallel** |

---

## Validation Checklist

Before marking phase complete:

### Phase 3.2 (Core Types)
- [ ] All core type tests pass
- [ ] SessionSchema and CombinedSchema compile
- [ ] HMap type safety verified
- [ ] SessionCommand ADT with dependent types works

### Phase 3.3 (SessionStateMachine)
- [ ] Template method is final
- [ ] 3 abstract methods defined correctly
- [ ] Idempotency works (cache hit/miss)
- [ ] State narrowing and merging works
- [ ] Cumulative acknowledgment correct
- [ ] Session lifecycle handled
- [ ] All contract tests pass
- [ ] Property-based invariants hold

### Phase 3.4 (KVStore Integration)
- [ ] KVStateMachine extends SessionStateMachine
- [ ] 3 abstract methods implemented
- [ ] User provides serialization (scodec)
- [ ] HMap[KVSchema] used for state
- [ ] Idempotency demonstrated
- [ ] kvstore application runs correctly

### Phase 3.5 (Tests & Polish)
- [ ] Unit tests for snapshot/restore pass
- [ ] Documentation complete
- [ ] Constitution compliance verified
- [ ] Code review complete

---

## Notes

### Library Structure
- **Separate library**: `session-state-machine/` (not part of core raft)
- **Dependencies**: Depends on core raft for HMap, Command, StateMachine trait
- **No Raft integration tests**: Only unit tests for state machine logic
- **Users integrate**: Users wire to their Raft setup in their applications

### Template Pattern Architecture
- SessionStateMachine is **abstract base class**
- Users **extend** and implement 3 protected methods:
  1. `applyCommand(command: UC): State[HMap[UserSchema], (command.Response, List[SR])]`
  2. `handleSessionCreated(sessionId, capabilities): State[HMap[UserSchema], List[SR]]`
  3. `handleSessionExpired(sessionId): State[HMap[UserSchema], List[SR]]`
- Users also implement serialization methods (library has no serialization dependencies)
- Template method `apply` is **final** - defines session management flow

### HMap Type Safety
- `SessionSchema` = fixed 4-prefix schema for session management
- `UserSchema` = user-defined schema with their prefixes
- `CombinedSchema[U] = Tuple.Concat[SessionSchema, U]` (type-level concatenation)
- Compile-time type checking for all state access
- Zero runtime overhead vs Map[String, Any]

### No Serialization in Library
- Library does NOT depend on scodec or any serialization framework
- Users implement `takeSnapshot` and `restoreFromSnapshot` methods
- Users choose their own serialization library (scodec, protobuf, JSON, etc.)
- KVStore example shows how to implement using scodec

### TDD Enforcement
- ⚠️ **CRITICAL**: Tests MUST be written before implementation
- All T004-T007 must fail before starting T008-T012
- All T013-T018 must fail before starting T019-T028
- All T029-T030 must fail before starting T031-T036

---

**Status**: ✅ Ready for execution
**Next**: Run T001 to begin implementation

