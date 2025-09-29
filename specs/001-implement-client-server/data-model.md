# Data Model: Client-Server Communication for Raft Protocol

**Date**: 2025-09-24  
**Feature**: 001-implement-client-server  
**Status**: Complete

## Entity Location Guide

| **Location** | **Description** | **Examples** |
|--------------|-----------------|--------------|
| **🌐 Protocol** | Shared message definitions used by both client and server | ClientMessage, ServerMessage |
| **🖥️ Server-Side** | Data managed exclusively by server instances | Server-Local Connection State, Action Stream |
| **📱 Client-Side** | Data managed exclusively by client instances | Client Retry State, Response Cache |
| **🔄 Both Sides** | Entities maintained independently on both sides | Connection metadata, configuration |

## Core Entities

### Server-Local Connection State 📍 **Server-Side Only**
Server-specific session management with all session data consolidated into connection states.

**Fields**:
- `sessions: Map[SessionId, ConnectionState]` - All session data consolidated by state
- `routingIdToSession: Map[RoutingId, SessionId]` - Fast lookup for incoming ZeroMQ messages

**Session Management**:
- `sessions.get(sessionId)` provides complete session state
- `routingIdToSession.get(routingId)` provides fast session lookup for incoming messages
- `Connected` states contain active routing ID, also stored in routingIdToSession map
- `Disconnected` states preserve session data but no routing ID mapping
- Session cleanup removes entries from both maps

**Expiration Management**:
- Updated via `copy(expiredAt = Instant.now() + timeoutDuration)` on session state
- Flexible timeout durations based on server state:
  - Normal operation: `normalTimeout` (e.g., 30 seconds)
  - Leader transition: `leaderTransitionTimeout` (e.g., 60 seconds)
  - Session continuation: `normalTimeout` (refreshed after successful reconnection)
- Timeout detection scans all session states for `expiredAt < Instant.now()`
- No separate timeout tracking structures needed

**Connection Management**:
- `CreateSession`: Add new `Connected` state to sessions map + routingIdToSession mapping
- `ContinueSession`: Transform `Disconnected` → `Connected` + add new routingIdToSession mapping  
- `Disconnect`: Transform `Connected` → `Disconnected` + remove routingIdToSession mapping
- `Cleanup`: Remove expired sessions from both sessions and routingIdToSession maps

### Protocol Messages 📍 **Protocol Shared**

#### Client Messages 📍 **Protocol Shared**
Sealed trait hierarchy for all client-to-server messages.

**Message Types**:
- `CreateSession(capabilities: Map[String, String], nonce: Long)` - Initial session creation
- `ContinueSession(sessionId: SessionId, nonce: Long)` - Resume existing session (needs sessionId for reconnection)
- `KeepAlive(timestamp: Instant)` - Heartbeat message
- `ClientRequest(requestId: RequestId, payload: ByteVector, createdAt: Instant)` - Generic client request (read or write)
- `ServerRequestAck(requestId: RequestId)` - Acknowledgment of server request receipt
- `CloseSession(reason: CloseReason)` - Explicit session termination

#### Server Messages 📍 **Protocol Shared**
Sealed trait hierarchy for all server-to-client messages.

**Message Types**:
- `SessionCreated(sessionId: SessionId, nonce: Long)` - Successful session creation (includes new sessionId)
- `SessionContinued(nonce: Long)` - Successful session resumption  
- `SessionRejected(reason: RejectionReason, leaderId: Option[MemberId])` - Session creation/continuation failure
- `SessionClosed(reason: SessionCloseReason, leaderId: Option[MemberId])` - Server-initiated session termination
- `KeepAliveResponse(timestamp: Instant)` - Heartbeat acknowledgment (echoes client timestamp)
- `ClientResponse(requestId: RequestId, result: ByteVector)` - Client request execution result
- `ServerRequest(requestId: RequestId, payload: ByteVector, createdAt: Instant)` - Work dispatch to client
- `RequestError(reason: RequestErrorReason, leaderId: Option[MemberId])` - Request processing error

