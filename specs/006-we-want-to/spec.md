# Feature Specification: TypeScript Client Library

**Feature Branch**: `006-we-want-to`  
**Created**: 2025-12-24  
**Status**: Draft  
**Input**: User description: "we want to create a typescript client similar to client-server-client and client-server-protocol"

## Execution Flow (main)
```
1. Parse user description from Input
   → If empty: ERROR "No feature description provided"
2. Extract key concepts from description
   → Identify: actors, actions, data, constraints
3. For each unclear aspect:
   → Mark with [NEEDS CLARIFICATION: specific question]
4. Fill User Scenarios & Testing section
   → If no clear user flow: ERROR "Cannot determine user scenarios"
5. Generate Functional Requirements
   → Each requirement must be testable
   → Mark ambiguous requirements
6. Identify Key Entities (if data involved)
7. Run Review Checklist
   → If any [NEEDS CLARIFICATION]: WARN "Spec has uncertainties"
   → If implementation details found: ERROR "Remove tech details"
8. Return: SUCCESS (spec ready for planning)
```

---

## ⚡ Quick Guidelines
- ✅ Focus on WHAT users need and WHY
- ❌ Avoid HOW to implement (no tech stack, APIs, code structure)
- 👥 Written for business stakeholders, not developers

### Section Requirements
- **Mandatory sections**: Must be completed for every feature
- **Optional sections**: Include only when relevant to the feature
- When a section doesn't apply, remove it entirely (don't leave as "N/A")

### For AI Generation
When creating this spec from a user prompt:
1. **Mark all ambiguities**: Use [NEEDS CLARIFICATION: specific question] for any assumption you'd need to make
2. **Don't guess**: If the prompt doesn't specify something (e.g., "login system" without auth method), mark it
3. **Think like a tester**: Every vague requirement should fail the "testable and unambiguous" checklist item
4. **Common underspecified areas**:
   - User types and permissions
   - Data retention/deletion policies  
   - Performance targets and scale
   - Error handling behaviors
   - Integration requirements
   - Security/compliance needs

---

## User Scenarios & Testing (mandatory)

### Primary User Story
As a JavaScript/TypeScript application developer, I want to connect to a ZIO Raft cluster and submit commands/queries, so that my application can interact with distributed stateful services built with ZIO Raft using the same client-server protocol that Scala clients use.

### Acceptance Scenarios
1. **Given** a ZIO Raft cluster is running, **When** a TypeScript application creates a client and connects to the cluster, **Then** the client establishes a durable session and can submit commands.
2. **Given** a TypeScript client has an active session, **When** the client submits a command with a binary payload, **Then** the client receives a response with the command result.
3. **Given** a TypeScript client has an active session, **When** the client submits a read-only query, **Then** the client receives a response with the query result.
4. **Given** a TypeScript client is connected to a follower node, **When** that node rejects the session because it's not the leader, **Then** the client automatically reconnects to the leader node.
5. **Given** a TypeScript client has an active session, **When** the network connection is interrupted, **Then** the client automatically reconnects and resumes the existing session without losing pending requests.
6. **Given** a TypeScript client has pending commands, **When** a response is not received within the timeout period, **Then** the client automatically retries the request.
7. **Given** a TypeScript client is idle, **When** the keep-alive interval elapses, **Then** the client sends a heartbeat message to maintain session liveness.
8. **Given** a TypeScript client receives server-initiated requests, **When** the application provides a handler for these requests, **Then** the handler is invoked with the request payload and the client acknowledges the request to the server.

### Edge Cases
- If a client connects while the cluster is undergoing a leadership election, the client should retry connection attempts across cluster members until a leader is available.
- If a session expires on the server (due to missed keep-alives), pending requests should fail with an error and the client should terminate (matching Scala client behavior).
- If the same request is submitted multiple times while disconnected, all requests should be queued and sent once reconnected.
- If binary payload encoding or validation fails during command/query submission, the client should throw a synchronous exception immediately without attempting to send the message.
- The client should support raw binary payloads using a framing protocol for message boundaries.
- Each client instance manages a single session; applications needing multiple concurrent sessions should create multiple client instances.
- During normal disconnection/reconnection (not session expiry), pending requests are preserved and resent after successful reconnection.

## Requirements (mandatory)

