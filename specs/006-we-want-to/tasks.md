# Tasks: TypeScript Client Library

**Feature Branch**: `006-we-want-to`  
**Input**: Design documents from `/Users/keisar/Desktop/Projects/Unit/zio-raft/specs/006-we-want-to/`  
**Prerequisites**: plan.md ✅, research.md ✅, data-model.md ✅, design.md ✅, tests.md ✅, quickstart.md ✅

## 🎯 Implementation Status (as of 2025-12-30)

**Phase 3.1-3.9: Core Implementation** ✅ **COMPLETE** (T001-T035)
- ✅ All 35 implementation tasks completed
- ✅ ~4,300 LOC production code written
- ✅ TypeScript builds successfully with strict mode
- ✅ 32/32 unit tests passing
- ✅ Buffer architecture decision implemented and documented

**Phase 3.10: Unit Tests** ⚠️ **IN PROGRESS** (T036-T040)
- ✅ 32 unit tests implemented and passing
- ⚠️ Additional test scenarios remain (T036-T040)

**Phase 3.11-3.12: Integration & Performance Tests** ❌ **NOT STARTED** (T041-T047)
- Integration tests require running Scala server
- Performance benchmarks require controlled environment

**Phase 3.13: Documentation** ❌ **NOT STARTED** (T048-T050)
- README, API docs, examples pending

## Overview

This task list implements an idiomatic TypeScript/Node.js client library for ZIO Raft clusters. The client provides wire protocol compatibility with the Scala client while using modern Node.js patterns (EventEmitter, Promises, async/await).

**Target**: ~15-20 TypeScript source files, 95 test scenarios, comprehensive type definitions

---

## Path Conventions

**Project Root**: `/Users/keisar/Desktop/Projects/Unit/zio-raft/typescript-client/`

**Structure**:
```
typescript-client/
├── src/
│   ├── index.ts
│   ├── client.ts
│   ├── config.ts
│   ├── types.ts
│   ├── errors.ts
│   ├── state/
│   ├── protocol/
│   ├── transport/
│   ├── utils/
│   └── events/
├── tests/
│   ├── unit/
│   └── integration/
└── package.json
```

---

## Phase 3.1: Project Setup & Infrastructure

### T001: ✅ Update package.json and Dependencies
**Files**: `typescript-client/package.json`  
**Status**: ✅ COMPLETED  
**Description**: Update package.json with correct dependencies and scripts
- Update dependencies: `zeromq` (v6.x), remove `@msgpack/msgpack` (not needed per research)
- Add dev dependencies: `vitest` (replace Jest), `@vitest/ui`, `c8` (coverage)
- Update scripts: `test`, `test:watch`, `test:coverage`, `build`, `lint`, `format`
- Verify Node.js engine: >= 18.0.0
- Add repository, author, license metadata

**Acceptance**: package.json has correct dependencies and scripts

---

### T002: ✅ Update TypeScript Configuration
**Files**: `typescript-client/tsconfig.json`  
**Status**: ✅ COMPLETED  
**Description**: Configure TypeScript for strict type safety
- Enable `strict: true`
- Set `target: "ES2022"`, `module: "commonjs"`, `moduleResolution: "node"`
- Configure `outDir: "dist"`, `rootDir: "src"`
- Add `types: ["node", "vitest"]`
- Enable `declaration: true` for type definitions
- Set `skipLibCheck: true` for faster builds

**Acceptance**: TypeScript compiles without errors with strict mode

---

### T003: ✅ Configure Vitest Test Framework
**Files**: `typescript-client/vitest.config.ts`  
**Status**: ✅ COMPLETED  
**Description**: Create Vitest configuration for unit and integration tests
- Configure test environment: `node`
- Set up coverage: `c8` provider, thresholds (80% lines, branches, functions)
- Configure test file patterns: `**/*.test.ts`
- Add separate configs for unit vs integration tests
- Enable watch mode and UI

**Acceptance**: `npm test` runs successfully (even with 0 tests)

---

### T004: ✅ Update ESLint and Prettier Configuration
**Files**: `typescript-client/.eslintrc.js`, `typescript-client/.prettierrc.js`  
**Status**: ✅ COMPLETED  
**Description**: Configure linting and formatting
- ESLint: TypeScript parser, recommended rules, no `any` types
- Prettier: 2-space indent, single quotes, trailing commas
- Add lint-staged for pre-commit hooks (optional)

**Acceptance**: `npm run lint` and `npm run format:check` pass

---

## Phase 3.2: Core Type Definitions

