# Test Scenarios: TypeScript Client Library

**Feature**: TypeScript Client Library  
**Date**: 2025-12-29  
**Status**: Complete

## Overview

This document catalogs all test scenarios for the TypeScript Raft client library. Tests are derived from acceptance scenarios, functional requirements, edge cases, and design considerations. We follow a **No TDD** approach - implementation comes before test execution.

---

## Test Categories

1. **Unit Tests**: Individual component testing with mocks
2. **Integration Tests**: Full client lifecycle with real transport
3. **Compatibility Tests**: Wire protocol validation against Scala server
4. **Performance Tests**: Throughput and latency validation

---

## Unit Tests

### Protocol Layer Tests

#### TC-PROTO-001: Encode CreateSession Message
**Objective**: Verify CreateSession message encodes correctly

**Setup**:
```typescript
const capabilities = { version: '1.0.0', client: 'typescript' };
const nonce = 12345n;
const message: CreateSession = { type: 'CreateSession', capabilities, nonce };
```

**Test Steps**:
1. Call `encodeClientMessage(message)`
2. Verify buffer starts with protocol signature: `0x7a 0x72 0x61 0x66 0x74`
3. Verify protocol version byte: `0x01`
4. Verify discriminator byte: `0x01` (CreateSession)
5. Verify capabilities encoding (map size + key/value pairs)
6. Verify nonce encoding (8 bytes little-endian)

**Expected Result**: Byte-for-byte match with Scala codec output for equivalent message

---

#### TC-PROTO-002: Decode SessionCreated Message
**Objective**: Verify SessionCreated message decodes correctly

**Setup**:
```typescript
// Create buffer with:
// - Protocol header (signature + version)
// - Discriminator 0x01 (SessionCreated)
// - SessionId (16 bytes UUID)
// - Nonce (8 bytes)
```

**Test Steps**:
1. Call `decodeServerMessage(buffer)`
2. Verify returns SessionCreated object
3. Verify sessionId is valid UUID string
4. Verify nonce matches expected value

**Expected Result**: Correct SessionCreated object with all fields populated

---

#### TC-PROTO-003: Encode ClientRequest Message
**Objective**: Verify ClientRequest message encodes with all fields

**Setup**:
```typescript
const message: ClientRequest = {
  type: 'ClientRequest',
  requestId: 42n,
  lowestPendingRequestId: 40n,
  payload: Buffer.from([1, 2, 3, 4]),
  createdAt: new Date('2025-12-29T10:00:00Z')
};
```

**Test Steps**:
1. Call `encodeClientMessage(message)`
2. Verify protocol header
3. Verify discriminator: `0x04` (ClientRequest)
4. Verify requestId encoding (8 bytes, value 42)
5. Verify lowestPendingRequestId encoding (8 bytes, value 40)
6. Verify payload length-prefix + bytes
7. Verify timestamp ISO 8601 string encoding

**Expected Result**: Buffer matches Scala codec output for same message

---

#### TC-PROTO-004: Decode ClientResponse Message
**Objective**: Verify ClientResponse message decodes correctly

**Setup**: Buffer with ClientResponse encoding

**Test Steps**:
1. Call `decodeServerMessage(buffer)`
2. Verify returns ClientResponse object
3. Verify requestId is bigint
4. Verify result payload is Buffer with correct bytes

**Expected Result**: Correct ClientResponse object

---

#### TC-PROTO-005: Encode Query Message
**Objective**: Verify Query message encodes with correlation ID

**Setup**:
```typescript
const message: Query = {
  type: 'Query',
  correlationId: '550e8400-e29b-41d4-a716-446655440000',
  payload: Buffer.from([5, 6, 7, 8]),
  createdAt: new Date()
};
```

**Test Steps**:
1. Call `encodeClientMessage(message)`
2. Verify protocol header
3. Verify discriminator: `0x08` (Query)
4. Verify correlationId encoding (16 bytes UUID binary)
5. Verify payload encoding

**Expected Result**: Correct Query encoding

---

#### TC-PROTO-006: Decode QueryResponse Message
**Objective**: Verify QueryResponse message decodes correctly

**Setup**: Buffer with QueryResponse encoding

**Test Steps**:
1. Call `decodeServerMessage(buffer)`
2. Verify returns QueryResponse object
3. Verify correlationId is UUID string
4. Verify result payload is Buffer

**Expected Result**: Correct QueryResponse object

---

#### TC-PROTO-007: Encode KeepAlive Message
**Objective**: Verify KeepAlive message encodes timestamp correctly

**Setup**:
```typescript
const message: KeepAlive = {
  type: 'KeepAlive',
  timestamp: new Date('2025-12-29T10:00:00.123Z')
};
```

