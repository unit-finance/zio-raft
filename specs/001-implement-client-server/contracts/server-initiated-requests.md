# Server-Initiated Requests API Contract

**Feature**: Client-Server Communication for Raft Protocol  
**Contract Type**: One-Way Server-to-Client Work Dispatch  
**Version**: 1.0  

## Overview
Defines the protocol contract for servers to initiate one-way requests to connected clients, enabling patterns like work queue dispatch and distributed task execution with immediate acknowledgment and asynchronous processing.

## Message Definitions

### Server → Client: ServerRequest

**Purpose**: Dispatch one-way work from server to client for processing

**Message Structure**:
```scala
case class ServerRequest(
  requestId: RequestId,        // Unique request identifier
  payload: ByteVector,         // Work payload data
  createdAt: Instant           // Request creation timestamp
)
```

**Validation Rules**:
- `requestId` must be unique across all server-initiated requests
- `payload` size must not exceed configured maximum
- Server derives session ID from ZeroMQ routing ID using `routingIdToSession` mapping

**Notes**:
- No `sessionId` field - server targets client via ZeroMQ routing ID
- Simplified structure focuses on essential work dispatch
- Client processes work asynchronously after acknowledgment

### Client → Server: ServerRequestAck

**Purpose**: Immediate acknowledgment of server request receipt

**Message Structure**:
```scala
case class ServerRequestAck(
  requestId: RequestId         // Echo of server request ID for correlation
)
```

**Notes**:
- Client acknowledges immediately upon receiving server request
- No processing result returned - this is a one-way fire-and-forget pattern
- Server considers request delivered upon receiving acknowledgment
- No `sessionId` field - server correlates response via ZeroMQ routing ID
- Client processes work asynchronously in background after acknowledgment

## Protocol Flow Examples

### Successful Request Delivery
```
Server → Client: ServerRequest(requestId=101, payload=..., createdAt=T1)
Client → Server: ServerRequestAck(requestId=101)
[Client processes work asynchronously - server doesn't wait for completion]
```

### Request Delivery Timeout
```
Server → Client: ServerRequest(requestId=102, payload=..., createdAt=T2)
[Client does not acknowledge within timeout]
Server: [Marks request as undelivered, may retry or redistribute]
```

### Duplicate Request Handling
```
Server → Client: ServerRequest(requestId=103, payload=..., createdAt=T3)
Client → Server: ServerRequestAck(requestId=103)
Server → Client: ServerRequest(requestId=103, payload=..., createdAt=T3)  // Retry due to network issue
Client → Server: ServerRequestAck(requestId=103)  // Cached acknowledgment, no reprocessing
```

### Immediate Acknowledgment Pattern
```
Server → Client: ServerRequest(requestId=104, payload=..., createdAt=T4)
Client: [Immediately acknowledges without processing]
Client → Server: ServerRequestAck(requestId=104)  
Client: [Processes work in background asynchronously]
```

## Idempotency and Retry Handling

### Request Deduplication
- Client maintains cache of recent server request IDs
- Duplicate requests receive immediate acknowledgment without reprocessing
- Cache expiration based on configurable time window

### Server Retry Logic
- Server may retry unacknowledged requests after timeout
- Retry attempts use same request ID for deduplication
- Exponential backoff applied between retry attempts

### Client Acknowledgment Caching
- Client caches acknowledgments for recent request IDs
- Duplicate requests receive cached acknowledgment immediately
- Cache prevents duplicate work from retry attempts

## Client Capability Management

### Capability Declaration
- Clients declare supported capabilities as key-value pairs during session creation
- Server trusts client capability declarations and uses them for request routing
- Server routes requests to clients based on declared capability names and values
- No server-side capability validation - client is responsible for accurate declarations

### Load Balancing
- Server distributes requests across capable clients
- Load balancing based on session health and availability
- Simple round-robin or random distribution strategies

## Error Handling

### Client Network Issues
- Server detects TCP disconnection via ZeroMQ DisconnectMessage
- Server detects session expiration via keep-alive timeout (expiredAt < current time)
- Unacknowledged requests marked as undelivered
- Requests may be redistributed to other capable clients

### Request Rejection (via absence of acknowledgment)
- Client may choose not to acknowledge unsupported requests
- Client may delay acknowledgment if overloaded  
- Server interprets lack of acknowledgment as delivery failure
- No explicit rejection messages - silence indicates inability to process

### Session State During Network Issues
- **TCP Disconnection**: Unacknowledged requests preserved, session marked as Disconnected, can be continued
- **Expiration**: Session immediately removed from local state, requests redistributed, ExpireSessionAction sent to Raft
- **Leader Change**: All sessions marked as Disconnected, clients reconnect and continue sessions
- Session continuation does not restore request state (server manages delivery tracking locally)

## Performance Requirements

### Latency Targets
- Request dispatch: <50ms from server to client
- Acknowledgment processing: <10ms for immediate response
- Work processing: Variable, handled asynchronously by client

### Throughput Targets
- Support 10,000+ concurrent server-initiated requests
- Handle 1,000+ requests per second dispatch rate
- Scale to 100+ capable clients per request type

### Resource Limits
- Maximum payload size: 10MB per request
- Maximum concurrent requests per client: 10
- Acknowledgment timeout: 30 seconds (configurable)
- Maximum unacknowledged requests per server: 100,000

## Client Stream API

### Server-Initiated Request Stream
The client provides a dedicated stream for consuming server-initiated requests:

```scala
trait RaftClient {
  // Main action processing stream (internal)
  def actionStream: ZStream[Any, Throwable, Unit]
  
  // Dedicated stream for server requests (user consumption)  
  def serverInitiatedRequestStream: ZStream[Any, Throwable, ServerRequest]
}
```

### Usage Example
```scala
for {
  client <- RaftClient.connect(clientConfig)
  
  // Process server-initiated requests in background
  requestProcessor <- client.serverInitiatedRequestStream
    .mapZIO { serverRequest =>
      for {
        // Process the work payload
        result <- processWork(serverRequest.payload)
        
        // Send immediate acknowledgment
        _ <- client.acknowledgeServerRequest(serverRequest.requestId)
      } yield result
    }
    .runDrain
    .fork
    
  // Continue with normal client operations
  response <- client.submitCommand(myCommand)
  
  _ <- requestProcessor.interrupt
  _ <- client.disconnect()
} yield response
```

### Stream Processing Architecture
- **Multiple Event Sources**: ZeroMQ messages, user requests, timer events
- **Unified Action Stream**: All events merged into single reactive processing stream
- **Separate Server Request Stream**: Filtered stream of server-initiated requests for user consumption
- **Timer-Based Events**: Connection timeouts, keep-alive sending handled via periodic streams

## Security Considerations

### Request Authorization
- Server routes requests based on client-declared capability names and values
- Client is responsible for handling requests according to their declared capabilities
- Request payloads may contain sensitive data requiring encryption
- Request IDs must be unpredictable to prevent enumeration

### Resource Protection
- Client-side resource limits prevent DoS attacks
- Server-side rate limiting per client session
- Acknowledgment timeout enforcement prevents resource exhaustion
- Simple fire-and-forget pattern reduces attack surface

---
*Contract version 1.0 - 2025-09-24*
