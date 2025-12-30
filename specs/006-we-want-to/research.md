# Phase 0: Research & Technical Decisions

**Feature**: TypeScript Client Library  
**Date**: 2025-12-29  
**Status**: Complete

## Overview

This document consolidates technical research and decisions for the TypeScript client library. The library provides Node.js/TypeScript applications with access to ZIO Raft clusters using idiomatic JavaScript patterns while maintaining wire protocol compatibility with the Scala client.

---

## Research Areas

### 1. Wire Protocol Compatibility

**Decision**: Use scodec-style binary encoding with discriminated unions

**Rationale**:
- The Scala client-server-protocol uses scodec for binary serialization
- Protocol format: `[signature:5 bytes][version:1 byte][discriminator:1 byte][message data]`
- Signature: `0x7a 0x72 0x61 0x66 0x74` ("zraft")
- Protocol version: `0x01`
- Each message type has a discriminator value (1-9)
- Fields encoded using standard binary formats (varints, length-prefixed strings, ISO instant timestamps)

**Implementation Strategy**:
- Create TypeScript codec functions matching each Scala codec
- Use `Buffer` for binary manipulation (Node.js native, zero-copy)
- Implement discriminated encoding/decoding for message hierarchies
- Test byte-for-byte compatibility against Scala reference implementation

**Alternatives Considered**:
- Protocol Buffers/gRPC: Rejected - requires changing server protocol
- JSON over WebSocket: Rejected - incompatible with existing ZMQ transport
- MessagePack: Considered for internal use but not required for wire compatibility

**Reference Files**:
- `/client-server-protocol/src/main/scala/zio/raft/protocol/Codecs.scala` (lines 1-336)
- `/client-server-protocol/src/main/scala/zio/raft/protocol/ClientMessages.scala` (lines 1-90)
- `/client-server-protocol/src/main/scala/zio/raft/protocol/ServerMessages.scala` (lines 1-133)

---

### 2. Transport Layer (ZeroMQ)

**Decision**: Use `zeromq` npm package (v6.x) with DEALER socket pattern

**Rationale**:
- ZeroMQ provides high-performance, async message transport
- DEALER socket pattern matches Scala client (client-side DEALER, server-side ROUTER)
- Automatic reconnection support built into ZMQ
- Node.js bindings are mature and stable (N-API based, no native recompilation needed)

**Implementation Strategy**:
- Wrap ZMQ socket in TypeScript transport abstraction
- Use `zeromq` async iterator API for receiving messages
- Handle ZMQ-specific concerns (routing identity, reconnection) in transport layer
- Provide clean separation between transport and protocol layers

**Performance Characteristics**:
- ZMQ provides sub-millisecond latency for local connections
- Supports 10K+ messages/second easily on modern hardware
- Zero-copy message passing where possible

**Alternatives Considered**:
- TCP sockets: Rejected - requires manual reconnection logic and framing
- WebSocket: Rejected - incompatible with server's ZMQ ROUTER
- HTTP/2: Rejected - requires server changes

**Reference**:
- `zeromq` package: https://www.npmjs.com/package/zeromq
- `/client-server-client/src/main/scala/zio/raft/client/ClientTransport.scala`

---

### 3. State Machine Architecture

**Decision**: Functional state machine with discriminated union types

**Rationale**:
- TypeScript discriminated unions provide type-safe state transitions
- Matches Scala client's functional ADT pattern conceptually
- Each state implements same `handle(event)` interface
- Compiler enforces exhaustive pattern matching
- Immutable state transitions (each handler returns new state)

**States**:
1. **Disconnected**: Initial state, waiting for connect() call
2. **ConnectingNewSession**: Creating new session with CreateSession message
3. **ConnectingExistingSession**: Resuming session with ContinueSession message
4. **Connected**: Active session, processing requests/responses

**Event Types**:
- **Action**: User API calls (connect, submitCommand, submitQuery, disconnect)
- **ServerMessage**: Incoming protocol messages from server
- **KeepAliveTick**: Timer event for heartbeat
- **TimeoutCheck**: Timer event for request timeout detection

**Implementation Pattern**:
```typescript
interface StateHandler {
  handle(event: StreamEvent): Promise<ClientState>;
}
```

