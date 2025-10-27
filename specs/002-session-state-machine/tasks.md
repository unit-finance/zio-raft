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

- [x] **T001** ✅ DONE - session-state-machine library project exists
  - **Files**: Project structure in place
  - **Success**: Library project structure exists as separate module

- [x] **T002** ✅ DONE - session-state-machine module in build.sbt
  - **Files**: `build.sbt`
  - **Success**: Module compiles, dependencies configured

- [x] **T003** ✅ DONE - Compilation works with strict checking
  - **Success**: Compilation is strict

---

## Phase 3.2: Core Types (Data Model) - Tests First! ⚠️

### Contract Tests (Write FIRST - Must Fail)

- [x] **T004-REWRITE** ✅ DONE - SessionMetadata test rewritten
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SessionMetadataSpec.scala`
  - **Tests**: 3 tests passing - creation, immutability, empty capabilities
  - **Status**: ✅ Tests pass

- [x] **T005-REWRITE** ✅ DONE - PendingServerRequest test rewritten
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/PendingServerRequestSpec.scala`
  - **Tests**: 4 tests passing - only payload and lastSentAt fields, immutability, different types
  - **Status**: ✅ Tests pass

- [x] **T006-REWRITE** ✅ DONE - ServerRequestForSession test created
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/ServerRequestForSessionSpec.scala`
  - **Tests**: 3 tests passing - wrapper creation, cross-session targeting
  - **Status**: ✅ Tests pass

- [x] **T007-REWRITE** ✅ DONE - Schema test with composite keys rewritten
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SchemaSpec.scala`
  - **Tests**: 5 tests passing - composite keys, range queries, numeric ordering
  - **Status**: ✅ Tests pass (15 tests total)

### Implementation (After Tests Fail)

- [x] **T008** ✅ DONE - SessionMetadata case class implemented
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionMetadata.scala`
  - **Fields**: `capabilities: Map[String, String]`, `createdAt: Instant` (NO sessionId - it's in the key!)
  - **Note**: Updated to remove sessionId field per PR comment

- [x] **T009** ✅ DONE - PendingServerRequest[SR] case class implemented
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/PendingServerRequest.scala`
  - **Fields**: `payload: SR`, `lastSentAt: Instant` (id and sessionId removed - in composite key!)
  - **Note**: Updated to remove redundant fields per PR comment

- [x] **T010** ✅ DONE - SessionSchema with composite keys defined
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/package.scala`
  - **Content**: Uses composite keys (SessionId, RequestId) for cache and serverRequests
  - **Note**: Byte-based HMap keys with proper numeric ordering

 - [x] **T011** ✅ DONE - SessionCommand ADT with ServerRequestForSession wrapper
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionCommand.scala`
  - **Cases**: ClientRequest (with lowestPendingRequestId), ServerRequestAck, CreateSession, SessionExpired, GetRequestsForRetry
  - **Note**: Added ServerRequestForSession wrapper per PR comment

---

## Phase 3.3: Abstract Base Class (SessionStateMachine) - Tests First! ⚠️

### ⚠️ OLD TESTS DELETED - RESTARTING FRESH

**All old tests deleted due to structural issues with old architecture. Need to write new tests for:**
- Byte-based keys
- Composite key structure  
- ServerRequestForSession wrapper
- Chunk-based API
- Cross-session server requests

### Contract Tests (Write FIRST - Must Fail) - TODO

- [x] **T012-NEW** ✅ DONE - Idempotency test with composite keys (PC-1)
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/IdempotencySpec.scala`
  - **Tests**: 2 tests passing - cache hit prevents applyCommand call, different requestIds work correctly
  - **Status**: ✅ Tests pass

- [x] **T013-NEW** ✅ DONE - Contract test for response caching with composite keys (PC-2)
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/ResponseCachingSpec.scala`
  - **Tests**: First request caches at composite key, second retrieves from composite key
  - **Status**: ✅ Tests pass

- [x] **T014-NEW** ✅ DONE - Contract test for cumulative acknowledgment with composite keys (PC-3)
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/CumulativeAckSpec.scala`
  - **Tests**: Ack N removes all serverRequests where (sessionId, requestId <= N), use range queries
  - **Status**: ✅ Tests pass

- [x] **T015-NEW** ✅ DONE - Contract test for server request cross-session targeting
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/ServerRequestTargetingSpec.scala`
  - **Tests**: ServerRequestForSession allows targeting ANY session, verify requests go to correct sessions
  - **Status**: ✅ Tests pass

