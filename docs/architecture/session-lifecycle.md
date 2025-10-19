# Session Lifecycle Architecture

**Module**: client-server-server  
**Component**: Session Management  
**Last Updated**: 2025-10-19 (PR #15)

---

## Overview

The ZIO Raft client-server protocol implements **durable sessions** that survive network failures, leader changes, and client reconnections. This document describes the complete session lifecycle and resource management strategy.

---

## Session States

```
┌─────────────────────────────────────────────────────────────┐
│                    Session Lifecycle                        │
└─────────────────────────────────────────────────────────────┘

    CreateSession
         │
         ▼
    ┌─────────┐
    │ PENDING │ (waiting for Raft confirmation)
    └─────────┘
         │ SessionCreationConfirmed
         ▼
    ┌───────────┐
    │ CONNECTED │ ◄──────────┐ ContinueSession
    └───────────┘            │
         │                   │
         │ ConnectionClosed  │
         ▼                   │
    ┌──────────────┐         │
    │ DISCONNECTED │─────────┘
    └──────────────┘
         │
         │ CloseSession OR Timeout
         ▼
    ┌──────────┐
    │ EXPIRED  │ (permanently removed)
    └──────────┘
```

---

## Message Semantics

### **Client → Server Messages**

#### `CreateSession(capabilities, nonce)`
- **Purpose**: Initiate new durable session
- **State Change**: None → PENDING
- **Resources Created**: 
  - Session metadata
  - Pending session record
  - Routing ID mapping
- **Raft Notification**: Yes (RaftAction.CreateSession)

#### `ContinueSession(sessionId, nonce)`
- **Purpose**: Resume existing session after disconnect
- **State Change**: DISCONNECTED → CONNECTED
- **Resources Updated**:
  - Routing ID mapping (new routing)
  - Connection state (new expiresAt)
- **Resource Cleanup**:
  - OLD routing ID (transport.disconnect)
  - OLD routing mapping (removed from routingToSession)
- **Raft Notification**: No (session already in Raft state)

#### `ConnectionClosed`
- **Purpose**: Notify server of OS/TCP level disconnect
- **State Change**: CONNECTED → DISCONNECTED
- **Resources Preserved**:
  - ✅ Session metadata (can reconnect)
  - ✅ Session timeout (will expire if not reconnected)
- **Resources Cleared**:
  - ❌ Routing ID mapping
  - ❌ Active routing ID in connection
- **Raft Notification**: No (session still valid)
- **Recovery**: Client can send `ContinueSession` to reconnect

#### `CloseSession(reason)`
- **Purpose**: Explicitly terminate session (permanent)
- **State Change**: ANY → EXPIRED
- **Resources Removed**: ALL
  - ❌ Session metadata
  - ❌ Connection state
  - ❌ Routing mappings
  - ❌ Pending session records
- **Raft Notification**: Yes (RaftAction.ExpireSession)
- **Recovery**: Cannot reconnect (session gone)

---

## Resource Management Matrix

| Message | Transport Disconnect | Routing Map | Metadata | Connection | Raft Notify | Reconnectable |
|---------|---------------------|-------------|----------|------------|-------------|---------------|
| **CreateSession** | - | Add | Add (pending) | Add | Yes (Create) | N/A |
| **ContinueSession** | Old (if exists) | Update | Keep | Update | No | N/A |
| **ConnectionClosed** | Already gone | Remove | Keep | Mark disconnected | No | ✅ Yes |
| **CloseSession** | Yes | Remove | Remove | Remove | Yes (Expire) | ❌ No |
| **Timeout Cleanup** | Yes | Remove | Remove | Remove | Yes (Expire) | ❌ No |

---

## Implementation Details

### Sessions Data Structure

```scala
case class Sessions(
  metadata: Map[SessionId, SessionMetadata],           // Session info
  connections: Map[SessionId, SessionConnection],      // Connection state + timeout
  routingToSession: Map[RoutingId, SessionId],        // Routing lookup
  pendingSessions: Map[SessionId, PendingSession]     // Awaiting Raft confirm
)
```

### Key Methods

#### `disconnect(sessionId, routingId): Sessions`
**Purpose**: Soft disconnect (keep session, clear routing)
```scala
def disconnect(sessionId: SessionId, routingId: RoutingId): Sessions =
  copy(
    connections = connections.updatedWith(sessionId)(
      _.map(_.copy(routingId = None))  // Clear routing, keep timeout
    ),
    routingToSession = routingToSession.removed(routingId)
  )
```

#### `removeSession(sessionId, routingId): Sessions`
**Purpose**: Hard delete (remove everything)
```scala
def removeSession(sessionId: SessionId, routingId: RoutingId): Sessions =
  copy(
    metadata = metadata.removed(sessionId),
    connections = connections.removed(sessionId),
    routingToSession = routingToSession.removed(routingId),
    pendingSessions = pendingSessions.removed(sessionId)
  )
```

#### `reconnect(sessionId, routingId, now, config): Sessions`
**Purpose**: Reconnect with new routing ID
```scala
def reconnect(sessionId: SessionId, routingId: RoutingId, now: Instant, config: ServerConfig): Sessions = {
  connections.get(sessionId) match {
    case Some(conn) =>
      val expiresAt = now.plus(config.sessionTimeout)
      // Clean up old routing mapping
      val cleanedRouting = conn.routingId
        .map(old => routingToSession.removed(old))
        .getOrElse(routingToSession)
      copy(
        connections = connections.updated(sessionId, 
          conn.copy(routingId = Some(routingId), expiresAt = expiresAt)),
        routingToSession = cleanedRouting.updated(routingId, sessionId)
      )
  }
}
```

---

## Reconnection Flow Detail

### Scenario: Client Reconnects After Network Failure

```
1. Client had: routingId = [0x01, 0x02, 0x03]
2. Network fails
3. Client reconnects with: routingId = [0x04, 0x05, 0x06]
4. Client sends: ContinueSession(sessionId, newNonce)

Server Processing:
┌─────────────────────────────────────────────────────────┐
│ 1. Lookup session: sessions.getMetadata(sessionId)     │
│    ✅ Found: Session exists, can reconnect              │
├─────────────────────────────────────────────────────────┤
│ 2. Get old routing: sessions.getRoutingId(sessionId)   │
│    Result: Some([0x01, 0x02, 0x03])                    │
├─────────────────────────────────────────────────────────┤
│ 3. Check if different: old != new?                     │
│    ✅ True: [0x01...] != [0x04...]                     │
├─────────────────────────────────────────────────────────┤
│ 4. Cleanup old transport:                              │
│    transport.disconnect([0x01, 0x02, 0x03])            │
│    └─> Closes old TCP/ZMQ connection                   │
├─────────────────────────────────────────────────────────┤
│ 5. Send response to NEW routing:                       │
│    transport.sendMessage([0x04...], SessionContinued)  │
├─────────────────────────────────────────────────────────┤
│ 6. Update session state:                               │
│    sessions.reconnect(sessionId, [0x04...], now)       │
│    ├─> Remove old mapping: [0x01...] → sessionId       │
│    ├─> Add new mapping: [0x04...] → sessionId          │
│    ├─> Update connection: routingId = Some([0x04...])  │
│    └─> Refresh timeout: expiresAt = now + timeout      │
└─────────────────────────────────────────────────────────┘

Result:
✅ Old connection closed (no resource leak)
✅ New connection established
✅ Session metadata preserved
✅ Timeout refreshed
```

---

## Timeout and Expiration

### Timeout Tracking

Each connected/disconnected session has an `expiresAt: Instant`:
- Updated on: CreateSession, ContinueSession, KeepAlive
- Checked by: Periodic cleanup tick (every `config.cleanupInterval`)
- Action: Remove expired sessions and notify Raft

### Cleanup Flow

```scala
case StreamEvent.CleanupTick =>
  for {
    now <- Clock.instant
    (expiredIds, newSessions) = sessions.removeExpired(now)
    _ <- ZIO.foreachDiscard(expiredIds) { sessionId =>
      for {
        // 1. Notify Raft to remove from replicated state
        _ <- raftActionsOut.offer(RaftAction.ExpireSession(sessionId))
        
        // 2. Notify client if still connected
        routingIdOpt = sessions.getRoutingId(sessionId)
        _ <- routingIdOpt match {
          case Some(routingId) =>
            transport.sendMessage(routingId, 
              SessionClosed(SessionTimeout, None)).orDie
          case None => ZIO.unit
        }
      } yield ()
    }
  } yield copy(sessions = newSessions)
```

---

## Edge Cases Handled

### 1. **Duplicate ContinueSession (Same Routing ID)**
```scala
oldRoutingId == newRoutingId
```
**Handling**: Skip disconnect, just refresh timeout (idempotent)

### 2. **Concurrent Connections to Same Session**
```scala
Client A: routingId = [0x01...]
Client B: routingId = [0x02...]
Both try ContinueSession(sessionId, ...)
```
**Handling**: Last one wins, previous connection gets disconnected

### 3. **ContinueSession for Non-Existent Session**
```scala
sessions.getMetadata(sessionId) = None
```
**Handling**: Send `SessionRejected(SessionNotFound, nonce, None)`

### 4. **ConnectionClosed for Unknown Session**
```scala
sessions.findSessionByRouting(routingId) = None
```
**Handling**: Log and ignore (already cleaned up or never existed)

---

## Performance Considerations

### Memory Footprint Per Session

```scala
SessionMetadata:     ~100 bytes (capabilities map)
SessionConnection:   ~50 bytes (routing + instant)
PendingSession:      ~150 bytes (routing + nonce + capabilities + instant)
Routing Mapping:     ~40 bytes (routing → sessionId)

Total (Connected):   ~190 bytes/session
Total (Pending):     ~340 bytes/session
```

### Cleanup Efficiency

- **Periodic cleanup**: O(n) where n = number of sessions
- **Reconnection**: O(1) lookup + O(1) map updates
- **Removal**: O(1) for each map operation

---

## Testing Recommendations

### Unit Tests Should Cover:
- [ ] Session creation → pending → connected flow
- [ ] Reconnection with same routing ID (idempotent)
- [ ] Reconnection with different routing ID (cleanup old)
- [ ] Disconnection preserves metadata
- [ ] Close removes all state and notifies Raft
- [ ] Timeout cleanup removes expired sessions
- [ ] Concurrent reconnections (race conditions)

### Integration Tests Should Cover:
- [ ] Session survives leader change
- [ ] Session reconnection after network partition
- [ ] Multiple clients reconnecting simultaneously
- [ ] Session expiry during disconnection

---

## Metrics and Monitoring

Recommended metrics to track:
- Active sessions count (gauge)
- Pending sessions count (gauge)
- Disconnected sessions count (gauge)
- Session creation rate (counter)
- Session reconnection rate (counter)
- Session close rate (counter)
- Session expiry rate (counter)
- Cleanup duration (histogram)

---

## Future Enhancements

Potential improvements for production:
1. **Session persistence**: Store session state in Raft for crash recovery
2. **Session migration**: Move sessions between servers on rebalancing
3. **Connection pooling**: Reuse routing IDs when possible
4. **Graceful draining**: Reject new sessions before shutdown
5. **Metrics integration**: Expose session lifecycle metrics

---

*Architecture Documentation - Session Lifecycle*  
*Based on implementation in PR #15*  
*Follows Constitution v1.0.0 principles*