**Alternatives Considered**:
- Class hierarchy with inheritance: Rejected - discriminated unions are more idiomatic TypeScript
- Simple if/else state checking: Rejected - loses type safety
- XState library: Rejected - adds unnecessary dependency for simple state machine

**Reference Files**:
- `/typescript-client/src/state/clientState.ts` (existing implementation)
- `/client-server-client/src/main/scala/zio/raft/client/RaftClient.scala` (lines 110-579)

---

### 4. Request Queue Management

**Decision**: In-memory Map-based pending request tracking

**Rationale**:
- Requests identified by RequestId (uint64)
- Queries identified by CorrelationId (UUID string)
- Need O(1) lookup by ID for response correlation
- Need O(1) insertion/deletion
- Need timeout tracking per request

**Data Structures**:
- `PendingRequests`: Map<RequestId, PendingRequest>
- `PendingQueries`: Map<CorrelationId, PendingQuery>
- Each entry contains: payload, promise (for result), timestamp (for timeout)

**Request ID Generation**:
- Use incrementing counter for RequestId (matches Scala client)
- Counter starts at 1 (zero reserved as sentinel)
- Use UUID v4 for CorrelationId (query correlation)

**Timeout Strategy**:
- Periodic timeout check (100ms interval)
- Compare current time vs request timestamp + configured timeout
- Reject promise with TimeoutError on timeout
- **No automatic retry** - application decides whether to retry manually

**Memory Management**:
- Completed requests removed immediately from map
- Timed-out requests removed after promise rejection
- Server uses `lowestPendingRequestId` to evict cache entries

**Alternatives Considered**:
- Priority queue for timeouts: Rejected - overkill for expected request volumes
- Automatic retry on timeout: Rejected - spec requires manual retry only
- Persistent queue: Rejected - requests are transient, session expiry terminates client

**Reference Files**:
- `/typescript-client/src/state/pendingRequests.ts` (existing implementation)
- `/typescript-client/src/state/pendingQueries.ts` (existing implementation)

---

### 5. Async API Design (Promises vs Callbacks)

**Decision**: Promise-based API with async/await support

**Rationale**:
- Modern JavaScript/TypeScript standard for async operations
- Composable with async/await syntax
- Error handling via try/catch or .catch()
- Matches ecosystem expectations (ioredis, pg, mongodb patterns)

**API Shape**:
```typescript
class RaftClient {
  constructor(config: ClientConfig); // Lazy init, no connection
  async connect(): Promise<void>;
  async submitCommand(payload: Uint8Array): Promise<Uint8Array>;
  async submitQuery(payload: Uint8Array): Promise<Uint8Array>;
  async disconnect(): Promise<void>;
  
  // EventEmitter methods
  on(event: 'connected', handler: (evt: ConnectedEvent) => void): this;
  on(event: 'disconnected', handler: (evt: DisconnectedEvent) => void): this;
  on(event: 'reconnecting', handler: (evt: ReconnectingEvent) => void): this;
  on(event: 'sessionExpired', handler: (evt: SessionExpiredEvent) => void): this;
}
```

**Promise Behavior**:
- Constructor: Synchronous, no Promise
- `connect()`: Resolves when session established, rejects on failure
- `submitCommand()`/`submitQuery()`:
  - Throws synchronously if validation fails (bad payload format)
  - Returns Promise that:
    - Resolves with Uint8Array response when received
    - Rejects with TimeoutError if timeout expires
    - If called while disconnected: waits for reconnection (up to configured timeout)

**Error Types**:
- `ValidationError`: Synchronous throw for bad input
- `TimeoutError`: Promise rejection for request timeout
- `ConnectionError`: Promise rejection for connection failures
- `SessionExpiredError`: Promise rejection when session expires

**Alternatives Considered**:
- Callback-based API: Rejected - outdated Node.js pattern
- Observable/Stream API (RxJS): Rejected - heavy dependency, not idiomatic for simple request/response
- Separate typed/untyped methods: Rejected - generic decoders add complexity without benefit

**Reference**:
- Spec clarifications Session 2025-12-29 (lazy init, queue-with-timeout, raw binary)