- [x] **T016-NEW** ✅ DONE - Contract test for Chunk-based server request handling
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/ServerRequestChunkSpec.scala`
  - **Tests**: Verify Chunk is kept through pipeline, no List conversions
  - **Status**: ✅ Tests pass

- [x] **T017-NEW** ✅ DONE - Contract test for session lifecycle with cross-session server requests
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SessionLifecycleSpec.scala`
  - **Tests**: CreateSession and SessionExpired can emit server requests for OTHER sessions
  - **Status**: ✅ Tests pass

### Implementation - ALREADY COMPLETE ✅

- [x] **T008** ✅ SessionMetadata case class implemented (PR comment: removed sessionId field)
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionMetadata.scala`
  - **Fields**: `capabilities: Map[String, String]`, `createdAt: Instant`

- [x] **T009** ✅ PendingServerRequest[SR] case class implemented (PR comment: removed id and sessionId)
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/PendingServerRequest.scala`
  - **Fields**: `payload: SR`, `lastSentAt: Instant`

- [x] **T010** ✅ SessionSchema with composite keys defined
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/package.scala`
  - **Content**: Composite keys (SessionId, RequestId) for cache and serverRequests, byte-based encoding

- [x] **T011** ✅ SessionCommand ADT with ServerRequestForSession wrapper
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionCommand.scala`
  - **Note**: Added ServerRequestForSession wrapper per PR comment, added lowestPendingRequestId to ClientRequest

- [x] **T019-T027** ✅ SessionStateMachine fully implemented
  - **File**: `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
  - **Features**:
    - Template pattern with final apply method
    - Idempotency checking with composite keys
    - Cache cleanup using range queries
    - Cumulative acknowledgment using range + removedAll
    - Session lifecycle with cross-session server requests
    - addServerRequests takes Chunk[ServerRequestForSession[SR]] (PR comment: keep as Chunk, sessionId in wrapper)
    - handleGetRequestsForRetry uses foldRight (PR comment: no vars)
    - handleSessionExpired properly adds server requests (PR comment: bug fix)
    - hasPendingRequests for dirty read optimization

---

## Phase 3.4: KVStore Implementation - Tests First! ⚠️

### Unit Tests (Write FIRST - Must Fail)

- [x] **T029** [P] ✅ DONE - Unit test for KVStateMachine with SessionStateMachine
  - **File**: `kvstore/src/test/scala/zio/kvstore/KVStateMachineSpec.scala`
  - **Tests**: Set, Get operations, idempotency checking
  - **Status**: ✅ Tests pass

- [x] **T030** [P] ✅ DONE - Unit test for KV with session lifecycle
  - **File**: `kvstore/src/test/scala/zio/kvstore/KVSessionSpec.scala`
  - **Tests**: Session creation, expiration, state isolation
  - **Status**: ✅ Tests pass

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

- [x] **T037** ✅ DONE - Snapshot/restore unit test
  - **File**: `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SnapshotSpec.scala`
  - **Tests**: Take snapshot, restore, verify state consistency (unit level, no Raft integration)
  - **Note**: Uses test implementation of SessionStateMachine with user-provided serialization
  - **Success**: ✅ Snapshot/restore works correctly

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
- [x] ✅ All core types implemented
- [x] ✅ SessionSchema with composite keys compiles
- [x] ✅ HMap byte-based keys with proper ordering
- [x] ✅ SessionCommand ADT with dependent types works
- [x] ✅ Core type tests: 15 tests passing

### Phase 3.3 (SessionStateMachine)
- [x] ✅ Template method is final
- [x] ✅ 3 abstract methods defined (using ServerRequestForSession[SR])
- [x] ✅ Idempotency with composite keys works
- [x] ✅ State uses full HMap[Schema] (no narrowing - simplified)
- [x] ✅ Cumulative acknowledgment with range queries
- [x] ✅ Session lifecycle handled (server requests for other sessions supported)
- [x] ✅ Chunk-based API (no List conversions)
- [x] ✅ foldRight in handleGetRequestsForRetry (no vars)
- [x] ✅ hasPendingRequests for dirty read optimization
- [x] ✅ Composable State monad design with .withLog
- [x] ✅ Idempotency tests: 2 tests passing
- [x] ✅ Additional behavior tests added (cumulative ack, session lifecycle, chunk)
  - Covered by: `CumulativeAckSpec.scala`, `SessionLifecycleSpec.scala`, `ServerRequestChunkSpec.scala`

### Phase 3.4 (KVStore Integration)
- [x] ✅ KVStateMachine extends SessionStateMachine
- [x] ✅ 3 abstract methods implemented
- [x] ✅ User provides serialization (scodec)
- [x] ✅ HMap[KVSchema] used for state (via `KVCompleteSchema` combination)
- [x] ✅ Idempotency demonstrated
- [ ] kvstore application runs correctly

### Phase 3.5 (Tests & Polish)
- [x] ✅ Unit tests for snapshot/restore pass
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

