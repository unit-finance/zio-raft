# Tasks: TypeScript Client Library (Updated 2025-12-28)

**Input**: Design documents from `/specs/006-we-want-to/`
**Prerequisites**: plan.md (complete), research.md (complete + Section 13), data-model.md (complete), quickstart.md (complete)

**CRITICAL UPDATE (2025-12-28)**: After `/clarify` command added idiomatic TypeScript constraint, this task list has been regenerated to incorporate TypeScript best practices. See research.md Section 13 for detailed DO/DON'T guidance.

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → Extract: TypeScript 5.0+, Node.js 18+, zeromq, Buffer API
   → Project type: Single TypeScript library package
   → **NEW**: Apply idiomatic TypeScript patterns everywhere
2. Load design documents:
   → data-model.md: Extract 10 core entities
   → research.md: Extract technical decisions + Section 13 idiom guidelines
3. Generate tasks by category:
   → Setup: Project init, dependencies, TypeScript config (COMPLETE)
   → Core: Protocol codecs, transport, state machine, client API
   → Refactoring: Update non-idiomatic code
   → Tests: Codec tests, state tests, integration tests
   → Polish: Performance, docs, optimization
4. Apply task rules:
   → Different files = mark [P] for parallel
   → Same file = sequential (no [P])
   → Implementation-first approach (NOT TDD for this TypeScript library)
   → **NEW**: Follow idiom checklist from research.md Section 13
5. Number tasks sequentially (T001, T002...)
6. Generate dependency graph
7. SUCCESS: Tasks ready for execution
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[X]**: Task completed
- **⚠️**: Task needs refactoring for idiomatic TypeScript
- Include exact file paths in descriptions

## Path Conventions
- **Single TypeScript library**: `typescript-client/src/`, `typescript-client/tests/`
- All paths relative to repository root

## Idiomatic TypeScript Guidelines
Before implementing any task, review research.md Section 13:
- ✅ Wire protocol (codecs): MUST match Scala exactly
- ✅ Everything else: MUST be idiomatic TypeScript
- ❌ Don't port Scala patterns (ZRef, ZStream, ZIO)
- ✅ Use Node.js patterns (EventEmitter, Promises, classes with private state)
- ✅ Library should feel like ioredis, pg, or mongodb

---

## Phase 3.1: Project Setup ✅ COMPLETE

- [X] **T001** Create typescript-client project structure
- [X] **T002** Initialize package.json with dependencies
- [X] **T003** [P] Configure TypeScript compiler
- [X] **T004** [P] Configure ESLint and Prettier
- [X] **T005** [P] Configure Jest for TypeScript

**Status**: All setup tasks complete. No changes needed.

---

## Phase 3.2: Core Type Definitions ✅ COMPLETE (Idiomatic)

- [X] **T006** [P] Implement branded types in `typescript-client/src/types.ts`
- [X] **T007** [P] Define protocol constants in `typescript-client/src/protocol/constants.ts`
- [X] **T008** Define protocol message types in `typescript-client/src/protocol/messages.ts`

**Status**: Branded types are idiomatic TypeScript. Helper objects are acceptable. No refactoring needed.

---

## Phase 3.3: Binary Protocol Codecs ✅ COMPLETE (Wire Protocol)

- [X] **T009** Implement encoding primitives in `typescript-client/src/protocol/codecs.ts`
- [X] **T010** Implement decoding primitives in `typescript-client/src/protocol/codecs.ts`
- [X] **T011** Implement protocol header codec in `typescript-client/src/protocol/codecs.ts`
- [X] **T012** Implement client message encoding in `typescript-client/src/protocol/codecs.ts`
- [X] **T013** Implement server message decoding in `typescript-client/src/protocol/codecs.ts`

**Status**: Wire protocol layer MUST match Scala exactly. No refactoring needed.

---

## Phase 3.4: Transport Layer ✅ COMPLETE (Idiomatic)

- [X] **T014** [P] Define transport interface in `typescript-client/src/transport/transport.ts`
- [X] **T015** Implement ZMQ transport in `typescript-client/src/transport/zmqTransport.ts`
- [X] **T016** [P] Implement mock transport for testing in `typescript-client/src/transport/mockTransport.ts`