---

### 6. Event System (Observability)

**Decision**: Node.js EventEmitter with minimal event set

**Rationale**:
- `EventEmitter` is standard Node.js pattern for event-driven APIs
- TypeScript provides strong typing for event names and payloads
- Minimal event set as per spec: only connection state changes
- No built-in logging - applications observe events for diagnostics

**Event Types** (all emitted from client):
1. **connected**: Session established (includes sessionId, endpoint)
2. **disconnected**: Connection lost (includes reason)
3. **reconnecting**: Attempting reconnection (includes attempt count)
4. **sessionExpired**: Session expired by server (terminal event)

**Event Payloads**:
```typescript
interface ConnectedEvent {
  sessionId: string;
  endpoint: string;
  timestamp: Date;
}

interface DisconnectedEvent {
  reason: 'network' | 'server-closed' | 'client-shutdown';
  timestamp: Date;
}

interface ReconnectingEvent {
  attempt: number;
  endpoint: string;
  timestamp: Date;
}

interface SessionExpiredEvent {
  sessionId: string;
  timestamp: Date;
}
```

**Usage Pattern**:
```typescript
client.on('connected', (evt) => {
  logger.info('Connected', { sessionId: evt.sessionId });
});

client.on('sessionExpired', () => {
  logger.error('Session expired - terminating');
  process.exit(1);
});
```

**Alternatives Considered**:
- Verbose events (requestSent, responseReceived): Rejected - spec requires minimal
- Tiered event system: Rejected - adds complexity without clear benefit
- Separate logger interface: Rejected - EventEmitter is simpler and more flexible

**Reference**:
- Spec clarifications Session 2025-12-29 (minimal events only)
- FR-015, FR-016 (event requirements)

---

### 7. Session Management & Reconnection

**Decision**: Automatic reconnection with session resumption using ContinueSession

**Rationale**:
- Server maintains session state across client disconnections
- Session identified by SessionId (UUID string)
- Client stores sessionId after SessionCreated response
- On reconnection: send ContinueSession(sessionId, nonce) instead of CreateSession
- Pending requests preserved across reconnections

**Reconnection Logic**:
1. Detect disconnection (ZMQ socket event)
2. Transition to ConnectingExistingSession state (preserving sessionId)
3. Try next cluster member in round-robin fashion
4. Send ContinueSession message
5. On SessionContinued: resume Connected state with preserved pending requests
6. On SessionRejected(SessionNotFound): fall back to CreateSession (new session)

**Session Expiry**:
- Server expires session if keep-alives not received within configured window
- Server sends SessionClosed(SessionExpired) or SessionRejected(SessionExpired)
- Client emits 'sessionExpired' event
- Client fails all pending requests with SessionExpiredError
- Client terminates (matches Scala client behavior)

**Keep-Alive Protocol**:
- Client sends KeepAlive(timestamp) at configured interval (default 30s)
- Server responds with KeepAliveResponse(clientTimestamp, serverTimestamp)
- Provides RTT measurement capability (client timestamp round-trip)
- Session ID derived from ZMQ routing identity (not in message body)

**Alternatives Considered**:
- No automatic reconnection: Rejected - poor UX for transient network issues
- Exponential backoff: Deferred to configuration (could add later)
- Session persistence to disk: Rejected - adds complexity, server state is authoritative

**Reference**:
- FR-003, FR-005, FR-011 (session requirements)
- `/client-server-client/src/main/scala/zio/raft/client/RaftClient.scala` (session states)

---

### 8. Performance & Batching

**Decision**: Optional request batching at application level, not in library

**Rationale**:
- Target: 1,000-10,000+ requests/second throughput
- Single request overhead: protocol encoding + ZMQ send (sub-millisecond)
- At 10K req/s: ~100μs per request budget
- Node.js event loop can handle this easily

**Performance Strategy**:
- Minimize allocations in hot path (reuse Buffers where possible)
- Protocol encoding: pre-compute message headers
- Use ZMQ zero-copy where possible
- Avoid JSON serialization in protocol layer (binary only)

