# Data Model: TypeScript Client Library

**Feature**: TypeScript Client Library  
**Date**: 2025-12-29  
**Status**: Complete

## Overview

This document defines the data model for the TypeScript Raft client library. All types are strongly typed using TypeScript interfaces and discriminated unions to ensure type safety at compile time.

---

## Core Entities

### 1. Client

**Description**: The main interface that applications use to interact with the Raft cluster.

**Properties**:
```typescript
class RaftClient {
  // Private internal state
  private config: ClientConfig;
  private transport: ClientTransport;
  private state: ClientState;
  private actionQueue: AsyncQueue<ClientAction>;
  private serverRequestQueue: AsyncQueue<ServerRequest>;
  private eventEmitter: TypedEventEmitter;
  private eventLoop: Promise<void> | null;
  
  // Public API
  constructor(config: ClientConfig);
  async connect(): Promise<void>;
  async submitCommand(payload: Buffer): Promise<Buffer>;
  async submitQuery(payload: Buffer): Promise<Buffer>;
  async disconnect(): Promise<void>;
  
  // Event subscription
  on(event: 'connected', handler: (evt: ConnectedEvent) => void): this;
  on(event: 'disconnected', handler: (evt: DisconnectedEvent) => void): this;
  on(event: 'reconnecting', handler: (evt: ReconnectingEvent) => void): this;
  on(event: 'sessionExpired', handler: (evt: SessionExpiredEvent) => void): this;
  
  // Server-initiated request stream
  onServerRequest(handler: (request: ServerRequest) => void): void;
}
```

**Lifecycle**:
1. **Constructed**: Config validated, no connection initiated
2. **Connecting**: `connect()` called, establishing session
3. **Connected**: Active session, processing requests
4. **Reconnecting**: Temporary disconnection, automatic recovery
5. **Terminated**: Session expired or explicit disconnect

**Validation Rules**:
- Constructor validates config completeness and correctness
- Throws `ValidationError` for invalid configuration
- Only one `connect()` call allowed (subsequent calls ignored)

**State Management**:
- Internal state machine tracks connection lifecycle
- State transitions driven by unified event stream
- All state immutable (functional updates)

---

### 2. Session

**Description**: A durable server-side session that persists across reconnections.

**Properties**:
```typescript
interface Session {
  readonly sessionId: SessionId;
  readonly capabilities: Map<string, string>;
  readonly createdAt: Date;
  readonly serverRequestTracker: ServerRequestTracker;
}
```

**Type Definitions**:
```typescript
type SessionId = string; // UUID format
```

**Lifecycle**:
1. **Created**: Server generates SessionId, returns SessionCreated
2. **Active**: Client maintains session via keep-alives
3. **Reconnecting**: Client resumes with ContinueSession
4. **Expired**: Server terminates due to missed keep-alives (terminal)

**Validation Rules**:
- SessionId must be non-empty string (UUID format)
- Capabilities must match initial CreateSession request
- CreatedAt timestamp preserved across reconnections

**Server-Side Behavior**:
- Session persists on server across client disconnections
- Session expires if keep-alives not received within server-configured window
- Session identified by ZMQ routing identity (implicit in transport)

---

### 3. Command

**Description**: A write operation submitted by the client.

**Properties**:
```typescript
interface Command {
  readonly requestId: RequestId;
  readonly lowestPendingRequestId: RequestId;
  readonly payload: Buffer;
  readonly createdAt: Date;
}

interface PendingCommand {
  readonly requestId: RequestId;
  readonly payload: Buffer;
  readonly promise: Promise<Buffer>; // Resolves with response
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
  readonly createdAt: Date;
}
```

**Type Definitions**:
```typescript
type RequestId = bigint; // Unsigned 64-bit integer
```

**Lifecycle**:
1. **Submitted**: `submitCommand(payload)` called
2. **Queued**: Added to pending requests map
3. **Sent**: Encoded and sent via transport (when connected)
4. **Awaiting Response**: Promise pending
5. **Completed**: ClientResponse received, promise resolved
6. **Timed Out**: Timeout expired, promise rejected with TimeoutError
7. **Failed**: Error occurred, promise rejected

