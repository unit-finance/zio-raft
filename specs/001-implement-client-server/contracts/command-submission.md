# Client Request API Contract

**Feature**: Client-Server Communication for Raft Protocol  
**Contract Type**: Client Request Submission and Execution  
**Version**: 1.0  

## Overview
Defines the protocol contract for submitting client requests (reads and writes) to the Raft cluster, ensuring linearizable execution and proper error handling.

## Message Definitions

### Client → Server: ClientRequest

**Purpose**: Submit a request (read or write) for execution by the Raft state machine

**Message Structure**:
```scala
case class ClientRequest(
  requestId: RequestId,        // Unique request identifier for idempotency
  payload: ByteVector,         // Request payload (command or query)
  createdAt: Instant           // Request creation timestamp
)
```

**Validation Rules**:
- `requestId` must be unique within session scope
- `payload` must be valid for current state machine
- Server derives session ID from ZeroMQ routing ID using `routingIdToSession` mapping

**Expected Responses**:
- `ClientResponse` - Success or failure with execution result
- `RequestError` - Request processing error with optional leader redirection
- `SessionRejected` - Invalid or expired session

### Server → Client: ClientResponse

**Purpose**: Return result of request execution

**Message Structure**:
```scala
case class ClientResponse(
  requestId: RequestId,        // Echo of client request ID
  result: ByteVector           // Execution result data
)
```

**Notes**:
- Simplified response structure with direct result data
- Errors handled through separate error response messages if needed
- No `sessionId` field - server responds to same ZeroMQ routing ID
- Simplified structure without internal timing/index details

**Request Types**:
- Both read and write operations use the same `ClientRequest` message structure
- Client library can distinguish request types internally but wire protocol is unified
- Server processes requests based on payload content

## Protocol Flow Examples

### Successful Request Submission
```
Client → Server: ClientRequest(requestId=1, payload=..., createdAt=T1)
Server: [Derive sessionId from routing ID, check if leader, forward to Raft state machine via action stream]
Server → Client: ClientResponse(requestId=1, result=responseData)
```

### Request Submission to Non-Leader
```
Client → Server: ClientRequest(requestId=2, payload=..., createdAt=T2)
Server → Client: RequestError(reason=NotLeader, leaderId=Some("node-2"))
Client → NewLeader: ClientRequest(requestId=2, payload=..., createdAt=T2)
NewLeader → Client: ClientResponse(requestId=2, result=responseData)
```

### Read Operation
```
Client → Server: ClientRequest(requestId=3, payload=readQuery..., createdAt=T3)
Server → Client: ClientResponse(requestId=3, result=readData)
```

### Request Execution Error (via separate error message)
```
Client → Server: ClientRequest(requestId=4, payload=..., createdAt=T4)
Server → Client: RequestError(reason=InvalidRequest, leaderId=None)
```

## Idempotency Guarantees

### Request Deduplication
- Server maintains cache of recent requests per session
- Duplicate requests return cached response without re-execution
- Cache size and retention configurable per deployment

### Retry Behavior
- Client must retry requests that timeout without response
- Same `requestId` must be used for retry attempts
- Server detects retries and returns cached result if already executed
- Client library handles retry logic and timeout configuration

### Client Connection State Management
**Request Queuing Behavior**:
- **Connecting State**: Queue requests locally, do not send to server
- **Connected State**: Send requests immediately to server
- **Disconnected State**: Queue requests locally, do not send to server

**State Transition Handling**:
- **Connecting → Connected**: Resend all queued pending requests
- **Connected → Connecting**: Retain all pending requests in queue  
- **Connected → Disconnected**: Error all pending requests with connection failure
- **Disconnected → Connecting**: Begin connection establishment, retain queued requests

## Error Handling

### Leader Redirection
- Server checks Raft state before processing requests
- Non-leader nodes return `RequestError(reason=NotLeader, leaderId=Some(...))` with current leader information
- Client must retry to leader node
- Leader information may be stale; client handles subsequent redirections
- Requests forwarded to Raft state machine via action stream on leader

### Network Failures
- Client implements exponential backoff for retries
- Requests may be executed even if response is lost
- Idempotency prevents duplicate execution on retry

### Session Errors
- Requests from expired/invalid sessions return `SessionRejected(reason=SessionNotFound, leaderId=...)`
- Client must establish new session before retrying
- Session validation performed by deriving session from routing ID mapping

## Linearizability Guarantees

### Read Operations
- Read requests return data that reflects all previously committed operations
- Reads are consistent with the linearizable order of operations
- Simplified response structure focuses on result data

### Write Operations
- Commands are executed in strict Raft log order
- Committed commands are durable and replicated
- Response indicates successful commitment and execution
- Internal Raft details abstracted from client protocol

## Performance Requirements

### Latency Targets
- Request execution: <200ms for local operations
- Read requests: <50ms for cached data
- Leader redirection: <10ms response time

### Throughput Targets
- Support 1000+ requests per second per node
- Handle 100+ concurrent request submissions
- Maintain performance during leader elections

### Resource Limits
- Maximum request payload size: 1MB
- Maximum concurrent requests per session: 100
- Timeout configuration managed by client library

---
*Contract version 1.0 - 2025-09-24*