**Batching Consideration**:
- Spec mentions "batching optimizations" (NFR-002)
- Decision: Provide Promise.all() pattern example in docs
- Application can batch multiple submitCommand() calls
- Library sends immediately (no internal batching)
- Internal batching would add latency and complexity

**Batching Example** (application-level):
```typescript
const results = await Promise.all([
  client.submitCommand(payload1),
  client.submitCommand(payload2),
  client.submitCommand(payload3)
]);
// All sent immediately, responses arrive as ready
```

**Alternatives Considered**:
- Automatic request batching: Rejected - adds latency, unclear API semantics
- Nagle-style algorithm: Rejected - inappropriate for request/response pattern
- Explicit batch API: Deferred - can add later if needed

**Reference**:
- NFR-001, NFR-002 (performance requirements)

---

### 9. Testing Strategy

**Decision**: Vitest for unit/integration tests, mock transport for unit tests

**Rationale**:
- Vitest: Modern, fast, TypeScript-native test framework
- Better DX than Jest (faster, better error messages)
- Mock transport layer for unit testing state machine
- Integration tests against real ZMQ sockets
- Property-based testing for protocol encoding (if needed)

**Test Coverage**:
1. **Unit Tests**:
   - Protocol encoding/decoding (byte-for-byte verification)
   - State machine transitions (all states, all events)
   - Pending request/query management
   - Timeout detection
   - Error handling

2. **Integration Tests**:
   - Full client lifecycle (connect, request, disconnect)
   - Reconnection scenarios
   - Session resumption
   - Keep-alive protocol
   - Server-initiated requests
   - Session expiry handling

3. **Compatibility Tests**:
   - Wire protocol compatibility with Scala server
   - Codec round-trip tests (encode/decode identity)

**Test Structure**:
```
tests/
├── unit/
│   ├── protocol/
│   │   ├── codecs.test.ts
│   │   └── messages.test.ts
│   ├── state/
│   │   ├── clientState.test.ts
│   │   ├── pendingRequests.test.ts
│   │   └── pendingQueries.test.ts
│   └── transport/
│       └── mockTransport.test.ts
└── integration/
    ├── client-lifecycle.test.ts
    ├── reconnection.test.ts
    └── compatibility.test.ts
```

**Alternatives Considered**:
- Jest: Currently used, but Vitest offers better TypeScript support
- No mock transport: Rejected - makes unit tests flaky and slow
- E2E tests only: Rejected - slow feedback loop, hard to test edge cases

**Reference**:
- V. Test-Driven Maintenance (Constitution)
- Existing test structure in `/typescript-client/tests/`

---

### 10. Error Handling Patterns

**Decision**: Custom error classes extending Error, Promise rejections for async errors

**Rationale**:
- TypeScript/JavaScript standard: extend Error class
- Type-safe error handling with instanceof checks
- Promise rejections for all async failures
- Synchronous throws only for validation errors (fail-fast)

**Error Hierarchy**:
```typescript
class RaftClientError extends Error {
  name = 'RaftClientError';
}

class ValidationError extends RaftClientError {
  name = 'ValidationError';
  // Thrown synchronously for bad input
}

class TimeoutError extends RaftClientError {
  name = 'TimeoutError';
  requestId?: RequestId;
  correlationId?: CorrelationId;
}

class ConnectionError extends RaftClientError {
  name = 'ConnectionError';
  endpoint?: string;
  cause?: Error;
}

class SessionExpiredError extends RaftClientError {
  name = 'SessionExpiredError';
  sessionId: string;
}

class ProtocolError extends RaftClientError {
  name = 'ProtocolError';
  // For wire protocol violations
}
```

**Error Handling Examples**:
```typescript
// Synchronous validation error
try {
  await client.submitCommand(invalidPayload); // throws ValidationError immediately
} catch (err) {
  if (err instanceof ValidationError) {
    // Handle bad input
  }
}

// Async timeout error
try {
  const result = await client.submitCommand(payload);
} catch (err) {
  if (err instanceof TimeoutError) {
    // Manually retry if desired
    const result = await client.submitCommand(payload);
  }
}

// Session expired (terminal)
client.on('sessionExpired', (evt) => {
  // Cleanup and exit
  process.exit(1);
});
```