### T005: ✅ Implement Core Types (types.ts)
**Files**: `typescript-client/src/types.ts`  
**Status**: ✅ COMPLETED  
**Description**: Define core type aliases and newtypes
- `SessionId` = `string` (UUID format)
- `RequestId` = `bigint` (uint64)
- `CorrelationId` = `string` (UUID format)
- `Nonce` = `bigint` (uint64)
- `MemberId` = `string`
- `Capabilities` = `Record<string, string>`
- Add JSDoc comments for each type

**Acceptance**: All core types defined with proper TypeScript types

---

### T006: ✅ Implement Error Class Hierarchy (errors.ts)
**Files**: `typescript-client/src/errors.ts`  
**Status**: ✅ COMPLETED  
**Description**: Create custom error classes
- `RaftClientError` (base class extends Error)
- `ValidationError` (synchronous validation failures)
- `TimeoutError` (request timeouts, includes requestId/correlationId)
- `ConnectionError` (transport failures, includes endpoint and cause)
- `SessionExpiredError` (session terminated by server)
- `ProtocolError` (wire protocol violations)
- Each error has proper `name` property and typed fields

**Acceptance**: All error classes defined, instanceof checks work

---

### T007: ✅ Implement Client Configuration (config.ts)
**Files**: `typescript-client/src/config.ts`  
**Status**: ✅ COMPLETED  
**Description**: Define ClientConfig interface and validation
- `ClientConfig` interface with required and optional fields
- Validation function: `validateConfig(config): void | throws ValidationError`
- Check: endpoints non-empty, capabilities non-empty, positive timeouts
- Default values: connectionTimeout=5000, requestTimeout=30000, keepAliveInterval=30000, queuedRequestTimeout=60000, maxReconnectAttempts=Infinity, reconnectInterval=1000

**Acceptance**: Config validates correctly, throws ValidationError for bad input

---

## Phase 3.3: Protocol Layer (Binary Codecs)

### T008 [P]: Implement Protocol Constants (protocol/constants.ts)
**Files**: `typescript-client/src/protocol/constants.ts`  
**Description**: Define protocol signature, version, discriminators
- `PROTOCOL_SIGNATURE`: `0x7a 0x72 0x61 0x66 0x74` ("zraft")
- `PROTOCOL_VERSION`: `0x01`
- ClientMessage discriminators (CreateSession=1, ContinueSession=2, KeepAlive=3, ClientRequest=4, ServerRequestAck=5, CloseSession=6, ConnectionClosed=7, Query=8)
- ServerMessage discriminators (SessionCreated=1, SessionContinued=2, SessionRejected=3, SessionClosed=4, KeepAliveResponse=5, ClientResponse=6, ServerRequest=7, RequestError=8, QueryResponse=9)

**Acceptance**: All constants defined and exported

---

### T009 [P]: Define Protocol Message Types (protocol/messages.ts)
**Files**: `typescript-client/src/protocol/messages.ts`  
**Description**: Define TypeScript types for all protocol messages
- `ClientMessage` discriminated union (all client → server messages)
- `ServerMessage` discriminated union (all server → client messages)
- Individual message types: `CreateSession`, `ContinueSession`, `KeepAlive`, `ClientRequest`, `ServerRequestAck`, `CloseSession`, `ConnectionClosed`, `Query`
- Individual response types: `SessionCreated`, `SessionContinued`, `SessionRejected`, `SessionClosed`, `KeepAliveResponse`, `ClientResponse`, `ServerRequest`, `RequestError`, `QueryResponse`
- Rejection and close reason enums

**Acceptance**: All message types defined with discriminated unions

---

### T010: Implement Field-Level Encoders (protocol/fields.ts)
**Files**: `typescript-client/src/protocol/fields.ts`  
**Description**: Implement low-level field encoding/decoding
- `encodeString(str: string): Buffer` - length-prefixed UTF-8
- `decodeString(buffer: Buffer, offset: number): [string, number]`
- `encodeBigInt(value: bigint): Buffer` - 8 bytes little-endian
- `decodeBigInt(buffer: Buffer, offset: number): [bigint, number]`
- `encodeUUID(uuid: string): Buffer` - 16 bytes binary (no dashes)
- `decodeUUID(buffer: Buffer, offset: number): [string, number]`
- `encodeTimestamp(date: Date): Buffer` - ISO 8601 string, length-prefixed
- `decodeTimestamp(buffer: Buffer, offset: number): [Date, number]`
- `encodePayload(payload: Uint8Array): Buffer` - length-prefixed bytes
- `decodePayload(buffer: Buffer, offset: number): [Uint8Array, number]`
- `encodeCapabilities(caps: Record<string, string>): Buffer` - count + key/value pairs
- `decodeCapabilities(buffer: Buffer, offset: number): [Record<string, string>, number]`