**Validation Rules**:
- Payload must be non-null Buffer
- RequestId must be unique and monotonically increasing
- `lowestPendingRequestId` indicates cache eviction boundary

**Request ID Generation**:
- Start at 1 (zero reserved as sentinel)
- Increment sequentially for each command
- Thread-safe counter (atomic increment)

**Timeout Behavior**:
- Each command has individual timeout (from config)
- Timeout starts when request added to pending map
- On timeout: promise rejected, request removed from pending map
- **No automatic retry** - application must manually retry

---

### 4. Query

**Description**: A read-only operation submitted by the client.

**Properties**:
```typescript
interface Query {
  readonly correlationId: CorrelationId;
  readonly payload: Buffer;
  readonly createdAt: Date;
}

interface PendingQuery {
  readonly correlationId: CorrelationId;
  readonly payload: Buffer;
  readonly promise: Promise<Buffer>; // Resolves with response
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
  readonly createdAt: Date;
}
```

**Type Definitions**:
```typescript
type CorrelationId = string; // UUID format
```

**Lifecycle**:
1. **Submitted**: `submitQuery(payload)` called
2. **Queued**: Added to pending queries map
3. **Sent**: Encoded and sent via transport (when connected)
4. **Awaiting Response**: Promise pending
5. **Completed**: QueryResponse received, promise resolved
6. **Timed Out**: Timeout expired, promise rejected with TimeoutError
7. **Failed**: Error occurred, promise rejected

**Validation Rules**:
- Payload must be non-null Buffer
- CorrelationId must be unique UUID v4
- Queries are idempotent (safe to retry)

**Correlation ID Generation**:
- Generate new UUID v4 for each query
- Use `crypto.randomUUID()` (Node.js 18+ built-in)

**Difference from Command**:
- Queries use correlation ID (UUID) instead of request ID (sequential int)
- Queries don't have `lowestPendingRequestId` field
- Queries are read-only (no state mutation on server)

---

### 5. KeepAlive

**Description**: A periodic heartbeat message to maintain session liveness.

**Properties**:
```typescript
interface KeepAlive {
  readonly timestamp: Date; // ISO 8601 instant
}

interface KeepAliveResponse {
  readonly clientTimestamp: Date; // Original client timestamp
  readonly serverTimestamp: Date; // Server processing timestamp
}
```

**Lifecycle**:
1. **Timer Tick**: Keep-alive interval elapsed (default 30s)
2. **Sent**: KeepAlive(current timestamp) sent to server
3. **Response**: Server responds with KeepAliveResponse
4. **RTT Calculation**: Optional (clientTimestamp round-trip time)

**Validation Rules**:
- Timestamp must be valid ISO 8601 instant
- Keep-alive interval must be > 0

**Timing Considerations**:
- Interval configurable (default 30s)
- Server session timeout typically 2-3x keep-alive interval
- Missing keep-alives lead to session expiry

**Purpose**:
- Prevents server-side session expiry
- Provides RTT measurement capability
- Validates connection liveness

---

### 6. ServerRequest

**Description**: A server-initiated request sent to the client.

**Properties**:
```typescript
interface ServerRequest {
  readonly requestId: RequestId;
  readonly payload: Buffer;
}

interface ServerRequestAck {
  readonly requestId: RequestId;
}
```

**Lifecycle**:
1. **Received**: ServerRequest message from server
2. **Deduplicated**: Check against ServerRequestTracker
3. **Delivered**: Handler invoked with payload (if new request)
4. **Acknowledged**: ServerRequestAck sent automatically

**Validation Rules**:
- RequestId must be consecutive (no gaps)
- Payload must be non-null Buffer

**Deduplication Logic**:
```typescript
class ServerRequestTracker {
  private lastAcknowledgedRequestId: bigint = 0n;
  
  check(requestId: bigint): 'Process' | 'OldRequest' | 'OutOfOrder' {
    if (requestId === this.lastAcknowledgedRequestId + 1n) {
      return 'Process'; // Expected next request
    } else if (requestId <= this.lastAcknowledgedRequestId) {
      return 'OldRequest'; // Already processed
    } else {
      return 'OutOfOrder'; // Gap in sequence
    }
  }
  
  acknowledge(requestId: bigint): void {
    this.lastAcknowledgedRequestId = requestId;
  }
}
```

