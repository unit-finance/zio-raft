# Component Analysis Report

This document provides a detailed code review and analysis of each component in the `typescript-client` and `kvstore-cli-ts` projects.

---

## Table of Contents

1. [typescript-client Components](#typescript-client-components)
   - [client.ts - RaftClient](#1-clientts---raftclient)
   - [config.ts - Configuration](#2-configts---configuration)
   - [types.ts - Branded Types](#3-typests---branded-types)
   - [errors.ts - Error Hierarchy](#4-errorsts---error-hierarchy)
   - [state/clientState.ts - State Machine](#5-stateclientstatets---state-machine)
   - [state/pendingRequests.ts - Request Tracking](#6-statependingrequeststs---request-tracking)
   - [state/pendingQueries.ts - Query Tracking](#7-statependingqueriessts---query-tracking)
   - [state/serverRequestTracker.ts - Deduplication](#8-stateserverrequesttrackersts---deduplication)
   - [protocol/messages.ts - Message Types](#9-protocolmessagests---message-types)
   - [protocol/codecs.ts - Binary Codecs](#10-protocolcodecsts---binary-codecs)
   - [protocol/constants.ts - Protocol Constants](#11-protocolconstantsts---protocol-constants)
   - [transport/transport.ts - Transport Interface](#12-transporttransportts---transport-interface)
   - [transport/zmqTransport.ts - ZeroMQ Implementation](#13-transportzmqtransportts---zeromq-implementation)
   - [testing/MockTransport.ts - Test Transport](#14-testingmocktransportts---test-transport)
   - [utils/asyncQueue.ts - Async Queue](#15-utilsasyncqueuets---async-queue)
   - [utils/streamMerger.ts - Stream Merger](#16-utilsstreammergersts---stream-merger)
   - [events/ - Event System](#17-events---event-system)
2. [kvstore-cli-ts Components](#kvstore-cli-ts-components)
   - [index.ts - CLI Entry Point](#18-indexts---cli-entry-point)
   - [kvClient.ts - KVStore Client](#19-kvclientts---kvstore-client)
   - [commands/ - Command Implementations](#20-commands---command-implementations)
   - [codecs.ts - KVStore Codecs](#21-codecsts---kvstore-codecs)
   - [validation.ts - Input Validation](#22-validationts---input-validation)
   - [formatting.ts - Output Formatting](#23-formattingts---output-formatting)
   - [errors.ts - CLI Errors](#24-errorsts---cli-errors)
   - [types.ts - CLI Types](#25-typests---cli-types)

---

# typescript-client Components

## 1. client.ts - RaftClient

**Location:** `typescript-client/src/client.ts`  
**Lines:** 569  
**Purpose:** Main public API for the library

### Overview

`RaftClient` is the primary entry point for applications. It extends Node.js `EventEmitter` to provide an event-driven, Promise-based API for interacting with a ZIO Raft cluster.

### Architecture Pattern

The client implements a **unified event stream architecture**:
1. User actions (connect, submit, disconnect) are queued to an `AsyncQueue`
2. Server messages, keep-alive ticks, and timeouts are merged into a single stream
3. A main event loop processes events through the state machine
4. State transitions produce messages to send and events to emit

### Key Components

| Component | Type | Purpose |
|-----------|------|---------|
| `config` | `ClientConfig` | Validated configuration |
| `transport` | `ClientTransport` | Network abstraction (injectable) |
| `stateManager` | `StateManager` | State machine orchestrator |
| `actionQueue` | `AsyncQueue<ClientAction>` | User action queue |
| `serverRequestQueue` | `AsyncQueue<ServerRequest>` | Server-initiated requests for handlers |
| `currentState` | `ClientState` | Current FSM state |

### Public API

```typescript
class RaftClient extends EventEmitter {
  constructor(configInput: ClientConfigInput, transport?: ClientTransport)
  async connect(): Promise<void>
  async submitCommand(payload: Buffer): Promise<Buffer>
  async submitQuery(payload: Buffer): Promise<Buffer>
  async disconnect(): Promise<void>
  onServerRequest(handler: (request: ServerRequest) => void): void
}
```

### Observations

**Strengths:**
- Clean separation of concerns - transport is injectable for testing
- Event-driven architecture with proper lifecycle management
- Proper error propagation through Promise rejections

**Areas for Improvement:**
1. **TODO on line 437:** Comment suggests uncertainty about transport disconnect timing
2. **Debug logging:** Heavy use of `debugLog` - consider log levels
3. **Error swallowing in `onServerRequest`:** User handler errors are swallowed (line 276) - may hide bugs

### Code Smells

```typescript
// Line 322-327: "as any" cast indicates type system mismatch
connectResolve: (action as any).resolve, 
connectReject: (action as any).reject,
```

This indicates the `ConnectAction` interface in `clientState.ts` doesn't include resolve/reject, but the actual action does. Type definitions are inconsistent.

---

## 2. config.ts - Configuration

**Location:** `typescript-client/src/config.ts`  
**Lines:** 95  
**Purpose:** Configuration validation and defaults

### Overview

Provides configuration types and validation for the Raft client.

### Key Functions

```typescript
function validateConfig(config: ClientConfig): void  // Throws on invalid
function createConfig(input: ClientConfigInput): ClientConfig  // Apply defaults
```

### Default Values

| Setting | Default | Purpose |
|---------|---------|---------|
| `connectionTimeout` | 5000ms | Connection establishment timeout |
| `keepAliveInterval` | 30000ms | Heartbeat interval |
| `requestTimeout` | 10000ms | Request completion timeout |

### Validation Rules

1. `clusterMembers` cannot be empty
2. `capabilities` cannot be empty
3. All timeout values must be positive
4. Addresses must start with `tcp://` or `ipc://`

### Observations

**Strengths:**
- Fail-fast validation
- Sensible defaults
- Clear error messages with specific member IDs

**No Issues Found**

---

## 3. types.ts - Branded Types

**Location:** `typescript-client/src/types.ts`  
**Lines:** 87  
**Purpose:** Type-safe identifiers using branded types

### Overview

Implements the TypeScript branded type pattern for compile-time type safety on identifiers that would otherwise be plain strings or numbers.

### Branded Types

| Type | Base | Purpose |
|------|------|---------|
| `SessionId` | `string` | Session identifier |
| `RequestId` | `bigint` | Command request ID (monotonic) |
| `MemberId` | `string` | Cluster member identifier |
| `Nonce` | `bigint` | Request/response correlation |
| `CorrelationId` | `string` | Query correlation (UUID) |

### Pattern Implementation

```typescript
export type SessionId = string & { readonly __brand: 'SessionId' };

export const SessionId = {
  fromString: (value: string): SessionId => {
    if (!value || value.length === 0) {
      throw new Error('SessionId cannot be empty');
    }
    return value as SessionId;
  },
  unwrap: (id: SessionId): string => id as string,
};
```

### Observations

**Strengths:**
- Prevents mixing up different ID types at compile time
- Factory functions provide validation on construction
- Matches Scala's Newtype pattern conceptually

**Questions:**
- TODOs on lines 21-22 and 49-50 question whether branded types add value for numeric IDs

**Recommendation:**
The unwrap ceremony is worth it for string-based IDs where confusion is likely (SessionId vs MemberId vs CorrelationId). For numeric IDs (RequestId, Nonce), the value is less clear since they're rarely mixed up.

---

## 4. errors.ts - Error Hierarchy

**Location:** `typescript-client/src/errors.ts`  
**Lines:** 87  
**Purpose:** Custom error types for the client

### Error Hierarchy

```
RaftClientError (base)
├── ValidationError     - Invalid input (sync throw)
├── TimeoutError        - Request/query timeout
├── ConnectionError     - Transport-level failure
├── SessionExpiredError - Terminal session failure
└── ProtocolError       - Wire protocol violation
```

### Observations

**Strengths:**
- Proper prototype chain maintenance for `instanceof` checks
- Error-specific context (endpoint, cause, requestId, etc.)
- Clear separation of error categories

**No Issues Found**

---

## 5. state/clientState.ts - State Machine

**Location:** `typescript-client/src/state/clientState.ts`  
**Lines:** 1493  
**Purpose:** Core state machine implementation

### Overview

This is the heart of the client - a comprehensive finite state machine that handles all session lifecycle and request management.

### States (Discriminated Union)

| State | Purpose |
|-------|---------|
| `Disconnected` | Initial state, not connected |
| `ConnectingNewSession` | Creating new session |
| `ConnectingExistingSession` | Resuming after disconnect |
| `Connected` | Active session |

### State Handlers

Each state has a dedicated handler class:

```typescript
class DisconnectedStateHandler { ... }
class ConnectingNewSessionStateHandler { ... }
class ConnectingExistingSessionStateHandler { ... }
class ConnectedStateHandler { ... }
```

### Event Types

The state machine processes four event types:
1. `ActionEvent` - User API calls (connect, submit, disconnect)
2. `ServerMessageEvent` - Messages from server
3. `KeepAliveTickEvent` - Timer for heartbeats
4. `TimeoutCheckEvent` - Timer for request timeouts

### State Transition Result

```typescript
interface StateTransitionResult {
  readonly newState: ClientState;
  readonly messagesToSend?: Array<ClientMessage>;
  readonly eventsToEmit?: Array<ClientEventData>;
}
```

### Key Behaviors

**Session Creation (ConnectingNewSession):**
- Sends `CreateSession` message
- Handles `SessionCreated` → transition to Connected
- Handles `SessionRejected` → redirect to leader or try next member
- Timeout → try next member

**Connected State:**
- Handles `SubmitCommand` → create `ClientRequest`, track pending
- Handles `SubmitQuery` → create `Query`, track pending
- Handles `ClientResponse` → complete pending request
- Handles `QueryResponse` → complete pending query
- Handles `ServerRequest` → deduplicate and emit to user
- Handles `SessionClosed` → reconnect or terminate

### Observations

**Strengths:**
- Comprehensive state coverage
- Immutable state transitions (returns new state)
- Clean separation of handler logic per state
- Proper nonce validation to prevent stale message processing

**Areas for Improvement:**

1. **File size (1493 lines):** Consider splitting into separate files per handler

2. **TODO on line 148:** Actions missing resolve/reject callbacks in interface

3. **Line 322-327:** Type casting with `as any` for callbacks - see comment about type mismatch

4. **Resend logic incomplete (line 504):** Comment says "Note: The actual ClientRequest and Query messages will be constructed by the RaftClient" but the client doesn't seem to handle this

5. **Debug logging in production code:** Heavy debug logging throughout

### Code Quality Issues

```typescript
// Line 1486: Exhaustive check using never
default:
  const _exhaustive: never = state;
  throw new Error(`Unknown state: ${(_exhaustive as ClientState).state}`);
```

Good pattern for exhaustive switch statements.

---

## 6. state/pendingRequests.ts - Request Tracking

**Location:** `typescript-client/src/state/pendingRequests.ts`  
**Lines:** 143  
**Purpose:** Track in-flight command requests

### Overview

Manages pending command (write) requests, storing callbacks to resolve/reject the original Promise.

### Interface

```typescript
interface PendingRequestData {
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
  readonly createdAt: Date;
  lastSentAt: Date; // Mutable for resend tracking
}
```

### Key Methods

| Method | Purpose |
|--------|---------|
| `add()` | Register new pending request |
| `complete()` | Resolve request with result |
| `fail()` | Reject request with error |
| `lowestPendingRequestIdOr()` | For protocol field |
| `resendAll()` | Mark all for resend (reconnect) |
| `resendExpired()` | Mark timed-out for resend |
| `failAll()` | Fail all on session termination |

### Observations

**Strengths:**
- Clean interface for request lifecycle
- Proper callback storage for Promise resolution
- Timeout tracking with `lastSentAt`

**Note:** `lastSentAt` is mutable while other fields are readonly - intentional for resend tracking without recreating objects.

---

## 7. state/pendingQueries.ts - Query Tracking

**Location:** `typescript-client/src/state/pendingQueries.ts`  
**Lines:** 127  
**Purpose:** Track in-flight read-only queries

### Overview

Nearly identical to `PendingRequests` but uses `CorrelationId` instead of `RequestId`.

### Key Difference

- Uses UUID-based `CorrelationId` for correlation (queries don't have monotonic IDs)
- No `lowestPendingRequestIdOr()` method (not needed for queries)

### Observations

**Code Duplication:**
This file is almost identical to `pendingRequests.ts`. Consider:
- Generic base class with `Map<K, PendingData<K>>`
- Or keep separate for explicit type safety (current approach)

Current approach is fine given the small file size and explicit types.

---

## 8. state/serverRequestTracker.ts - Deduplication

**Location:** `typescript-client/src/state/serverRequestTracker.ts`  
**Lines:** 57  
**Purpose:** Deduplicate server-initiated requests

### Overview

Tracks the last acknowledged server request ID to detect duplicates and out-of-order messages.

### Algorithm

```typescript
shouldProcess(requestId: RequestId): ServerRequestResult {
  if (incoming === current + 1n) return { type: 'Process' };     // Expected next
  if (incoming <= current)        return { type: 'OldRequest' }; // Duplicate
  else                            return { type: 'OutOfOrder' }; // Gap detected
}
```

### Immutable Update Pattern

```typescript
withLastAcknowledged(requestId: RequestId): ServerRequestTracker {
  return new ServerRequestTracker(requestId);
}
```

### Observations

**Strengths:**
- Simple and correct logic
- Immutable update pattern
- Clear result types

**Gap Handling:**
When `OutOfOrder` is returned, the request is dropped. This assumes the protocol guarantees in-order delivery or that gaps will be retransmitted.

---

## 9. protocol/messages.ts - Message Types

**Location:** `typescript-client/src/protocol/messages.ts`  
**Lines:** 220  
**Purpose:** Protocol message type definitions

### Overview

Defines all client-to-server and server-to-client message types as discriminated unions.

### Client Messages

| Type | Purpose |
|------|---------|
| `CreateSession` | Create new session |
| `ContinueSession` | Resume existing session |
| `KeepAlive` | Heartbeat |
| `ClientRequest` | Write command |
| `Query` | Read-only query |
| `ServerRequestAck` | Acknowledge server request |
| `CloseSession` | Terminate session |
| `ConnectionClosed` | Connection terminated (auto-generated) |

### Server Messages

| Type | Purpose |
|------|---------|
| `SessionCreated` | Session creation success |
| `SessionContinued` | Session resume success |
| `SessionRejected` | Session creation/resume rejected |
| `SessionClosed` | Server terminated session |
| `KeepAliveResponse` | Heartbeat acknowledgment |
| `ClientResponse` | Command result |
| `QueryResponse` | Query result |
| `ServerRequest` | Server-initiated request |
| `RequestError` | Request error |

### Observations

**Strengths:**
- Complete coverage of protocol
- Clean discriminated union with `type` discriminator
- Matches Scala protocol definitions

**No Issues Found**

---

## 10. protocol/codecs.ts - Binary Codecs

**Location:** `typescript-client/src/protocol/codecs.ts`  
**Lines:** 705  
**Purpose:** Binary encoding/decoding matching Scala scodec

### Overview

Implements binary serialization compatible with the Scala server's scodec-based protocol.

### Encoding Primitives

| Function | Format |
|----------|--------|
| `encodeString8` | uint8 length + UTF-8 |
| `encodeString` | uint16 length + UTF-8 |
| `encodePayload` | int32 length + bytes |
| `encodeMap` | uint16 count + key-value pairs |
| `encodeTimestamp` | int64 epoch millis |
| `encodeNonce` | 8 bytes bigint |
| `encodeRequestId` | 8 bytes bigint |

### Protocol Header

```
[5 bytes: "zraft" signature][1 byte: version]
```

### Bit-Level Decoding

The most complex part is `decodeOptionalMemberId` (lines 196-249) which handles scodec's bit-level encoding for optional fields:

```
Bit 0: presence flag
Bits 1-16: uint16 length (if present)
Remaining: UTF-8 string bytes
```

This requires bit manipulation across byte boundaries.

### Observations

**Strengths:**
- Complete implementation matching Scala scodec
- Thorough comments explaining bit-level encoding
- Proper error handling with descriptive messages

**Complexity:**
- Bit-level decoding is complex but necessary
- Well-documented with examples in comments

**Potential Issue (line 105-106):**
```typescript
throw new ProtocolError(`Invalid hasValue byte: ${hasValue} (expected 0x00 or 0xFF)`);
throw new ProtocolError(`Invalid hasValue byte: ${hasValue} (expected 0 or 1)`);
```
Duplicate unreachable throw statement (dead code).

---

## 11. protocol/constants.ts - Protocol Constants

**Location:** `typescript-client/src/protocol/constants.ts`  
**Lines:** 77  
**Purpose:** Protocol constants and discriminator enums

### Constants

```typescript
PROTOCOL_SIGNATURE = Buffer.from([0x7a, 0x72, 0x61, 0x66, 0x74]); // "zraft"
PROTOCOL_VERSION = 1;
```

### Enums

- `ClientMessageType` - Message type discriminators (1-8)
- `ServerMessageType` - Message type discriminators (1-9)
- `RejectionReasonCode` - Session rejection reasons
- `SessionCloseReasonCode` - Session close reasons
- `CloseReasonCode` - Client close reasons
- `RequestErrorReasonCode` - Request error reasons

### Observations

**No Issues Found** - Clean, well-organized constants.

---

## 12. transport/transport.ts - Transport Interface

**Location:** `typescript-client/src/transport/transport.ts`  
**Lines:** 31  
**Purpose:** Transport abstraction interface

### Interface

```typescript
interface ClientTransport {
  connect(address: string): Promise<void>;
  disconnect(): Promise<void>;
  sendMessage(message: ClientMessage): Promise<void>;
  incomingMessages: AsyncIterable<ServerMessage>;
}
```

### Observations

**Strengths:**
- Clean interface
- AsyncIterable for message stream (modern pattern)
- Allows for easy testing via MockTransport

**No Issues Found**

---

## 13. transport/zmqTransport.ts - ZeroMQ Implementation

**Location:** `typescript-client/src/transport/zmqTransport.ts`  
**Lines:** 185  
**Purpose:** Production ZeroMQ transport

### Overview

Uses ZeroMQ's draft CLIENT socket to communicate with the server's SERVER socket.

### Socket Configuration

```typescript
this.socket = new ZmqClient({
  linger: 0,
  heartbeatInterval: 100,
  heartbeatTimeToLive: 1000,
  heartbeatTimeout: 1000,
});
```

### Key Behaviors

1. **Connection:** Simple `socket.connect(address)` - ZMQ handles queueing
2. **Message sending:** Encodes via codecs, sends through socket
3. **Message receiving:** Async generator over socket receive iterator
4. **Disconnection:** `socket.disconnect(address)` triggers server ConnectionClosed

### Observations

**Strengths:**
- Proper ZMQ socket lifecycle
- Event listeners for debugging
- Clean async iterator for receiving

**Debug Logging:**
Heavy debug logging throughout - consider log levels or conditional logging.

**Potential Issue:**
```typescript
// Line 64-65
this.socket.connect(address);
this.currentAddress = address;
```

`connect()` is synchronous in ZMQ (queues for later) - but if it throws, `currentAddress` won't be set. Current error handling is correct.

---

## 14. testing/MockTransport.ts - Test Transport

**Location:** `typescript-client/src/testing/MockTransport.ts`  
**Lines:** 163  
**Purpose:** Mock transport for testing

### Overview

Provides controlled message injection and sent message tracking for tests.

### Key Features

```typescript
class MockTransport implements ClientTransport {
  autoRespondToCreateSession = true;  // Auto-respond with SessionCreated
  autoResponseDelay = 10;             // Response delay in ms
  
  injectMessage(message: ServerMessage): void;
  getLastSentMessage(): ClientMessage | undefined;
  getSentMessagesOfType<T>(type: T): Extract<ClientMessage, { type: T }>[];
  waitForMessageType(type: T, timeoutMs: number): Promise<...>;
}
```

### Observations

**Strengths:**
- Good test helper methods
- Auto-response feature for common scenarios
- Type-safe message filtering

**TODO on line 29:**
> "I don't like MockTransport, implement E2E tests and remove it"

This indicates a preference for integration tests over mock-based unit tests. Valid concern - MockTransport tests may not catch real protocol issues.

---

## 15. utils/asyncQueue.ts - Async Queue

**Location:** `typescript-client/src/utils/asyncQueue.ts`  
**Lines:** 153  
**Purpose:** Promise-based async queue with iterator

### Overview

A queue that supports both explicit `take()`/`put()` and async iteration via `for-await-of`.

### Key Features

- Non-blocking `offer()`/`put()`
- Blocking `take()` - waits if empty
- Async iteration support
- Proper close semantics - pending waiters receive done signal

### Implementation Pattern

Uses an array of pending waiters that get resolved when items arrive:

```typescript
private waiting: Array<(item: T | IteratorResult<T>) => void> = [];
```

### Observations

**Strengths:**
- Correct async queue semantics
- Proper cleanup on close
- Both pull and iterator consumption

**Edge Case:**
Line 62-68 in `take()`:
```typescript
this.waiting.push((item) => {
  if (typeof item === 'object' && item !== null && 'done' in item) {
    throw new Error('Queue is closed and empty');
  }
  resolve(item as T);
});
```

Throwing inside a callback stored in an array is problematic - the error won't propagate to the caller. This could cause unhandled rejections.

---

## 16. utils/streamMerger.ts - Stream Merger

**Location:** `typescript-client/src/utils/streamMerger.ts`  
**Lines:** 44  
**Purpose:** Merge multiple async iterables

### Overview

Implements race semantics - yields items from any source as they arrive.

### Algorithm

```typescript
async function* mergeStreams<T>(...iterables: AsyncIterable<T>[]): AsyncIterable<T> {
  // Create iterators
  // Initialize pending promises (one per iterator)
  // Race all pending, yield winner, queue next from that iterator
  // Remove exhausted iterators
}
```

### Observations

**Strengths:**
- Correct race semantics
- Clean async generator implementation
- Handles iterator exhaustion properly

**No Issues Found**

---

## 17. events/ - Event System

### eventNames.ts

**Location:** `typescript-client/src/events/eventNames.ts`  
**Lines:** 45  
**Purpose:** Event name constants

Provides type-safe event names:

```typescript
const ClientEvents = {
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  RECONNECTING: 'reconnecting',
  SESSION_EXPIRED: 'sessionExpired',
  SERVER_REQUEST_RECEIVED: 'serverRequestReceived',
  ERROR: 'error',
} as const;
```

### eventTypes.ts

**Location:** `typescript-client/src/events/eventTypes.ts`  
**Lines:** 228  
**Purpose:** Event type definitions

Defines discriminated unions for all event types and provides an `EventFactory` for creating timestamped events.

### Observations

**Strengths:**
- Type-safe event handling
- Factory pattern for consistent timestamps
- Clear separation of public and internal events

**No Issues Found**

---

# kvstore-cli-ts Components

## 18. index.ts - CLI Entry Point

**Location:** `kvstore-cli-ts/src/index.ts`  
**Lines:** 38  
**Purpose:** CLI program entry point

### Overview

Uses Commander.js to register commands and parse arguments.

### Commands Registered

1. `set <key> <value>` - Store a key-value pair
2. `get <key>` - Retrieve a value
3. `watch <key>` - Monitor updates

### Observations

**Strengths:**
- Clean command registration
- Shows help if no command provided

**No Issues Found**

---

## 19. kvClient.ts - KVStore Client

**Location:** `kvstore-cli-ts/src/kvClient.ts`  
**Lines:** 210  
**Purpose:** Typed wrapper around RaftClient for KVStore operations

### Overview

Provides a domain-specific API on top of the generic RaftClient.

### API

```typescript
class KVClient {
  constructor(config: KVClientConfig)
  async connect(): Promise<void>
  async disconnect(): Promise<void>
  async set(key: string, value: string): Promise<void>
  async get(key: string): Promise<string | null>
  async watch(key: string): Promise<void>
  async *notifications(): AsyncIterableIterator<WatchNotification>
}
```

### Key Behaviors

- Encodes domain requests via codecs
- Decodes results from binary
- Wraps errors with operation-specific context
- Provides async iterator for watch notifications

### Observations

**Strengths:**
- Clean domain-specific API
- Proper error wrapping
- Graceful disconnect (logs but doesn't throw)

**Complexity in `notifications()`:**
Lines 155-208 implement a manual async queue for notifications. This works but is somewhat complex. Alternative: could use a library or the existing `AsyncQueue`.

---

## 20. commands/ - Command Implementations

### set.ts, get.ts, watch.ts

Each command follows the same pattern:

1. Validate inputs
2. Parse endpoints
3. Create KVClient
4. Connect
5. Execute operation
6. Display result
7. Disconnect and exit

### Observations

**Strengths:**
- Consistent structure across commands
- Proper cleanup in error paths
- Signal handling in watch command

**Code Duplication:**
All three commands have nearly identical boilerplate:
- Client creation
- Connect/disconnect
- Error handling

Consider extracting common pattern:
```typescript
async function withKVClient(
  options: { endpoints: string },
  fn: (client: KVClient) => Promise<void>
): Promise<void> {
  // Create, connect, run fn, disconnect, handle errors
}
```

---

## 21. codecs.ts - KVStore Codecs

**Location:** `kvstore-cli-ts/src/codecs.ts`  
**Lines:** 143  
**Purpose:** KVStore-specific binary encoding

### Format

Uses simple discriminator byte + length-prefixed UTF-8:

| Request | Format |
|---------|--------|
| Set | `0x53 'S' + key + value` |
| Get | `0x47 'G' + key` |
| Watch | `0x57 'W' + key` |
| Notification | `0x4E 'N' + key + value` |

### Get Result Format

```
0x00 = None
0xFF + value = Some(value)
```

### Observations

**Dead Code (lines 105-106):**
```typescript
throw new ProtocolError(`Invalid hasValue byte: ${hasValue} (expected 0x00 or 0xFF)`);
throw new ProtocolError(`Invalid hasValue byte: ${hasValue} (expected 0 or 1)`);
```
Second throw is unreachable.

**TODO on line 139:**
```typescript
sequenceNumber: 0n, // TODO: Extract from metadata if available
```
Sequence number is hardcoded to 0.

---

## 22. validation.ts - Input Validation

**Location:** `kvstore-cli-ts/src/validation.ts`  
**Lines:** 131  
**Purpose:** CLI input validation

### Validation Rules

| Field | Rules |
|-------|-------|
| Key | Non-empty, max 256 bytes UTF-8, valid UTF-8 |
| Value | Non-empty, max 1MB UTF-8, valid UTF-8 |
| Endpoint | Must be `tcp://host:port`, port 1-65535 |

### Endpoint Parsing

Format: `memberId1=tcp://host1:port1,memberId2=tcp://host2:port2`

### Observations

**Strengths:**
- UTF-8 round-trip validation
- Reasonable size limits
- Clear error messages

**No Issues Found**

---

## 23. formatting.ts - Output Formatting

**Location:** `kvstore-cli-ts/src/formatting.ts`  
**Lines:** 97  
**Purpose:** Output and error formatting

### Functions

- `formatError(error)` - User-friendly error messages
- `formatNotification(notification)` - Watch output format
- `getExitCode(error)` - Unix-style exit codes

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Validation error |
| 2 | Network error (connection/timeout) |
| 3 | Operational error |
| 130 | SIGINT |

### Observations

**Strengths:**
- Consistent error formatting
- Proper exit code conventions
- Good comments explaining exit code rationale

**No Issues Found**

---

## 24. errors.ts - CLI Errors

**Location:** `kvstore-cli-ts/src/errors.ts`  
**Lines:** 53  
**Purpose:** CLI-specific error classes

### Error Classes

| Class | Purpose |
|-------|---------|
| `ValidationError` | Client-side validation failures |
| `OperationError` | Runtime errors during execution |
| `ProtocolError` | Binary protocol errors |

### Observations

**Strengths:**
- Rich context fields (field, actual, expected, operation, reason)
- Proper prototype chain maintenance

**No Issues Found**

---

## 25. types.ts - CLI Types

**Location:** `kvstore-cli-ts/src/types.ts`  
**Lines:** 108  
**Purpose:** Domain type definitions

### Types Defined

- Command types (`SetCommand`, `GetCommand`, `WatchCommand`)
- Configuration types (`EndpointConfig`)
- Result types (`SetResult`, `GetResult`, `WatchResult`)
- Protocol types (`WatchNotification`, `KVClientRequest`, `KVQuery`, `KVServerRequest`)

### Observations

**Unused Types:**
Some types (like `Command`, `CommandResult`) appear to be defined but not used in the codebase. The actual command handling uses the Commander.js action callbacks directly.

---

# Summary

## Overall Architecture Quality

**Strengths:**
1. Clean separation of concerns across layers
2. Type-safe design with branded types and discriminated unions
3. Testable architecture with injectable transport
4. Comprehensive state machine for session management
5. Proper error handling with custom error types

## Issues Found

### Critical
None

### Medium
1. **Type inconsistency:** `ConnectAction` interface missing resolve/reject (causes `as any` casts)
2. **Dead code:** Duplicate throw statements in codecs
3. **Incomplete resend logic:** Comments indicate resend should happen but implementation is unclear

### Low
1. Heavy debug logging in production code
2. Code duplication in command implementations
3. Large file size for `clientState.ts` (1493 lines)
4. Some unused type definitions in kvstore-cli-ts

## Recommendations

1. **Fix type definitions:** Add resolve/reject to `ConnectAction` interface
2. **Extract common command pattern:** Reduce duplication in CLI commands
3. **Split clientState.ts:** Separate file per state handler
4. **Add log levels:** Replace debug logging with configurable levels
5. **Remove dead code:** Clean up duplicate throw statements
6. **Clarify resend logic:** Document or implement pending request resend on reconnect
