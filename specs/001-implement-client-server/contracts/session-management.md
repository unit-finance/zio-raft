# Session Management API Contract

**Feature**: Client-Server Communication for Raft Protocol  
**Contract Type**: Session Lifecycle Management  
**Version**: 1.0  

## Overview
Defines the protocol contract for creating, continuing, and managing durable client sessions that persist across leader changes and network failures.

## Message Definitions

### Client → Server: CreateSession

**Purpose**: Create a new durable session with the Raft cluster

**Message Structure**:
```scala
case class CreateSession(
  capabilities: Map[String, String], // Client capability definitions (name -> version/config)
  nonce: Long                        // Client-generated nonce for response correlation
)
```

**Validation Rules**:
- `capabilities` must be non-empty map
- `nonce` must be non-zero value for response correlation
- Server derives client identity from ZeroMQ routing ID
- Server accepts capabilities as declared by client without validation

**Capability Examples**:
```scala
Map(
  "worker" -> "v1.2",           // Worker capability with version
  "queue-processor" -> "batch", // Queue processing with batch mode
  "priority" -> "high"          // Priority level configuration
)
```

**Expected Responses**:
- `SessionCreated` - Success case with generated session ID
- `SessionRejected` - Failure case with rejection reason

### Server → Client: SessionCreated

**Purpose**: Confirm successful session creation with server-generated session ID

**Message Structure**:
```scala
case class SessionCreated(
  sessionId: SessionId,        // Server-generated unique session identifier
  nonce: Long                  // Echo of client nonce for correlation
)
```

**Validation Rules**:
- `sessionId` must be unique UUID string
- `nonce` must match client request nonce
- Server responds to same ZeroMQ routing ID that initiated request

**Notes**:
- Simplified response structure without complex cluster information
- Client discovers cluster topology through other mechanisms if needed

### Client → Server: ContinueSession

**Purpose**: Resume an existing session after disconnection or leader change

**Message Structure**:
```scala
case class ContinueSession(
  sessionId: SessionId,        // Previously assigned session identifier
  nonce: Long                  // Client-generated nonce for response correlation
)
```

**Validation Rules**:
- `sessionId` must be valid UUID format
- Session must not be expired or explicitly terminated
- Server updates `routingIdToSession` mapping with new routing ID

**Expected Responses**:
- `SessionContinued` - Success case confirming session resumption
- `SessionRejected` - Failure case (expired, not found, not leader, etc.)

**Notes**:
- This is the only client message that includes `sessionId` (needed for reconnection)
- Server maps new ZeroMQ routing ID to existing session ID
- Removes old routing ID mapping if client reconnected from different connection

### Server → Client: SessionRejected

**Purpose**: Indicate session creation or continuation failure

**Message Structure**:
```scala
case class SessionRejected(
  reason: RejectionReason,     // Specific reason for rejection
  leaderId: Option[MemberId]   // Current leader for redirection (if applicable)
)
```

**Rejection Reasons**:
- `NotLeader` - Server is not the current leader
- `SessionNotFound` - Session ID not found in cluster (includes expired sessions)
- `ClusterUnavailable` - Cluster lacks quorum or is undergoing election

**Notes**:
- No `sessionId` field - server responds to same ZeroMQ routing ID
- Simplified structure focuses on essential rejection information
- `leaderId` provided for `NotLeader` rejections to enable client redirection

### Server → Client: SessionContinued

**Purpose**: Confirm successful session continuation after reconnection

**Message Structure**:
```scala
case class SessionContinued(
  nonce: Long                  // Echo of client nonce for correlation
)
```

**Validation Rules**:
- `nonce` must match client request nonce
- Server responds to same ZeroMQ routing ID that initiated request

**Notes**:
- No `sessionId` field - server responds to same ZeroMQ routing ID
- Handled locally by server without Raft state machine interaction
- Server updates local routing ID mapping and connection state

## Protocol Flow Examples

### Successful Session Creation
```
Client → Server: CreateSession(capabilities=Map("worker" -> "v1.2", "priority" -> "high"), nonce=12345)
Server: [Check if leader, forward CreateSessionAction to Raft state machine via action stream]
Server: [Create sessions(sessionId) = Connected(routingId, expiredAt), routingIdToSession += routingId → sessionId]
Server → Client: SessionCreated(sessionId="uuid-123", nonce=12345)
```

### Session Continuation After Disconnect
```
Client → Server: ContinueSession(sessionId="uuid-123", nonce=67890)
Server: [Check if leader, handle locally - no Raft forwarding needed]
Server: [Update routingIdToSession mapping: remove old, add routingId → sessionId]
Server: [Update sessions(sessionId): Disconnected → Connected(routingId, expiredAt = now + timeout)]
Server → Client: SessionContinued(nonce=67890)
```

### Session Rejection (Not Leader)
```
Client → Server: CreateSession(capabilities=Map("worker" -> "v1.0"), nonce=11111)
Server → Client: SessionRejected(reason=NotLeader, leaderId=Some("node-2"))
```

### Session Rejection (Not Found - Expired)
```
Client → Server: ContinueSession(sessionId="uuid-123", nonce=22222)
Server → Client: SessionRejected(reason=SessionNotFound, leaderId=None)
[Session was expired and immediately removed from server state]
```

## Error Handling

### Network Failures
- Client must retry session creation/continuation with exponential backoff
- Server must handle duplicate session creation attempts idempotently
- Session state must survive leader changes through Raft replication

### Leader Changes
- Server monitors Raft state and rejects operations when not leader
- Create Session requests to non-leader return `SessionRejected(reason=NotLeader, leaderId=...)`
- Continue Session requests to non-leader return `SessionRejected(reason=NotLeader, leaderId=...)`  
- Client must retry to current leader using provided `leaderId`
- Session state is preserved during leader transitions via Raft replication
- New leader rebuilds `routingIdToSession` mappings as clients reconnect with ContinueSession

### Session Timeouts
- Server tracks session liveness via keep-alive messages using `expiredAt` timestamps
- Sessions with `expiredAt < current time` are marked as expired
- Expired sessions are cleaned up from both Raft state and local mappings
- Clients must create new sessions after expiration (cannot continue expired sessions)

## Security Considerations

### Session ID Security
- Session IDs must be cryptographically secure UUIDs
- Session hijacking prevented through ZeroMQ routing ID to session ID mapping correlation
- No session enumeration attacks possible

### Capability Management
- Server stores client capability declarations as-is without validation
- Client is responsible for declaring accurate and supported capabilities
- Capability changes not permitted after session creation (requires new session)
- Server uses capability declarations for work dispatch routing decisions

## Performance Requirements

### Latency Targets
- Session creation: <100ms under normal conditions (includes Raft forwarding)
- Session continuation: <50ms under normal conditions (local operation)
- Response to invalid session: <10ms

### Throughput Targets
- Support 1000+ concurrent sessions per server node
- Handle 100+ session creations per second
- Maintain performance during leader elections
- Efficient routing ID to session ID mapping lookups

---
*Contract version 1.0 - 2025-09-24*