**Handler Registration**:
- Application registers handler via `onServerRequest(handler)`
- Handler receives ServerRequest object
- Acknowledgment sent automatically (no manual ack required)

---

### 7. Capabilities

**Description**: A set of feature flags or version indicators provided by the application.

**Properties**:
```typescript
type Capabilities = Record<string, string>;
```

**Example**:
```typescript
const capabilities = {
  version: '1.0.0',
  features: 'compression,encryption',
  clientType: 'typescript'
};
```

**Lifecycle**:
1. **Provided**: Application passes capabilities to client constructor
2. **Sent**: Included in CreateSession message
3. **Negotiated**: Server validates and responds with SessionCreated
4. **Stored**: Preserved in session for entire session lifetime

**Validation Rules**:
- Must be non-empty object
- Keys and values must be non-empty strings
- No specific capability names enforced (application-defined)

**Purpose**:
- Protocol version negotiation
- Feature detection and capability matching
- Client identification and metadata

**Note**: The TypeScript client does not define specific capability values; they are passed through from the application.

---

### 8. ConnectionEvent

**Description**: An event emitted by the client for connection state changes.

**Event Types**:
```typescript
interface ConnectedEvent {
  readonly type: 'connected';
  readonly sessionId: string;
  readonly endpoint: string;
  readonly timestamp: Date;
}

interface DisconnectedEvent {
  readonly type: 'disconnected';
  readonly reason: 'network' | 'server-closed' | 'client-shutdown';
  readonly timestamp: Date;
}

interface ReconnectingEvent {
  readonly type: 'reconnecting';
  readonly attempt: number;
  readonly endpoint: string;
  readonly timestamp: Date;
}

interface SessionExpiredEvent {
  readonly type: 'sessionExpired';
  readonly sessionId: string;
  readonly timestamp: Date;
}

type ConnectionEvent = 
  | ConnectedEvent
  | DisconnectedEvent
  | ReconnectingEvent
  | SessionExpiredEvent;
```

**Emission Rules**:
- Events emitted asynchronously via EventEmitter
- Events delivered in order of occurrence
- Handlers registered before `connect()` receive all events

**Event Semantics**:
- **connected**: Session successfully established (SessionCreated or SessionContinued)
- **disconnected**: Connection lost (ZMQ disconnect, server close, or client disconnect)
- **reconnecting**: Attempting to reconnect after disconnection (includes attempt count)
- **sessionExpired**: Session terminated by server (terminal event, no reconnection)

---

## Client State Machine

### State Types

```typescript
type ClientState =
  | DisconnectedState
  | ConnectingNewSessionState
  | ConnectingExistingSessionState
  | ConnectedState;
```

### State Definitions

#### 1. DisconnectedState

**Description**: Initial state, not connected to cluster.

```typescript
interface DisconnectedState {
  readonly state: 'Disconnected';
  readonly config: ClientConfig;
}
```

**Valid Transitions**:
- `connect()` → `ConnectingNewSessionState`

---

#### 2. ConnectingNewSessionState

**Description**: Creating a new session (first connection).

```typescript
interface ConnectingNewSessionState {
  readonly state: 'ConnectingNewSession';
  readonly config: ClientConfig;
  readonly capabilities: Map<string, string>;
  readonly nonce: Nonce;
  readonly currentMemberId: MemberId;
  readonly createdAt: Date;
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
}
```

**Valid Transitions**:
- `SessionCreated` → `ConnectedState`
- `SessionRejected` → `ConnectingNewSessionState` (retry next member)
- `timeout` → `ConnectingNewSessionState` (retry next member)

---

#### 3. ConnectingExistingSessionState

**Description**: Resuming an existing session after reconnection.