### Functional Requirements
- **FR-001**: The TypeScript client library MUST support connecting to a ZIO Raft cluster using the same wire protocol as the Scala client.
- **FR-002**: The client MUST support creating a new durable session with capability negotiation using user-provided capabilities passed through the library (the client does not define specific capabilities).
- **FR-003**: The client MUST support resuming an existing session after disconnection.
- **FR-004**: The client MUST support submitting commands (write operations) and receiving responses with binary payloads; submission MUST throw synchronous exceptions for immediate validation or encoding failures.
- **FR-005**: The client MUST support submitting queries (read-only operations) and receiving responses with binary payloads; submission MUST throw synchronous exceptions for immediate validation or encoding failures.
- **FR-006**: The client MUST automatically send keep-alive messages at configurable intervals to maintain session liveness.
- **FR-007**: The client MUST automatically detect leadership changes and reconnect to the current leader when receiving "not leader" rejections.
- **FR-008**: The client MUST retry commands/queries that timeout before receiving a response.
- **FR-009**: The client MUST handle server-initiated requests by allowing applications to register handlers and automatically acknowledging received requests.
- **FR-010**: The client MUST maintain a queue of pending requests and ensure they are sent/retried upon reconnection.
- **FR-011**: The client MUST support graceful disconnection that properly closes the session.
- **FR-012**: The client MUST provide an event-based message/event loop architecture for observing connection state changes (connecting, connected, disconnected) and all diagnostic information for application monitoring and logging.
- **FR-013**: The client MUST correlate responses with requests using request IDs for commands and correlation IDs for queries.
- **FR-014**: The client MUST support configurable timeouts for connections, requests, and keep-alives.
- **FR-015**: The client MUST use ZeroMQ (ZMQ) as the transport layer for all communication with the Raft cluster.
- **FR-016**: The client MUST provide TypeScript type definitions for all public APIs.
- **FR-017**: The client MUST support Node.js runtime environments.
- **FR-018**: The client MUST use a framing protocol for raw binary payloads to handle message boundaries correctly.
- **FR-019**: When a session expires (SessionExpired rejection or SessionClosed with SessionExpired reason), the client MUST fail all pending requests with an error and terminate, matching the Scala client behavior.
- **FR-020**: Each client instance MUST manage exactly one session; applications requiring multiple concurrent sessions must create multiple client instances.
- **FR-021**: The client MUST support high throughput request processing (1,000-10,000+ requests per second) with batching optimizations where applicable.

### Non-Functional Requirements
- **NFR-001**: The client MUST achieve throughput of 1,000-10,000+ requests per second for command and query operations.
- **NFR-002**: The client SHOULD implement batching strategies to optimize network utilization at high request volumes.
- **NFR-003**: The client MUST NOT include built-in logging; applications are responsible for all logging and diagnostics through event observation.

### Key Entities (include if feature involves data)
- **Client**: The main interface that applications use to interact with the Raft cluster; manages connection lifecycle and request/response correlation.
- **Session**: A durable server-side session that persists across reconnections; identified by a session ID; has a lifetime governed by keep-alive messages.
- **Command**: A write operation submitted by the client; has a unique request ID; expects a response; may be retried on timeout.
- **Query**: A read-only operation submitted by the client; has a correlation ID; expects a response; may be retried on timeout.
- **KeepAlive**: A periodic heartbeat message to maintain session liveness; includes a timestamp for RTT measurement.
- **ServerRequest**: A server-initiated request sent to the client; has a request ID; requires acknowledgment; delivered to application-registered handlers.
- **Capabilities**: A set of feature flags or version indicators provided by the application using the client library; exchanged during session creation for protocol negotiation; the client passes these through without defining specific capability values.

---

## Clarifications

### Session 2025-12-24
- Q: How should connection state changes be exposed to applications? → A: Event-based with a message/event loop architecture for handling state changes
- Q: When command/query submission fails (validation or encoding errors), what should happen? → A: Throw synchronous exception immediately
- Q: What are the expected performance characteristics for request throughput? → A: High throughput (1K-10K+ req/sec, batching)
- Q: What level of built-in logging/tracing should the client provide? → A: None (application handles all logging)
- Q: What capabilities should be included in the initial session creation? → A: User-provided, passed through library

---

## Dependencies & Assumptions
- The TypeScript client assumes the server implements the ZIO Raft client-server protocol as defined in the `client-server-protocol` Scala module.
- Session lifecycle and expiry rules are governed by the server; the client adapts to server behavior.
- The client assumes reliable message ordering over the transport layer (ZeroMQ DEALER/ROUTER pattern).
- The client uses ZeroMQ (ZMQ) for transport, requiring a Node.js-compatible ZMQ library.
- Binary payload framing follows the pattern established in the maitred Frame.ts reference implementation.
- The client follows the same reconnection and session expiry semantics as the Scala client: on session expiry, pending requests fail and the client terminates.
- Each client instance manages a single session; this matches the Scala client architecture.
- Capabilities are application-defined and passed through the client library (similar to how maitred wraps the Scala client); the TypeScript client does not define specific capability values.
- Packaging and distribution will be handled separately from this feature.
- Example applications are not included in the initial implementation.

---

## Review & Acceptance Checklist
*GATE: Automated checks run during main() execution*

### Content Quality
- [ ] No implementation details (languages, frameworks, APIs)
- [ ] Focused on user value and business needs
- [ ] Written for non-technical stakeholders
- [ ] All mandatory sections completed

### Requirement Completeness
- [x] No [NEEDS CLARIFICATION] markers remain
- [ ] Requirements are testable and unambiguous  
- [ ] Success criteria are measurable
- [ ] Scope is clearly bounded
- [ ] Dependencies and assumptions identified

---

## Execution Status
*Updated by main() during processing*

- [x] User description parsed
- [x] Key concepts extracted
- [x] Ambiguities marked
- [x] User scenarios defined
- [x] Requirements generated
- [x] Entities identified
- [ ] Review checklist passed

---
