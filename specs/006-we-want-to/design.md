# Design: TypeScript Client Library

**Feature**: TypeScript Client Library  
**Date**: 2025-12-29  
**Status**: Complete

## Overview

This document provides a high-level architectural design for the idiomatic TypeScript/Node.js client library for ZIO Raft clusters. The design ensures wire protocol compatibility with the Scala client while using modern Node.js patterns (EventEmitter, Promises, async/await).

---

## Design Principles

1. **Idiomatic TypeScript**: Use Node.js ecosystem patterns, not Scala ports
2. **Wire Protocol Compatibility**: Byte-for-byte compatible with Scala client-server-protocol
3. **Type Safety**: Leverage TypeScript's type system for compile-time safety
4. **Minimal API Surface**: Simple, focused API following principle of least surprise
5. **Zero Built-in Logging**: Applications handle all logging via event observation
6. **High Performance**: 1K-10K+ req/sec throughput with low latency overhead

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Application Layer                          │
│  (User Code: submitCommand, submitQuery, event handlers)        │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ├─── Constructor(config) → validates
                         ├─── connect() → Promise<void>
                         ├─── submitCommand(payload) → Promise<Uint8Array>
                         ├─── submitQuery(payload) → Promise<Uint8Array>
                         ├─── disconnect() → Promise<void>
                         └─── on(event, handler) → EventEmitter
                         │
