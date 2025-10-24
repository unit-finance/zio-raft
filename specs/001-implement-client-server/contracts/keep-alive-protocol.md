# Keep-Alive Protocol API Contract

**Feature**: Client-Server Communication for Raft Protocol  
**Contract Type**: Connection Health Monitoring  
**Version**: 1.0  

## Overview
Defines the protocol contract for bidirectional keep-alive (heartbeat) messages between clients and servers to detect connection failures and maintain session health.

## Message Definitions

### Client → Server: KeepAlive

**Purpose**: Periodic heartbeat message to indicate client health and maintain session

**Message Structure**:
```scala
case class KeepAlive(
  timestamp: Instant           // Client-side timestamp
)
```

**Validation Rules**:
- `timestamp` must be recent (within clock skew tolerance)
- Server derives session ID from ZeroMQ routing ID using `routingIdToSession` mapping

**Notes**:
- No `sessionId` field - server derives session ID from ZeroMQ routing ID using `routingIdToSession` mapping
- Simplified design focuses only on liveness detection
- Client processes work asynchronously, no status reporting needed

### Server → Client: KeepAliveResponse

**Purpose**: Acknowledge client heartbeat to confirm server liveness

**Message Structure**:
```scala
case class KeepAliveResponse(
  timestamp: Instant           // Echo of client's timestamp
)
```

**Validation Rules**:
- `timestamp` should echo the exact timestamp from client's KeepAlive message
- Server responds to same ZeroMQ routing ID (no `sessionId` needed)

**Notes**:
- Server echoes client timestamp to allow client to drop stale responses
- Enables client to measure round-trip time and detect out-of-order responses
- Client can ignore responses with timestamps older than expected

## Protocol Flow Examples

### Normal Heartbeat Exchange
```
Client → Server: KeepAlive(timestamp=T1)
Server → Client: KeepAliveResponse(timestamp=T1)  // Echo client's timestamp
[Server updates expiredAt = now() + timeoutDuration for this session locally]
[Client validates response timestamp matches T1, measures RTT = now() - T1]
```

### Stale Response Handling
```
Client → Server: KeepAlive(timestamp=T1)
Client → Server: KeepAlive(timestamp=T2)  // New heartbeat before response
Server → Client: KeepAliveResponse(timestamp=T1)  // Delayed response
Server → Client: KeepAliveResponse(timestamp=T2)  // Current response
[Client drops T1 response as stale, processes T2 response]
```

### Missed Heartbeat Detection (Timeout Expiration)
```
[Client fails to send heartbeat within expected interval]
Server: CleanupAction (from ZStream.tick(1.second)) detects expiredAt < current time
Server: Immediately removes session from local state
Server: Triggers ExpireSessionAction to Raft for consistency
[Session cannot be continued - client must create new session]
```

### TCP Connection Drop Detection
```
Server: Receives ZeroMQ DisconnectMessage for routing ID
Server: Marks session ConnectionState as Disconnected
Server: Removes routingIdToSession mapping for dropped connection
[Session can be continued when client reconnects]
```

### Leader Change Handling
```
Server: Becomes new leader
Server: Marks all existing sessions as Disconnected
Server: Clears all routingIdToSession mappings
Server: Initializes expiredAt for all sessions = current time + extended grace period
[Clients will reconnect and continue sessions]
```

## Timing Requirements

### Heartbeat Intervals
- **Client Interval**: 30 seconds (configurable, range: 10s-120s)
- **Server Timeout**: 90 seconds (3x client interval, configurable)
- **Cleanup Interval**: 1 second (fixed - ZStream.tick triggers CleanupAction to check for expired sessions)
- **Warning Threshold**: 75 seconds (85% of timeout)
- **Clock Skew Tolerance**: ±10 seconds

### Timing Flow
```
T0: Client sends heartbeat (expiredAt = T0+90s)
T0+30s: Client sends next heartbeat (expiredAt = T0+120s)
T0+60s: Client sends next heartbeat (expiredAt = T0+150s)
[Client stops sending heartbeats]
T0+150s: CleanupAction detects expiredAt < current time
T0+150s: Session immediately removed + ExpireSessionAction sent to Raft
```

### Adaptive Intervals
- Busy clients may increase heartbeat frequency
- Idle clients may use standard interval
- Network quality may influence timing parameters

## Error Handling

### Network Failures

#### Client-Side Detection
**Client Response**:
1. Detect missing KeepAliveResponse within timeout period
2. Attempt immediate reconnection
3. Use exponential backoff for retries
4. Consider session continuation if reconnection succeeds