**Acceptance**: All field codecs round-trip correctly (encode → decode → equals original)

---

### T011: Implement ClientMessage Encoding (protocol/codecs.ts part 1)
**Files**: `typescript-client/src/protocol/codecs.ts`  
**Description**: Implement `encodeClientMessage(msg: ClientMessage): Buffer`
- Write protocol header (signature + version)
- Write discriminator based on message type
- Encode message-specific fields using field encoders
- Handle all 8 ClientMessage types
- Pre-allocate buffer with reasonable size (1KB), resize if needed
- Return buffer with exact length (subarray)

**Acceptance**: All ClientMessage types encode correctly

---

### T012: Implement ServerMessage Decoding (protocol/codecs.ts part 2)
**Files**: `typescript-client/src/protocol/codecs.ts`  
**Description**: Implement `decodeServerMessage(buffer: Buffer): ServerMessage`
- Validate protocol header (signature + version)
- Read discriminator
- Decode message-specific fields using field decoders
- Handle all 9 ServerMessage types
- Throw ProtocolError for invalid messages
- Return typed ServerMessage object

**Acceptance**: All ServerMessage types decode correctly

---

## Phase 3.4: Transport Layer

### T013: Define Transport Interface (transport/transport.ts)
**Files**: `typescript-client/src/transport/transport.ts`  
**Description**: Define ClientTransport interface
- `connect(endpoint: string): Promise<void>`
- `disconnect(): Promise<void>`
- `sendMessage(msg: ClientMessage): Promise<void>`
- `incomingMessages: AsyncIterable<ServerMessage>`
- `onDisconnect(handler: () => void): void`

**Acceptance**: Interface defined and exported

---

