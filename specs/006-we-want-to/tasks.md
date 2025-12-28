# Tasks: TypeScript Client Library

**Input**: Design documents from `/specs/006-we-want-to/`
**Prerequisites**: plan.md (complete), research.md (complete), data-model.md (complete), design.md (complete), tests.md (complete), quickstart.md (complete)

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → Extract: TypeScript 5.0+, Node.js 18+, zeromq, Buffer API
   → Project type: Single TypeScript library package
2. Load design documents:
   → data-model.md: Extract 10 core entities
   → research.md: Extract technical decisions (protocol, transport, state machine)
   → tests.md: Extract 100+ test cases across 8 categories
3. Generate tasks by category:
   → Setup: Project init, dependencies, TypeScript config
   → Core: Protocol codecs, transport, state machine, client API
   → Tests: Codec tests, state tests, integration tests
   → Polish: Performance, docs, optimization
4. Apply task rules:
   → Different files = mark [P] for parallel
   → Same file = sequential (no [P])
   → Implementation-first approach (NOT TDD for this TypeScript library)
5. Number tasks sequentially (T001, T002...)
6. Generate dependency graph
7. SUCCESS: Tasks ready for execution
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Path Conventions
- **Single TypeScript library**: `typescript-client/src/`, `typescript-client/tests/`
- All paths relative to repository root

---

## Phase 3.1: Project Setup

- [X] **T001** Create typescript-client project structure:
  - Initialize directory: `typescript-client/`
  - Create subdirectories: `src/`, `src/protocol/`, `src/state/`, `src/transport/`, `src/events/`, `src/utils/`, `tests/unit/`, `tests/integration/`
  - Create placeholder files: `src/index.ts`, `README.md`, `.gitignore`

- [X] **T002** Initialize package.json with dependencies:
  - Package name: `@zio-raft/typescript-client`
  - Dependencies: `zeromq` (latest), `@types/node`
  - DevDependencies: `typescript` (5.0+), `@types/jest`, `jest`, `ts-jest`, `prettier`, `eslint`, `@typescript-eslint/parser`, `@typescript-eslint/eslint-plugin`
  - Scripts: `build`, `test`, `lint`, `format`
  - File: `typescript-client/package.json`

- [X] **T003** [P] Configure TypeScript compiler:
  - Target: ES2020
  - Module: CommonJS
  - Strict mode: enabled
  - Declaration: true (for .d.ts files)
  - Source maps: true
  - Output directory: `dist/`
  - File: `typescript-client/tsconfig.json`

- [X] **T004** [P] Configure ESLint and Prettier:
  - ESLint: TypeScript parser, recommended rules, no-explicit-any, strict-boolean-expressions
  - Prettier: single quotes, trailing commas, 120 char width
  - Files: `typescript-client/.eslintrc.js`, `typescript-client/.prettierrc`

- [X] **T005** [P] Configure Jest for TypeScript:
  - Preset: ts-jest
  - Test environment: node
  - Coverage thresholds: 80% branches, statements, functions, lines
  - File: `typescript-client/jest.config.js`

---

## Phase 3.2: Core Type Definitions

- [X] **T006** [P] Implement branded types in `typescript-client/src/types.ts`:
  - `SessionId` (string brand) with `fromString()`, `unwrap()`
  - `RequestId` (bigint brand) with `zero`, `fromBigInt()`, `next()`, `unwrap()`
  - `MemberId` (string brand) with `fromString()`, `unwrap()`
  - `Nonce` (bigint brand) with `generate()`, `fromBigInt()`, `unwrap()`
  - `CorrelationId` (string brand) with `generate()`, `fromString()`, `unwrap()`

- [X] **T007** [P] Define protocol constants in `typescript-client/src/protocol/constants.ts`:
  - `PROTOCOL_SIGNATURE`: Buffer `[0x7a, 0x72, 0x61, 0x66, 0x74]` ("zraft")
  - `PROTOCOL_VERSION`: number `1`
  - `ClientMessageType` enum (1-8)
  - `ServerMessageType` enum (1-9)
  - `RejectionReasonCode`, `SessionCloseReasonCode`, `CloseReasonCode`, `RequestErrorReasonCode` enums

