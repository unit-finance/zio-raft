# Research: TypeScript Client Library

**Date**: 2025-12-24  
**Feature**: TypeScript Client for ZIO Raft

## Overview
This document consolidates research findings for implementing a TypeScript client library that communicates with ZIO Raft clusters using the same wire protocol as the Scala client.

---

## 1. Wire Protocol Analysis

### Decision: Use scodec-compatible binary format
**Rationale**: The Scala implementation uses scodec for binary serialization with a specific protocol structure. The TypeScript client must encode/decode messages in the exact same format for compatibility.

**Protocol Structure**:
```
[Protocol Header] [Message Type] [Message Data]
   5 bytes + 1        1 byte        variable
```

- **Protocol Signature**: `[0x7a, 0x72, 0x61, 0x66, 0x74]` (ASCII "zraft")
- **Protocol Version**: `1` (1 byte)
- **Message Type Discriminator**: 1 byte (uint8)
- **Message Data**: Variable length, depends on message type

**Alternatives Considered**:
- JSON over WebSocket: Rejected - requires protocol translation layer, adds latency
- Protocol Buffers: Rejected - would require server changes, not compatible with existing scodec implementation
- Custom binary format: Rejected - must match existing Scala implementation exactly

---

## 2. Binary Encoding Strategy

### Decision: Implement scodec-compatible codecs in TypeScript
**Rationale**: Each protocol message type requires precise binary encoding matching the Scala scodec codecs. TypeScript will use Buffer API with manual byte manipulation.

**Key Encoding Patterns**:

1. **Variable-size strings** (UTF-8):
   - Length prefix (uint8 or uint16 depending on field)
   - UTF-8 encoded bytes

2. **Variable-size byte arrays** (payloads):
   - Length prefix (int32)
   - Raw bytes

3. **Maps** (capabilities):
   - Count (uint16)
   - For each entry: length-prefixed key + length-prefixed value

4. **Timestamps** (Instant):
   - int64 epoch milliseconds

5. **Discriminated unions** (enums):
   - uint8 discriminator
   - No additional data for singleton cases

**Client Message Type IDs**:
- 1: CreateSession
- 2: ContinueSession
- 3: KeepAlive
- 4: ClientRequest
- 5: ServerRequestAck
- 6: CloseSession
- 7: ConnectionClosed
- 8: Query

**Server Message Type IDs**:
- 1: SessionCreated
- 2: SessionContinued
- 3: SessionRejected
- 4: SessionClosed
- 5: KeepAliveResponse
- 6: ClientResponse
- 7: ServerRequest
- 8: RequestError
- 9: QueryResponse

**Alternatives Considered**:
- Use existing JavaScript binary serialization libraries: Rejected - none match scodec's exact encoding behavior
- Generate codecs from Scala definitions: Deferred - manual implementation faster for initial version

---

## 3. ZeroMQ Integration

### Decision: Use zeromq.js (Node.js native bindings)
**Rationale**: The Scala client uses ZMQ DEALER sockets for client communication. zeromq.js provides native ZMQ bindings for Node.js with the required socket types.

**Socket Configuration** (mirroring Scala ClientTransport):
```typescript
Socket Type: DEALER
Linger: 0 (immediate close)
Heartbeat: interval=1s, ttl=10s, timeout=30s
High Water Mark: send=200000, receive=200000
```

**Connection Pattern**:
- Multiple addresses (cluster members) configured upfront
- Connect to first member, try next on failure/rejection
- Support disconnect/reconnect to different addresses

**Alternatives Considered**:
- ws (WebSocket): Rejected - requires server-side WebSocket support, ZMQ not available
- net (TCP sockets): Rejected - missing ZMQ features like automatic reconnection, heartbeat
- zmq.js: Rejected - older library, less maintained than zeromq.js

---

## 4. Event Loop Architecture

### Decision: Single event loop with merged streams
**Rationale**: The Scala client uses ZIO's ZStream.merge to combine action commands, server messages, keep-alive ticks, and timeout checks into a unified event stream. TypeScript will use Node.js EventEmitter pattern with async iteration.

**Event Sources**:
1. **Action Queue**: User commands (connect, disconnect, submitCommand, submitQuery)
2. **ZMQ Messages**: Incoming server messages from ZMQ socket
3. **Keep-Alive Timer**: Periodic (configurable, default 30s)
4. **Timeout Timer**: Periodic checks for request retry (100ms intervals)

**Event Processing**:
- Single state machine processes all events sequentially
- State transitions are deterministic based on current state + event type
- No concurrent state modifications

**Alternatives Considered**:
- Separate event loops per concern: Rejected - difficult to coordinate, race conditions
- Promise-based async/await: Rejected - doesn't handle streaming events well
- RxJS observables: Rejected - adds dependency, EventEmitter sufficient

---

## 5. State Machine Design

### Decision: Mimic Scala ClientState sealed trait with TypeScript classes
**Rationale**: The Scala client uses a functional state machine with sealed trait hierarchy. TypeScript will use discriminated union of state objects with state-specific event handlers.

