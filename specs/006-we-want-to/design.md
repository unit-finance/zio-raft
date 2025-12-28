# Design: TypeScript Client Library

**Date**: 2025-12-24  
**Feature**: TypeScript Client for ZIO Raft

## Overview
This document describes the high-level architecture and design of the TypeScript client library for ZIO Raft clusters. The design mirrors the Scala client architecture while adapting to TypeScript/Node.js idioms.

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      Application Layer                       │
│  (Uses RaftClient API, observes events, handles responses) │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                        RaftClient                            │
│  - connect() / disconnect()                                  │
│  - submitCommand(payload): Promise<Buffer>                   │
│  - query(payload): Promise<Buffer>                           │
│  - serverRequests(): AsyncIterator<ServerRequest>           │
│  - on(event, handler): EventEmitter pattern                  │
└────────────────────────────┬────────────────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
   ┌──────────┐      ┌─────────────┐     ┌─────────────┐
   │  Action  │      │   ZMQ       │     │ Timer       │
   │  Queue   │      │  Messages   │     │  Events     │
   └──────────┘      └─────────────┘     └─────────────┘
         │                   │                   │
         └───────────────────┼───────────────────┘
                             ▼
                    ┌─────────────────┐
                    │  Unified Event  │
                    │  Loop (merged)  │
                    └─────────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │  State Machine  │
                    │  (ClientState)  │
                    └─────────────────┘
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
   ┌──────────┐      ┌──────────────┐   ┌──────────────┐
   │ Pending  │      │  Pending     │   │  Server      │
   │ Requests │      │  Queries     │   │  Request     │
   │          │      │              │   │  Tracker     │
   └──────────┘      └──────────────┘   └──────────────┘
         │                   │                   │
         └───────────────────┼───────────────────┘
                             ▼
                    ┌─────────────────┐
                    │   Transport     │
                    │  (ZMQ DEALER)   │
                    └─────────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │  Protocol       │
                    │  Codecs         │
                    │  (encode/decode)│
                    └─────────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │ ZIO Raft Server │
                    │   (Scala)       │
                    └─────────────────┘
```

---

## 2. Module Structure

### 2.1 Core Modules

```
typescript-client/src/
├── index.ts                    # Public API exports
├── client.ts                   # RaftClient main class
├── config.ts                   # ClientConfig and validation
├── types.ts                    # Shared type definitions (branded types)
│
├── transport/
│   ├── transport.ts            # ClientTransport interface
│   ├── zmqTransport.ts         # ZMQ implementation
│   └── mockTransport.ts        # Mock for testing
│
├── protocol/
│   ├── messages.ts             # Message type definitions
│   ├── codecs.ts               # Binary encoding/decoding
│   ├── constants.ts            # Protocol constants
│   └── frame.ts                # Binary framing utilities (if needed)
│
├── state/
│   ├── clientState.ts          # State machine implementation
│   ├── pendingRequests.ts      # PendingRequests tracker
│   ├── pendingQueries.ts       # PendingQueries tracker
│   ├── serverRequestTracker.ts # Server request deduplication
│   └── requestIdRef.ts         # RequestId counter
│
├── events/
│   ├── eventTypes.ts           # ClientEvent definitions
│   └── eventEmitter.ts         # Event emission utilities
│
└── utils/
    ├── asyncQueue.ts           # Async action queue
    └── timer.ts                # Timer utilities
```

---

## 3. State Machine Design

### 3.1 States and Transitions

```
┌─────────────┐
│             │ connect()
│ Disconnected├────────────────────┐
│             │                    │
└─────────────┘                    ▼
                        ┌────────────────────────┐
                        │ ConnectingNewSession   │
                        │  - Send CreateSession  │
                        └───────┬────────────────┘
                                │
              SessionCreated    │    SessionRejected
              ┌─────────────────┼─────────────┐
              │                 │             │
              ▼                 │             ▼
      ┌─────────────┐           │     ┌──────────────┐
      │             │           │     │ Try next     │
      │  Connected  │           │     │ member       │
      │             │           │     └──────────────┘
      └──────┬──────┘           │
             │                  │
             │ SessionClosed    │
             │ (not expired)    │
             └──────────────────┘
                     │
                     ▼
          ┌─────────────────────────┐
          │ ConnectingExistingSession│
          │  - Send ContinueSession │
          └────────────────────────┘