### Request/Response Correlation

#### Client Request
Encapsulates client-initiated requests.

**Fields**:
- `requestId: RequestId` - Unique identifier for idempotency
- `payload: ByteVector` - Request data
- `createdAt: Instant` - Request creation timestamp

#### Server-Initiated Request
Contains work dispatched from server to client.

**Fields**:
- `requestId: RequestId` - Unique identifier for correlation
- `payload: ByteVector` - Task-specific data
- `createdAt: Instant` - Request creation timestamp

**Note**: Target session ID managed server-side when routing via ZeroMQ. Task types, timeouts, and retry attempts managed by server implementation.

### Keep-Alive Protocol 📍 **Protocol Shared**

#### Keep-Alive Message 📍 **Protocol Shared**
Heartbeat messages sent by clients to maintain connection health.

**Fields**:
- `timestamp: Instant` - Client-side timestamp

**Note**: Session ID derived from routing ID lookup on server side.

#### Keep-Alive Response 📍 **Protocol Shared**
Server acknowledgments to client keep-alive messages.

**Fields**:
- `timestamp: Instant` - Server-side response timestamp

### Configuration and Metadata 📍 **Both Sides**

#### Client Retry State 📍 **Client-Side Only**
Client-side connection state management and request queuing behavior.

**Fields**:
- `connectionState: ClientConnectionState` - Current client connection state
- `pendingRequests: Map[RequestId, PendingRequest]` - Outstanding request tracking
- `sessionId: Option[SessionId]` - Current session ID (if any)
- `timeoutConfig: TimeoutConfig` - Retry timeout configuration

**Client Connection States**:
```scala
sealed trait ClientConnectionState

// Client is attempting to establish connection/session
case object Connecting extends ClientConnectionState

// Client has active session and can send requests
case object Connected extends ClientConnectionState  

// Client is intentionally disconnected (user choice - NOT a failure state)
case object Disconnected extends ClientConnectionState
```

**Request Handling by State**:

**Connecting State**:
- Assign `requestId`, add to `pendingRequests`
- **Do NOT send** request over ZeroMQ yet
- Wait for connection to be established

**Connected State**:
- Assign `requestId`, add to `pendingRequests`
- **Send request immediately** over ZeroMQ

**Disconnected State**:
- Assign `requestId`, add to `pendingRequests`  
- **Do NOT send** request over ZeroMQ
- User has intentionally disconnected from cluster

**State Transition Behavior**:

**Connecting → Connected**:
- **Resend ALL pending requests** over ZeroMQ
- Requests were queued during connection establishment

**Connected → Connecting**:
- **Retain ALL pending requests** in queue
- Connection lost, attempting to reconnect

**Connected → Disconnected**:  
- **Error ALL pending requests** with connection failure
- User has intentionally disconnected

**Disconnected → Connecting**:
- Begin connection establishment process
- Existing pending requests remain queued

**State Distinction**:
- **Connecting**: Automatic state during connection establishment or network failure recovery
- **Disconnected**: User-initiated state where client chooses not to be connected to cluster
- Network failures transition: Connected → Connecting (automatic retry)
- User disconnection: Connected → Disconnected (intentional, requires explicit reconnect)