**Test Steps**:
1. Call `encodeClientMessage(message)`
2. Verify protocol header
3. Verify discriminator: `0x03` (KeepAlive)
4. Verify timestamp encoding (ISO 8601 string with milliseconds)

**Expected Result**: Correct KeepAlive encoding

---

#### TC-PROTO-008: Decode KeepAliveResponse Message
**Objective**: Verify KeepAliveResponse decodes both timestamps

**Setup**: Buffer with KeepAliveResponse encoding

**Test Steps**:
1. Call `decodeServerMessage(buffer)`
2. Verify returns KeepAliveResponse object
3. Verify clientTimestamp is Date object
4. Verify serverTimestamp is Date object

**Expected Result**: Correct KeepAliveResponse with both timestamps

---

#### TC-PROTO-009: Protocol Version Validation
**Objective**: Verify unsupported protocol version is rejected

**Setup**: Buffer with protocol version `0x02` (unsupported)

**Test Steps**:
1. Call `decodeServerMessage(buffer)`
2. Expect ProtocolError thrown
3. Verify error message includes version mismatch details

**Expected Result**: ProtocolError thrown, does not crash

---

#### TC-PROTO-010: Invalid Signature Rejection
**Objective**: Verify invalid signature is rejected

**Setup**: Buffer with signature `0x00 0x00 0x00 0x00 0x00` (invalid)

**Test Steps**:
1. Call `decodeServerMessage(buffer)`
2. Expect ProtocolError thrown
3. Verify error message mentions invalid signature

**Expected Result**: ProtocolError thrown

---

#### TC-PROTO-011: Round-Trip Encoding All Client Messages
**Objective**: Verify all ClientMessage types round-trip correctly

**Setup**: Create one instance of each ClientMessage type

**Test Steps**:
For each ClientMessage type:
1. Encode message to buffer
2. Verify Scala codec produces identical buffer
3. Decode buffer back to message object
4. Verify decoded equals original (deep equality)

**Expected Result**: All messages round-trip successfully

---

### State Machine Tests

#### TC-STATE-001: Disconnected Handles Connect Action
**Objective**: Verify Disconnected state transitions to ConnectingNewSession

**Setup**: DisconnectedState with mock transport

**Test Steps**:
1. Create Connect action event
2. Call `state.handle(event, transport, queue)`
3. Verify transport.connect() called with first endpoint
4. Verify transport.sendMessage() called with CreateSession
5. Verify returns ConnectingNewSessionState

**Expected Result**: Transition to ConnectingNewSession, CreateSession sent

---

#### TC-STATE-002: Disconnected Rejects SubmitCommand
**Objective**: Verify SubmitCommand fails in Disconnected state (before connect)

**Setup**: DisconnectedState with mock transport

**Test Steps**:
1. Create SubmitCommand action with promise
2. Call `state.handle(event, transport, queue)`
3. Verify promise rejected with error "Not connected"
4. Verify state remains Disconnected

**Expected Result**: Promise rejected, no state transition

---

#### TC-STATE-003: ConnectingNewSession Handles SessionCreated
**Objective**: Verify session establishment transitions to Connected

**Setup**: ConnectingNewSessionState with mock transport

**Test Steps**:
1. Create SessionCreated event with sessionId
2. Call `state.handle(event, transport, queue)`
3. Verify returns ConnectedState with sessionId
4. Verify 'connected' event emitted

**Expected Result**: Transition to Connected, event emitted

---

#### TC-STATE-004: ConnectingNewSession Handles SessionRejected
**Objective**: Verify rejection retries with next cluster member

**Setup**: ConnectingNewSessionState with 2 cluster members

**Test Steps**:
1. Create SessionRejected event with NotLeader reason
2. Call `state.handle(event, transport, queue)`
3. Verify transport disconnects from first member
4. Verify transport connects to second member
5. Verify CreateSession sent again
6. Verify returns ConnectingNewSessionState (still connecting)

**Expected Result**: Retry with next member, stay in connecting state

---

#### TC-STATE-005: Connected Handles SubmitCommand
**Objective**: Verify command submission in Connected state

**Setup**: ConnectedState with mock transport, empty pending requests

**Test Steps**:
1. Create SubmitCommand action with payload and promise
2. Call `state.handle(event, transport, queue)`
3. Verify request added to pendingRequests
4. Verify transport.sendMessage() called with ClientRequest
5. Verify requestId is sequential (starting from 1)
6. Verify state remains Connected

**Expected Result**: Request queued and sent, promise pending

---

#### TC-STATE-006: Connected Handles ClientResponse
**Objective**: Verify response completes pending command

