# Test Plan: TypeScript Client Library

**Date**: 2025-12-24  
**Feature**: TypeScript Client for ZIO Raft

## Overview
This document defines all test cases for the TypeScript client library, organized by category. Tests verify protocol compatibility, behavioral correctness, and performance requirements.

---

## 1. Protocol Codec Tests

### 1.1 Client Message Encoding

**TC-CODEC-001: CreateSession encoding**
- **Given**: CreateSession message with capabilities and nonce
- **When**: Encode to binary
- **Then**: Protocol header + type discriminator (1) + encoded capabilities + encoded nonce

**TC-CODEC-002: ContinueSession encoding**
- **Given**: ContinueSession message with sessionId and nonce
- **When**: Encode to binary
- **Then**: Protocol header + type discriminator (2) + encoded sessionId + encoded nonce

**TC-CODEC-003: KeepAlive encoding**
- **Given**: KeepAlive message with timestamp
- **When**: Encode to binary
- **Then**: Protocol header + type discriminator (3) + encoded timestamp (int64 epoch millis)

**TC-CODEC-004: ClientRequest encoding**
- **Given**: ClientRequest with requestId, lowestPendingRequestId, payload, createdAt
- **When**: Encode to binary
- **Then**: Protocol header + type discriminator (4) + two int64 IDs + int32-prefixed payload + timestamp

**TC-CODEC-005: Query encoding**
- **Given**: Query with correlationId, payload, createdAt
- **When**: Encode to binary
- **Then**: Protocol header + type discriminator (8) + uint16-prefixed correlationId + int32-prefixed payload + timestamp

**TC-CODEC-006: ServerRequestAck encoding**
- **Given**: ServerRequestAck with requestId
- **When**: Encode to binary
- **Then**: Protocol header + type discriminator (5) + int64 requestId

**TC-CODEC-007: CloseSession encoding**
- **Given**: CloseSession with ClientShutdown reason
- **When**: Encode to binary
- **Then**: Protocol header + type discriminator (6) + uint8 reason code (1)

**TC-CODEC-008: ConnectionClosed encoding**
- **Given**: ConnectionClosed (singleton)
- **When**: Encode to binary
- **Then**: Protocol header + type discriminator (7), no additional data

### 1.2 Server Message Decoding

**TC-CODEC-101: SessionCreated decoding**
- **Given**: Binary data with SessionCreated message
- **When**: Decode from binary
- **Then**: SessionCreated object with correct sessionId and nonce

**TC-CODEC-102: SessionContinued decoding**
- **Given**: Binary data with SessionContinued message
- **When**: Decode from binary
- **Then**: SessionContinued object with correct nonce

**TC-CODEC-103: SessionRejected decoding**
- **Given**: Binary data with SessionRejected message
- **When**: Decode from binary
- **Then**: SessionRejected object with reason, nonce, and optional leaderId

**TC-CODEC-104: SessionClosed decoding**
- **Given**: Binary data with SessionClosed message
- **When**: Decode from binary
- **Then**: SessionClosed object with reason and optional leaderId

**TC-CODEC-105: KeepAliveResponse decoding**
- **Given**: Binary data with KeepAliveResponse message
- **When**: Decode from binary
- **Then**: KeepAliveResponse object with correct timestamp

**TC-CODEC-106: ClientResponse decoding**
- **Given**: Binary data with ClientResponse message
- **When**: Decode from binary
- **Then**: ClientResponse object with requestId and result payload

**TC-CODEC-107: QueryResponse decoding**
- **Given**: Binary data with QueryResponse message
- **When**: Decode from binary
- **Then**: QueryResponse object with correlationId and result payload

**TC-CODEC-108: ServerRequest decoding**
- **Given**: Binary data with ServerRequest message
- **When**: Decode from binary
- **Then**: ServerRequest object with requestId, payload, createdAt

**TC-CODEC-109: RequestError decoding**
- **Given**: Binary data with RequestError message
- **When**: Decode from binary
- **Then**: RequestError object with requestId and ResponseEvicted reason

### 1.3 Roundtrip Tests