```

### 3.2 State Handler Pattern

Each state implements:
```typescript
interface StateHandler {
  handle(event: StreamEvent): Promise<ClientState>;
}
```

The state machine processes events sequentially, transitioning between states based on:
- User actions (connect, disconnect, submit)
- Server messages (responses, rejections, closures)
- Timer events (keep-alive, timeout checks)

---

## 4. Event Loop Architecture

### 4.1 Unified Event Stream

The client merges multiple event sources into a single async iterator:

```typescript
async function* unifiedEventStream(): AsyncIterator<StreamEvent> {
  const actionQueue = new AsyncQueue<ClientAction>();
  const zmqMessages = zmqTransport.incomingMessages();
  const keepAliveTicker = setInterval(() => ..., keepAliveInterval);
  const timeoutChecker = setInterval(() => ..., 100);
  
  // Merge all sources using async generator
  while (true) {
    yield await Promise.race([
      actionQueue.take().then(action => ({ type: 'Action', action })),
      zmqMessages.next().then(msg => ({ type: 'ServerMsg', message: msg })),
      // ... other sources
    ]);
  }
}
```

### 4.2 Event Processing Loop

```typescript
async function run() {
  let state: ClientState = { state: 'Disconnected', config };
  
  for await (const event of unifiedEventStream()) {
    try {
      const newState = await state.handle(event, transport, dependencies);
      if (newState.state !== state.state) {
        emit('stateChange', { oldState: state.state, newState: newState.state });
      }
      state = newState;
    } catch (error) {
      // Error handling...
    }
  }
}
```

---

## 5. Request/Response Correlation

### 5.1 Command Flow (Client → Server → Client)

```
Application                Client              Transport             Server
     │                        │                    │                    │
     │ submitCommand()        │                    │                    │
     ├───────────────────────>│                    │                    │
     │                        │ Allocate RequestId │                    │
     │                        │ Add to pending     │                    │
     │                        │ map with Promise   │                    │
     │                        │                    │                    │
     │                        │ ClientRequest      │                    │
     │                        ├───────────────────>│ ClientRequest      │
     │                        │                    ├───────────────────>│
     │                        │                    │                    │
     │                        │                    │ ClientResponse     │
     │                        │ ClientResponse     │<───────────────────┤
     │                        │<───────────────────┤                    │
     │                        │ Lookup RequestId   │                    │
     │                        │ Resolve Promise    │                    │
     │<───────────────────────┤                    │                    │
     │  result: Buffer        │                    │                    │
```

### 5.2 Query Flow (Similar but uses CorrelationId)

Queries use UUID-based correlation IDs instead of sequential request IDs, allowing them to bypass the session state machine's command sequencing.

---

## 6. Reconnection Strategy

### 6.1 Leader Discovery

```
1. Initial connection: Try first member from cluster config
2. If SessionRejected with NotLeader:
   - If leaderId hint provided: connect to that member
   - Otherwise: try next member in round-robin
3. If SessionClosed with NotLeaderAnymore:
   - Same as above
4. On network failure:
   - Try next member