**Setup**: ConnectedState with pending request (ID 42)

**Test Steps**:
1. Create ClientResponse event with requestId 42 and result payload
2. Call `state.handle(event, transport, queue)`
3. Verify pending request removed from map
4. Verify promise resolved with result payload
5. Verify state remains Connected

**Expected Result**: Promise resolved, request removed

---

#### TC-STATE-007: Connected Handles SubmitQuery
**Objective**: Verify query submission in Connected state

**Setup**: ConnectedState with mock transport

**Test Steps**:
1. Create SubmitQuery action with payload and promise
2. Call `state.handle(event, transport, queue)`
3. Verify query added to pendingQueries
4. Verify transport.sendMessage() called with Query message
5. Verify correlationId is UUID format
6. Verify state remains Connected

**Expected Result**: Query queued and sent, promise pending

---

#### TC-STATE-008: Connected Handles QueryResponse
**Objective**: Verify query response completes pending query

**Setup**: ConnectedState with pending query (correlation ID)

**Test Steps**:
1. Create QueryResponse event with correlationId and result
2. Call `state.handle(event, transport, queue)`
3. Verify pending query removed from map
4. Verify promise resolved with result payload
5. Verify state remains Connected

**Expected Result**: Promise resolved, query removed

---

#### TC-STATE-009: Connected Handles Network Disconnect
**Objective**: Verify automatic reconnection on network failure

**Setup**: ConnectedState with active session

**Test Steps**:
1. Create Disconnect event from transport
2. Call `state.handle(event, transport, queue)`
3. Verify 'disconnected' event emitted
4. Verify returns ConnectingExistingSessionState (preserving sessionId)
5. Verify pending requests preserved in new state

**Expected Result**: Transition to reconnecting, session preserved

---

#### TC-STATE-010: ConnectingExistingSession Handles SessionContinued
**Objective**: Verify session resumption after reconnection

**Setup**: ConnectingExistingSessionState with sessionId, pending requests

**Test Steps**:
1. Create SessionContinued event
2. Call `state.handle(event, transport, queue)`
3. Verify 'connected' event emitted
4. Verify all pending requests resent via transport
5. Verify returns ConnectedState with same sessionId

**Expected Result**: Session resumed, pending requests resent

---

#### TC-STATE-011: Connected Handles SessionExpired
**Objective**: Verify session expiry terminates client

**Setup**: ConnectedState with active session, pending requests

**Test Steps**:
1. Create SessionClosed event with SessionExpired reason
2. Call `state.handle(event, transport, queue)`
3. Verify 'sessionExpired' event emitted
4. Verify all pending requests rejected with SessionExpiredError
5. Verify returns terminal state (no further transitions)

**Expected Result**: Session terminated, all requests failed, event emitted

---

#### TC-STATE-012: Connected Handles KeepAliveTick
**Objective**: Verify keep-alive heartbeat sent periodically

**Setup**: ConnectedState with active session

**Test Steps**:
1. Create KeepAliveTick event
2. Call `state.handle(event, transport, queue)`
3. Verify transport.sendMessage() called with KeepAlive message
4. Verify timestamp in KeepAlive is current time
5. Verify state remains Connected

**Expected Result**: KeepAlive sent, state unchanged

---

#### TC-STATE-013: Connected Handles TimeoutCheck
**Objective**: Verify timed-out requests are rejected

**Setup**: ConnectedState with pending request created 31 seconds ago (timeout 30s)

**Test Steps**:
1. Create TimeoutCheck event
2. Call `state.handle(event, transport, queue)`
3. Verify timed-out request identified
4. Verify promise rejected with TimeoutError
5. Verify request removed from pending map

**Expected Result**: Timed-out request rejected and removed

---

#### TC-STATE-014: Connected Handles ServerRequest
**Objective**: Verify server-initiated request delivered to handler

**Setup**: ConnectedState with ServerRequestTracker (last ack 5)

**Test Steps**:
1. Create ServerRequest event with requestId 6 (next consecutive)
2. Call `state.handle(event, transport, queue)`
3. Verify request added to serverRequestQueue
4. Verify ServerRequestAck sent via transport
5. Verify ServerRequestTracker updated (last ack 6)
6. Verify state remains Connected

**Expected Result**: Request delivered, acknowledged, tracker updated

---

#### TC-STATE-015: Connected Handles Duplicate ServerRequest
**Objective**: Verify duplicate server request acknowledged but not delivered

**Setup**: ConnectedState with ServerRequestTracker (last ack 10)

**Test Steps**:
1. Create ServerRequest event with requestId 10 (duplicate)
2. Call `state.handle(event, transport, queue)`
3. Verify ServerRequestAck sent
4. Verify request NOT added to serverRequestQueue
5. Verify state remains Connected