- [X] **T008** Define protocol message types in `typescript-client/src/protocol/messages.ts`:
  - Client messages: `CreateSession`, `ContinueSession`, `KeepAlive`, `ClientRequest`, `Query`, `ServerRequestAck`, `CloseSession`, `ConnectionClosed`
  - Server messages: `SessionCreated`, `SessionContinued`, `SessionRejected`, `SessionClosed`, `KeepAliveResponse`, `ClientResponse`, `QueryResponse`, `ServerRequest`, `RequestError`
  - Discriminated unions: `ClientMessage`, `ServerMessage`
  - Reason types: `RejectionReason`, `SessionCloseReason`, `CloseReason`, `RequestErrorReason`

---

## Phase 3.3: Binary Protocol Codecs

- [ ] **T009** Implement encoding primitives in `typescript-client/src/protocol/codecs.ts`:
  - `encodeString(str: string): Buffer` - uint16 length prefix + UTF-8 bytes
  - `encodePayload(payload: Buffer): Buffer` - int32 length prefix + raw bytes
  - `encodeMap(map: Map<string, string>): Buffer` - uint16 count + entries
  - `encodeTimestamp(date: Date): Buffer` - int64 epoch milliseconds
  - `encodeNonce(nonce: Nonce): Buffer` - 8 bytes (bigint)
  - `encodeRequestId(id: RequestId): Buffer` - 8 bytes (bigint)
  - `encodeSessionId(id: SessionId): Buffer` - uint16 length + UTF-8
  - `encodeMemberId(id: MemberId): Buffer` - uint16 length + UTF-8
  - `encodeCorrelationId(id: CorrelationId): Buffer` - uint16 length + UTF-8

- [ ] **T010** Implement decoding primitives in `typescript-client/src/protocol/codecs.ts`:
  - `decodeString(buffer: Buffer, offset: number): { value: string, newOffset: number }`
  - `decodePayload(buffer: Buffer, offset: number): { value: Buffer, newOffset: number }`
  - `decodeMap(buffer: Buffer, offset: number): { value: Map<string, string>, newOffset: number }`
  - `decodeTimestamp(buffer: Buffer, offset: number): { value: Date, newOffset: number }`
  - `decodeNonce(buffer: Buffer, offset: number): { value: Nonce, newOffset: number }`
  - `decodeRequestId(buffer: Buffer, offset: number): { value: RequestId, newOffset: number }`
  - `decodeSessionId(buffer: Buffer, offset: number): { value: SessionId, newOffset: number }`
  - `decodeMemberId(buffer: Buffer, offset: number): { value: MemberId, newOffset: number }`
  - `decodeCorrelationId(buffer: Buffer, offset: number): { value: CorrelationId, newOffset: number }`

- [ ] **T011** Implement protocol header codec in `typescript-client/src/protocol/codecs.ts`:
  - `encodeProtocolHeader(): Buffer` - signature (5 bytes) + version (1 byte)
  - `decodeProtocolHeader(buffer: Buffer, offset: number): number` - verify signature and version, return newOffset
  - Throw error if signature doesn't match or version != 1

- [ ] **T012** Implement client message encoding in `typescript-client/src/protocol/codecs.ts`:
  - `encodeClientMessage(message: ClientMessage): Buffer`
  - Protocol header + message type discriminator + message-specific fields
  - Handle all 8 client message types per discriminator

- [ ] **T013** Implement server message decoding in `typescript-client/src/protocol/codecs.ts`:
  - `decodeServerMessage(buffer: Buffer): ServerMessage`
  - Verify protocol header, read discriminator, decode message-specific fields
  - Handle all 9 server message types per discriminator

---

## Phase 3.4: Transport Layer

- [ ] **T014** [P] Define transport interface in `typescript-client/src/transport/transport.ts`:
  - Interface `ClientTransport` with methods:
    - `connect(address: string): Promise<void>`
    - `disconnect(): Promise<void>`
    - `send(message: ClientMessage): Promise<void>`
    - `receive(): AsyncIterator<ServerMessage>`