**Client Stream Architecture**:
```scala
// Client Action Types (for unified stream processing):
sealed trait ClientAction
case class NetworkMessageAction(message: ServerMessage) extends ClientAction
case class UserClientRequestAction(request: ClientRequest) extends ClientAction
case object TimeoutCheckAction extends ClientAction
case object SendKeepAliveAction extends ClientAction

// Multiple event streams merged into unified action stream:
val zeromqStream: ZStream[Any, Throwable, ClientAction] = 
  serverMessageStream.map(NetworkMessageAction(_))

val userRequestStream: ZStream[Any, Throwable, ClientAction] = 
  clientRequestStream.map(UserClientRequestAction(_))

val timeoutStream: ZStream[Any, Nothing, ClientAction] = 
  ZStream.tick(connectionTimeout).as(TimeoutCheckAction)

val keepAliveStream: ZStream[Any, Nothing, ClientAction] = 
  ZStream.tick(keepAliveInterval).as(SendKeepAliveAction)

val unifiedClientStream: ZStream[Any, Throwable, ClientAction] = 
  zeromqStream.merge(userRequestStream).merge(timeoutStream).merge(keepAliveStream)

// Client processes unified actions and manages connection state
val clientActionStream: ZStream[Any, Throwable, Unit] = 
  unifiedClientStream.mapZIO(processClientAction)

// Separate stream for server-initiated requests (for user consumption)  
val serverInitiatedRequestStream: ZStream[Any, Throwable, ServerRequest] = 
  zeromqStream.collect { 
    case NetworkMessageAction(serverRequest: ServerRequest) => serverRequest 
  }
```

**Client Action Processing**:
```scala
def processClientAction(action: ClientAction): ZIO[Any, Throwable, Unit] = action match {
  case NetworkMessageAction(message) => 
    handleServerMessage(message) // Process responses, session messages, etc.
    
  case UserClientRequestAction(request) =>
    connectionState match {
      case Connected => sendRequestImmediately(request)
      case Connecting | Disconnected => queueRequest(request)
    }
    
  case TimeoutCheckAction => 
    connectionState match {
      case Connecting => checkConnectionTimeout() // May transition to retry or error
      case _ => ZIO.unit
    }
    
  case SendKeepAliveAction =>
    connectionState match {
      case Connected => sendKeepAlive()
      case _ => ZIO.unit // No keep-alive when not connected
    }
}
```

#### Response Cache 📍 **Client-Side Only**
Client-side cache for server-initiated request idempotency.

**Fields**:
- `cachedResponses: Map[RequestId, CachedResponse]` - Response storage by request ID
- `maxCacheSize: Int` - Maximum number of cached responses
- `cacheTimeout: Duration` - Time to live for cached entries

#### Cluster Configuration 📍 **Both Sides**
Current cluster membership and leadership information.

**Fields**:
- `members: List[MemberId]` - All cluster members
- `currentLeader: Option[MemberId]` - Current leader (if known)
- `lastUpdated: Instant` - Timestamp of last topology update

### Connection State 📍 **Server-Side Only**
Discriminated union containing all session data based on connection status.

**State Definition**:
```scala
sealed trait ConnectionState {
  def capabilities: Map[String, String]
  def createdAt: Instant
  def expiredAt: Instant
}

case class Connected(
  routingId: RoutingId,
  capabilities: Map[String, String],
  createdAt: Instant,
  expiredAt: Instant
) extends ConnectionState

case class Disconnected(
  capabilities: Map[String, String],
  createdAt: Instant,
  expiredAt: Instant
) extends ConnectionState

```

**State Transitions**:
```scala
// Initial connection
() → Connected (via CreateSession)

// Network disconnection (TCP drop or leader change)
Connected → Disconnected (remove routing ID, keep session data)

// Reconnection after disconnect  
Disconnected → Connected (via ContinueSession)

// Heartbeat timeout expiration (immediate cleanup)
Connected|Disconnected → (immediately removed from sessions map + ExpireSessionAction to Raft)

// Expired session continuation attempts
ContinueSession(expired sessionId) → SessionRejected(SessionNotFound)
```

**Usage**:
- `Connected`: Session active with routing ID, receiving heartbeats
- `Disconnected`: Session exists but no routing ID (TCP drop/leader change), session data preserved for reconnection
- Sessions that expire (timeout) are immediately removed from local state

### Server Action Stream 📍 **Server-Side Only**
Actions forwarded from server to Raft state machine for processing.