**Client States**:
1. **Disconnected**: Initial state, waiting for connect() call
2. **ConnectingNewSession**: Creating new session, waiting for SessionCreated
3. **ConnectingExistingSession**: Resuming session, waiting for SessionContinued
4. **Connected**: Active session, processing requests/queries

**State Transitions**:
- Each state implements `handle(event)` method returning next state
- Immutable state updates (new state object per transition)
- Leader redirection: transition to Connecting state with different member
- Session expiry: fail pending requests, transition to terminal state

**Alternatives Considered**:
- Mutable state object: Rejected - harder to reason about, doesn't match Scala pattern
- State machine library (XState): Rejected - overkill, manual implementation clearer
- Async/await control flow: Rejected - doesn't model concurrent operations well

---

## 6. Request/Response Correlation

### Decision: Map-based tracking with Request ID / Correlation ID
**Rationale**: Scala client uses separate PendingRequests and PendingQueries structures. TypeScript will mirror this pattern with Map<RequestId, PendingData> and Map<CorrelationId, PendingData>.

**Request ID Management**:
- Client maintains monotonic counter (Long, starts at 0)
- Each command gets unique incrementing ID
- Requests track: payload, promise/callback, createdAt, lastSentAt

**Query Correlation**:
- Each query gets UUID-based correlation ID
- Queries track: payload, promise/callback, createdAt, lastSentAt

**Timeout & Retry**:
- Periodic check (100ms) compares lastSentAt vs current time
- Resend if elapsed > configurable timeout (default 10s)
- Update lastSentAt on each send

**Alternatives Considered**:
- Single unified pending map: Rejected - commands and queries have different ID spaces
- Promise-only (no explicit tracking): Rejected - can't implement retry without tracking
- External request ID (UUID): Rejected - must match Scala's Long-based counter for interop

---

## 7. Error Handling Strategy

### Decision: Synchronous exceptions + Promise rejections
**Rationale**: Per clarifications, validation errors throw sync exceptions immediately. Async errors (timeouts, network failures, server rejections) reject returned promises.

**Error Categories**:

1. **Immediate Validation Errors** (throw sync):
   - Invalid config (empty capabilities, no cluster members)
   - Invalid payload encoding
   - Client not connected

2. **Async Operation Errors** (reject Promise):
   - Network timeout
   - Session rejection by server
   - Request timeout after retries
   - Session expiry

3. **Terminal Errors** (emit event + reject all pending):
   - Session expired: fail all pending, emit 'terminated' event
   - Unrecoverable network error

**Alternatives Considered**:
- Result/Either types: Rejected - not idiomatic TypeScript, adds complexity
- Error codes only (no exceptions): Rejected - misses validation errors early
- All errors async: Rejected - clarification specified sync exceptions for validation

---

## 8. Binary Framing Reference

### Decision: Use maitred Frame.ts pattern for framing
**Rationale**: User specified maitred Frame.ts as reference. This provides length-prefixed framing for delimiting messages over streaming ZMQ sockets.

**Framing Pattern** (from maitred reference):
```typescript
// Outgoing: [4-byte length][message bytes]
function frame(data: Buffer): Buffer {
  const length = Buffer.allocUnsafe(4);
  length.writeUInt32BE(data.length, 0);
  return Buffer.concat([length, data]);
}

// Incoming: accumulate until full message available
function unframe(accumulated: Buffer): { message: Buffer | null, remaining: Buffer } {
  if (accumulated.length < 4) return { message: null, remaining: accumulated };
  const length = accumulated.readUInt32BE(0);
  if (accumulated.length < 4 + length) return { message: null, remaining: accumulated };
  const message = accumulated.slice(4, 4 + length);
  const remaining = accumulated.slice(4 + length);
  return { message, remaining };
}
```

**Integration with ZMQ**:
- ZMQ DEALER socket already provides message framing
- Additional framing may not be needed if using ZMQ message boundaries
- Research needed: confirm ZMQ message semantics match Scala implementation

**Alternatives Considered**:
- No framing: Investigate if ZMQ DEALER already provides sufficient message boundaries
- Delimiter-based: Rejected - length-prefix more efficient and unambiguous

---

## 9. Performance Optimization

### Decision: Implement request batching for high throughput
**Rationale**: Spec requires 1K-10K+ req/sec. Batching multiple requests into single ZMQ sends reduces syscall overhead.

**Batching Strategy**:
- Accumulate requests in micro-batches (e.g., 10ms window or 100 requests, whichever comes first)
- Send all as separate ZMQ messages in single send operation
- Maintain individual request ID tracking for correlation