- [ ] **T015** Implement ZMQ transport in `typescript-client/src/transport/zmqTransport.ts`:
  - Class `ZmqTransport implements ClientTransport`
  - Constructor: create zeromq DEALER socket, configure options (linger=0, heartbeat, high water marks)
  - `connect()`: connect to ZMQ address, store current address
  - `disconnect()`: disconnect from current address
  - `send()`: encode message, send via socket
  - `receive()`: async generator yielding decoded server messages from socket

- [ ] **T016** [P] Implement mock transport for testing in `typescript-client/src/transport/mockTransport.ts`:
  - Class `MockTransport implements ClientTransport`
  - In-memory queue for sent messages
  - Manual control for injecting server messages
  - Useful for unit testing without real ZMQ

---

## Phase 3.5: State Management Structures

- [ ] **T017** [P] Implement RequestId counter in `typescript-client/src/utils/requestIdRef.ts`:
  - Interface `RequestIdRef` with `current: RequestId` and `next(): RequestId`
  - Factory function `createRequestIdRef(): RequestIdRef` starting at 0n
  - Thread-safe increment (Node.js single-threaded, but document the pattern)

- [ ] **T018** Implement PendingRequests tracker in `typescript-client/src/state/pendingRequests.ts`:
  - Interface `PendingRequestData`: `payload`, `resolve`, `reject`, `createdAt`, `lastSentAt`
  - Class `PendingRequests` with Map<RequestId, PendingRequestData>
  - Methods: `add()`, `complete()`, `contains()`, `lowestPendingRequestIdOr()`, `resendAll()`, `resendExpired()`, `dieAll()`

- [ ] **T019** [P] Implement PendingQueries tracker in `typescript-client/src/state/pendingQueries.ts`:
  - Interface `PendingQueryData`: `payload`, `resolve`, `reject`, `createdAt`, `lastSentAt`
  - Class `PendingQueries` with Map<CorrelationId, PendingQueryData>
  - Methods: `add()`, `complete()`, `contains()`, `resendAll()`, `resendExpired()`, `dieAll()`

- [ ] **T020** [P] Implement ServerRequestTracker in `typescript-client/src/state/serverRequestTracker.ts`:
  - Interface `ServerRequestTracker` with `lastAcknowledgedRequestId: RequestId`
  - Type `ServerRequestResult`: `Process | OldRequest | OutOfOrder`
  - Method `shouldProcess(requestId: RequestId): ServerRequestResult`
  - Method `acknowledge(requestId: RequestId): ServerRequestTracker`

---

## Phase 3.6: State Machine Implementation

- [ ] **T021** Define state types in `typescript-client/src/state/clientState.ts`:
  - Type `ClientState` = discriminated union of:
    - `DisconnectedState`
    - `ConnectingNewSessionState`
    - `ConnectingExistingSessionState`
    - `ConnectedState`
  - Each state interface per data-model.md design

- [ ] **T022** Implement Disconnected state handler in `typescript-client/src/state/clientState.ts`:
  - `class DisconnectedStateHandler`
  - `handle(event: StreamEvent): Promise<ClientState>`
  - Handle `Connect` action: transition to ConnectingNewSession, send CreateSession
  - Handle `SubmitCommand`/`SubmitQuery`: reject with "Not connected"
  - Handle server messages: log warning, stay in Disconnected

- [ ] **T023** Implement ConnectingNewSession state handler in `typescript-client/src/state/clientState.ts`:
  - `class ConnectingNewSessionStateHandler`
  - Handle `SessionCreated`: transition to Connected if nonce matches, resend pending
  - Handle `SessionRejected(NotLeader)`: try leader or next member
  - Handle `SessionRejected(InvalidCapabilities)`: fail pending, terminate
  - Handle `SubmitCommand`/`SubmitQuery`: queue in pending
  - Handle timeout: try next member

- [ ] **T024** Implement ConnectingExistingSession state handler in `typescript-client/src/state/clientState.ts`:
  - `class ConnectingExistingSessionStateHandler`
  - Handle `SessionContinued`: transition to Connected if nonce matches, resend pending
  - Handle `SessionRejected(NotLeader)`: try leader or next member
  - Handle `SessionRejected(SessionExpired)`: fail pending, terminate
  - Handle timeout: try next member