**TC-CODEC-201: All client messages roundtrip**
- **Given**: Each client message type
- **When**: Encode then decode
- **Then**: Decoded message equals original

**TC-CODEC-202: All server messages roundtrip**
- **Given**: Each server message type
- **When**: Encode then decode
- **Then**: Decoded message equals original

### 1.4 Edge Cases

**TC-CODEC-301: Empty capabilities map**
- **Given**: CreateSession with empty capabilities
- **When**: Validate before encoding
- **Then**: Throw ValidationError

**TC-CODEC-302: Large payload (10MB)**
- **Given**: ClientRequest with 10MB payload
- **When**: Encode and decode
- **Then**: Successful roundtrip, payload intact

**TC-CODEC-303: Invalid protocol signature**
- **Given**: Binary data with wrong signature
- **When**: Decode
- **Then**: Throw Error "Invalid protocol signature"

**TC-CODEC-304: Unsupported protocol version**
- **Given**: Binary data with version != 1
- **When**: Decode
- **Then**: Throw Error "Unsupported protocol version"

---

## 2. State Machine Tests

### 2.1 Disconnected State

**TC-STATE-001: Connect action from Disconnected**
- **Given**: Disconnected state
- **When**: Receive Connect action
- **Then**: Transition to ConnectingNewSession, send CreateSession

**TC-STATE-002: SubmitCommand while Disconnected**
- **Given**: Disconnected state
- **When**: Receive SubmitCommand action
- **Then**: Reject promise with "Not connected" error

**TC-STATE-003: Server message while Disconnected**
- **Given**: Disconnected state
- **When**: Receive server message
- **Then**: Log warning, remain in Disconnected

### 2.2 ConnectingNewSession State

**TC-STATE-101: SessionCreated in ConnectingNewSession**
- **Given**: ConnectingNewSession state with pending requests
- **When**: Receive SessionCreated with matching nonce
- **Then**: Transition to Connected, resend pending requests

**TC-STATE-102: SessionRejected NotLeader**
- **Given**: ConnectingNewSession state
- **When**: Receive SessionRejected(NotLeader) with leaderId
- **Then**: Disconnect, connect to leader, send CreateSession

**TC-STATE-103: SessionRejected InvalidCapabilities**
- **Given**: ConnectingNewSession state with pending requests
- **When**: Receive SessionRejected(InvalidCapabilities)
- **Then**: Fail all pending requests, terminate

**TC-STATE-104: Connection timeout**
- **Given**: ConnectingNewSession state, no SessionCreated received
- **When**: Timeout elapsed
- **Then**: Try next member

**TC-STATE-105: SubmitCommand while connecting**
- **Given**: ConnectingNewSession state
- **When**: Receive SubmitCommand action
- **Then**: Queue request, do not send yet

### 2.3 ConnectingExistingSession State

**TC-STATE-201: SessionContinued in ConnectingExistingSession**
- **Given**: ConnectingExistingSession state with pending requests
- **When**: Receive SessionContinued with matching nonce
- **Then**: Transition to Connected, resend pending requests

**TC-STATE-202: SessionRejected SessionExpired**
- **Given**: ConnectingExistingSession state with pending requests
- **When**: Receive SessionRejected(SessionExpired)
- **Then**: Fail all pending requests, terminate

**TC-STATE-203: SessionRejected NotLeader during resume**
- **Given**: ConnectingExistingSession state
- **When**: Receive SessionRejected(NotLeader) with leaderId
- **Then**: Disconnect, connect to leader, send ContinueSession

### 2.4 Connected State

**TC-STATE-301: SubmitCommand in Connected**
- **Given**: Connected state
- **When**: Receive SubmitCommand action
- **Then**: Allocate RequestId, send ClientRequest, track pending

**TC-STATE-302: SubmitQuery in Connected**
- **Given**: Connected state
- **When**: Receive SubmitQuery action
- **Then**: Generate CorrelationId, send Query, track pending

**TC-STATE-303: ClientResponse received**
- **Given**: Connected state with pending request
- **When**: Receive ClientResponse with matching requestId
- **Then**: Resolve promise, remove from pending