#### Server-Side Detection
**Heartbeat Timeout (Expiration)**:
1. CleanupAction (from ZStream.tick(1.second)) triggers session expiration check
2. For each session where current time > expiredAt:
   - Immediately remove session from local sessions map
   - Remove from local routingIdToSession mapping  
   - Trigger ExpireSessionAction to Raft state machine for consistency

**TCP Connection Drop**:
1. Receive ZeroMQ DisconnectMessage for routing ID
2. Mark session ConnectionState as Disconnected (locally)
3. Remove routingIdToSession mapping for dropped routing ID
4. Session remains in Raft state - can be continued on reconnection

**Leader Change**:
1. Server becomes leader
2. Mark all sessions as Disconnected (locally)
3. Clear all routingIdToSession mappings
4. Initialize expiredAt for all sessions with extended grace period

**Note**: Keep-alive tracking is server-local only - no Raft writes for heartbeat updates

### Periodic Session Cleanup
**Implementation Requirement**:
- Server MUST implement periodic cleanup using `ZStream.tick(1.second)` to produce `CleanupAction` events
- Merge cleanup stream with ZeroMQ message stream (mapped to `MessageAction`) into unified action stream
- On `CleanupAction`, scan all sessions in local `sessions` map for expiration
- For sessions where `current time > expiredAt`, immediately:
  1. Remove session from `sessions` map
  2. Remove from `routingIdToSession` map
  3. Send `ExpireSessionAction` to Raft state machine
- This provides reactive, unified action processing for both messages and cleanup

### Clock Synchronization Issues

#### Clock Skew Detection
- Client compares sent timestamp with echoed timestamp for round-trip time
- Server validates client timestamp is within reasonable skew tolerance
- No server timestamp comparison needed since server echoes client time

#### Timestamp Validation
- Reject heartbeats with future timestamps (beyond skew tolerance)
- Reject heartbeats with very old timestamps
- Log clock synchronization warnings

## Session State Management

### Heartbeat Impact on Session Lifecycle
```
Session Created → ConnectionState.Connected(expiredAt = now + timeout)
Connected → Heartbeat Received → expiredAt = now + timeout  
Connected → expiredAt passes → Immediately removed from local state + ExpireSessionAction to Raft
Connected → TCP Drop/Leader Change → ConnectionState.Disconnected
Disconnected → Session Continuation → Connected(expiredAt = now + timeout)
Disconnected → expiredAt passes → Immediately removed from local state + ExpireSessionAction to Raft
Expired session continuation attempt → SessionRejected(SessionNotFound) 
```

### Leader Change Handling
- Simple heartbeat acknowledgment - no leader information in responses
- Clients detect leader changes through session operations (not heartbeats)
- Session state preserved during leader transitions via Raft replication
- New leader initializes keep-alive tracking from Raft session list
- All existing sessions get expiredAt = current time + extended grace period

### Reconnection Protocol
1. Client detects heartbeat timeout
2. Client attempts session continuation
3. Server validates session state
4. Resume heartbeat protocol if successful

## Performance Requirements

### Latency Targets
- Heartbeat response: <100ms under normal conditions
- Timeout detection: Within 1 heartbeat interval
- Session cleanup: <30 seconds after timeout

### Throughput Targets
- Support 10,000+ concurrent client heartbeats
- Handle heartbeat bursts during reconnection scenarios
- Maintain performance during leader elections
- CleanupAction processing (1-second interval) must handle large session counts efficiently

### Resource Usage
- Minimal CPU overhead for heartbeat processing
- Efficient storage of session heartbeat state
- Bounded memory usage for timeout tracking

## Monitoring and Observability

### Health Metrics
- Active session count by node
- Heartbeat success/failure rates
- Session timeout frequencies
- Network latency distributions

### Alerting Conditions
- High session timeout rates
- Excessive heartbeat failures
- Clock skew beyond tolerance
- Resource exhaustion indicators

### Debugging Information
```scala
case class HeartbeatDiagnostics(
  sessionId: SessionId,
  lastHeartbeats: List[Instant],
  currentExpiredAt: Instant,
  averageRoundTripTime: Duration,
  lastRoundTripTime: Duration,
  droppedStaleResponses: Int
)
```

## Security Considerations

### Heartbeat Authentication
- Heartbeats associated with authenticated sessions via ZeroMQ routing ID lookup to session ID
- Server validates routing ID exists in `routingIdToSession` mapping and corresponds to active session
- Rate limiting prevents heartbeat flooding attacks

### Information Disclosure
- No cluster or session status in heartbeat messages
- Only timestamps exchanged - minimal information disclosure
- Simple acknowledgment pattern prevents information leakage

---
*Contract version 1.0 - 2025-09-24*