**Status**: Already uses classes, async/await, Promises. Idiomatic. No refactoring needed.

---

## Phase 3.5: State Management Structures ⚠️ NEEDS REFACTORING

- [X] **T017** [P] Implement RequestId counter in `typescript-client/src/utils/requestIdRef.ts` ⚠️
  - **ISSUE**: Too Scala-ish (ZRef port). Should be private field in RaftClient.
  - **REFACTOR**: See T061 below

- [X] **T018** Implement PendingRequests tracker in `typescript-client/src/state/pendingRequests.ts` ✅
  - **STATUS**: Already idiomatic (class with private Map, public methods)

- [X] **T019** [P] Implement PendingQueries tracker in `typescript-client/src/state/pendingQueries.ts` ✅
  - **STATUS**: Already idiomatic (class with private Map, public methods)

- [X] **T020** [P] Implement ServerRequestTracker in `typescript-client/src/state/serverRequestTracker.ts` ⚠️
  - **ISSUE**: Forced immutability pattern (returns new instance). Should use mutable private state.
  - **REFACTOR**: See T062 below

---

## Phase 3.6: State Machine Implementation (Apply Idiomatic Patterns)

**Guidance**: Use classes with private state, NOT functional state updates. Reference: research.md Section 13.5

- [X] **T021** Define state types in `typescript-client/src/state/clientState.ts`:
  - Type `ClientState` = discriminated union of: `DisconnectedState`, `ConnectingNewSessionState`, `ConnectingExistingSessionState`, `ConnectedState`
  - Each state interface per data-model.md design
  - **IDIOMATIC**: Use discriminated unions (already TypeScript-native)

- [X] **T022** Implement Disconnected state handler in `typescript-client/src/state/clientState.ts`:
  - `class DisconnectedStateHandler`
  - `handle(event: StreamEvent): Promise<ClientState>`
  - Handle `Connect` action: transition to ConnectingNewSession, send CreateSession
  - Handle `SubmitCommand`/`SubmitQuery`: reject with "Not connected"
  - **IDIOMATIC**: Use class with methods, return new state (state transitions are immutable)

- [X] **T023** Implement ConnectingNewSession state handler in `typescript-client/src/state/clientState.ts`:
  - `class ConnectingNewSessionStateHandler`
  - Handle `SessionCreated`: transition to Connected if nonce matches, resend pending
  - Handle `SessionRejected(NotLeader)`: try leader or next member
  - Handle timeout: try next member
  - **IDIOMATIC**: State handlers can be classes with private helper methods

- [X] **T024** Implement ConnectingExistingSession state handler in `typescript-client/src/state/clientState.ts`:
  - `class ConnectingExistingSessionStateHandler`
  - Handle `SessionContinued`: transition to Connected if nonce matches, resend pending
  - Handle `SessionRejected(SessionExpired)`: fail pending, terminate
  - **IDIOMATIC**: Same pattern as T023

- [ ] **T025** Implement Connected state handler in `typescript-client/src/state/clientState.ts`:
  - `class ConnectedStateHandler`
  - Handle all operations: SubmitCommand, SubmitQuery, ClientResponse, QueryResponse, SessionClosed, etc.
  - **IDIOMATIC**: Complex state logic in class methods, not nested functions

---

## Phase 3.7: Event System (Use EventEmitter)

**Guidance**: Use Node.js EventEmitter, NOT custom observables or ZStream patterns. Reference: research.md Section 13.2

- [ ] **T026** [P] Define event types in `typescript-client/src/events/eventTypes.ts`:
  - Type `ClientEvent` = discriminated union of: `StateChangeEvent`, `ConnectionAttemptEvent`, `ConnectionSuccessEvent`, etc.
  - Each event interface with readonly fields
  - **IDIOMATIC**: Discriminated unions for events are TypeScript-native

- [ ] **T027** [P] Implement event emitter utilities in `typescript-client/src/events/eventEmitter.ts`:
  - Type-safe event emitter wrapper using Node.js EventEmitter
  - Type-safe `on()` and `off()` methods for each ClientEvent type
  - `emit()` helper that takes ClientEvent and dispatches to listeners
  - **IDIOMATIC**: Use `import { EventEmitter } from 'events'`, NOT custom implementation