**TC-STATE-304: QueryResponse received**
- **Given**: Connected state with pending query
- **When**: Receive QueryResponse with matching correlationId
- **Then**: Resolve promise, remove from pending

**TC-STATE-305: SessionClosed NotLeaderAnymore**
- **Given**: Connected state with pending requests
- **When**: Receive SessionClosed(NotLeaderAnymore) with leaderId
- **Then**: Transition to ConnectingExistingSession, connect to leader

**TC-STATE-306: SessionClosed SessionExpired**
- **Given**: Connected state with pending requests
- **When**: Receive SessionClosed(SessionExpired)
- **Then**: Fail all pending requests, emit sessionExpired event, terminate

**TC-STATE-307: KeepAlive tick**
- **Given**: Connected state
- **When**: KeepAlive timer fires
- **Then**: Send KeepAlive message

**TC-STATE-308: Request timeout and retry**
- **Given**: Connected state with pending request, no response after timeout
- **When**: Timeout check fires
- **Then**: Resend ClientRequest, update lastSentAt

**TC-STATE-309: Disconnect action**
- **Given**: Connected state
- **When**: Receive Disconnect action
- **Then**: Send CloseSession, disconnect transport, transition to Disconnected

**TC-STATE-310: ServerRequest received**
- **Given**: Connected state
- **When**: Receive ServerRequest
- **Then**: Emit serverRequestReceived event, send ServerRequestAck

**TC-STATE-311: Duplicate ServerRequest (already processed)**
- **Given**: Connected state, already processed requestId
- **When**: Receive same ServerRequest again
- **Then**: Re-send ServerRequestAck, do not re-emit event

**TC-STATE-312: Out-of-order ServerRequest**
- **Given**: Connected state, expecting requestId N
- **When**: Receive ServerRequest with requestId N+2
- **Then**: Drop request, log warning

### 2.5 State Persistence Across Reconnection

**TC-STATE-401: Pending requests preserved during reconnection**
- **Given**: Connected state with 10 pending requests
- **When**: Network failure, reconnect successful
- **Then**: All 10 requests resent, responses eventually received

---

## 3. Transport Tests

### 3.1 ZMQ Transport

**TC-TRANSPORT-001: Connect to single address**
- **Given**: ZmqTransport
- **When**: connect("tcp://localhost:5555")
- **Then**: ZMQ socket connects successfully

**TC-TRANSPORT-002: Disconnect from address**
- **Given**: Connected ZmqTransport
- **When**: disconnect()
- **Then**: ZMQ socket disconnects cleanly

**TC-TRANSPORT-003: Send message**
- **Given**: Connected ZmqTransport
- **When**: send(CreateSession message)
- **Then**: Message sent to ZMQ socket

**TC-TRANSPORT-004: Receive message**
- **Given**: Connected ZmqTransport with incoming message
- **When**: Iterate receive()
- **Then**: Decoded ServerMessage yielded

**TC-TRANSPORT-005: Reconnect to different address**
- **Given**: Connected to address A
- **When**: disconnect(), connect(address B)
- **Then**: Successfully connected to B

**TC-TRANSPORT-006: Send high volume (1K messages/sec)**
- **Given**: Connected ZmqTransport
- **When**: Send 1000 messages in 1 second
- **Then**: All messages sent without error

---

## 4. Pending Request Tests

### 4.1 Request Tracking

**TC-PENDING-001: Add pending request**
- **Given**: PendingRequests
- **When**: add(requestId, payload, resolve, reject, timestamp)
- **Then**: Request tracked in map

**TC-PENDING-002: Complete pending request**
- **Given**: PendingRequests with requestId=123
- **When**: complete(123, result)
- **Then**: Promise resolved with result, request removed

**TC-PENDING-003: Timeout expired request**
- **Given**: PendingRequests with request sent 11 seconds ago
- **When**: resendExpired(transport, now, 10s timeout)
- **Then**: Request resent, lastSentAt updated

**TC-PENDING-004: Resend all pending**
- **Given**: PendingRequests with 5 pending requests
- **When**: resendAll(transport)
- **Then**: All 5 requests resent