**Expected Result**: Acknowledged but not delivered

---

#### TC-STATE-016: Connected Handles Out-of-Order ServerRequest
**Objective**: Verify out-of-order server request logged and not processed

**Setup**: ConnectedState with ServerRequestTracker (last ack 10)

**Test Steps**:
1. Create ServerRequest event with requestId 15 (gap from 11-14)
2. Call `state.handle(event, transport, queue)`
3. Verify NO ServerRequestAck sent
4. Verify request NOT added to queue
5. Verify warning logged (gap detected)
6. Verify state remains Connected

**Expected Result**: Ignored (waiting for missing requests)

---

### Pending Request Management Tests

#### TC-PENDING-001: Add Pending Request
**Objective**: Verify request added to pending map

**Setup**: Empty PendingRequests

**Test Steps**:
1. Create promise (resolve/reject callbacks)
2. Call `pending.add(42n, payload, resolve, reject, createdAt)`
3. Verify map contains requestId 42
4. Verify size is 1

**Expected Result**: Request added successfully

---

#### TC-PENDING-002: Complete Pending Request
**Objective**: Verify request completion resolves promise

**Setup**: PendingRequests with request ID 42

**Test Steps**:
1. Call `pending.complete(42n, resultPayload)`
2. Verify promise resolved with resultPayload
3. Verify request removed from map
4. Verify size is 0

**Expected Result**: Promise resolved, request removed

---

#### TC-PENDING-003: Timeout Pending Request
**Objective**: Verify timeout rejects promise

**Setup**: PendingRequests with request ID 42

**Test Steps**:
1. Call `pending.timeout(42n)`
2. Verify promise rejected with TimeoutError
3. Verify TimeoutError contains requestId 42
4. Verify request removed from map

**Expected Result**: Promise rejected with TimeoutError

---

#### TC-PENDING-004: Check Timeouts
**Objective**: Verify timeout detection logic

**Setup**: PendingRequests with 3 requests:
- Request 1: created 10 seconds ago
- Request 2: created 31 seconds ago (timed out)
- Request 3: created 5 seconds ago

**Test Steps**:
1. Call `pending.checkTimeouts(now, 30000)` (30s timeout)
2. Verify returns array containing only request 2

**Expected Result**: Only timed-out requests identified

---

#### TC-PENDING-005: Lowest Pending Request ID
**Objective**: Verify lowest ID calculation for cache eviction

**Setup**: PendingRequests with IDs: 10, 5, 20, 15

**Test Steps**:
1. Call `pending.lowestPendingRequestId()`
2. Verify returns 5n

**Expected Result**: Correct lowest ID returned

---

### Server Request Tracker Tests

#### TC-TRACKER-001: Check Consecutive Request (Process)
**Objective**: Verify consecutive request marked for processing

**Setup**: ServerRequestTracker (last ack 10)

**Test Steps**:
1. Call `tracker.check(11n)`
2. Verify returns 'Process'

**Expected Result**: Marked for processing

---

#### TC-TRACKER-002: Check Old Request (Duplicate)
**Objective**: Verify old request marked as duplicate

**Setup**: ServerRequestTracker (last ack 10)

**Test Steps**:
1. Call `tracker.check(8n)`
2. Verify returns 'OldRequest'

**Expected Result**: Marked as old/duplicate

---

#### TC-TRACKER-003: Check Out-of-Order Request (Gap)
**Objective**: Verify gap detected in request sequence

**Setup**: ServerRequestTracker (last ack 10)

**Test Steps**:
1. Call `tracker.check(15n)` (gap from 11-14)
2. Verify returns 'OutOfOrder'

**Expected Result**: Marked as out-of-order

---

#### TC-TRACKER-004: Acknowledge Request
**Objective**: Verify acknowledge updates last ack ID

**Setup**: ServerRequestTracker (last ack 10)

**Test Steps**:
1. Call `tracker.acknowledge(11n)`
2. Verify `tracker.check(11n)` now returns 'OldRequest'
3. Verify `tracker.check(12n)` now returns 'Process'

**Expected Result**: Last ack updated correctly

---

### Configuration Validation Tests

#### TC-CONFIG-001: Valid Configuration Accepted
**Objective**: Verify valid config passes validation

**Setup**:
```typescript
const config = {
  endpoints: ['tcp://localhost:5555'],
  capabilities: { version: '1.0.0' }
};
```

**Test Steps**:
1. Create RaftClient with config
2. Verify no ValidationError thrown

**Expected Result**: Client created successfully

---

#### TC-CONFIG-002: Empty Endpoints Rejected
**Objective**: Verify empty endpoints array rejected

