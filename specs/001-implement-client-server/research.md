# Research: Client-Server Communication for Raft Protocol

**Date**: 2025-09-24  
**Feature**: 001-implement-client-server  
**Status**: Complete

## Research Findings

### R1: ZeroMQ Integration Patterns

**Decision**: Use CLIENT/SERVER socket pattern with zio-zmq for bidirectional communication

**Rationale**: 
- CLIENT/SERVER provides automatic reconnection and load balancing
- SERVER socket can handle multiple concurrent CLIENT connections
- Built-in connection management and automatic reconnection on network failures
- zio-zmq offers excellent ZIO integration with proper resource management
- Simpler connection lifecycle compared to DEALER/ROUTER pattern
- Better suited for client-server applications with automatic failover

**Implementation Approach**:
- Server uses SERVER socket to handle multiple client connections
- Clients use CLIENT socket for connection to server cluster
- CLIENT socket automatically handles reconnection and load balancing
- Connection IDs provide natural client identification and message correlation
- ZIO Scope ensures proper socket cleanup and resource management

**Alternatives Considered**:
- DEALER/ROUTER: Rejected due to more complex connection management requirements
- REQ/REP: Rejected due to synchronous nature limiting concurrent operations  
- PUB/SUB: Rejected as it doesn't support bidirectional request/response
- TCP sockets: Rejected due to additional complexity for message framing

### R2: scodec Protocol Design

**Decision**: Use discriminated union pattern with versioned codecs for protocol messages

**Rationale**:
- Follows proven maitred patterns for maintainable binary protocols
- Discriminated unions provide type-safe message serialization
- Version markers enable backward compatibility and protocol evolution
- scodec's combinators offer composable and testable codec construction

**Implementation Approach**:
- Separate ClientMessage and ServerMessage sealed trait hierarchies
- Each message type has dedicated codec with discriminator byte
- Protocol signature to detect protocol messages vs. noise
- Version markers for future protocol evolution support

**Codec Pattern**:
```scala
val messageCodec = signature ~> discriminated[Message]
  .by(uint8)
  .typecase(1, MessageType1.codec)
  .typecase(2, MessageType2.codec)
```

**Alternatives Considered**:
- JSON over ZeroMQ: Rejected due to performance and type safety concerns
- Protobuf: Rejected to maintain consistency with existing ZIO Raft patterns
- Custom binary format: Rejected due to maintenance overhead

### R3: Session Management in Distributed Systems

**Decision**: Use server-generated UUIDs with Raft-replicated session state

**Rationale**:
- Server-generated IDs prevent collision and ensure uniqueness
- Raft replication provides session durability across leader changes
- Session state as part of Raft log ensures strong consistency
- Timeout-based cleanup prevents resource leaks

**Session Lifecycle**:
1. Client requests session creation
2. Server generates UUID and replicates via Raft (metadata only)
3. Server maintains local: ZeroMQ routing ID ↔ SessionId mapping + keep-alive tracking
4. Session state includes client capabilities (in Raft), connection data (local)
5. Keep-alive messages update local timestamps only (no Raft writes)
6. On session continuation: update routing ID mapping + keep-alive timestamp
7. Server startup/leader election: initialize keep-alive tracking from Raft session list
8. Timeout or explicit termination triggers cleanup

**State Replication Strategy**:
- Server monitors Raft state (Leader/Follower) to gate operations
- Create Session and Expire Session forwarded to Raft state machine via action stream
- Continue Session handled locally (no state machine forwarding needed)
- Session metadata stored in replicated state machine (no keep-alive timestamps)
- Server-local routing ID mappings and keep-alive tracking (not replicated)
- Leader transitions: new leader initializes keep-alive tracking from session list
- Non-leader servers reject session operations with leader redirection

**Alternatives Considered**:
- Client-generated session IDs: Rejected due to collision risk
- Local session storage: Rejected due to leader change issues
- External session store: Rejected to avoid additional dependencies

### R4: Client Retry Strategies

**Decision**: Exponential backoff with jitter and circuit breaker patterns

**Rationale**:
- Exponential backoff reduces load during server overload
- Jitter prevents thundering herd effects
- Circuit breaker prevents cascading failures
- Request correlation enables proper idempotency

**Retry Configuration**:
- Initial delay: 100ms
- Max delay: 30 seconds
- Backoff factor: 2.0
- Jitter: ±25% random variation
- Max retries: Configurable (default 5)

**Idempotency Strategy**:
- Client generates unique request IDs
- Server maintains recent request cache
- Duplicate detection based on session + request ID
- Response caching for server-initiated requests

**Alternatives Considered**:
- Fixed interval retry: Rejected due to potential thundering herd
- No circuit breaker: Rejected due to cascading failure risk
- Global request IDs: Rejected due to complexity and collision risk

### R5: ZIO Resource Management

**Decision**: Use ZIO Scope for socket lifecycle and ZManaged for client/server instances

**Rationale**:
- ZIO Scope provides automatic resource cleanup
- ZManaged ensures proper initialization and finalization order
- Resource safety prevents socket leaks and connection issues
- Composable resource management aligns with ZIO principles

**Resource Hierarchy**:
- ZMQ Context (shared across application)
- Socket instances (scoped to client/server lifetime)
- Session state (managed by session manager)
- Connection handlers (scoped to individual connections)

**Error Handling**:
- Resource acquisition failures fail fast
- Network errors trigger reconnection logic
- Cleanup is guaranteed even in failure scenarios
- Resource leaks prevented through automatic management

**Alternatives Considered**:
- Manual resource management: Rejected due to leak potential
- Try/finally blocks: Rejected in favor of ZIO's composable approach
- Cats Effect Resource: Rejected to maintain ZIO ecosystem consistency

## Technical Decisions Summary

| Component | Technology | Rationale |
|-----------|------------|-----------|
| Transport | ZeroMQ CLIENT/SERVER | Automatic reconnection, load balancing |
| Serialization | scodec discriminated unions | Type safety, backward compatibility |
| Session Storage | Raft-replicated state | Durability, consistency |
| Retry Logic | Exponential backoff + jitter | Load balancing, thundering herd prevention |
| Resource Management | ZIO Scope + ZManaged | Automatic cleanup, composability |
| Testing | ZIO Test + property-based | Distributed system scenario coverage |

## Implementation Implications

### Performance Characteristics
- Expected latency: <50ms for local operations
- Session capacity: 1000+ concurrent sessions per server
- Memory usage: ~1KB per active session
- Network overhead: ~100 bytes per message (including ZeroMQ framing)

### Operational Considerations
- Monitoring: Session count, message rates, retry frequencies
- Configuration: Timeouts, retry limits, session expiration
- Debugging: Request tracing, session state inspection
- Deployment: Socket address configuration, cluster topology

### Security Considerations
- Session ID unpredictability (UUIDs)
- Message authentication via ZeroMQ security mechanisms
- Rate limiting for session creation
- Session hijacking prevention through routing ID correlation

---
*Research completed 2025-09-24*