**TC-PENDING-005: Lowest pending requestId**
- **Given**: PendingRequests with requestIds [5, 10, 15]
- **When**: lowestPendingRequestIdOr(default)
- **Then**: Returns RequestId(5)

**TC-PENDING-006: Die all pending on session expiry**
- **Given**: PendingRequests with 3 pending requests
- **When**: dieAll(new Error("Session expired"))
- **Then**: All 3 promises rejected with error

### 4.2 Query Tracking

**TC-PENDING-101: Add pending query**
- **Given**: PendingQueries
- **When**: add(correlationId, payload, resolve, reject, timestamp)
- **Then**: Query tracked in map

**TC-PENDING-102: Complete pending query**
- **Given**: PendingQueries with correlationId="abc"
- **When**: complete("abc", result)
- **Then**: Promise resolved with result, query removed

**TC-PENDING-103: Timeout expired query**
- **Given**: PendingQueries with query sent 11 seconds ago
- **When**: resendExpired(transport, now, 10s timeout)
- **Then**: Query resent, lastSentAt updated

---

## 5. Integration Tests

### 5.1 Basic Operations

**TC-INTEGRATION-001: Connect and disconnect**
- **Given**: RaftClient with cluster config
- **When**: connect() then disconnect()
- **Then**: Session created and closed cleanly

**TC-INTEGRATION-002: Submit command and receive response**
- **Given**: Connected RaftClient
- **When**: submitCommand(Buffer.from("test"))
- **Then**: Response received, promise resolved

**TC-INTEGRATION-003: Submit query and receive response**
- **Given**: Connected RaftClient
- **When**: query(Buffer.from("get:key"))
- **Then**: Response received, promise resolved

**TC-INTEGRATION-004: Multiple commands in sequence**
- **Given**: Connected RaftClient
- **When**: Submit 10 commands sequentially
- **Then**: All 10 responses received in order

**TC-INTEGRATION-005: Multiple queries in parallel**
- **Given**: Connected RaftClient
- **When**: Submit 10 queries concurrently
- **Then**: All 10 responses received (order not guaranteed)

### 5.2 Reconnection Scenarios

**TC-INTEGRATION-101: Reconnect after network failure**
- **Given**: Connected RaftClient, network drops
- **When**: Network restored
- **Then**: Client reconnects, pending requests complete

**TC-INTEGRATION-102: Leader change during operation**
- **Given**: Connected to follower node
- **When**: Server sends SessionClosed(NotLeaderAnymore) with leader hint
- **Then**: Client reconnects to leader, operations continue

**TC-INTEGRATION-103: Cluster election in progress**
- **Given**: All cluster members return NotLeader
- **When**: Client tries to connect
- **Then**: Client retries across members until leader elected

### 5.3 Error Scenarios

**TC-INTEGRATION-201: Session expiry**
- **Given**: Connected RaftClient, stop sending keep-alives
- **When**: Server times out session
- **Then**: Client receives SessionExpired, terminates, pending requests rejected

**TC-INTEGRATION-202: Invalid capabilities**
- **Given**: RaftClient with unsupported capabilities
- **When**: connect()
- **Then**: SessionRejected(InvalidCapabilities), connection fails

**TC-INTEGRATION-203: Request timeout and retry**
- **Given**: Connected RaftClient
- **When**: Submit command, server delays response beyond timeout
- **Then**: Client retries request, eventually receives response

### 5.4 Server-Initiated Requests

**TC-INTEGRATION-301: Receive and acknowledge server request**
- **Given**: Connected RaftClient
- **When**: Server sends ServerRequest
- **Then**: Client emits serverRequestReceived event, sends ack

**TC-INTEGRATION-302: Duplicate server request**
- **Given**: Connected RaftClient, already processed server request
- **When**: Server resends same ServerRequest
- **Then**: Client re-acks, does not re-emit event

---

## 6. Performance Tests

### 6.1 Throughput