- [ ] **T025** Implement Connected state handler in `typescript-client/src/state/clientState.ts`:
  - `class ConnectedStateHandler`
  - Handle `SubmitCommand`: allocate RequestId, send ClientRequest, track pending
  - Handle `SubmitQuery`: generate CorrelationId, send Query, track pending
  - Handle `ClientResponse`: complete pending request
  - Handle `QueryResponse`: complete pending query
  - Handle `SessionClosed(NotLeaderAnymore)`: reconnect to leader
  - Handle `SessionClosed(SessionExpired)`: fail all pending, terminate
  - Handle `KeepAliveTick`: send KeepAlive
  - Handle `TimeoutCheck`: resend expired requests/queries
  - Handle `Disconnect`: send CloseSession, disconnect transport
  - Handle `ServerRequest`: emit event, send ack, track for deduplication

---

## Phase 3.7: Event System

- [ ] **T026** [P] Define event types in `typescript-client/src/events/eventTypes.ts`:
  - Type `ClientEvent` = discriminated union of:
    - `StateChangeEvent`, `ConnectionAttemptEvent`, `ConnectionSuccessEvent`, `ConnectionFailureEvent`
    - `MessageReceivedEvent`, `MessageSentEvent`
    - `RequestTimeoutEvent`, `QueryTimeoutEvent`
    - `SessionExpiredEvent`, `ServerRequestReceivedEvent`
  - Each event interface with readonly fields

- [ ] **T027** [P] Implement event emitter utilities in `typescript-client/src/events/eventEmitter.ts`:
  - Type-safe event emitter wrapper using Node.js EventEmitter
  - Type-safe `on()` and `off()` methods for each ClientEvent type
  - `emit()` helper that takes ClientEvent and dispatches to listeners

---

## Phase 3.8: Client API Implementation

- [ ] **T028** Implement ClientConfig in `typescript-client/src/config.ts`:
  - Interface `ClientConfig` with fields: `clusterMembers`, `capabilities`, `connectionTimeout`, `keepAliveInterval`, `requestTimeout`
  - Default constants: `DEFAULT_CONNECTION_TIMEOUT` (5000ms), `DEFAULT_KEEP_ALIVE_INTERVAL` (30000ms), `DEFAULT_REQUEST_TIMEOUT` (10000ms)
  - `validate(config: ClientConfig): void` - throw if empty clusterMembers or capabilities

- [ ] **T029** [P] Implement action queue in `typescript-client/src/utils/asyncQueue.ts`:
  - Class `AsyncQueue<T>` with async `put(item: T)` and `take(): Promise<T>`
  - Used for queuing user actions (connect, disconnect, submit)

- [ ] **T030** Implement unified event loop in `typescript-client/src/client.ts`:
  - Function `createUnifiedEventStream()`: AsyncIterator<StreamEvent>
  - Merge action queue, ZMQ messages, keep-alive timer, timeout timer
  - Use Promise.race() pattern to await next event from any source

- [ ] **T031** Implement RaftClient class in `typescript-client/src/client.ts`:
  - Class `RaftClient` with constructor taking `ClientConfig`
  - Fields: transport, config, actionQueue, serverRequestQueue, eventEmitter
  - Public methods: `connect()`, `disconnect()`, `submitCommand()`, `query()`, `serverRequests()`, `on()`, `off()`
  - Private method: `run()` - event loop that folds state transitions

- [ ] **T032** Implement client lifecycle in `typescript-client/src/client.ts`:
  - `connect()`: enqueue Connect action
  - `disconnect()`: enqueue Disconnect action
  - `submitCommand()`: enqueue SubmitCommand action, return promise
  - `query()`: enqueue SubmitQuery action, return promise
  - `serverRequests()`: async iterator from serverRequestQueue
  - `run()`: start event loop, fold over unified event stream with state machine

- [ ] **T033** [P] Export public API in `typescript-client/src/index.ts`:
  - Export `RaftClient`, `ClientConfig`
  - Export branded types: `SessionId`, `RequestId`, `MemberId`, `Nonce`, `CorrelationId`
  - Export event types: `ClientEvent` and all subtypes
  - Export error classes: `ValidationError`, `NetworkError`, `SessionError`, `TimeoutError`