### T014: Implement ZMQ Transport (transport/zmqTransport.ts)
**Files**: `typescript-client/src/transport/zmqTransport.ts`  
**Description**: Implement ClientTransport using zeromq
- Create ZMQ DEALER socket
- Implement connect/disconnect methods
- Implement sendMessage: encode ClientMessage, send via socket
- Implement incomingMessages async generator: receive from socket, decode ServerMessage
- Handle ZMQ errors gracefully (log, don't crash)
- Implement disconnect detection and callback

**Acceptance**: ZMQ transport connects, sends/receives messages

---

### T015 [P]: Implement Mock Transport for Testing (transport/mockTransport.ts)
**Files**: `typescript-client/src/transport/mockTransport.ts`  
**Description**: Implement in-memory mock transport for unit tests
- Implements ClientTransport interface
- Queues for sent/received messages
- Manual message injection for testing
- Synchronous operations for deterministic tests

**Acceptance**: Mock transport works in unit tests

---

## Phase 3.5: State Machine Implementation

### T016 [P]: Define State Machine Types (state/clientState.ts part 1)
**Files**: `typescript-client/src/state/clientState.ts`  
**Description**: Define ClientState discriminated union and event types
- `ClientState` union: `DisconnectedState | ConnectingNewSessionState | ConnectingExistingSessionState | ConnectedState`
- Define each state interface with all required fields
- `StreamEvent` union: `ActionEvent | ServerMessageEvent | KeepAliveTickEvent | TimeoutCheckEvent`
- `ClientAction` types: `Connect | SubmitCommand | SubmitQuery | Disconnect`
- `StateHandler` interface: `handle(event: StreamEvent, ...): Promise<ClientState>`

**Acceptance**: All state and event types defined

---

### T017: Implement DisconnectedState Handler (state/clientState.ts part 2)
**Files**: `typescript-client/src/state/clientState.ts`  
**Description**: Implement `DisconnectedState.handle()` method
- Handle `Connect` action: generate nonce, connect transport, send CreateSession, transition to ConnectingNewSession
- Handle `SubmitCommand`/`SubmitQuery`: reject promise with "Not connected" error
- Handle other events: log and stay in Disconnected

**Acceptance**: Disconnected state handles all events correctly

---

### T018: Implement ConnectingNewSessionState Handler (state/clientState.ts part 3)
**Files**: `typescript-client/src/state/clientState.ts`  
**Description**: Implement `ConnectingNewSessionState.handle()` method
- Handle `SessionCreated`: store sessionId, emit 'connected' event, transition to Connected
- Handle `SessionRejected`: disconnect, try next cluster member, send CreateSession again, stay in ConnectingNewSession
- Handle `SubmitCommand`/`SubmitQuery`: add to pending queue (wait for connection)
- Handle timeout: retry with next cluster member

**Acceptance**: ConnectingNewSession handles session creation flow

---

### T019: Implement ConnectingExistingSessionState Handler (state/clientState.ts part 4)
**Files**: `typescript-client/src/state/clientState.ts`  
**Description**: Implement `ConnectingExistingSessionState.handle()` method
- Handle `SessionContinued`: emit 'connected', resend pending requests, transition to Connected
- Handle `SessionRejected(SessionNotFound)`: fall back to CreateSession, transition to ConnectingNewSession
- Handle `SessionRejected(SessionExpired)`: emit 'sessionExpired', fail all pending requests, terminate
- Handle `SubmitCommand`/`SubmitQuery`: add to pending queue

**Acceptance**: ConnectingExisting handles session resumption

---

### T020: Implement ConnectedState Handler (state/clientState.ts part 5)
**Files**: `typescript-client/src/state/clientState.ts`  
**Description**: Implement `ConnectedState.handle()` method
- Handle `SubmitCommand`: generate requestId, add to pendingRequests, send ClientRequest
- Handle `SubmitQuery`: generate correlationId, add to pendingQueries, send Query
- Handle `ClientResponse`: complete pending request, resolve promise
- Handle `QueryResponse`: complete pending query, resolve promise
- Handle `Disconnect`: send CloseSession, disconnect transport, transition to Disconnected
- Handle network disconnect: emit 'disconnected', transition to ConnectingExisting
- Handle `SessionClosed(SessionExpired)`: emit 'sessionExpired', fail all pending, terminate
- Handle `SessionRejected(NotLeader)`: disconnect, reconnect to leader, transition to ConnectingExisting
- Handle `KeepAliveTick`: send KeepAlive message
- Handle `TimeoutCheck`: check pending request/query timeouts, reject timed-out ones
- Handle `ServerRequest`: check tracker, deliver to queue or acknowledge duplicate

**Acceptance**: Connected state handles all events correctly

---

## Phase 3.6: Request Management

### T021 [P]: Implement PendingRequests Manager (state/pendingRequests.ts)
**Files**: `typescript-client/src/state/pendingRequests.ts`  
**Description**: Implement request queue management
- `PendingRequests` class with Map<RequestId, PendingCommand>
- Methods: `add`, `complete`, `timeout`, `remove`, `checkTimeouts`, `lowestPendingRequestId`, `size`, `clear`
- Each entry stores: requestId, payload, resolve/reject callbacks, createdAt timestamp

**Acceptance**: Pending requests managed correctly, timeouts detected

---

### T022 [P]: Implement PendingQueries Manager (state/pendingQueries.ts)
**Files**: `typescript-client/src/state/pendingQueries.ts`  
**Description**: Implement query queue management (similar to PendingRequests)
- `PendingQueries` class with Map<CorrelationId, PendingQuery>
- Methods: `add`, `complete`, `timeout`, `remove`, `checkTimeouts`, `size`, `clear`

**Acceptance**: Pending queries managed correctly

---

### T023 [P]: Implement ServerRequestTracker (state/serverRequestTracker.ts)
**Files**: `typescript-client/src/state/serverRequestTracker.ts`  
**Description**: Implement server request deduplication
- `ServerRequestTracker` class with `lastAcknowledged: bigint`
- `check(requestId: bigint): 'Process' | 'OldRequest' | 'OutOfOrder'`
- `acknowledge(requestId: bigint): void`
- Logic: consecutive=Process, duplicate=OldRequest, gap=OutOfOrder

**Acceptance**: Server request tracking works correctly

---

## Phase 3.7: Utilities

### T024 [P]: Implement AsyncQueue (utils/asyncQueue.ts)
**Files**: `typescript-client/src/utils/asyncQueue.ts`  
**Description**: Implement async queue for action/event passing
- `AsyncQueue<T>` class using array + Promise resolvers
- Methods: `offer(item: T)`, async iterator for consumption
- Unbounded queue (no size limit)

**Acceptance**: AsyncQueue works for event passing

---

### T025 [P]: Implement RequestIdRef Counter (utils/requestIdRef.ts)
**Files**: `typescript-client/src/utils/requestIdRef.ts`  
**Description**: Implement atomic counter for request IDs
- `RequestIdRef` class with `private current: bigint = 0n`
- Methods: `next(): bigint` (returns ++current), `current(): bigint`

**Acceptance**: RequestIdRef generates sequential IDs

---

### T026 [P]: Implement Stream Merger Utility (utils/streamMerger.ts)
**Files**: `typescript-client/src/utils/streamMerger.ts`  
**Description**: Implement utility to merge multiple async iterables
- `mergeStreams<T>(...iterables: AsyncIterable<T>[]): AsyncIterable<T>`
- Yields items from any stream as they arrive
- Used for unified event stream (action queue + server messages + timers)

**Acceptance**: Stream merger combines multiple async sources

---

## Phase 3.8: Event System

### T027 [P]: Implement TypedEventEmitter (events/eventEmitter.ts)
**Files**: `typescript-client/src/events/eventEmitter.ts`  
**Description**: Wrap Node.js EventEmitter with TypeScript types
- `TypedEventEmitter` class extends EventEmitter
- Type-safe `on`, `emit`, `off` methods
- Event types from eventTypes.ts

**Acceptance**: EventEmitter has type-safe event handling

---

### T028 [P]: Define Event Types (events/eventTypes.ts)
**Files**: `typescript-client/src/events/eventTypes.ts`  
**Description**: Define connection event types
- `ConnectedEvent`: sessionId, endpoint, timestamp
- `DisconnectedEvent`: reason, timestamp
- `ReconnectingEvent`: attempt, endpoint, timestamp
- `SessionExpiredEvent`: sessionId, timestamp
- `ConnectionEvent` union type

**Acceptance**: All event types defined

---

## Phase 3.9: Main Client API

### T029: ✅ Implement RaftClient Constructor and Config (client.ts part 1)
**Files**: `typescript-client/src/client.ts`  
**Status**: ✅ COMPLETED  
**Description**: Implement RaftClient class initialization
- Constructor: validate config, create transport, create queues, initialize state to Disconnected
- Store config, transport, actionQueue, serverRequestQueue, eventEmitter
- EventEmitter inheritance for event handling
- No connection initiated in constructor (lazy init)

**Acceptance**: Client can be constructed without connecting

---

### T030: Implement RaftClient connect() Method (client.ts part 2)
**Files**: `typescript-client/src/client.ts`  
**Description**: Implement explicit connection method
- `async connect(): Promise<void>`
- Start event loop (if not running)
- Enqueue Connect action
- Wait for 'connected' event or timeout
- Return Promise that resolves when connected

**Acceptance**: connect() establishes session

---

### T031: Implement RaftClient Command/Query Submission (client.ts part 3)
**Files**: `typescript-client/src/client.ts`  
**Description**: Implement request submission methods
- `async submitCommand(payload: Uint8Array): Promise<Uint8Array>`
  - Validate payload (throw ValidationError if bad)
  - Create Promise with resolve/reject
  - Enqueue SubmitCommand action
  - Return promise
- `async submitQuery(payload: Uint8Array): Promise<Uint8Array>` (similar)

**Acceptance**: Commands and queries can be submitted, promises resolve

---

### T032: Implement RaftClient Event Loop (client.ts part 4)
**Files**: `typescript-client/src/client.ts`  
**Description**: Implement main event processing loop
- `private async runEventLoop(): Promise<void>`
- Merge streams: actionQueue, transport.incomingMessages, keepAliveTimer, timeoutTimer
- Iterate over unified stream
- Call `currentState.handle(event, ...)` for each event
- Update currentState with returned new state
- Handle errors gracefully (log, continue)

**Acceptance**: Event loop processes all events, state transitions work

---

### T033: Implement RaftClient disconnect() and Cleanup (client.ts part 5)
**Files**: `typescript-client/src/client.ts`  
**Description**: Implement graceful shutdown
- `async disconnect(): Promise<void>`
- Enqueue Disconnect action
- Wait for event loop to terminate
- Cleanup resources (transport, queues, timers)

**Acceptance**: Client disconnects gracefully

---

### T034: Implement Server Request Handler Registration (client.ts part 6)
**Files**: `typescript-client/src/client.ts`  
**Description**: Implement server-initiated request handling
- `onServerRequest(handler: (req: ServerRequest) => void): void`
- Consume from serverRequestQueue
- Invoke handler for each server request
- Handle errors in user handler gracefully

**Acceptance**: Server requests delivered to handler

---

### T035: ✅ Implement Public API Exports (index.ts)
**Files**: `typescript-client/src/index.ts`  
**Status**: ✅ COMPLETED  
**Description**: Export public API
- Export `RaftClient` class
- Export error classes
- Export type definitions
- Export event types
- No internal implementation details exposed

**Acceptance**: Public API clean and well-typed

---

**📝 Note**: Tasks T001-T035 (all core implementation) are **COMPLETE** as of 2025-12-30. 
- All source files implemented (~4,300 LOC)
- TypeScript compiles successfully  
- 32/32 unit tests passing
- Remaining tasks (T036-T050) focus on additional testing and documentation

---

## Phase 3.10: Unit Tests

### T036 [P]: Protocol Codec Unit Tests (tests/unit/protocol/codecs.test.ts)
**Files**: `typescript-client/tests/unit/protocol/codecs.test.ts`  
**Description**: Test protocol encoding/decoding (TC-PROTO-001 through TC-PROTO-011)
- Test CreateSession encoding
- Test SessionCreated decoding
- Test ClientRequest encoding
- Test ClientResponse decoding
- Test Query encoding
- Test QueryResponse decoding
- Test KeepAlive encoding
- Test KeepAliveResponse decoding
- Test protocol version validation
- Test invalid signature rejection
- Test round-trip for all message types

**Acceptance**: All protocol codec tests pass

---

### T037 [P]: State Machine Unit Tests (tests/unit/state/clientState.test.ts)
**Files**: `typescript-client/tests/unit/state/clientState.test.ts`  
**Description**: Test state machine transitions with mock transport (TC-STATE-001 through TC-STATE-016)
- Test Disconnected handles Connect
- Test Disconnected rejects SubmitCommand
- Test ConnectingNew handles SessionCreated
- Test ConnectingNew handles SessionRejected retry
- Test Connected handles SubmitCommand
- Test Connected handles ClientResponse
- Test Connected handles SubmitQuery
- Test Connected handles QueryResponse
- Test Connected handles network disconnect (reconnection)
- Test ConnectingExisting handles SessionContinued
- Test Connected handles SessionExpired (terminal)
- Test Connected handles KeepAliveTick
- Test Connected handles TimeoutCheck
- Test Connected handles ServerRequest
- Test Connected handles duplicate ServerRequest
- Test Connected handles out-of-order ServerRequest

**Acceptance**: All state machine tests pass

---

### T038 [P]: Pending Request Management Tests (tests/unit/state/pendingRequests.test.ts)
**Files**: `typescript-client/tests/unit/state/pendingRequests.test.ts`  
**Description**: Test request queue management (TC-PENDING-001 through TC-PENDING-005)
- Test add pending request
- Test complete pending request (promise resolution)
- Test timeout pending request (promise rejection)
- Test checkTimeouts logic
- Test lowestPendingRequestId calculation

**Acceptance**: Pending request tests pass

---

### T039 [P]: Server Request Tracker Tests (tests/unit/state/serverRequestTracker.test.ts)
**Files**: `typescript-client/tests/unit/state/serverRequestTracker.test.ts`  
**Description**: Test server request deduplication (TC-TRACKER-001 through TC-TRACKER-004)
- Test consecutive request (Process)
- Test old request (OldRequest)
- Test out-of-order request (OutOfOrder)
- Test acknowledge updates tracker

**Acceptance**: Server request tracker tests pass

---

### T040 [P]: Configuration Validation Tests (tests/unit/config.test.ts)
**Files**: `typescript-client/tests/unit/config.test.ts`  
**Description**: Test config validation (TC-CONFIG-001 through TC-CONFIG-004)
- Test valid config accepted
- Test empty endpoints rejected
- Test empty capabilities rejected
- Test negative timeout rejected

**Acceptance**: Config validation tests pass

---

## Phase 3.11: Integration Tests

### T041 [P]: Full Lifecycle Integration Test (tests/integration/lifecycle.test.ts)
**Files**: `typescript-client/tests/integration/lifecycle.test.ts`  
**Description**: Test complete client lifecycle (TC-INT-001 through TC-INT-005)
- Test full connect → command → disconnect cycle
- Test multiple commands sequentially
- Test multiple commands concurrently (Promise.all)
- Test query submission
- Test keep-alive maintenance

**Acceptance**: Full lifecycle works end-to-end

**Prerequisites**: Mock Scala server or test harness

---

### T042 [P]: Reconnection Integration Tests (tests/integration/reconnection.test.ts)
**Files**: `typescript-client/tests/integration/reconnection.test.ts`  
**Description**: Test reconnection scenarios (TC-INT-006 through TC-INT-010)
- Test network disconnection and automatic reconnection
- Test pending requests preserved across reconnection
- Test requests during disconnection queue with timeout
- Test queued requests timeout if reconnection delayed
- Test leadership change reconnection (NotLeader)

**Acceptance**: Reconnection works correctly

**Prerequisites**: Mock server that can disconnect/reconnect

---

### T043 [P]: Session Expiry Integration Tests (tests/integration/sessionExpiry.test.ts)
**Files**: `typescript-client/tests/integration/sessionExpiry.test.ts`  
**Description**: Test session expiry handling (TC-INT-011, TC-INT-012)
- Test session expiry due to missed keep-alives
- Test all pending requests fail on session expiry

**Acceptance**: Session expiry handled correctly

---

### T044 [P]: Server Request Integration Tests (tests/integration/serverRequests.test.ts)
**Files**: `typescript-client/tests/integration/serverRequests.test.ts`  
**Description**: Test server-initiated requests (TC-INT-013 through TC-INT-015)
- Test server request delivery to handler
- Test consecutive server requests processed in order
- Test duplicate server request ignored

**Acceptance**: Server requests work correctly

---

### T045 [P]: Edge Case Tests (tests/integration/edgeCases.test.ts)
**Files**: `typescript-client/tests/integration/edgeCases.test.ts`  
**Description**: Test edge cases (TC-EDGE-001 through TC-EDGE-010)
- Test empty/null payload validation
- Test connect called twice
- Test disconnect before connect
- Test submit command before connect
- Test large payload (1MB)
- Test very large payload (10MB)
- Test cluster all unreachable
- Test malformed server response
- Test request timeout with manual retry

**Acceptance**: All edge cases handled gracefully

---

## Phase 3.12: Compatibility & Performance Tests

### T046 [P]: Wire Protocol Compatibility Tests (tests/integration/compatibility.test.ts)
**Files**: `typescript-client/tests/integration/compatibility.test.ts`  
**Description**: Test wire protocol compatibility with Scala server (TC-COMPAT-001 through TC-COMPAT-006)
- Test CreateSession wire format
- Test ClientRequest wire format
- Test SessionCreated decoding
- Test ClientResponse decoding
- Test interoperability with Scala client (same cluster)
- Test all message types round-trip

**Acceptance**: Byte-for-byte wire protocol compatibility verified

**Prerequisites**: Real Scala ZIO Raft server

---

### T047 [P]: Performance Benchmarks (tests/performance/benchmarks.test.ts)
**Files**: `typescript-client/tests/performance/benchmarks.test.ts`  
**Description**: Validate performance targets (TC-PERF-001 through TC-PERF-006)
- Benchmark single request latency (p50, p95, p99)
- Benchmark throughput at 1,000 req/s
- Benchmark throughput at 10,000 req/s
- Benchmark concurrent requests (Promise.all)
- Benchmark protocol encoding/decoding overhead
- Benchmark memory usage under sustained load

**Acceptance**: Performance targets met (1K-10K req/s, <10ms overhead)

**Prerequisites**: Performance test environment (controlled network/hardware)

---

## Phase 3.13: Documentation & Examples

### T048 [P]: Write README.md (typescript-client/README.md)
**Files**: `typescript-client/README.md`  
**Description**: Create comprehensive README
- Overview and features
- Installation instructions
- Quick start example
- API documentation overview
- Configuration reference
- Event system documentation
- Error handling guide
- Performance tips
- Troubleshooting section
- Links to full documentation

**Acceptance**: README provides clear getting-started guide

---

### T049 [P]: Generate API Documentation (typescript-client/docs/)
**Files**: `typescript-client/docs/` (generated)  
**Description**: Generate TypeDoc API documentation
- Configure TypeDoc
- Generate HTML documentation
- Document all public APIs with JSDoc comments
- Include examples in API docs

**Acceptance**: API docs generated and browsable

---

### T050 [P]: Create Example Applications (typescript-client/examples/)
**Files**: `typescript-client/examples/`  
**Description**: Create example applications demonstrating usage
- `examples/basic-usage.ts` - Simple command/query submission
- `examples/high-throughput.ts` - Concurrent request batching
- `examples/reconnection.ts` - Automatic reconnection demo
- `examples/server-requests.ts` - Server-initiated request handling
- `examples/error-handling.ts` - Comprehensive error handling

**Acceptance**: All examples run successfully

---

## Dependencies

### Critical Path (Blocking Dependencies)
```
Setup (T001-T004)
  ↓
Types (T005-T007)
  ↓
Protocol Constants/Messages (T008-T009)
  ↓
Field Codecs (T010)
  ↓
Message Codecs (T011-T012)
  ↓
Transport Interface (T013) → ZMQ Transport (T014)
  ↓
State Types (T016)
  ↓
State Handlers (T017-T020) + Request Management (T021-T023)
  ↓
Utilities (T024-T026) + Events (T027-T028)
  ↓
Client API (T029-T035)
  ↓
Tests (T036-T047)
  ↓
Documentation (T048-T050)
```

### Parallel Execution Groups

**Group 1: Setup** (after repo prep)
- T001, T002, T003, T004 [P] - All setup tasks can run in parallel

**Group 2: Types & Errors** (after setup)
- T005, T006, T007 [P] - Core types, errors, config all independent

**Group 3: Protocol Layer** (after types)
- T008, T009 [P] - Constants and message types independent
- T010 (after T008, T009) - Field codecs depend on constants/types
- T011, T012 (after T010) - Message codecs depend on field codecs (sequential in same file)

**Group 4: Transport & Mock** (after protocol)
- T013 → T014 (sequential: interface then implementation)
- T015 [P] - Mock transport parallel with ZMQ transport tests

**Group 5: State Machine** (after transport)
- T016 [P] - State types (independent)
- T017-T020 (sequential) - State handlers (same file, depend on each other)

**Group 6: Support Systems** (after state types)
- T021, T022, T023 [P] - Request managers all independent
- T024, T025, T026 [P] - Utilities all independent
- T027, T028 [P] - Event system independent

**Group 7: Client API** (after state machine + utilities)
- T029-T035 (sequential) - All in client.ts, depend on each other

**Group 8: Unit Tests** (after implementation)
- T036, T037, T038, T039, T040 [P] - All unit tests independent

**Group 9: Integration Tests** (after client API)
- T041, T042, T043, T044, T045 [P] - All integration tests independent

**Group 10: Final Validation** (after integration tests)
- T046, T047 [P] - Compatibility and performance tests parallel

**Group 11: Documentation** (anytime after API stable)
- T048, T049, T050 [P] - All documentation tasks independent

---

## Parallel Execution Example

```bash
# Group 1: Setup (run all in parallel)
Task: "Update package.json dependencies and scripts (T001)"
Task: "Configure TypeScript strict mode (T002)"
Task: "Set up Vitest test framework (T003)"
Task: "Configure ESLint and Prettier (T004)"

# Group 2: Types (run all in parallel after Group 1)
Task: "Implement core types in types.ts (T005)"
Task: "Create error class hierarchy in errors.ts (T006)"
Task: "Define ClientConfig and validation in config.ts (T007)"

# Group 3: Protocol (sequential for same file)
Task: "Define protocol constants (T008)"
Task: "Define protocol message types (T009)"
Task: "Implement field-level encoders/decoders (T010)"
Task: "Implement ClientMessage encoding in codecs.ts (T011)"
Task: "Implement ServerMessage decoding in codecs.ts (T012)"

# ... and so on
```

---

## Validation Checklist

**Before marking feature complete**:

- [ ] All 50 tasks completed
- [ ] All 95 test scenarios pass (from tests.md)
- [ ] Wire protocol byte-for-byte compatible with Scala client
- [ ] Performance benchmarks meet targets (1K-10K req/s, <10ms overhead)
- [ ] TypeScript compiles with strict mode, no errors
- [ ] Linting passes (no `any` types, proper error handling)
- [ ] Code coverage >= 80% (lines, branches, functions)
- [ ] Quickstart examples run successfully
- [ ] API documentation generated and complete
- [ ] README provides clear getting-started guide
- [ ] All edge cases handled gracefully (no crashes)
- [ ] Event system works correctly (4 events emitted at right times)
- [ ] Reconnection preserves pending requests
- [ ] Session expiry terminates client cleanly
- [ ] No memory leaks under sustained load
- [ ] TypeScript type definitions exported correctly

---

## Notes

- **No TDD**: This is a TypeScript library, not Scala/ZIO. Implementation comes before tests as per user rules and spec clarifications.
- **Idiomatic TypeScript**: Use Node.js patterns (EventEmitter, Promises, classes), not Scala patterns, as per critical spec requirement (Clarifications 2025-12-28).
- **Commit Strategy**: Commit after each task or logical group of parallel tasks.
- **Testing Strategy**: Unit tests with mock transport, integration tests with real/mock server.
- **Performance Validation**: Run benchmarks on dedicated hardware to validate NFR-001/NFR-002.

---

## Task Generation Rules Applied

1. **From Data Model**: Each entity (Client, Session, Command, Query, etc.) → type definitions + tests
2. **From Design**: Architecture components → implementation tasks (protocol, transport, state machine, client API)
3. **From Tests.md**: 95 test scenarios → unit test tasks (T036-T040), integration test tasks (T041-T047)
4. **From Quickstart**: Examples → documentation tasks (T048-T050)
5. **Ordering**: Setup → Types → Protocol → Transport → State → Client → Tests → Docs
6. **Parallelization**: Different files marked [P], same file sequential

---

**Total Tasks**: 50  
**Estimated Implementation Time**: 2-3 weeks for experienced TypeScript developer  
**Lines of Code**: ~3,000-4,000 (implementation) + ~2,000-3,000 (tests)

**Ready for execution with `/tasks` command or manual task-by-task implementation.**