**TC-PERF-001: 1000 req/sec sustained**
- **Given**: Connected RaftClient
- **When**: Submit 10,000 commands over 10 seconds
- **Then**: All commands complete, average throughput >= 1000 req/sec

**TC-PERF-002: 10,000 req/sec burst**
- **Given**: Connected RaftClient
- **When**: Submit 10,000 commands as fast as possible
- **Then**: Client handles burst without crashing, throughput >= 10K req/sec

**TC-PERF-003: Memory usage under load**
- **Given**: Connected RaftClient
- **When**: Submit 100,000 commands over 10 seconds
- **Then**: Memory usage remains stable, no leaks

### 6.2 Latency

**TC-PERF-101: P50 latency < 10ms**
- **Given**: Connected RaftClient
- **When**: Submit 1000 commands
- **Then**: 50th percentile latency < 10ms

**TC-PERF-102: P99 latency < 100ms**
- **Given**: Connected RaftClient
- **When**: Submit 1000 commands
- **Then**: 99th percentile latency < 100ms

---

## 7. Protocol Compatibility Tests

### 7.1 Interoperability with Scala Server

**TC-COMPAT-001: TypeScript client connects to Scala server**
- **Given**: Scala RaftServer running
- **When**: TypeScript RaftClient connects
- **Then**: Session created successfully

**TC-COMPAT-002: Submit command to Scala server**
- **Given**: TypeScript client connected to Scala server
- **When**: submitCommand(payload)
- **Then**: Response received and decoded correctly

**TC-COMPAT-003: Receive server request from Scala server**
- **Given**: TypeScript client connected to Scala server
- **When**: Scala server sends ServerRequest
- **Then**: TypeScript client decodes and acknowledges correctly

### 7.2 Golden File Tests

**TC-COMPAT-101: Decode Scala-encoded messages**
- **Given**: Golden files with Scala-encoded messages
- **When**: TypeScript client decodes
- **Then**: All messages decoded correctly

**TC-COMPAT-102: Encode messages matching Scala encoding**
- **Given**: Test messages
- **When**: TypeScript client encodes
- **Then**: Output matches Scala-encoded golden files (byte-for-byte)

---

## 8. Edge Case Tests

### 8.1 Boundary Values

**TC-EDGE-001: RequestId overflow (near MAX_SAFE_INTEGER)**
- **Given**: RequestId at Number.MAX_SAFE_INTEGER - 10
- **When**: Allocate 20 more request IDs
- **Then**: Handle gracefully (use bigint, no overflow)

**TC-EDGE-002: Empty payload**
- **Given**: ClientRequest with 0-byte payload
- **When**: Encode and decode
- **Then**: Successful roundtrip

**TC-EDGE-003: Maximum capabilities (1000 entries)**
- **Given**: CreateSession with 1000 capability entries
- **When**: Encode and send
- **Then**: Successful encoding, no truncation

### 8.2 Concurrent Operations

**TC-EDGE-101: Disconnect while requests pending**
- **Given**: Connected with 10 pending requests
- **When**: disconnect()
- **Then**: All pending requests rejected with "Disconnected" error

**TC-EDGE-102: Multiple concurrent submitCommand calls**
- **Given**: Connected RaftClient
- **When**: 100 concurrent submitCommand() calls
- **Then**: All assigned unique RequestIds, all complete correctly

---

## Summary

Total test cases: **100+**

Test categories:
- **Protocol Codecs**: 30 tests (encoding, decoding, roundtrip, edge cases)
- **State Machine**: 25 tests (state transitions, event handling)
- **Transport**: 6 tests (ZMQ socket operations)
- **Pending Tracking**: 9 tests (request/query lifecycle)
- **Integration**: 15 tests (end-to-end scenarios)
- **Performance**: 5 tests (throughput, latency)
- **Compatibility**: 5 tests (interop with Scala server)
- **Edge Cases**: 5 tests (boundary values, concurrency)

All tests aim to verify:
1. Protocol compatibility with Scala implementation
2. Correct state machine behavior
3. Performance requirements (1K-10K req/sec)
4. Resilience (reconnection, retry, error handling)
5. Type safety and API correctness