---

## Phase 3.9: Unit Tests

- [ ] **T034** [P] Codec tests for client messages in `typescript-client/tests/unit/codecs.clientMessages.test.ts`:
  - Test TC-CODEC-001 through TC-CODEC-008 (all client message types)
  - Verify encoding produces correct binary format
  - Verify roundtrip encoding then decoding equals original

- [ ] **T035** [P] Codec tests for server messages in `typescript-client/tests/unit/codecs.serverMessages.test.ts`:
  - Test TC-CODEC-101 through TC-CODEC-109 (all server message types)
  - Verify decoding parses binary correctly
  - Verify roundtrip decoding then encoding (if applicable)

- [ ] **T036** [P] Codec edge case tests in `typescript-client/tests/unit/codecs.edgeCases.test.ts`:
  - Test TC-CODEC-301 through TC-CODEC-304
  - Empty capabilities, large payloads, invalid signature, wrong version

- [ ] **T037** [P] State machine Disconnected tests in `typescript-client/tests/unit/state.disconnected.test.ts`:
  - Test TC-STATE-001 through TC-STATE-003
  - Verify transitions from Disconnected state

- [ ] **T038** [P] State machine ConnectingNewSession tests in `typescript-client/tests/unit/state.connectingNew.test.ts`:
  - Test TC-STATE-101 through TC-STATE-105
  - Verify transitions during new session connection

- [ ] **T039** [P] State machine ConnectingExistingSession tests in `typescript-client/tests/unit/state.connectingExisting.test.ts`:
  - Test TC-STATE-201 through TC-STATE-203
  - Verify transitions during session resumption

- [ ] **T040** [P] State machine Connected tests in `typescript-client/tests/unit/state.connected.test.ts`:
  - Test TC-STATE-301 through TC-STATE-312
  - Verify all operations in connected state

- [ ] **T041** [P] State machine reconnection tests in `typescript-client/tests/unit/state.reconnection.test.ts`:
  - Test TC-STATE-401
  - Verify pending requests preserved across reconnection

- [ ] **T042** [P] Transport tests in `typescript-client/tests/unit/transport.test.ts`:
  - Test TC-TRANSPORT-001 through TC-TRANSPORT-006
  - Use mock ZMQ socket to avoid real network

- [ ] **T043** [P] PendingRequests tests in `typescript-client/tests/unit/pendingRequests.test.ts`:
  - Test TC-PENDING-001 through TC-PENDING-006
  - Verify add, complete, timeout, resend, die operations

- [ ] **T044** [P] PendingQueries tests in `typescript-client/tests/unit/pendingQueries.test.ts`:
  - Test TC-PENDING-101 through TC-PENDING-103
  - Similar to PendingRequests but with CorrelationId

---

## Phase 3.10: Integration Tests

- [ ] **T045** Integration test setup in `typescript-client/tests/integration/setup.ts`:
  - Helper to start mock Raft server (or connect to real kvstore)
  - Helper to create RaftClient with test config
  - Teardown helpers

- [ ] **T046** Basic operations integration tests in `typescript-client/tests/integration/basicOperations.test.ts`:
  - Test TC-INTEGRATION-001 through TC-INTEGRATION-005
  - Connect/disconnect, submit command, submit query, multiple commands, multiple queries

- [ ] **T047** Reconnection scenarios integration tests in `typescript-client/tests/integration/reconnection.test.ts`:
  - Test TC-INTEGRATION-101 through TC-INTEGRATION-103
  - Network failure, leader change, election in progress

- [ ] **T048** Error scenarios integration tests in `typescript-client/tests/integration/errors.test.ts`:
  - Test TC-INTEGRATION-201 through TC-INTEGRATION-203
  - Session expiry, invalid capabilities, request timeout

- [ ] **T049** Server-initiated requests integration tests in `typescript-client/tests/integration/serverRequests.test.ts`:
  - Test TC-INTEGRATION-301 through TC-INTEGRATION-302
  - Receive and ack, duplicate handling

---

## Phase 3.11: Performance & Compatibility Tests