**Raft Action Types**:
```scala
sealed trait ServerAction
case class CreateSessionAction(sessionId: SessionId, capabilities: Map[String, String]) extends ServerAction
case class ClientMessageAction(sessionId: SessionId, message: ClientMessage) extends ServerAction  
case class ExpireSessionAction(sessionId: SessionId, reason: ExpirationReason) extends ServerAction
```

**Local Action Types** (for unified stream processing):
```scala
sealed trait LocalAction
case class MessageAction(routingId: RoutingId, message: ClientMessage) extends LocalAction
case object CleanupAction extends LocalAction
```

**Stream Architecture**:
```scala
val cleanupStream: ZStream[Any, Nothing, LocalAction] = 
  ZStream.tick(1.second).as(CleanupAction)

val messageStream: ZStream[Any, Throwable, LocalAction] = 
  zeromqStream.map(msg => MessageAction(msg.routingId, msg.content))

val unifiedStream: ZStream[Any, Throwable, LocalAction] = 
  cleanupStream.merge(messageStream)

val resultStream: ZStream[Any, Throwable, ServerAction] = 
  unifiedStream.flatMap(handler)

// Handler processes local actions and produces Raft actions
def handler(action: LocalAction): ZStream[Any, Throwable, ServerAction] = action match {
  case CleanupAction => 
    ZStream.fromIterable(removeExpiredSessions()).map(ExpireSessionAction(_))
  case MessageAction(routingId, message) => 
    processMessage(routingId, message) // Returns ZStream[ServerAction]
}

// removeExpiredSessions(): List[SessionId] 
// - Scans sessions map for expiredAt < now
// - Removes expired sessions from sessions map
// - Removes corresponding routingIdToSession mappings  
// - Returns list of removed session IDs
```

**Note**: Server-initiated requests and pending work queues are managed directly by the Raft state machine, not via the server action stream.

**Reactive Pipeline Flow**:
```
Raw Events → LocalAction → Handler → ServerAction → Raft State Machine
     ↓             ↓           ↓           ↓              ↓
ZeroMQ msgs,  CleanupAction,  Remove    CreateSession,  Apply to
Timer ticks   MessageAction   expired   ClientMessage,  replicated
                             sessions  ExpireSession   state
                             locally
```

**Action Stream Flow**:
- `cleanupStream` + `messageStream` → `unifiedStream` of local actions
- `unifiedStream.flatMap(handler)` → `resultStream` of Raft actions  
- Server validates leader status before queueing actions to Raft
- Actions processed by Raft state machine in order
- Results communicated back to server for client response
- Non-leader servers reject actions with leader redirection

## Type Definitions 📍 **Protocol Shared**

### Newtypes and Value Classes 📍 **Protocol Shared**
Following ZIO Raft patterns for type safety:

```scala
type SessionId = SessionId.Type
object SessionId extends Newtype[String]

type RequestId = RequestId.Type  
object RequestId extends Newtype[Long]

type ClientCapability = ClientCapability.Type
object ClientCapability extends Newtype[String]
```

### Enumerations
Sealed trait hierarchies for bounded value sets:

```scala
sealed trait ConnectionState
object ConnectionState {
  case object Connected extends ConnectionState
  case object Disconnected extends ConnectionState
  case object Expired extends ConnectionState
}

sealed trait CloseReason
object CloseReason {
  case object Graceful extends CloseReason
  case object Timeout extends CloseReason
  case object Error extends CloseReason
}

sealed trait RejectionReason
object RejectionReason {
  case object SessionNotFound extends RejectionReason  // Includes expired sessions
}
```

## Relationships