```typescript
interface ConnectingExistingSessionState {
  readonly state: 'ConnectingExistingSession';
  readonly config: ClientConfig;
  readonly sessionId: SessionId;
  readonly capabilities: Map<string, string>;
  readonly nonce: Nonce;
  readonly currentMemberId: MemberId;
  readonly createdAt: Date;
  readonly serverRequestTracker: ServerRequestTracker;
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
}
```

**Valid Transitions**:
- `SessionContinued` → `ConnectedState`
- `SessionRejected(SessionNotFound)` → `ConnectingNewSessionState`
- `SessionRejected(SessionExpired)` → Terminal (emit sessionExpired, terminate)
- `timeout` → `ConnectingExistingSessionState` (retry next member)

---

#### 4. ConnectedState

**Description**: Active session with the cluster.

```typescript
interface ConnectedState {
  readonly state: 'Connected';
  readonly config: ClientConfig;
  readonly sessionId: SessionId;
  readonly capabilities: Map<string, string>;
  readonly createdAt: Date;
  readonly serverRequestTracker: ServerRequestTracker;
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
  readonly currentMemberId: MemberId;
}
```

**Valid Transitions**:
- `disconnect()` → `DisconnectedState`
- `network disconnect` → `ConnectingExistingSessionState` (automatic reconnection)
- `SessionClosed(SessionExpired)` → Terminal (emit sessionExpired, terminate)
- `SessionRejected(NotLeader)` → `ConnectingExistingSessionState` (reconnect to leader)

---

## Configuration

### ClientConfig

**Description**: Client configuration provided to constructor.

```typescript
interface ClientConfig {
  // Required
  readonly endpoints: string[]; // ['tcp://host:port', ...]
  readonly capabilities: Record<string, string>;
  
  // Optional (with defaults)
  readonly connectionTimeout?: number; // Default: 5000ms
  readonly requestTimeout?: number; // Default: 30000ms
  readonly keepAliveInterval?: number; // Default: 30000ms
  readonly queuedRequestTimeout?: number; // Default: 60000ms
  readonly maxReconnectAttempts?: number; // Default: Infinity
  readonly reconnectInterval?: number; // Default: 1000ms
}
```

**Validation Rules**:
- `endpoints`: Must be non-empty array of valid ZMQ endpoint strings
- `capabilities`: Must be non-empty object
- All timeout values must be positive integers
- Validation occurs in constructor, throws `ValidationError` if invalid

**Defaults Rationale**:
- Connection timeout: 5s (fail fast for misconfig)
- Request timeout: 30s (balance responsiveness vs slow writes)
- Keep-alive interval: 30s (typical session timeout / 2)
- Queued request timeout: 60s (resilience during reconnection)
- Max reconnect attempts: Infinity (keep trying)
- Reconnect interval: 1s (fast recovery)

---

## Error Types

### Error Hierarchy

```typescript
class RaftClientError extends Error {
  readonly name = 'RaftClientError';
}

class ValidationError extends RaftClientError {
  readonly name = 'ValidationError';
  constructor(message: string) {
    super(message);
  }
}

class TimeoutError extends RaftClientError {
  readonly name = 'TimeoutError';
  readonly requestId?: RequestId;
  readonly correlationId?: CorrelationId;
  
  constructor(message: string, id?: RequestId | CorrelationId) {
    super(message);
    if (typeof id === 'bigint') {
      this.requestId = id;
    } else if (typeof id === 'string') {
      this.correlationId = id;
    }
  }
}

class ConnectionError extends RaftClientError {
  readonly name = 'ConnectionError';
  readonly endpoint?: string;
  readonly cause?: Error;
  
  constructor(message: string, endpoint?: string, cause?: Error) {
    super(message);
    this.endpoint = endpoint;
    this.cause = cause;
  }
}

class SessionExpiredError extends RaftClientError {
  readonly name = 'SessionExpiredError';
  readonly sessionId: string;
  
  constructor(sessionId: string) {
    super(`Session expired: ${sessionId}`);
    this.sessionId = sessionId;
  }
}

class ProtocolError extends RaftClientError {
  readonly name = 'ProtocolError';
  
  constructor(message: string) {
    super(`Protocol error: ${message}`);
  }
}
```