**Setup**:
```typescript
const config = {
  endpoints: [],
  capabilities: { version: '1.0.0' }
};
```

**Test Steps**:
1. Attempt to create RaftClient
2. Verify ValidationError thrown
3. Verify error message mentions endpoints

**Expected Result**: ValidationError thrown

---

#### TC-CONFIG-003: Empty Capabilities Rejected
**Objective**: Verify empty capabilities rejected

**Setup**:
```typescript
const config = {
  endpoints: ['tcp://localhost:5555'],
  capabilities: {}
};
```

**Test Steps**:
1. Attempt to create RaftClient
2. Verify ValidationError thrown
3. Verify error message mentions capabilities

**Expected Result**: ValidationError thrown

---

#### TC-CONFIG-004: Negative Timeout Rejected
**Objective**: Verify negative timeout values rejected

**Setup**:
```typescript
const config = {
  endpoints: ['tcp://localhost:5555'],
  capabilities: { version: '1.0.0' },
  requestTimeout: -1000
};
```

**Test Steps**:
1. Attempt to create RaftClient
2. Verify ValidationError thrown
3. Verify error message mentions timeout

**Expected Result**: ValidationError thrown

---

## Integration Tests

### Client Lifecycle Tests

#### TC-INT-001: Full Connect → Command → Disconnect Cycle
**Objective**: Verify complete client lifecycle

**Setup**: Real ZMQ transport, mock server

**Test Steps**:
1. Create RaftClient with config
2. Register 'connected' event handler
3. Call `await client.connect()`
4. Verify 'connected' event emitted with sessionId
5. Call `result = await client.submitCommand(payload)`
6. Verify result is Buffer with expected response
7. Call `await client.disconnect()`
8. Verify 'disconnected' event emitted

**Expected Result**: Full lifecycle completes successfully

---

#### TC-INT-002: Multiple Commands Sequentially
**Objective**: Verify multiple commands work correctly

**Setup**: Connected client

**Test Steps**:
1. Submit command 1, await result
2. Submit command 2, await result
3. Submit command 3, await result
4. Verify all results correct
5. Verify requestIds are sequential (1, 2, 3)

**Expected Result**: All commands complete successfully

---

#### TC-INT-003: Multiple Commands Concurrently (Promise.all)
**Objective**: Verify concurrent command submission

**Setup**: Connected client

**Test Steps**:
1. Create 100 command promises
2. Call `results = await Promise.all(promises)`
3. Verify all 100 results received
4. Verify no duplicate requestIds
5. Verify all promises resolved (no rejections)

**Expected Result**: All commands complete concurrently

---

#### TC-INT-004: Query Submission
**Objective**: Verify query (read-only) operations

**Setup**: Connected client

**Test Steps**:
1. Call `result = await client.submitQuery(payload)`
2. Verify result is Buffer with expected response
3. Verify correlationId is UUID format

**Expected Result**: Query completes successfully

---

#### TC-INT-005: Keep-Alive Maintenance
**Objective**: Verify keep-alive heartbeats maintain session

**Setup**: Connected client with 2-second keep-alive interval

**Test Steps**:
1. Wait 10 seconds (5 keep-alive cycles)
2. Submit command to verify session still active
3. Verify command succeeds (session not expired)

**Expected Result**: Session remains active due to keep-alives

---

### Reconnection Tests

#### TC-INT-006: Network Disconnection and Reconnection
**Objective**: Verify automatic reconnection on network failure

**Setup**: Connected client, mock server that can disconnect

**Test Steps**:
1. Register 'disconnected' and 'reconnecting' event handlers
2. Trigger network disconnect from server
3. Verify 'disconnected' event emitted
4. Verify 'reconnecting' event emitted (attempt 1)
5. Allow server to accept reconnection
6. Verify 'connected' event emitted (SessionContinued)

**Expected Result**: Client automatically reconnects

---

#### TC-INT-007: Pending Requests Preserved Across Reconnection
**Objective**: Verify pending requests resent after reconnection

**Setup**: Connected client

**Test Steps**:
1. Submit command 1, don't wait for response
2. Trigger network disconnect immediately
3. Verify 'disconnected' event
4. Allow reconnection
5. Verify command 1 response received after reconnection

**Expected Result**: Pending request preserved and completed

---

#### TC-INT-008: Requests During Disconnection Queue with Timeout
**Objective**: Verify requests submitted while disconnected queue and wait

**Setup**: Disconnected client (after network failure)

**Test Steps**:
1. Submit command while disconnected
2. Verify promise does not reject immediately
3. Allow reconnection within timeout
4. Verify command sent after reconnection
5. Verify promise resolves with response