### Session Lifecycle Dependencies
```
Client → CreateSession → Server → Action Stream → sessions += sessionId → Connected(routingId, ...) + routingIdToSession += routingId → sessionId
Server → SessionCreated(sessionId, nonce) → Client 
Client → KeepAlive(timestamp=T1) → Server → [derive sessionId from routingId] → sessions(sessionId).copy(expiredAt = now + normalTimeout)
Server → KeepAliveResponse(timestamp=T1) → Client (echoes client timestamp for stale response detection)
TCP Disconnect (ZeroMQ DisconnectMessage) → sessions(sessionId): Connected → Disconnected + routingIdToSession -= routingId
Leader Change → All sessions: Connected → Disconnected + clear all routingIdToSession mappings + expiredAt = now + extendedGracePeriod
Client → ContinueSession(sessionId) → Server → sessions(sessionId): Disconnected → Connected(newRoutingId, expiredAt = now + normalTimeout) + routingIdToSession += newRoutingId → sessionId
Server → SessionContinued(nonce) → Client [No Raft forwarding needed]
CleanupAction (from ZStream.tick(1.second)) → removeExpiredSessions() → immediately remove sessions(sessionId) + routingIdToSession cleanup → returns List[SessionId]
Server → ExpireSessionAction(sessionId) for each removed session → Raft → remove session for consistency
Server Shutdown → SessionClosed(Shutdown, Some(leaderId)) → cleanup all sessions
Server Loses Leadership → SessionClosed(NotLeader, Some(newLeaderId)) → cleanup all sessions
Invalid Message (unknown sessionId) → SessionClosed(SessionError, None)
Server Startup/Leader Election → [Initialize both maps from existing session data, extend expiredAt with leader transition grace period]
Incoming ZeroMQ Message → routingIdToSession.get(routingId) → sessionId → sessions.get(sessionId) → process with derived sessionId
Non-Leader Server → [Reject with RequestError(NotLeader, leaderId)]
```

### Request/Response Flows
```
Client → ClientRequest → Server → ClientResponse(result) → Client (success path)
Server → ServerRequest(requestId, payload, createdAt) → Client → ServerRequestAck(requestId) → Server (immediate acknowledgment)
Server → SessionClosed(reason, leaderId) → Client (graceful session termination)
```

### State Replication
```
Create Session → Leader Check → Action Stream → Raft State Machine → Local Keep-Alive Init
Continue Session → Local Only (no Raft forwarding)
Keep-Alive Activity → Local Update Only (no Raft write)
Client Commands → Leader Check → Action Stream → Raft State Machine
Session Timeout → Action Stream → Raft State Machine (Expire Session)
Server Startup → Raft Session List → Local Keep-Alive Initialization
Non-Leader → Reject with RequestError(NotLeader, leaderId)
```

## Error Types 📍 **Protocol Shared**

### Rejection Reasons
```scala
sealed trait RejectionReason
case object NotLeader extends RejectionReason           // Server is not current leader
case object SessionNotFound extends RejectionReason     // Session ID invalid or expired  
case object ClusterUnavailable extends RejectionReason  // No quorum available
```

### Error Reasons
```scala
sealed trait RequestErrorReason  
case object NotLeaderRequest extends RequestErrorReason
case object SessionNotFound extends RequestErrorReason
case object InvalidRequest extends RequestErrorReason
case object Timeout extends RequestErrorReason
```

### Session Close Reasons
```scala
sealed trait SessionCloseReason
case object Shutdown extends SessionCloseReason            // Server shutting down
case object NotLeader extends SessionCloseReason           // Server no longer leader  
case object SessionError extends SessionCloseReason        // Invalid message received
```

## Validation Rules

### Session Validation
- Session ID must be valid UUID format
- Client capabilities must be non-empty map (server accepts declarations as-is)
- Heartbeat interval must not exceed timeout threshold
- Connection state must be valid enum value

### Request Validation
- Request ID must be unique within session scope
- Payload size must not exceed configured maximum
- Timeout values must be within acceptable ranges
- Retry count must not exceed configured maximum

### Protocol Validation
- Message format must match scodec schema
- Version compatibility must be maintained
- Required fields must be present and valid
- Optional fields must have sensible defaults

---
*Data model completed 2025-09-24*