┌────────────────────────┴────────────────────────────────────────┐
│                       RaftClient (Main Class)                   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Unified Event Stream                         │  │
│  │  (Merges: ActionQueue, ServerMessages, Timers)           │  │
│  └────────┬─────────────────────────────────────────────────┘  │
│           │                                                      │
│  ┌────────▼──────────────────────────────────────────────────┐ │
│  │          ClientState (State Machine)                      │ │
│  │  • Disconnected                                           │ │
│  │  • ConnectingNewSession                                   │ │
│  │  • ConnectingExistingSession                              │ │
│  │  • Connected                                              │ │
│  │                                                           │ │
│  │  Each state: handle(event) → Promise<nextState>          │ │
│  └────────┬──────────────────────────────────────────────────┘ │
│           │                                                      │
│  ┌────────▼────────┐  ┌─────────────────┐  ┌────────────────┐ │
│  │ PendingRequests │  │ PendingQueries  │  │ ServerRequest  │ │
│  │  (Map<ID, Req>) │  │ (Map<ID, Query>)│  │    Tracker     │ │
│  └─────────────────┘  └─────────────────┘  └────────────────┘ │
│                                                                  │
└────────────────────────┬────────────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────────────┐
│                  Protocol Layer                                 │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Message Codecs (Binary Serialization)                   │  │
│  │  • ClientMessage encoding (CreateSession, ClientRequest..│  │
│  │  • ServerMessage decoding (SessionCreated, ClientResponse│  │
│  │  • Protocol header validation (signature + version)      │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────┬────────────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────────────┐
│                  Transport Layer (ZeroMQ)                       │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  ClientTransport                                         │  │
│  │  • ZMQ DEALER socket                                     │  │
│  │  • connect(endpoint) / disconnect()                      │  │
│  │  • sendMessage(msg: ClientMessage)                       │  │
│  │  • incomingMessages: AsyncIterable<ServerMessage>       │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                         │
                         ▼
                  ┌──────────────┐
                  │ ZIO Raft     │
                  │ Server       │
                  │ (ZMQ ROUTER) │
                  └──────────────┘
```

---

## Component Breakdown

### 1. RaftClient (Main Entry Point)

**Responsibility**: Public API facade, lifecycle management, event loop orchestration

**Key Methods**:
```typescript
class RaftClient extends EventEmitter {
  constructor(config: ClientConfig) {
    // 1. Validate config (throw ValidationError if invalid)
    // 2. Create transport (ZMQ DEALER socket)
    // 3. Create action queue (unbounded async queue)
    // 4. Create server request queue
    // 5. Initialize state to Disconnected
    // 6. Event loop not started yet (lazy)
  }
  
  async connect(): Promise<void> {
    // 1. Start event loop (if not already running)
    // 2. Enqueue Connect action
    // 3. Return (state machine handles actual connection)
  }
  
  async submitCommand(payload: Uint8Array): Promise<Uint8Array> {
    // 1. Validate payload (throw ValidationError if bad)
    // 2. Create Promise + resolve/reject callbacks
    // 3. Enqueue SubmitCommand action with promise
    // 4. Return promise (resolves when response received or timeout)
  }
  
  async submitQuery(payload: Uint8Array): Promise<Uint8Array> {
    // Similar to submitCommand but uses SubmitQuery action
  }
  
  async disconnect(): Promise<void> {
    // 1. Enqueue Disconnect action
    // 2. Wait for event loop to terminate
  }
  
  onServerRequest(handler: (req: ServerRequest) => void): void {
    // Register handler for server-initiated requests
    // Internally: read from serverRequestQueue, invoke handler
  }
}
```

**Event Loop**:
```typescript
private async runEventLoop(): Promise<void> {
  // Merge streams:
  // 1. actionQueue (user API calls)
  // 2. transport.incomingMessages (server responses)
  // 3. keepAliveTimer (periodic tick)
  // 4. timeoutChecker (periodic timeout check)
  
  let currentState: ClientState = new DisconnectedState(this.config);
  
  for await (const event of this.unifiedStream()) {
    currentState = await currentState.handle(event, this.transport, ...);
  }
}
```

---

### 2. ClientState (State Machine)

**Responsibility**: Functional state machine, handles all events, transitions between states

**State Diagram**:
```
┌──────────────┐
│ Disconnected │──────connect()──────┐
└──────────────┘                     │
                                     ▼
                         ┌────────────────────────┐
                         │ ConnectingNewSession   │
                         │ (send CreateSession)   │
                         └───────────┬────────────┘
                                     │
                         SessionCreated
                                     │
                                     ▼
                         ┌────────────────────────┐
            ┌───────────►│      Connected         │
            │            │ (active session)       │
            │            └───────────┬────────────┘
            │                        │
            │            disconnect() │  network failure
            │                        ▼
            │            ┌────────────────────────┐
            │            │ ConnectingExisting     │
            └────────────│ Session (resume)       │
      SessionContinued   └────────────────────────┘
                                     │
                         SessionExpired (terminal)
                                     ▼
                                  [EXIT]
```

**State Handlers**:

Each state implements:
```typescript
interface StateHandler {
  handle(
    event: StreamEvent,
    transport: ClientTransport,
    serverRequestQueue: AsyncQueue<ServerRequest>
  ): Promise<ClientState>;
}
```

**Event Handling Examples**:

1. **Disconnected + Connect**:
   - Generate nonce
   - Pick first cluster member
   - Connect transport to endpoint
   - Send CreateSession message
   - Transition to ConnectingNewSession

2. **ConnectingNewSession + SessionCreated**:
   - Store sessionId
   - Emit 'connected' event
   - Transition to Connected

3. **Connected + SubmitCommand**:
   - Generate requestId
   - Add to pendingRequests
   - Send ClientRequest message
   - Stay in Connected (wait for response)

4. **Connected + ClientResponse**:
   - Lookup pending request by requestId
   - Resolve promise with response payload
   - Remove from pendingRequests
   - Stay in Connected

5. **Connected + Network Disconnect**:
   - Emit 'disconnected' event
   - Transition to ConnectingExistingSession (preserve sessionId, pending requests)

6. **ConnectingExistingSession + SessionContinued**:
   - Emit 'connected' event
   - Resend all pending requests
   - Transition to Connected

7. **Connected + SessionClosed(SessionExpired)**:
   - Emit 'sessionExpired' event
   - Fail all pending requests with SessionExpiredError
   - Terminate event loop (exit process)

---

### 3. Protocol Layer (Binary Codecs)

**Responsibility**: Byte-level encoding/decoding of protocol messages

**Message Format**:
```
┌────────────┬──────────┬──────────────┬─────────────────────┐
│ Signature  │ Version  │ Discriminator│  Message Payload    │
│  (5 bytes) │ (1 byte) │   (1 byte)   │  (variable length)  │
└────────────┴──────────┴──────────────┴─────────────────────┘
```

**Signature**: `0x7a 0x72 0x61 0x66 0x74` ("zraft")  
**Version**: `0x01`  
**Discriminators**:

**ClientMessage** (client → server):
- `0x01`: CreateSession
- `0x02`: ContinueSession
- `0x03`: KeepAlive
- `0x04`: ClientRequest
- `0x05`: ServerRequestAck
- `0x06`: CloseSession
- `0x07`: ConnectionClosed
- `0x08`: Query

**ServerMessage** (server → client):
- `0x01`: SessionCreated
- `0x02`: SessionContinued
- `0x03`: SessionRejected
- `0x04`: SessionClosed
- `0x05`: KeepAliveResponse
- `0x06`: ClientResponse
- `0x07`: ServerRequest
- `0x08`: RequestError
- `0x09`: QueryResponse

**Codec Implementation Pattern**:
```typescript
// Encoding
function encodeClientMessage(msg: ClientMessage): Buffer {
  const buffer = Buffer.allocUnsafe(1024); // Pre-allocate
  let offset = 0;
  
  // Write header
  offset = buffer.write('zraft', offset, 'latin1');
  buffer.writeUInt8(0x01, offset++); // Protocol version
  
  // Write discriminator + payload based on message type
  switch (msg.type) {
    case 'CreateSession':
      buffer.writeUInt8(0x01, offset++);
      offset = encodeCapabilities(msg.capabilities, buffer, offset);
      offset = encodeNonce(msg.nonce, buffer, offset);
      break;
    case 'ClientRequest':
      buffer.writeUInt8(0x04, offset++);
      offset = encodeRequestId(msg.requestId, buffer, offset);
      offset = encodeRequestId(msg.lowestPendingRequestId, buffer, offset);
      offset = encodePayload(msg.payload, buffer, offset);
      offset = encodeTimestamp(msg.createdAt, buffer, offset);
      break;
    // ... other message types
  }
  
  return buffer.subarray(0, offset);
}

// Decoding
function decodeServerMessage(buffer: Buffer): ServerMessage {
  let offset = 0;
  
  // Validate header
  const signature = buffer.toString('latin1', offset, offset + 5);
  if (signature !== 'zraft') throw new ProtocolError('Invalid signature');
  offset += 5;
  
  const version = buffer.readUInt8(offset++);
  if (version !== 0x01) throw new ProtocolError(`Unsupported version: ${version}`);
  
  // Read discriminator
  const discriminator = buffer.readUInt8(offset++);
  
  // Decode payload based on discriminator
  switch (discriminator) {
    case 0x01: // SessionCreated
      return {
        type: 'SessionCreated',
        sessionId: decodeSessionId(buffer, offset),
        nonce: decodeNonce(buffer, offset + 36)
      };
    case 0x06: // ClientResponse
      const [requestId, nextOffset] = decodeRequestId(buffer, offset);
      const [payload, finalOffset] = decodePayload(buffer, nextOffset);
      return {
        type: 'ClientResponse',
        requestId,
        result: payload
      };
    // ... other message types
    default:
      throw new ProtocolError(`Unknown discriminator: ${discriminator}`);
  }
}
```

**Field Encoding**:
- **String**: Length-prefixed varint + UTF-8 bytes
- **BigInt (RequestId/Nonce)**: 8 bytes little-endian uint64
- **UUID (SessionId/CorrelationId)**: 16 bytes raw binary (no dashes)
- **Timestamp (Date)**: ISO 8601 string, length-prefixed
- **Payload (Uint8Array)**: Length-prefixed varint + raw bytes
- **Map<String, String>**: varint count + (key, value) pairs

---

### 4. Transport Layer (ZeroMQ)

**Responsibility**: ZMQ socket management, connection/disconnection, message send/receive

**ZMQ Pattern**: DEALER (client) ↔ ROUTER (server)

**ClientTransport Interface**:
```typescript
interface ClientTransport {
  connect(endpoint: string): Promise<void>;
  disconnect(): Promise<void>;
  sendMessage(msg: ClientMessage): Promise<void>;
  incomingMessages: AsyncIterable<ServerMessage>;
  onDisconnect(handler: () => void): void;
}
```

**Implementation**:
```typescript
class ZmqTransport implements ClientTransport {
  private socket: zmq.Dealer;
  private currentEndpoint: string | null = null;
  
  async connect(endpoint: string): Promise<void> {
    this.socket = new zmq.Dealer();
    this.socket.connect(endpoint);
    this.currentEndpoint = endpoint;
  }
  
  async disconnect(): Promise<void> {
    if (this.socket) {
      this.socket.disconnect(this.currentEndpoint!);
      await this.socket.close();
      this.currentEndpoint = null;
    }
  }
  
  async sendMessage(msg: ClientMessage): Promise<void> {
    const encoded = encodeClientMessage(msg);
    await this.socket.send(encoded);
  }
  
  async *incomingMessages(): AsyncIterable<ServerMessage> {
    for await (const [buffer] of this.socket) {
      try {
        yield decodeServerMessage(buffer as Buffer);
      } catch (err) {
        // Log protocol error but don't crash
        console.error('Protocol decode error:', err);
      }
    }
  }
}
```

**ZMQ Routing Identity**:
- Server derives session ID from ZMQ routing identity
- Client doesn't explicitly manage routing identity (ZMQ handles automatically)
- Session persistence relies on ZMQ connection identity

**Reconnection**:
- On disconnect: close old socket, create new socket
- Connect to next cluster member (round-robin)
- ZMQ provides automatic reconnection for existing connections
- Client handles explicit reconnection for leadership changes

---

### 5. Request Management

**PendingRequests**:
```typescript
class PendingRequests {
  private map = new Map<bigint, PendingCommand>();
  
  add(requestId: bigint, payload: Uint8Array, promise: Promise<Uint8Array>): void {
    this.map.set(requestId, { requestId, payload, promise, createdAt: new Date() });
  }
  
  complete(requestId: bigint, result: Uint8Array): void {
    const pending = this.map.get(requestId);
    if (pending) {
      pending.resolve(result);
      this.map.delete(requestId);
    }
  }
  
  timeout(requestId: bigint): void {
    const pending = this.map.get(requestId);
    if (pending) {
      pending.reject(new TimeoutError('Request timeout', requestId));
      this.map.delete(requestId);
    }
  }
  
  checkTimeouts(now: Date, timeoutMs: number): bigint[] {
    const timedOut: bigint[] = [];
    for (const [id, req] of this.map) {
      if (now.getTime() - req.createdAt.getTime() > timeoutMs) {
        timedOut.push(id);
      }
    }
    return timedOut;
  }
  
  lowestPendingRequestId(): bigint | undefined {
    let lowest: bigint | undefined;
    for (const [id] of this.map) {
      if (lowest === undefined || id < lowest) lowest = id;
    }
    return lowest;
  }
}
```

**PendingQueries**: Similar structure but keyed by CorrelationId (UUID string)

**RequestIdRef** (Atomic Counter):
```typescript
class RequestIdRef {
  private current: bigint = 0n;
  
  next(): bigint {
    return ++this.current;
  }
  
  current(): bigint {
    return this.current;
  }
}
```

---

### 6. Server Request Handling

**Flow**:
1. Server sends ServerRequest(requestId, payload)
2. Client receives, checks ServerRequestTracker
3. If new request (consecutive ID): deliver to handler, send ack
4. If old request: send ack, don't deliver
5. If out-of-order: log warning, don't ack (wait for missing requests)

**ServerRequestTracker**:
```typescript
class ServerRequestTracker {
  private lastAcknowledged: bigint = 0n;
  
  check(requestId: bigint): 'Process' | 'OldRequest' | 'OutOfOrder' {
    if (requestId === this.lastAcknowledged + 1n) {
      return 'Process'; // Expected next
    } else if (requestId <= this.lastAcknowledged) {
      return 'OldRequest'; // Already processed
    } else {
      return 'OutOfOrder'; // Gap detected
    }
  }
  
  acknowledge(requestId: bigint): void {
    this.lastAcknowledged = requestId;
  }
}
```

---

## Sequence Diagrams

### Scenario 1: Successful Command Submission

```
App             Client          StateMachine    Transport       Server
 │                │                  │              │              │
 │  submitCommand │                  │              │              │
 ├───────────────►│                  │              │              │
 │                │  Enqueue Action  │              │              │
 │                ├─────────────────►│              │              │
 │                │                  │  SendMessage │              │
 │                │                  ├─────────────►│  ClientRequest
 │                │                  │              ├─────────────►│
 │                │                  │              │              │
 │   (Promise     │                  │              │ ClientResponse
 │    pending)    │                  │              │◄─────────────┤
 │                │                  │  Incoming    │              │
 │                │                  │◄─────────────┤              │
 │                │  Resolve Promise │              │              │
 │◄───────────────┼──────────────────┤              │              │
 │  Uint8Array    │                  │              │              │
```

### Scenario 2: Reconnection with Session Resumption

```
Client          StateMachine    Transport       Server
  │                  │              │              │
  │   (Connected)    │              │              │
  │                  │              │   Network    │
  │                  │              │   Failure    │
  │                  │              │◄─────────────┤
  │  Emit            │              │              │
  │  'disconnected'  │              │              │
  ◄─────────────────┤              │              │
  │                  │              │              │
  │   Transition to  │              │              │
  │   Connecting     │              │              │
  │   ExistingSession│              │              │
  │                  │  Connect new │              │
  │                  ├─────────────►│              │
  │                  │  endpoint    │              │
  │                  │              │              │
  │                  │  Send        │ContinueSession
  │                  ├─────────────►│─────────────►│
  │                  │              │              │
  │                  │              │SessionContinued
  │                  │              │◄─────────────┤
  │  Emit            │              │              │
  │  'connected'     │              │              │
  ◄─────────────────┤              │              │
  │                  │              │              │
  │   Resend Pending │              │              │
  │   Requests       ├─────────────►│─────────────►│
```

### Scenario 3: Session Expiry (Terminal)

```
Client          StateMachine    Transport       Server
  │                  │              │              │
  │   (Connected)    │              │              │
  │                  │              │              │
  │   (Missed        │              │              │
  │    KeepAlives)   │              │              │
  │                  │              │SessionClosed │
  │                  │              │(SessionExpired)
  │                  │              │◄─────────────┤
  │  Emit            │              │              │
  │  'sessionExpired'│              │              │
  ◄─────────────────┤              │              │
  │                  │              │              │
  │  Fail all        │              │              │
  │  pending         │              │              │
  │  requests        │              │              │
  │                  │              │              │
  │  Terminate       │              │              │
  │  event loop      │              │              │
  │                  X              X              │
```

---

## Project Structure

### New Files (TypeScript Client)

```
typescript-client/
├── src/
│   ├── index.ts                     # Public API exports
│   ├── client.ts                    # RaftClient class
│   ├── config.ts                    # ClientConfig interface + validation
│   ├── types.ts                     # Common types (SessionId, RequestId, etc.)
│   ├── errors.ts                    # Error class hierarchy
│   │
│   ├── state/
│   │   ├── clientState.ts           # State machine definitions
│   │   ├── disconnected.ts          # DisconnectedState handler
│   │   ├── connectingNew.ts         # ConnectingNewSessionState handler
│   │   ├── connectingExisting.ts    # ConnectingExistingSessionState handler
│   │   ├── connected.ts             # ConnectedState handler
│   │   ├── pendingRequests.ts       # PendingRequests manager
│   │   ├── pendingQueries.ts        # PendingQueries manager
│   │   └── serverRequestTracker.ts  # ServerRequestTracker
│   │
│   ├── protocol/
│   │   ├── constants.ts             # Protocol signature, version, discriminators
│   │   ├── messages.ts              # Message type definitions
│   │   ├── codecs.ts                # Binary encoding/decoding functions
│   │   └── fields.ts                # Field-level encoders/decoders
│   │
│   ├── transport/
│   │   ├── transport.ts             # ClientTransport interface
│   │   ├── zmqTransport.ts          # ZeroMQ implementation
│   │   └── mockTransport.ts         # Mock for unit tests
│   │
│   ├── utils/
│   │   ├── asyncQueue.ts            # Async queue implementation
│   │   ├── requestIdRef.ts          # Atomic counter for request IDs
│   │   └── streamMerger.ts          # Utility to merge async iterables
│   │
│   └── events/
│       ├── eventTypes.ts            # ConnectionEvent type definitions
│       └── eventEmitter.ts          # Typed EventEmitter wrapper
│
├── tests/
│   ├── unit/
│   │   ├── protocol/
│   │   │   ├── codecs.test.ts       # Codec round-trip tests
│   │   │   └── fields.test.ts       # Field encoding tests
│   │   ├── state/
│   │   │   ├── disconnected.test.ts # State handler tests
│   │   │   ├── connected.test.ts
│   │   │   ├── pendingRequests.test.ts
│   │   │   └── serverRequestTracker.test.ts
│   │   └── utils/
│   │       └── asyncQueue.test.ts
│   │
│   └── integration/
│       ├── lifecycle.test.ts        # Full connect/request/disconnect cycle
│       ├── reconnection.test.ts     # Reconnection scenarios
│       ├── sessionExpiry.test.ts    # Session expiry handling
│       └── compatibility.test.ts    # Wire protocol compatibility with Scala
│
├── package.json
├── tsconfig.json
└── vitest.config.ts
```

### No Changes to Existing Scala Projects

- `client-server-protocol/`: No changes (TypeScript client implements same protocol)
- `client-server-client/`: No changes (Scala client remains independent)
- `raft/`: No changes (server implementation unchanged)
- Other modules: No changes

---

## Dependencies

### Production Dependencies

```json
{
  "dependencies": {
    "zeromq": "^6.0.0"
  }
}
```

### Development Dependencies

```json
{
  "devDependencies": {
    "typescript": "^5.3.0",
    "vitest": "^1.0.0",
    "@types/node": "^18.0.0",
    "eslint": "^8.56.0",
    "@typescript-eslint/eslint-plugin": "^6.18.0",
    "@typescript-eslint/parser": "^6.18.0",
    "prettier": "^3.1.0"
  }
}
```

**Note**: Jest currently in package.json but plan recommends migrating to Vitest for better TypeScript support.

---

## Areas Affected & Testing Strategy

### Areas Affected

1. **New TypeScript project**: `typescript-client/` (no impact on existing Scala code)
2. **Documentation**: README for TypeScript client
3. **CI/CD**: Add TypeScript client tests to CI pipeline (separate from Scala tests)

### Testing Requirements

#### Unit Tests

1. **Protocol Codecs**:
   - Test: Round-trip encoding/decoding for all message types
   - Test: Byte-for-byte compatibility with Scala reference
   - Test: Protocol header validation (signature + version)
   - Test: Error handling for invalid messages

2. **State Machine**:
   - Test: All state transitions with mock transport
   - Test: Event handling in each state
   - Test: Edge cases (reconnection, session expiry)

3. **Request Management**:
   - Test: Pending request add/complete/timeout
   - Test: Request ID generation (sequential, no gaps)
   - Test: Timeout detection logic

4. **Server Request Tracking**:
   - Test: Consecutive ID detection
   - Test: Duplicate detection (old request)
   - Test: Out-of-order detection (gap)

#### Integration Tests

1. **Full Lifecycle**:
   - Test: Constructor → connect() → submitCommand() → disconnect()
   - Test: Promise resolution with actual response
   - Test: Event emission (connected, disconnected)

2. **Reconnection**:
   - Test: Network disconnect → automatic reconnection
   - Test: Session resumption (ContinueSession)
   - Test: Pending requests preserved and resent

3. **Session Expiry**:
   - Test: SessionExpired rejection handling
   - Test: All pending requests fail
   - Test: 'sessionExpired' event emitted

4. **Performance**:
   - Test: 1K-10K requests/second throughput
   - Test: Low latency overhead (<10ms client-side)

5. **Compatibility**:
   - Test: TypeScript client against real Scala server
   - Test: Wire protocol byte-for-byte match
   - Test: Interoperability with Scala client (same cluster)

---

## Protocol Changes

**No protocol changes required.**

- TypeScript client implements existing client-server-protocol (version 0x01)
- All message types already defined in Scala protocol
- Byte-level compatibility maintained

---

## Performance Considerations

### Optimization Strategies

1. **Buffer Pooling**:
   - Pre-allocate buffers for common message sizes
   - Reuse buffers across requests to reduce GC pressure

2. **Zero-Copy**:
   - Use `Buffer.subarray()` instead of `Buffer.slice()` where possible
   - Avoid intermediate buffer allocations in codec layer

3. **Fast Path for Common Messages**:
   - Optimize ClientRequest/ClientResponse encoding (hot path)
   - Cache protocol header bytes

4. **Minimal Allocations**:
   - Reuse Maps for pending requests (don't recreate)
   - Avoid unnecessary object spreading in state transitions

5. **Batch-Friendly API**:
   - Allow Promise.all() pattern for concurrent requests
   - No internal buffering (send immediately for low latency)

### Benchmarking

Planned benchmarks:
- Single request latency (round-trip time)
- Throughput (requests/second at various concurrency levels)
- Memory usage under sustained load
- Protocol encoding/decoding overhead

---

## Security Considerations

1. **Input Validation**:
   - Validate all config inputs in constructor
   - Validate payload types (Uint8Array) before encoding
   - Bounds checking in codec layer

2. **Protocol Validation**:
   - Verify signature and version on all incoming messages
   - Reject malformed messages gracefully (don't crash)
   - Limit message sizes to prevent memory exhaustion

3. **No Built-in Authentication**:
   - Capabilities can include auth tokens (application-level)
   - ZMQ transport doesn't provide encryption (use ZMQ CurveZMQ if needed)

4. **Error Information**:
   - Don't leak internal state in error messages
   - Sanitize endpoint strings in logs (no credentials)

---

## Future Enhancements (Out of Scope)

1. **Browser Support**: Requires WebSocket transport (ZMQ not available in browsers)
2. **Compression**: Payload compression for large payloads
3. **Metrics**: Built-in Prometheus metrics (currently requires application-level via events)
4. **Connection Pooling**: Multiple sessions per client instance
5. **Batching API**: Explicit batch API for multi-command transactions

---

## Summary

This design provides a production-ready, idiomatic TypeScript client library for ZIO Raft clusters. Key design decisions:

1. **Functional state machine** with discriminated unions for type safety
2. **Promise-based async API** matching modern Node.js patterns
3. **Byte-for-byte protocol compatibility** with Scala client
4. **Minimal event system** (4 events: connected, disconnected, reconnecting, sessionExpired)
5. **Zero built-in logging** - applications observe events for diagnostics
6. **High performance** - 1K-10K+ req/sec with minimal overhead
7. **Comprehensive testing** - unit + integration + compatibility tests

The design maintains clear separation of concerns (client → state machine → protocol → transport) while ensuring idiomatic TypeScript throughout. No changes to existing Scala codebase required.

---

## Next Steps

1. Generate tests.md - Extract test scenarios from acceptance criteria
2. Generate quickstart.md - End-to-end validation guide
3. Update agent context file - Add TypeScript client to agent knowledge
4. Re-evaluate Constitution Check - Verify design compliance
5. Document Phase 2 task generation approach