---

## Phase 3.8: Client API Implementation (Promise-Based, Simple Classes)

**Guidance**: Use Promises/async-await, classes with private state, simple configuration objects. Reference: research.md Section 13.3, 13.6

- [ ] **T028** Implement ClientConfig in `typescript-client/src/config.ts`:
  - Interface `ClientConfig` with fields: `clusterMembers`, `capabilities`, `connectionTimeout`, `keepAliveInterval`, `requestTimeout`
  - Default constants
  - `validate(config: ClientConfig): void` - throw if invalid
  - **IDIOMATIC**: Plain object configuration, NOT builder pattern

- [ ] **T029** [P] Implement action queue in `typescript-client/src/utils/asyncQueue.ts`:
  - Class `AsyncQueue<T>` with async `put(item: T)` and `take(): Promise<T>`
  - Used for queuing user actions (connect, disconnect, submit)
  - **IDIOMATIC**: Simple class with private queue, Promise-based async methods

- [ ] **T030** Implement unified event loop in `typescript-client/src/client.ts`:
  - Function `createUnifiedEventStream()`: AsyncIterator<StreamEvent>
  - Merge action queue, ZMQ messages, keep-alive timer, timeout timer
  - Use Promise.race() pattern to await next event from any source
  - **IDIOMATIC**: AsyncIterator is TypeScript-native, use modern async patterns

- [ ] **T031** Implement RaftClient class in `typescript-client/src/client.ts`:
  - Class `RaftClient extends EventEmitter` (for event emission)
  - Constructor takes `ClientConfig`
  - **Private fields**: currentState, transport, actionQueue, nextRequestId, pendingRequests, pendingQueries, serverRequestTracker
  - **IDIOMATIC**: 
    - Extend EventEmitter (not composition)
    - Use private fields (not separate Ref objects)
    - Encapsulate all state in the class
  - **NOTE**: RequestIdRef logic moves here (see T061)

- [ ] **T032** Implement client lifecycle methods in `typescript-client/src/client.ts`:
  - `async connect(): Promise<void>` - enqueue Connect action
  - `async disconnect(): Promise<void>` - enqueue Disconnect action
  - `async submitCommand(payload: Buffer): Promise<Buffer>` - enqueue SubmitCommand, return promise
  - `async query(payload: Buffer): Promise<Buffer>` - enqueue SubmitQuery, return promise
  - `serverRequests(): AsyncIterator<ServerRequest>` - async iterator from serverRequestQueue
  - Private method: `run()` - event loop that folds state transitions
  - **IDIOMATIC**: All public methods are async/Promise-based, simple signatures

- [ ] **T033** [P] Export public API in `typescript-client/src/index.ts`:
  - Export `RaftClient`, `ClientConfig`
  - Export branded types: `SessionId`, `RequestId`, `MemberId`, `Nonce`, `CorrelationId`
  - Export event types: `ClientEvent` and all subtypes
  - Export error classes: `ValidationError`, `NetworkError`, `SessionError`, `TimeoutError`
  - **IDIOMATIC**: Clean, minimal public API

---

## Phase 3.9: Unit Tests

- [ ] **T034** [P] Codec tests for client messages in `typescript-client/tests/unit/codecs.clientMessages.test.ts`
- [ ] **T035** [P] Codec tests for server messages in `typescript-client/tests/unit/codecs.serverMessages.test.ts`
- [ ] **T036** [P] Codec edge case tests in `typescript-client/tests/unit/codecs.edgeCases.test.ts`
- [ ] **T037** [P] State machine Disconnected tests in `typescript-client/tests/unit/state.disconnected.test.ts`
- [ ] **T038** [P] State machine ConnectingNewSession tests in `typescript-client/tests/unit/state.connectingNew.test.ts`
- [ ] **T039** [P] State machine ConnectingExistingSession tests in `typescript-client/tests/unit/state.connectingExisting.test.ts`
- [ ] **T040** [P] State machine Connected tests in `typescript-client/tests/unit/state.connected.test.ts`
- [ ] **T041** [P] State machine reconnection tests in `typescript-client/tests/unit/state.reconnection.test.ts`
- [ ] **T042** [P] Transport tests in `typescript-client/tests/unit/transport.test.ts`
- [ ] **T043** [P] PendingRequests tests in `typescript-client/tests/unit/pendingRequests.test.ts`
- [ ] **T044** [P] PendingQueries tests in `typescript-client/tests/unit/pendingQueries.test.ts`