5. Continue until successful or all members exhausted
```

### 6.2 Pending Request Handling

**During Reconnection (Not Expired)**:
- Keep pending requests in memory
- After successful reconnection (SessionContinued):
  - Resend all pending requests with updated lowestPendingRequestId
  - Update lastSentAt timestamps
  - Continue waiting for responses

**On Session Expiry**:
- Reject all pending request promises with error
- Reject all pending query promises with error
- Emit 'sessionExpired' event
- Terminate client (no automatic recovery)

---

## 7. Binary Protocol Implementation

### 7.1 Encoding Strategy

Each message codec follows this pattern:
```typescript
function encodeClientMessage(message: ClientMessage): Buffer {
  const parts: Buffer[] = [];
  
  // 1. Protocol header
  parts.push(PROTOCOL_SIGNATURE);                    // 5 bytes
  parts.push(Buffer.from([PROTOCOL_VERSION]));       // 1 byte
  
  // 2. Message type discriminator
  parts.push(Buffer.from([getMessageType(message)])); // 1 byte
  
  // 3. Message-specific data
  switch (message.type) {
    case 'CreateSession':
      parts.push(encodeCapabilities(message.capabilities));
      parts.push(encodeNonce(message.nonce));
      break;
    // ... other cases
  }
  
  return Buffer.concat(parts);
}
```

### 7.2 Decoding Strategy

```typescript
function decodeServerMessage(buffer: Buffer): ServerMessage {
  let offset = 0;
  
  // 1. Verify protocol header
  const signature = buffer.slice(offset, offset + 5);
  if (!signature.equals(PROTOCOL_SIGNATURE)) {
    throw new Error('Invalid protocol signature');
  }
  offset += 5;
  
  const version = buffer.readUInt8(offset);
  if (version !== PROTOCOL_VERSION) {
    throw new Error(`Unsupported protocol version: ${version}`);
  }
  offset += 1;
  
  // 2. Read message type
  const messageType = buffer.readUInt8(offset);
  offset += 1;
  
  // 3. Decode message-specific data
  switch (messageType) {
    case ServerMessageType.SessionCreated:
      return decodeSessionCreated(buffer, offset);
    // ... other cases
  }
}
```

### 7.3 Variable-Length Encoding

```typescript
// String encoding: [length: uint16][utf8 bytes]
function encodeString(str: string): Buffer {
  const utf8 = Buffer.from(str, 'utf8');
  const length = Buffer.allocUnsafe(2);
  length.writeUInt16BE(utf8.length);
  return Buffer.concat([length, utf8]);
}

// ByteVector encoding: [length: int32][raw bytes]
function encodePayload(payload: Buffer): Buffer {
  const length = Buffer.allocUnsafe(4);
  length.writeInt32BE(payload.length);
  return Buffer.concat([length, payload]);
}

// Map encoding: [count: uint16]([key][value])*
function encodeMap(map: Map<string, string>): Buffer {
  const parts: Buffer[] = [];
  const count = Buffer.allocUnsafe(2);
  count.writeUInt16BE(map.size);
  parts.push(count);
  
  for (const [key, value] of map) {
    parts.push(encodeString(key));
    parts.push(encodeString(value));
  }
  
  return Buffer.concat(parts);
}
```

---

## 8. ZeroMQ Transport

### 8.1 Socket Configuration

```typescript
class ZmqTransport implements ClientTransport {
  private socket: zmq.Dealer;
  private currentAddress: string | null = null;
  
  constructor(config: ClientConfig) {
    this.socket = new zmq.Dealer();
    
    // Configure socket options (matching Scala client)
    this.socket.linger = 0;                    // Immediate close
    this.socket.heartbeatInterval = 1000;      // 1 second
    this.socket.heartbeatTtl = 10000;          // 10 seconds
    this.socket.heartbeatTimeout = 30000;      // 30 seconds
    this.socket.sendHighWaterMark = 200000;
    this.socket.receiveHighWaterMark = 200000;
  }
  
  async connect(address: string): Promise<void> {
    if (this.currentAddress) {
      await this.disconnect();
    }
    this.socket.connect(address);
    this.currentAddress = address;
  }
  
  async disconnect(): Promise<void> {
    if (this.currentAddress) {
      this.socket.disconnect(this.currentAddress);
      this.currentAddress = null;
    }
  }
  
  async send(message: ClientMessage): Promise<void> {
    const encoded = encodeClientMessage(message);
    await this.socket.send(encoded);
  }
  
  async *receive(): AsyncIterator<ServerMessage> {
    for await (const [buffer] of this.socket) {
      yield decodeServerMessage(buffer as Buffer);
    }
  }
}
```

---

## 9. Performance Optimizations

### 9.1 Request Batching

For high throughput, batch multiple requests:

```typescript
class RequestBatcher {
  private batch: ClientRequest[] = [];
  private timer: NodeJS.Timeout | null = null;
  
  async add(request: ClientRequest): Promise<void> {
    this.batch.push(request);
    
    // Send batch if size threshold reached
    if (this.batch.length >= 100) {
      await this.flush();
    }
    
    // Or send after time window (10ms)
    if (!this.timer) {
      this.timer = setTimeout(() => this.flush(), 10);
    }
  }
  