- [ ] **T050** [P] Performance throughput tests in `typescript-client/tests/performance/throughput.test.ts`:
  - Test TC-PERF-001 through TC-PERF-003
  - Measure 1K req/sec sustained, 10K req/sec burst, memory usage

- [ ] **T051** [P] Performance latency tests in `typescript-client/tests/performance/latency.test.ts`:
  - Test TC-PERF-101 through TC-PERF-102
  - Measure P50 and P99 latency

- [ ] **T052** Protocol compatibility tests in `typescript-client/tests/compatibility/scala.test.ts`:
  - Test TC-COMPAT-001 through TC-COMPAT-003
  - Connect to real Scala server, submit commands, receive server requests

- [ ] **T053** [P] Golden file codec tests in `typescript-client/tests/compatibility/goldenFiles.test.ts`:
  - Test TC-COMPAT-101 through TC-COMPAT-102
  - Decode Scala-encoded messages from files, encode and compare with Scala output

---

## Phase 3.12: Performance Optimizations

- [ ] **T054** [P] Implement request batching in `typescript-client/src/utils/batcher.ts`:
  - Class `RequestBatcher` with configurable batch size and time window
  - Accumulate requests, flush on threshold or timer
  - Integrate into RaftClient send path

- [ ] **T055** [P] Implement buffer pooling in `typescript-client/src/utils/bufferPool.ts`:
  - Class `BufferPool` with acquire/release methods
  - Reuse buffers for encoding to reduce GC pressure
  - Integrate into codec functions

---

## Phase 3.13: Documentation & Polish

- [ ] **T056** [P] Write comprehensive README in `typescript-client/README.md`:
  - Installation instructions
  - Quick start example
  - API documentation overview
  - Link to generated TypeDoc
  - Performance characteristics
  - Protocol compatibility notes

- [ ] **T057** [P] Add TSDoc comments to all public APIs:
  - Document all exported classes, interfaces, types, functions
  - Include examples for complex APIs
  - Describe error conditions

- [ ] **T058** [P] Generate API documentation with TypeDoc:
  - Configure TypeDoc in package.json
  - Generate HTML docs to `typescript-client/docs/`
  - Include in README

- [ ] **T059** Run full test suite and fix any issues:
  - Execute `npm test` to run all unit, integration, performance tests
  - Ensure 80%+ code coverage
  - Fix any failing tests

- [ ] **T060** Run linter and formatter:
  - Execute `npm run lint` and fix all errors
  - Execute `npm run format` to format all files
  - Verify no ESLint or TypeScript errors

---

## Dependencies

### Foundation (T001-T005)
- T001 blocks T002-T005 (need directory structure first)
- T002-T005 can run in parallel after T001

### Types & Constants (T006-T008)
- T006-T007 can run in parallel (independent files)
- T008 depends on T006 (uses branded types)

### Protocol Codecs (T009-T013)
- T009 depends on T006-T007 (uses types and constants)
- T010 depends on T006-T007
- T011 depends on T007 (uses constants)
- T012 depends on T008-T011 (uses all primitives and message types)
- T013 depends on T008-T011

### Transport (T014-T016)
- T014 depends on T008 (uses message types)
- T015 depends on T012-T014 (uses encoding and interface)
- T016 depends on T014 (implements interface)

### State Management (T017-T020)
- T017 depends on T006 (uses RequestId)
- T018 depends on T006, T017 (uses RequestId, RequestIdRef)
- T019 depends on T006 (uses CorrelationId)
- T020 depends on T006 (uses RequestId)

### State Machine (T021-T025)
- T021 depends on T006, T017-T020 (uses all state management types)
- T022-T025 depend on T021 (implement state handlers)
- T022-T025 are sequential (same file, complex interdependencies)

### Events (T026-T027)
- T026 depends on T008 (uses message types)
- T027 depends on T026 (uses event types)

### Client API (T028-T033)
- T028 depends on T006 (uses branded types)
- T029 is independent (utility)
- T030 depends on T022-T027 (uses state machine and events)
- T031 depends on T028-T030 (main client class)
- T032 depends on T031 (lifecycle methods)
- T033 depends on T001-T032 (exports everything)