**Expected Result**: Request queued, sent after reconnection

---

#### TC-INT-009: Requests During Disconnection Timeout
**Objective**: Verify queued requests timeout if reconnection delayed

**Setup**: Disconnected client with 5-second queued request timeout

**Test Steps**:
1. Submit command while disconnected
2. Prevent reconnection for 6 seconds
3. Verify promise rejects with TimeoutError
4. Verify error message mentions timeout waiting for reconnection

**Expected Result**: Request times out, promise rejected

---

#### TC-INT-010: Leadership Change Reconnection
**Objective**: Verify reconnection to new leader on NotLeader rejection

**Setup**: Connected client to follower node

**Test Steps**:
1. Server sends SessionRejected(NotLeader) with leader hint
2. Verify 'disconnected' event
3. Verify client connects to leader endpoint
4. Verify 'connected' event with new endpoint

**Expected Result**: Client automatically finds and connects to leader

---

### Session Expiry Tests

#### TC-INT-011: Session Expiry Due to Missed Keep-Alives
**Objective**: Verify session expiry handling

**Setup**: Connected client with keep-alive disabled (for test)

**Test Steps**:
1. Wait for server session timeout period
2. Submit command (triggers SessionExpired rejection)
3. Verify 'sessionExpired' event emitted
4. Verify command promise rejected with SessionExpiredError
5. Verify event loop terminates

**Expected Result**: Session expires, client terminates gracefully

---

#### TC-INT-012: All Pending Requests Fail on Session Expiry
**Objective**: Verify all pending requests fail when session expires

**Setup**: Connected client with 3 pending requests

**Test Steps**:
1. Submit 3 commands, don't wait
2. Trigger SessionExpired from server
3. Verify all 3 promises reject with SessionExpiredError
4. Verify 'sessionExpired' event emitted

**Expected Result**: All pending requests fail, event emitted

---

### Server Request Handling Tests

#### TC-INT-013: Server-Initiated Request Delivery
**Objective**: Verify server requests delivered to handler

**Setup**: Connected client with server request handler registered

**Test Steps**:
1. Register handler: `client.onServerRequest(handler)`
2. Server sends ServerRequest(requestId 1, payload)
3. Verify handler invoked with request payload
4. Verify ServerRequestAck sent automatically

**Expected Result**: Request delivered, acknowledged

---

#### TC-INT-014: Consecutive Server Requests Processed
**Objective**: Verify consecutive server requests processed in order

**Setup**: Connected client with handler

**Test Steps**:
1. Server sends ServerRequest ID 1
2. Verify handler invoked for ID 1
3. Server sends ServerRequest ID 2
4. Verify handler invoked for ID 2
5. Server sends ServerRequest ID 3
6. Verify handler invoked for ID 3

**Expected Result**: All requests processed in order

---

#### TC-INT-015: Duplicate Server Request Ignored
**Objective**: Verify duplicate server request not delivered twice

**Setup**: Connected client with handler (last ack 5)

**Test Steps**:
1. Server sends ServerRequest ID 5 (duplicate)
2. Verify handler NOT invoked
3. Verify ServerRequestAck sent (idempotent ack)

**Expected Result**: Duplicate acknowledged but not delivered

---

## Compatibility Tests

### Wire Protocol Compatibility

#### TC-COMPAT-001: TypeScript Client → Scala Server CreateSession
**Objective**: Verify CreateSession wire format compatible

**Setup**: TypeScript client, real Scala server

**Test Steps**:
1. Call `client.connect()`
2. Capture network traffic (CreateSession message bytes)
3. Verify Scala server accepts message
4. Verify Scala server responds with SessionCreated
5. Compare TypeScript encoding with Scala reference

**Expected Result**: Byte-for-byte compatible, session established

---

#### TC-COMPAT-002: TypeScript Client → Scala Server ClientRequest
**Objective**: Verify ClientRequest wire format compatible

**Setup**: Connected TypeScript client, Scala server

**Test Steps**:
1. Call `client.submitCommand(payload)`
2. Capture ClientRequest message bytes
3. Verify Scala server accepts and processes
4. Verify Scala server responds with ClientResponse
5. Compare encoding with Scala reference

**Expected Result**: Byte-for-byte compatible, command processed

---

#### TC-COMPAT-003: Scala Server → TypeScript Client SessionCreated
**Objective**: Verify SessionCreated decoding compatible

**Setup**: TypeScript client connecting to Scala server

**Test Steps**:
1. Scala server sends SessionCreated
2. TypeScript client decodes message
3. Verify sessionId extracted correctly
4. Verify nonce matches sent value
5. Verify transition to Connected state

**Expected Result**: Message decoded correctly, session established