  private async flush(): Promise<void> {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    
    const toSend = this.batch;
    this.batch = [];
    
    // Send all as separate messages (ZMQ handles efficiently)
    for (const request of toSend) {
      await transport.send(request);
    }
  }
}
```

### 9.2 Buffer Pooling

Reuse buffers for encoding:
```typescript
class BufferPool {
  private pool: Buffer[] = [];
  private maxSize = 100;
  
  acquire(size: number): Buffer {
    const buffer = this.pool.pop();
    if (buffer && buffer.length >= size) {
      return buffer.slice(0, size);
    }
    return Buffer.allocUnsafe(size);
  }
  
  release(buffer: Buffer): void {
    if (this.pool.length < this.maxSize) {
      this.pool.push(buffer);
    }
  }
}
```

---

## 10. Error Handling

### 10.1 Error Categories

```typescript
// Validation errors (thrown synchronously)
class ValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ValidationError';
  }
}

// Network errors (async rejection)
class NetworkError extends Error {
  constructor(message: string, public readonly memberId?: MemberId) {
    super(message);
    this.name = 'NetworkError';
  }
}

// Session errors (async rejection)
class SessionError extends Error {
  constructor(
    message: string,
    public readonly reason: RejectionReason | SessionCloseReason
  ) {
    super(message);
    this.name = 'SessionError';
  }
}

// Timeout errors (async rejection)
class TimeoutError extends Error {
  constructor(message: string, public readonly requestId?: RequestId) {
    super(message);
    this.name = 'TimeoutError';
  }
}
```

### 10.2 Error Recovery

- **ValidationError**: Fix client code, no recovery
- **NetworkError**: Try next cluster member
- **SessionError (NotLeader)**: Redirect to leader
- **SessionError (SessionExpired)**: Terminal, reject all pending
- **TimeoutError**: Automatically retry request

---

## 11. Testing Strategy

### 11.1 Unit Tests

- Protocol codecs: Roundtrip encoding/decoding
- State machine: State transitions for each event
- Pending request tracking: Add, complete, timeout, retry
- RequestId counter: Monotonic increment, overflow handling

### 11.2 Integration Tests

- Connect to mock/real Raft cluster
- Submit commands and verify responses
- Test reconnection scenarios (network failure, leader change)
- Test session expiry handling
- Verify high throughput (1K+ req/sec)

### 11.3 Protocol Compatibility Tests

- Generate test messages with Scala server
- Verify TypeScript client can decode
- Generate messages with TypeScript client
- Verify Scala server can decode
- Use golden files for regression testing

---

## 12. Public API

```typescript
export class RaftClient {
  constructor(config: ClientConfig);
  
  // Lifecycle
  connect(): Promise<void>;
  disconnect(): Promise<void>;
  
  // Operations
  submitCommand(payload: Buffer): Promise<Buffer>;
  query(payload: Buffer): Promise<Buffer>;
  
  // Server-initiated requests
  serverRequests(): AsyncIterator<ServerRequest>;
  acknowledgeServerRequest(requestId: RequestId): Promise<void>;
  
  // Observability
  on(event: 'stateChange', handler: (e: StateChangeEvent) => void): this;
  on(event: 'connectionAttempt', handler: (e: ConnectionAttemptEvent) => void): this;
  on(event: 'connectionSuccess', handler: (e: ConnectionSuccessEvent) => void): this;
  on(event: 'connectionFailure', handler: (e: ConnectionFailureEvent) => void): this;
  on(event: 'sessionExpired', handler: (e: SessionExpiredEvent) => void): this;
  // ... other events
  
  off(event: string, handler: Function): this;
}

export interface ClientConfig {
  clusterMembers: Map<MemberId, string>;
  capabilities: Map<string, string>;
  connectionTimeout?: number;
  keepAliveInterval?: number;
  requestTimeout?: number;
}
```

---

## Summary

This design provides:

1. **Protocol Compatibility**: Exact binary encoding matching Scala implementation
2. **State Safety**: Type-safe state machine with discriminated unions
3. **High Performance**: Batching, buffer pooling, async event processing
4. **Resilience**: Automatic reconnection, leader discovery, request retry
5. **Observability**: Rich event system for monitoring without built-in logging
6. **Type Safety**: Strong TypeScript types throughout
7. **Testability**: Clear module boundaries, mockable dependencies

The architecture directly mirrors the proven Scala client while adapting to TypeScript/Node.js idioms and ecosystem.
