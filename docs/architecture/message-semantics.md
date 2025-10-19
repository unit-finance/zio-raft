# Protocol Message Semantics

**Module**: client-server-protocol  
**Component**: Message Definitions  
**Last Updated**: 2025-10-19 (PR #15)

---

## Overview

This document defines the **precise semantics** of each protocol message, including resource lifecycle implications and recovery scenarios. Understanding these semantics is critical for correct implementation.

---

## Critical Semantic Distinctions

### 1. **CloseSession vs ConnectionClosed**

These two messages have fundamentally different semantics:

| Aspect | `CloseSession` | `ConnectionClosed` |
|--------|----------------|-------------------|
| **Direction** | Client → Server | Client → Server |
| **Meaning** | "I'm done, delete my session" | "My connection dropped, keep my session" |
| **Intent** | Permanent termination | Temporary disconnect |
| **Session After** | ❌ Deleted | ✅ Preserved |
| **Can Reconnect** | ❌ No | ✅ Yes (via ContinueSession) |
| **Raft Notified** | ✅ Yes (ExpireSession) | ❌ No |
| **Use Case** | Logout, shutdown | Network failure, TCP reset |

#### Example Scenarios

**CloseSession**:
```
User logs out of application
→ Client sends: CloseSession(ClientShutdown)
→ Server removes all session state
→ Server notifies Raft to remove from cluster state
→ Client CANNOT reconnect with this session ID
```

**ConnectionClosed**:
```
Network cable unplugged
→ Client detects socket error
→ Client sends: ConnectionClosed
→ Server marks session as disconnected (keeps metadata)
→ Client reconnects when network restored
→ Client sends: ContinueSession(sessionId, nonce)
→ Server validates and reconnects same session
```

---

## Message Direction Reference

### **Client → Server (ClientMessage)**

| Message | Purpose | State Impact | Raft Notified |
|---------|---------|--------------|---------------|
| `CreateSession` | Create new session | Add pending session | Yes (CreateSession) |
| `ContinueSession` | Resume existing session | Reconnect | No |
| `KeepAlive` | Maintain session liveness | Refresh timeout | No |
| `ClientRequest` | Submit command/query | Queue for Raft | Yes (ClientRequest) |
| `ServerRequestAck` | Acknowledge server work | Track ack | Yes (ServerRequestAck) |
| `CloseSession` | **Permanent** termination | **Remove all** state | **Yes (ExpireSession)** |
| `ConnectionClosed` | **Temporary** disconnect | **Disconnect routing** | **No** |

### **Server → Client (ServerMessage)**

| Message | Purpose | When Sent | Client Action |
|---------|---------|-----------|---------------|
| `SessionCreated` | Session confirmed | After Raft commits CreateSession | Store sessionId, transition to Connected |
| `SessionContinued` | Reconnection successful | After validating ContinueSession | Transition to Connected |
| `SessionRejected` | Session operation denied | Not leader / session not found | Retry with leader / create new |
| `SessionClosed` | Server closing session | Shutdown / lost leadership / error | Reconnect or create new |
| `KeepAliveResponse` | Heartbeat ack | Response to KeepAlive | Measure RTT, update timeout |
| `ClientResponse` | Request result | After Raft executes request | Deliver to application |
| `ServerRequest` | Server-initiated work | Server dispatching work | Process and send ServerRequestAck |

---

## State Transition Semantics

### From PENDING to CONNECTED

```
Client: CreateSession(capabilities, nonce1)
Server: [Queues RaftAction.CreateSession]
        [Adds to pendingSessions]
        
Raft:   [Replicates session creation]
        [Calls server.confirmSessionCreation(sessionId)]
        
Server: [Queues SessionCreationConfirmed action]
        [Stream processes action]
        [Moves pending → connected]
        [Sends SessionCreated(sessionId, nonce1)]
        
Client: [Receives SessionCreated]
        [Stores sessionId]
        [Transitions to Connected state]
```

**Key Points**:
- Session creation is asynchronous (wait for Raft)
- Server must track pending sessions separately
- Nonce correlation ensures response matches request

### From CONNECTED to DISCONNECTED to CONNECTED

```
Client: [Network failure detected]
        ConnectionClosed
        
Server: [Receives ConnectionClosed]
        [Marks session as disconnected: routingId = None]
        [Keeps metadata and timeout]
        [Starts expiration countdown]
        
Client: [Network restored]
        ContinueSession(sessionId, nonce2)
        
Server: [Looks up old routing ID]
        [Disconnects old transport if exists]
        [Sends SessionContinued(nonce2)]
        [Updates routing maps]
        [Refreshes timeout]
        
Client: [Receives SessionContinued]
        [Resumes normal operation]
```

**Key Points**:
- Disconnection preserves session for reconnection window
- Old routing must be cleaned up before reconnecting
- Timeout continues during disconnection
- Will expire if not reconnected in time

### From ANY to EXPIRED

```
Scenario 1: Explicit Close
Client: CloseSession(ClientShutdown)
Server: [Removes all session state]
        [Notifies Raft: ExpireSession]
        
Scenario 2: Timeout
Server: [Cleanup tick detects expiry]
        [Removes all session state]
        [Notifies Raft: ExpireSession]
        [Sends SessionClosed(SessionTimeout) if connected]
```

---

## Recovery Scenarios

### Network Partition

```
1. Client-Server connection lost
2. Client sends ConnectionClosed (if detectable)
3. Session enters DISCONNECTED state
4. Partition heals
5. Client sends ContinueSession
6. Server reconnects if within timeout window
7. Otherwise: SessionRejected(SessionNotFound)
```

### Leader Change

```
1. Server loses leadership
2. Server sends SessionClosed(NotLeaderAnymore, Some(newLeaderId))
3. Client connects to new leader
4. Client sends ContinueSession(sessionId, nonce)
5. New leader validates session (from Raft state)
6. New leader reconnects session
```

### Client Crash and Restart

```
Option 1: Client has persisted sessionId
1. Client restarts
2. Client sends ContinueSession(sessionId, nonce)
3. Server reconnects if within timeout
4. Client resumes from last known state

Option 2: Client has no persisted state
1. Client restarts
2. Client sends CreateSession (new session)
3. Old session expires via timeout
```

---

## Implementation Checklist

When implementing session-related features:

- [ ] Determined message direction (Client→Server or Server→Client)
- [ ] Defined state change semantics (what changes, what's preserved)
- [ ] Identified all resources affected (metadata, routing, transport, Raft)
- [ ] Implemented cleanup for each resource layer
- [ ] Handled reconnection edge cases (old routing cleanup)
- [ ] Made operations idempotent where possible
- [ ] Added proper logging for debugging
- [ ] Tested with network failures and reconnections
- [ ] Documented in this file

---

## Common Pitfalls

1. **Confusing temporary vs permanent disconnect**
   - Use `ConnectionClosed` for recoverable failures
   - Use `CloseSession` for intentional termination

2. **Forgetting to clean up old routing on reconnect**
   - Always check for old routing ID
   - Disconnect at transport layer
   - Remove from routing maps

3. **Not preserving session on ConnectionClosed**
   - Keep metadata and timeout
   - Only clear routing ID

4. **Forgetting to notify Raft**
   - Notify on: CreateSession, ExpireSession
   - Don't notify on: ContinueSession, ConnectionClosed

---

*Protocol Message Semantics Documentation*  
*Based on implementation in PR #15*  
*Critical for maintaining session durability guarantees*