---

#### TC-COMPAT-004: Scala Server → TypeScript Client ClientResponse
**Objective**: Verify ClientResponse decoding compatible

**Setup**: TypeScript client with pending request, Scala server

**Test Steps**:
1. Scala server sends ClientResponse
2. TypeScript client decodes message
3. Verify requestId matches
4. Verify payload bytes match
5. Verify promise resolved

**Expected Result**: Message decoded, promise resolved

---

#### TC-COMPAT-005: Interoperability with Scala Client
**Objective**: Verify TypeScript and Scala clients can coexist

**Setup**: Same Raft cluster, 1 TypeScript client + 1 Scala client

**Test Steps**:
1. Both clients connect (separate sessions)
2. TypeScript client submits command 1
3. Scala client submits command 2
4. TypeScript client submits command 3
5. Verify all commands processed correctly
6. Verify no interference between clients

**Expected Result**: Both clients work independently on same cluster

---

#### TC-COMPAT-006: All Message Types Round-Trip
**Objective**: Comprehensive compatibility test for all message types

**Setup**: TypeScript client, Scala server

**Test Steps**:
For each ClientMessage type:
1. TypeScript client sends message
2. Scala server receives and responds
3. TypeScript client receives response
4. Verify entire cycle works

For each ServerMessage type:
1. Scala server sends message
2. TypeScript client receives and decodes
3. Verify message decoded correctly

**Expected Result**: All message types compatible

---

## Performance Tests

### Throughput Tests

#### TC-PERF-001: Single Request Latency
**Objective**: Measure round-trip time for single request

**Setup**: Connected client, Scala server (local network)

**Test Steps**:
1. Submit single command
2. Measure time from submit to response
3. Repeat 100 times
4. Calculate p50, p95, p99 latencies

**Expected Result**: 
- p50 < 5ms
- p95 < 10ms
- p99 < 20ms
(client-side overhead only, excludes Raft consensus time)

---

#### TC-PERF-002: Throughput at 1,000 req/s
**Objective**: Verify client handles 1K req/s

**Setup**: Connected client, Scala server

**Test Steps**:
1. Submit 1,000 commands over 1 second
2. Measure actual throughput
3. Verify all commands complete successfully
4. Measure latency distribution

**Expected Result**: 
- Throughput ≥ 1,000 req/s
- No errors
- p99 latency < 50ms

---

#### TC-PERF-003: Throughput at 10,000 req/s
**Objective**: Verify client handles 10K req/s

**Setup**: Connected client, Scala server (high-performance hardware)

**Test Steps**:
1. Submit 10,000 commands over 1 second
2. Measure actual throughput
3. Verify all commands complete successfully
4. Measure latency distribution

**Expected Result**: 
- Throughput ≥ 10,000 req/s
- No errors
- p99 latency < 100ms

---

#### TC-PERF-004: Concurrent Requests (Promise.all)
**Objective**: Measure performance of concurrent submission

**Setup**: Connected client

**Test Steps**:
1. Create 1,000 command promises
2. Submit all with Promise.all
3. Measure total time to complete
4. Measure peak memory usage

**Expected Result**: 
- All commands complete
- Total time < 2 seconds (1K req/s sustained)
- Memory usage stable (no leaks)

---

#### TC-PERF-005: Protocol Encoding Overhead
**Objective**: Measure encoding/decoding performance

**Setup**: Standalone codec benchmarks

**Test Steps**:
1. Encode 100,000 ClientRequest messages
2. Measure time per encode
3. Decode 100,000 ClientResponse messages
4. Measure time per decode

**Expected Result**:
- Encode: < 10μs per message
- Decode: < 10μs per message

---

#### TC-PERF-006: Memory Usage Under Sustained Load
**Objective**: Verify no memory leaks under load

**Setup**: Connected client, 10-minute test

**Test Steps**:
1. Submit 1,000 req/s for 10 minutes (600K total requests)
2. Monitor memory usage every 10 seconds
3. Verify memory usage stable (no growth)
4. Check for leaked timers, sockets, promises

**Expected Result**: 
- Memory usage flat (no leaks)
- No leaked resources

---

## Edge Case Tests

#### TC-EDGE-001: Payload Validation (Empty Payload)
**Objective**: Verify empty payload rejected

**Setup**: Connected client

**Test Steps**:
1. Call `client.submitCommand(Buffer.from([]))` (empty)
2. Verify ValidationError thrown synchronously
3. Verify error message mentions invalid payload

**Expected Result**: ValidationError thrown, no network send

---

#### TC-EDGE-002: Payload Validation (Null Payload)
**Objective**: Verify null payload rejected

**Setup**: Connected client