**Other Optimizations**:
- Reuse Buffer allocations where possible
- Lazy encode-on-send (don't encode until batching window closes)
- Pre-allocate Maps with expected size

**Alternatives Considered**:
- No batching: May not achieve 10K+ req/sec target
- Protocol-level batching: Rejected - requires server changes
- Multi-connection pooling: Rejected - spec specifies single session per client instance

---

## 10. Testing Strategy

### Decision: Unit tests + integration tests against mock/real server
**Rationale**: Protocol compatibility is critical. Test both encoding correctness and behavioral correctness.

**Test Categories**:

1. **Codec Tests**:
   - Encode/decode roundtrip for each message type
   - Edge cases (empty maps, max lengths, boundary values)
   - Compare with Scala-encoded bytes (golden files)

2. **State Machine Tests**:
   - State transitions for each event type
   - Reconnection scenarios (leader change, network failure)
   - Session expiry handling

3. **Integration Tests**:
   - Connect to real/mock Raft cluster
   - Submit commands and verify responses
   - Reconnection and retry behavior
   - Performance: verify 1K+ req/sec throughput

4. **Protocol Compatibility Tests**:
   - Test against actual Scala server implementation
   - Verify session lifecycle matches Scala client behavior

**Testing Tools**:
- Jest or Vitest for unit tests
- Mock ZMQ sockets for transport layer tests
- Dockerized Raft cluster for integration tests

**Alternatives Considered**:
- Property-based testing: Deferred - implement after initial release
- Load testing: Required but separate from unit/integration tests
- Contract testing: Could be added for protocol guarantees

---

## 11. Type Safety

### Decision: Strong TypeScript types for all protocol entities
**Rationale**: Leverage TypeScript's type system to prevent encoding errors and match Scala's type safety.

**Type Strategy**:
```typescript
// Newtype-style branded types
type SessionId = string & { readonly __brand: 'SessionId' };
type RequestId = bigint & { readonly __brand: 'RequestId' };
type MemberId = string & { readonly __brand: 'MemberId' };
type Nonce = bigint & { readonly __brand: 'Nonce' };
type CorrelationId = string & { readonly __brand: 'CorrelationId' };

// Discriminated unions for messages
type ClientMessage = 
  | { type: 'CreateSession'; capabilities: Map<string, string>; nonce: Nonce }
  | { type: 'ContinueSession'; sessionId: SessionId; nonce: Nonce }
  | ... // other message types

// State as discriminated union
type ClientState =
  | { state: 'Disconnected'; config: ClientConfig }
  | { state: 'Connecting'; sessionId: SessionId | null; ... }
  | { state: 'Connected'; sessionId: SessionId; ... }
```

**Benefits**:
- Compile-time type checking prevents many bugs
- IDE autocomplete for message types
- Matches Scala's sealed trait + newtype pattern

**Alternatives Considered**:
- Loose typing (any/unknown): Rejected - defeats purpose of TypeScript
- Classes instead of discriminated unions: Rejected - more verbose, less pattern matching
- io-ts for runtime validation: Deferred - focus on compile-time first

---

## 12. No Built-in Logging

### Decision: Event emission for all diagnostic information
**Rationale**: Per clarifications, no built-in logging. Applications observe events and implement their own logging.

**Event Types for Observability**:
```typescript
client.on('stateChange', (oldState, newState) => { ... });
client.on('connectionAttempt', (memberId, address) => { ... });
client.on('connectionSuccess', (memberId) => { ... });
client.on('connectionFailure', (memberId, error) => { ... });
client.on('messageReceived', (message) => { ... });
client.on('messageSent', (message) => { ... });
client.on('requestTimeout', (requestId) => { ... });
client.on('sessionExpired', () => { ... });
```

**Application Responsibility**:
- Listen to events and log as needed
- Implement structured logging (JSON, etc.)
- Configure log levels and filtering

**Alternatives Considered**:
- Built-in console.log: Rejected - per clarifications, no logging
- Optional logger injection: Rejected - adds complexity, events cleaner
- Debug module: Rejected - still built-in logging

---

## Open Questions

### Q1: ZMQ Framing Semantics
**Question**: Does ZMQ DEALER socket preserve message boundaries, making additional framing unnecessary?  
**Research Needed**: Test ZMQ message send/receive to confirm each send() corresponds to one receive() message.  
**Impact**: May simplify implementation if ZMQ handles framing.

### Q2: Batching Implementation
**Question**: Should batching combine multiple requests into single ZMQ message or send them as separate messages in rapid succession?  
**Research Needed**: Benchmark both approaches.  
**Impact**: Affects codec design and performance.

### Q3: TypeScript Target Version
**Question**: Compile to ES2020, ES2021, or ESNext?  
**Decision Needed**: Based on Node.js 18+ support and library dependencies.  
**Impact**: Affects available language features (bigint, etc.).

---

## Summary

All critical technical decisions have been made:
- Binary protocol encoding matches Scala scodec exactly
- ZeroMQ transport using zeromq.js
- Event-loop based state machine architecture
- Strong TypeScript types throughout
- Event emission for observability (no built-in logging)
- Request batching for high throughput
- Comprehensive testing strategy

**Ready for Phase 1: Design**