**Usage**:
- `ValidationError`: Thrown synchronously for bad input
- `TimeoutError`: Promise rejection for request timeout
- `ConnectionError`: Promise rejection for connection failures
- `SessionExpiredError`: Promise rejection when session expires
- `ProtocolError`: Thrown for wire protocol violations

---

## Pending Request Management

### PendingRequests

**Description**: Tracks pending commands awaiting responses.

```typescript
class PendingRequests {
  private requests: Map<RequestId, PendingCommand>;
  
  add(
    requestId: RequestId,
    payload: Uint8Array,
    resolve: (result: Uint8Array) => void,
    reject: (error: Error) => void,
    createdAt: Date
  ): void;
  
  complete(requestId: RequestId, result: Uint8Array): void;
  timeout(requestId: RequestId): void;
  remove(requestId: RequestId): void;
  
  lowestPendingRequestId(): RequestId | undefined;
  checkTimeouts(now: Date, timeout: number): RequestId[];
  
  get size(): number;
  clear(): void;
}
```

**Operations**:
- **add**: Add new pending request
- **complete**: Resolve promise with result, remove from map
- **timeout**: Reject promise with TimeoutError, remove from map
- **remove**: Remove without resolving/rejecting
- **lowestPendingRequestId**: Returns lowest pending ID (for cache eviction)
- **checkTimeouts**: Returns list of timed-out request IDs

---

### PendingQueries

**Description**: Tracks pending queries awaiting responses.

```typescript
class PendingQueries {
  private queries: Map<CorrelationId, PendingQuery>;
  
  add(
    correlationId: CorrelationId,
    payload: Uint8Array,
    resolve: (result: Uint8Array) => void,
    reject: (error: Error) => void,
    createdAt: Date
  ): void;
  
  complete(correlationId: CorrelationId, result: Uint8Array): void;
  timeout(correlationId: CorrelationId): void;
  remove(correlationId: CorrelationId): void;
  
  checkTimeouts(now: Date, timeout: number): CorrelationId[];
  
  get size(): number;
  clear(): void;
}
```

**Operations**: Similar to PendingRequests but keyed by CorrelationId (UUID string).

---

## Type Summary

| Type | Description | Format |
|------|-------------|--------|
| `SessionId` | Unique session identifier | UUID string |
| `RequestId` | Sequential command identifier | bigint (uint64) |
| `CorrelationId` | Query correlation identifier | UUID string |
| `Nonce` | Session creation nonce | bigint (uint64) |
| `MemberId` | Cluster member identifier | string |
| `Capabilities` | Client capability map | Record<string, string> |
| `Payload` | Binary command/query data | Buffer |
| `Timestamp` | ISO 8601 instant | Date |

---

## Relationships

```
RaftClient
  ├── config: ClientConfig
  ├── state: ClientState
  │     ├── Disconnected
  │     ├── ConnectingNewSession
  │     │     ├── pendingRequests: PendingRequests
  │     │     └── pendingQueries: PendingQueries
  │     ├── ConnectingExistingSession
  │     │     ├── session: SessionId
  │     │     ├── serverRequestTracker: ServerRequestTracker
  │     │     ├── pendingRequests: PendingRequests
  │     │     └── pendingQueries: PendingQueries
  │     └── Connected
  │           ├── session: SessionId
  │           ├── serverRequestTracker: ServerRequestTracker
  │           ├── pendingRequests: PendingRequests
  │           └── pendingQueries: PendingQueries
  ├── transport: ClientTransport
  │     └── zmqSocket: ZMQ DEALER
  ├── actionQueue: AsyncQueue<ClientAction>
  ├── serverRequestQueue: AsyncQueue<ServerRequest>
  └── eventEmitter: TypedEventEmitter
```

---

## Next Steps

Data model complete. Proceed to:
1. Design.md - High-level architecture and solution overview
2. Tests.md - Test scenarios derived from acceptance criteria
3. Quickstart.md - End-to-end validation guide