---

## Phase 3.10: Integration Tests

- [ ] **T045** Integration test setup in `typescript-client/tests/integration/setup.ts`
- [ ] **T046** Basic operations integration tests in `typescript-client/tests/integration/basicOperations.test.ts`
- [ ] **T047** Reconnection scenarios integration tests in `typescript-client/tests/integration/reconnection.test.ts`
- [ ] **T048** Error scenarios integration tests in `typescript-client/tests/integration/errors.test.ts`
- [ ] **T049** Server-initiated requests integration tests in `typescript-client/tests/integration/serverRequests.test.ts`

---

## Phase 3.11: Performance & Compatibility Tests

- [ ] **T050** [P] Performance throughput tests in `typescript-client/tests/performance/throughput.test.ts`
- [ ] **T051** [P] Performance latency tests in `typescript-client/tests/performance/latency.test.ts`
- [ ] **T052** Protocol compatibility tests in `typescript-client/tests/compatibility/scala.test.ts`
- [ ] **T053** [P] Golden file codec tests in `typescript-client/tests/compatibility/goldenFiles.test.ts`

---

## Phase 3.12: Performance Optimizations

- [ ] **T054** [P] Implement request batching in `typescript-client/src/utils/batcher.ts`
- [ ] **T055** [P] Implement buffer pooling in `typescript-client/src/utils/bufferPool.ts`

---

## Phase 3.13: Documentation & Polish

- [ ] **T056** [P] Write comprehensive README in `typescript-client/README.md`
- [ ] **T057** [P] Add TSDoc comments to all public APIs
- [ ] **T058** [P] Generate API documentation with TypeDoc
- [ ] **T059** Run full test suite and fix any issues
- [ ] **T060** Run linter and formatter

---

## Phase 3.14: Refactoring for Idiomatic TypeScript (NEW)

**These tasks refactor existing code to follow idiomatic TypeScript patterns from research.md Section 13**

- [ ] **T061** Refactor RequestIdRef into RaftClient private field:
  - **Current**: `typescript-client/src/utils/requestIdRef.ts` - Standalone Ref object (Scala ZRef port)
  - **Target**: Move into `typescript-client/src/client.ts` as private field
  - **Changes**:
    - Remove `requestIdRef.ts` file
    - Add to RaftClient: `private nextRequestId: bigint = 0n`
    - Add private method: `private allocateRequestId(): RequestId { return RequestId.fromBigInt(this.nextRequestId++) }`
    - Update imports in state machine files
  - **Rationale**: research.md Section 13.1 - Use private class fields, not Ref objects
  - **Files affected**: 
    - DELETE: `typescript-client/src/utils/requestIdRef.ts`
    - MODIFY: `typescript-client/src/client.ts`
    - MODIFY: Any imports of RequestIdRef

- [ ] **T062** Refactor ServerRequestTracker to use mutable private state:
  - **Current**: `typescript-client/src/state/serverRequestTracker.ts` - Returns new instance on update (immutable pattern)
  - **Target**: Use mutable private state
  - **Changes**:
    - Change `acknowledge(id: RequestId): ServerRequestTracker` to `acknowledge(id: RequestId): void`
    - Remove `withLastAcknowledged()` method
    - Update internal state directly: `this.lastAcknowledgedRequestId = id`
    - Update all callers to not expect new instance
  - **Rationale**: research.md Section 13.5 - Mutable private state is fine, don't force immutability
  - **Files affected**:
    - MODIFY: `typescript-client/src/state/serverRequestTracker.ts`
    - MODIFY: Any code calling `acknowledge()` or `withLastAcknowledged()`