**Test Steps**:
1. Call `client.submitCommand(null as any)`
2. Verify ValidationError thrown synchronously

**Expected Result**: ValidationError thrown

---

#### TC-EDGE-003: Connect Called Twice
**Objective**: Verify duplicate connect() call ignored

**Setup**: Client in Connected state

**Test Steps**:
1. Call `await client.connect()` (already connected)
2. Verify no error thrown
3. Verify state remains Connected (no duplicate session)
4. Verify warning logged

**Expected Result**: Second connect() ignored gracefully

---

#### TC-EDGE-004: Disconnect Before Connect
**Objective**: Verify disconnect() on disconnected client is no-op

**Setup**: Newly created client (never connected)

**Test Steps**:
1. Call `await client.disconnect()`
2. Verify no error thrown
3. Verify state remains Disconnected

**Expected Result**: No-op, no error

---

#### TC-EDGE-005: Submit Command Before Connect
**Objective**: Verify command before connect throws error

**Setup**: Newly created client (never connected)

**Test Steps**:
1. Call `client.submitCommand(payload)`
2. Verify promise rejects with error "Not connected"
3. Verify no network send attempted

**Expected Result**: Promise rejected immediately

---

#### TC-EDGE-006: Large Payload (1MB)
**Objective**: Verify large payloads handled correctly

**Setup**: Connected client

**Test Steps**:
1. Create 1MB payload (Buffer)
2. Call `result = await client.submitCommand(payload)`
3. Verify command completes successfully
4. Verify result payload matches expected response

**Expected Result**: Large payload works (no size limit in client)

---

#### TC-EDGE-007: Very Large Payload (10MB)
**Objective**: Verify very large payloads work (server limits may apply)

**Setup**: Connected client

**Test Steps**:
1. Create 10MB payload
2. Submit command
3. If server accepts: verify success
4. If server rejects: verify rejection handled gracefully

**Expected Result**: Client handles either success or rejection

---

#### TC-EDGE-008: Cluster All Unreachable
**Objective**: Verify behavior when all cluster members unreachable

**Setup**: Client with 3 endpoints, all unreachable

**Test Steps**:
1. Call `client.connect()`
2. Verify connection attempts to all 3 members
3. Verify 'reconnecting' events emitted for each attempt
4. Verify client keeps retrying (infinite retry by default)

**Expected Result**: Continuous retry, no crash

---

#### TC-EDGE-009: Malformed Server Response
**Objective**: Verify malformed message doesn't crash client

**Setup**: Connected client, mock server sends garbage

**Test Steps**:
1. Mock server sends invalid bytes (bad signature)
2. Verify client logs ProtocolError
3. Verify client does not crash
4. Verify event loop continues running

**Expected Result**: Error logged, client continues

---

#### TC-EDGE-010: Request Timeout with Manual Retry
**Objective**: Verify manual retry after timeout works

**Setup**: Connected client with 1-second request timeout

**Test Steps**:
1. Submit command, server delays response > 1s
2. Verify promise rejects with TimeoutError after 1s
3. Manually retry: `result = await client.submitCommand(payload)`
4. Server responds to retry
5. Verify retry promise resolves successfully

**Expected Result**: Manual retry works after timeout

---

## Test Execution Summary

**Total Test Scenarios**: 95

**Breakdown**:
- Unit Tests: 45
  - Protocol: 11
  - State Machine: 16
  - Pending Requests: 5
  - Server Request Tracker: 4
  - Configuration: 4
  - Errors: 5
- Integration Tests: 30
  - Lifecycle: 5
  - Reconnection: 5
  - Session Expiry: 2
  - Server Requests: 3
  - Compatibility: 6
  - Performance: 6
  - Edge Cases: 10
- Compatibility Tests: 6
- Performance Tests: 6
- Edge Case Tests: 10

**Priority**:
- **P0 (Critical)**: Protocol compatibility, state transitions, basic lifecycle
- **P1 (High)**: Reconnection, session management, error handling
- **P2 (Medium)**: Performance validation, edge cases
- **P3 (Low)**: Stress tests, extreme edge cases

**Test Environment**:
- Unit: Mock transport, no network
- Integration: Real ZMQ sockets, mock Scala server (or test harness)
- Compatibility: Real Scala ZIO Raft server
- Performance: Dedicated test environment (controlled network/hardware)

---

## Next Steps

1. Implement test infrastructure (Vitest setup, mock transport, test utilities)
2. Implement tests incrementally alongside feature implementation
3. Run compatibility tests against real Scala server
4. Run performance benchmarks to validate NFR-001/NFR-002
5. Update test suite as new edge cases discovered

All test scenarios documented. Ready for quickstart.md generation.