### Unit Tests (T034-T044)
- T034-T036 depend on T012-T013 (codec tests need codecs)
- T037-T041 depend on T022-T025 (state machine tests need state handlers)
- T042 depends on T015-T016 (transport tests need transport)
- T043-T044 depend on T018-T019 (pending tracker tests need trackers)

### Integration Tests (T045-T049)
- T045 depends on T033 (setup uses full client)
- T046-T049 depend on T045 (use test helpers)

### Performance & Compatibility (T050-T053)
- T050-T051 depend on T033 (need full client for benchmarks)
- T052-T053 depend on T033 (need full client for compatibility)

### Optimizations (T054-T055)
- T054 depends on T031 (integrate into client)
- T055 depends on T012-T013 (integrate into codecs)

### Documentation (T056-T060)
- T056-T058 can run in parallel with implementation
- T059 depends on T034-T053 (run full test suite)
- T060 depends on T001-T055 (lint/format all code)

---

## Parallel Execution Examples

### Early Parallel (after T001 complete):
```bash
# Can run T002-T005 together:
Task T002: "Initialize package.json with zeromq, typescript, jest dependencies"
Task T003: "Configure tsconfig.json with strict mode, ES2020 target"
Task T004: "Configure ESLint and Prettier for TypeScript"
Task T005: "Configure Jest for TypeScript with ts-jest preset"
```

### Types Parallel (after T001-T005 complete):
```bash
# Can run T006-T007 together:
Task T006: "Implement branded types in src/types.ts"
Task T007: "Define protocol constants in src/protocol/constants.ts"
```

### Test Parallel (after T022-T025 complete):
```bash
# Can run T037-T044 together (different test files):
Task T037: "State machine Disconnected tests"
Task T038: "State machine ConnectingNewSession tests"
Task T039: "State machine ConnectingExistingSession tests"
Task T040: "State machine Connected tests"
Task T041: "State machine reconnection tests"
Task T042: "Transport tests"
Task T043: "PendingRequests tests"
Task T044: "PendingQueries tests"
```

---

## Notes

- **[P] tasks**: Different files, no dependencies - can execute in parallel
- **Implementation-first approach**: This is a new TypeScript library, not modifying existing Scala code, so we implement then test (not TDD)
- **Commit after each task**: Small, focused commits make review easier
- **TypeScript strict mode**: Enforces type safety throughout
- **Protocol compatibility**: Critical - must match Scala encoding byte-for-byte

---

## Validation Checklist
*GATE: Checked before marking tasks complete*

- [ ] All protocol messages have encoding/decoding tests with roundtrip verification
- [ ] All state machine transitions have corresponding unit tests
- [ ] Integration tests verify end-to-end scenarios against mock/real server
- [ ] Performance tests validate 1K-10K+ req/sec throughput requirement
- [ ] Compatibility tests confirm wire protocol matches Scala implementation
- [ ] Code coverage >= 80% for branches, statements, functions, lines
- [ ] No ESLint errors or TypeScript compilation errors
- [ ] All public APIs have TSDoc comments
- [ ] README includes installation, quick start, and API documentation links

---

## Summary

**Total Tasks**: 60 numbered tasks
- **Setup**: 5 tasks (T001-T005)
- **Core Types**: 3 tasks (T006-T008)
- **Protocol Codecs**: 5 tasks (T009-T013)
- **Transport**: 3 tasks (T014-T016)
- **State Management**: 4 tasks (T017-T020)
- **State Machine**: 5 tasks (T021-T025)
- **Events**: 2 tasks (T026-T027)
- **Client API**: 6 tasks (T028-T033)
- **Unit Tests**: 11 tasks (T034-T044)
- **Integration Tests**: 5 tasks (T045-T049)
- **Performance/Compatibility**: 4 tasks (T050-T053)
- **Optimizations**: 2 tasks (T054-T055)
- **Documentation/Polish**: 5 tasks (T056-T060)

**Parallelization**: 25 tasks marked [P] for parallel execution

**Estimated Completion**: 60 tasks × average 30-60 minutes per task = 30-60 hours of focused implementation time

**Ready for Execution**: ✅ All tasks are specific, have clear file paths, and include acceptance criteria from tests.md