**Alternatives Considered**:
- Result<T, E> type: Rejected - not idiomatic JavaScript, awkward with Promises
- Error codes vs classes: Rejected - classes provide better type safety
- Automatic retry on timeout: Rejected - spec requires manual retry

**Reference**:
- II. Explicit Error Handling (Constitution interpretation for TypeScript)
- Spec clarifications Session 2025-12-29 (no automatic retry)

---

### 11. Configuration & Defaults

**Decision**: Single configuration object with sensible defaults

**Rationale**:
- Simple, idiomatic TypeScript configuration pattern
- All timeouts configurable
- Cluster endpoints required, rest optional

**Configuration Interface**:
```typescript
interface ClientConfig {
  // Required
  endpoints: string[]; // ['tcp://localhost:5555', ...]
  capabilities: Record<string, string>; // { version: '1.0.0' }
  
  // Optional (with defaults)
  connectionTimeout?: number; // 5000ms
  requestTimeout?: number; // 30000ms
  keepAliveInterval?: number; // 30000ms
  queuedRequestTimeout?: number; // 60000ms (for disconnected queue)
  maxReconnectAttempts?: number; // Infinity (keep trying)
  reconnectInterval?: number; // 1000ms
}
```

**Default Values Rationale**:
- Connection timeout: 5s (enough for local/LAN, fail fast for misconfig)
- Request timeout: 30s (long enough for slow Raft writes, short enough to detect issues)
- Keep-alive interval: 30s (matches typical session timeout / 2)
- Queued request timeout: 60s (balance responsiveness vs resilience during reconnection)
- Max reconnect attempts: Infinity (keep trying indefinitely)
- Reconnect interval: 1s (fast recovery without hammering server)

**Validation**:
- Endpoints: Must be non-empty array
- Capabilities: Must be non-empty object
- Timeouts: Must be positive integers
- Throw ValidationError in constructor for invalid config

**Alternatives Considered**:
- Builder pattern: Rejected - overkill for simple config object
- Separate config classes per concern: Rejected - single object is simpler
- Environment variable overrides: Deferred - can add later

**Reference**:
- FR-002 (constructor validation)
- FR-008 (configurable timeouts)

---

## Summary of Key Decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| Wire Protocol | Scodec-style binary with discriminated unions | Byte-for-byte compatibility with Scala |
| Transport | ZeroMQ via `zeromq` npm package | High performance, mature Node.js bindings |
| State Machine | Functional with discriminated union types | Type-safe transitions, idiomatic TypeScript |
| Request Queue | Map-based in-memory tracking | O(1) operations, simple timeout handling |
| Async API | Promise-based with async/await | Modern JavaScript standard |
| Events | EventEmitter with 4 minimal events | Spec requirement, flexible observability |
| Session Management | Automatic reconnection with ContinueSession | Resilient to transient failures |
| Performance | Application-level batching, no internal batching | Low latency, simple semantics |
| Testing | Vitest with mock transport | Fast, type-safe, good DX |
| Error Handling | Custom Error classes, Promise rejections | Type-safe, idiomatic TypeScript |
| Configuration | Single config object with defaults | Simple, flexible, well-documented |

---

## Dependencies Confirmed

**Production Dependencies**:
- `zeromq` (v6.x): ZeroMQ Node.js bindings

**Development Dependencies**:
- `typescript` (v5.x): Language toolchain
- `vitest` (to be added): Test framework
- `@types/node` (v18.x): Node.js type definitions
- `eslint` + `@typescript-eslint/*`: Linting
- `prettier`: Code formatting

**No Additional Dependencies Needed**:
- MessagePack: Not needed (binary encoding is manual)
- UUID library: Use built-in `crypto.randomUUID()` (Node.js 18+)
- Event library: Use built-in `EventEmitter`

---

## Next Steps

Phase 0 complete. Ready for Phase 1 (Design):
1. Extract entities to data-model.md
2. Create comprehensive design.md with architecture diagrams
3. Extract test scenarios to tests.md
4. Generate quickstart.md for end-to-end validation
5. Update agent context file

All technical unknowns resolved. No NEEDS CLARIFICATION markers remain.