- [ ] **T063** [P] Review and simplify branded type helper objects (OPTIONAL):
  - **Current**: `typescript-client/src/types.ts` - Helper objects for branded types
  - **Review**: Check if all helper methods are needed
  - **Guidance**: Keep `fromString()`, `fromBigInt()`, `generate()` - these add value
  - **Consider removing**: `unwrap()` methods if rarely used (can cast directly)
  - **Rationale**: research.md Section 13.7 - Don't overdo the helper objects
  - **Status**: OPTIONAL - Current implementation is acceptable
  - **Files affected**:
    - MAYBE MODIFY: `typescript-client/src/types.ts`

---

## Dependencies (Updated)

### Phase 3.1-3.5: Foundation (COMPLETE)
- T001-T005: ✅ Setup complete
- T006-T008: ✅ Types complete  
- T009-T013: ✅ Codecs complete
- T014-T016: ✅ Transport complete
- T017-T020: ✅ State structures complete (but T017, T020 need refactoring)

### Phase 3.6: State Machine (NEXT)
- T021-T025 depend on T006-T020 (types, transport, state structures)
- T022-T025 are sequential (same file, complex interdependencies)

### Phase 3.7: Events
- T026-T027 depend on T008 (message types)
- Can run parallel [P] (different files)

### Phase 3.8: Client API
- T028-T032 depend on T021-T027 (state machine and events)
- T031-T032 sequential (same file)
- T029, T033 can run parallel [P]

### Phase 3.9-3.13: Testing & Documentation
- T034-T060 depend on T033 (complete client implementation)
- Most test files can run parallel [P]

### Phase 3.14: Refactoring (AFTER Client API)
- T061 depends on T031 (RaftClient class exists)
- T062 can run parallel with T061 [P]
- T063 is optional, can run parallel [P]

---

## Parallel Execution Examples

### After T020 (Before State Machine):
```bash
# Cannot parallelize T021-T025 (same file, sequential state machine logic)
```

### After T025 (Events Phase):
```bash
# Can parallelize T026-T027 (different files):
Task T026: "Define event types in src/events/eventTypes.ts"
Task T027: "Implement event emitter in src/events/eventEmitter.ts"
```

### After T033 (Testing Phase):
```bash
# Can parallelize T034-T044 (different test files):
Task T034: "Codec tests for client messages"
Task T035: "Codec tests for server messages"
Task T037: "State machine Disconnected tests"
... (all [P] tasks in Phase 3.9)
```

### Refactoring Phase (After Client API):
```bash
# Can parallelize T061-T063:
Task T061: "Refactor RequestIdRef into RaftClient"
Task T062: "Refactor ServerRequestTracker mutability"
Task T063: "Review branded type helpers" (optional)
```

---

## Summary

**Total Tasks**: 63 numbered tasks (60 original + 3 refactoring)
- **Setup**: 5 tasks ✅ COMPLETE
- **Core Types**: 3 tasks ✅ COMPLETE
- **Protocol Codecs**: 5 tasks ✅ COMPLETE
- **Transport**: 3 tasks ✅ COMPLETE
- **State Management**: 4 tasks ✅ COMPLETE (2 need refactoring)
- **State Machine**: 5 tasks ⏳ NEXT
- **Events**: 2 tasks
- **Client API**: 6 tasks
- **Unit Tests**: 11 tasks
- **Integration Tests**: 5 tasks
- **Performance/Compatibility**: 4 tasks
- **Optimizations**: 2 tasks
- **Documentation/Polish**: 5 tasks
- **Refactoring**: 3 tasks (NEW)

**Progress**: 20/63 tasks complete (32%)

**Parallelization**: 25 tasks marked [P] for parallel execution

**Estimated Remaining Time**: 
- State Machine: 8-10 hours
- Events + Client API: 10-12 hours  
- Testing: 15-20 hours
- Refactoring: 2-3 hours
- **Total**: ~40-50 hours of focused implementation time

**Idiomatic TypeScript Compliance**: 
- ✅ 17/20 completed tasks are already idiomatic
- ⚠️ 2 tasks (T017, T020) need refactoring
- ✅ All remaining tasks will follow research.md Section 13 guidelines

**Ready for Execution**: ✅ All tasks are specific, have clear file paths, and include idiomatic TypeScript guidance
