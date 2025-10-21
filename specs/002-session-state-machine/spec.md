# Feature Specification: Session State Machine Framework

**Feature Branch**: `002-composable-raft-state` (branch name unchanged)  
**Spec Directory**: `002-session-state-machine`  
**Created**: October 21, 2025  
**Status**: Draft  
**Input**: User description: "composable raft state machine implementation go with the client-server library."

**Context**: This feature implements the session state machine framework described in Chapter 6 of the Raft dissertation paper. It provides a session management layer that wraps user-defined state machines to provide linearizable semantics and exactly-once command execution through idempotency checking and response caching.

## Execution Flow (main)
```
1. Parse user description from Input
   → Feature identified: Session state machine framework for Raft with client-server integration
2. Extract key concepts from description
   → Actors: Application developers defining business logic state machines
   → Actions: Define commands, apply commands through session layer
   → Data: User state machine state, session state, command payloads
   → Constraints: Must integrate with existing client-server library for idempotency
3. Architecture clarified from context:
   → Client-server state machine wraps user state machines (runs before and after)
   → Pre-processing: Idempotency check based on session ID + request ID
   → Post-processing: Cache responses, manage server-initiated requests
   → User state machines process only non-duplicate commands
4. Fill User Scenarios & Testing section
   → Scenarios defined based on wrapped state machine execution model
5. Generate Functional Requirements
   → Requirements focus on state machine interface and composition with session layer
6. Identify Key Entities
   → User State Machine, Session State Machine, Command, Response Cache, State Snapshot identified
7. Run Review Checklist
   → Spec complete with architecture clarified
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

## User Scenarios & Testing

### Primary User Story
As a developer building a distributed application on top of Raft with the client-server library, I want to define a custom state machine that processes my business logic while a session state machine framework handles session management, idempotency, and exactly-once semantics. The framework provides the session state machine that wraps my user state machine and automatically manages: (1) idempotency checking via (sessionId, requestId) pairs, (2) response caching for linearizable semantics per Chapter 6.3 of the Raft dissertation, (3) server-initiated request tracking with cumulative acknowledgments, and (4) session lifecycle event forwarding. I provide the state machine logic only - integration with the client-server library is my responsibility (wiring RaftAction events, deserializing commands, serializing responses, providing a snapshot codec).

### Acceptance Scenarios

1. **Given** a developer has defined a simple user state machine (e.g., a counter), **When** a client submits a command with a new session ID and request ID, **Then** the client-server layer detects it's not a duplicate, forwards it to the user state machine, processes the command, caches the response, and returns the result to the client.

2. **Given** a client submits the same command twice (same session ID and request ID), **When** the second request arrives, **Then** the client-server layer detects the duplicate, retrieves the cached response, and returns it without invoking the user state machine again.

3. **Given** a developer has defined a user state machine, **When** they wire it into their application by routing RaftAction events from the client-server library to the state machine and sending state machine responses back via ServerAction events, **Then** all commands flow through idempotency checking automatically.

4. **Given** a user state machine processes a command and returns an error, **When** the client retries with the same request ID, **Then** the cached error response is returned without reprocessing the command.

5. **Given** multiple clients submit different commands in parallel, **When** Raft applies these commands in consensus order, **Then** each command goes through idempotency checking and is processed exactly once by the user state machine.

6. **Given** a user state machine generates server-initiated requests during command processing, **When** the state machine returns these requests, **Then** the user takes them and sends via ServerAction.SendServerRequest through the client-server library, and the session state machine tracks them as pending until acknowledged.

7. **Given** a user state machine needs to produce side effects (e.g., send a message to a client), **When** processing a command, **Then** the state machine returns both a response and a list of server-initiated requests, which the user must send through the client-server library.

8. **Given** a client receives a server-initiated request, **When** the client processes it, **Then** the client immediately sends an acknowledgment (ServerRequestAck) back to the server.

9. **Given** the server receives a ServerRequestAck for request ID N, **When** it is committed through Raft, **Then** the session state machine removes all pending server-initiated requests with ID ≤ N from its pending state (cumulative acknowledgment).

10. **Given** an external retry process needs to resend server-initiated requests, **When** it performs a dirty read of the state machine's unacknowledged list and applies retry policy locally, **Then** if the policy indicates requests need retry, it sends a GetRequestsForRetry command and the state machine atomically identifies requests, updates their lastSentAt, and returns the authoritative list. The process discards the dirty read data and uses only the command response. (Optimization: dirty read allows skipping command when nothing needs retry)

11. **Given** a Raft cluster performs a snapshot, **When** capturing state, **Then** both the user state machine state and the client-server session state (including response cache and pending server-initiated requests) are included in the snapshot.

### Edge Cases

- What happens when the response cache grows too large and needs eviction? (NOTE: The dissertation prescribes the "lowest sequence number protocol" for cache management - see Known Gaps section)
- What happens when a cached response is needed but has already been evicted? (NOTE: Requires RequestError response - see Known Gaps section)
- What happens if a user state machine's command processing is very slow?
- What happens if server-initiated request acknowledgments arrive out of order? (Not a problem with cumulative acknowledgment: ack for ID N removes all ≤ N regardless of order)

## Requirements


### Functional Requirements

#### State Machine Interface & Execution
- **FR-001**: System MUST allow developers to define custom state machines that accept commands and produce state transitions with responses
- **FR-002**: User state machines MUST process commands deterministically, accepting a command payload and current state, returning a new state and response
- **FR-003**: System MUST execute user state machines AFTER the client-server session state machine has performed idempotency checking
- **FR-004**: User state machines MUST NOT be invoked for duplicate commands (same session ID and request ID) - only the cached response is returned
- **FR-005**: State machines MUST be testable independently without requiring a full Raft cluster

#### Session-Based Idempotency (Chapter 6.3 of Raft Dissertation)
- **FR-006**: System MUST maintain a session for each connected client, tracking sequence numbers and responses as described in the Raft dissertation
- **FR-007**: Session state machine MUST filter duplicate requests by checking if a command's serial number (request ID) has already been executed for a given client session
- **FR-008**: System MUST cache responses for each session ID and request ID pair to enable immediate response to duplicate requests
- **FR-009**: System MUST support concurrent requests from a single client by tracking a set of sequence number and response pairs per session
- **FR-010**: Client sessions MUST support tracking the lowest sequence number for which the client has not yet received a response, allowing the state machine to discard responses for lower sequence numbers (NOTE: This requires the "Lowest Sequence Number Protocol" described in Known Gaps section to be implemented in client-server library first)
- **FR-011**: Session state MUST be replicated through the Raft log to ensure all servers agree on which commands are duplicates

#### Snapshot and State Management
- **FR-012**: System MUST support state snapshots using a single shared dictionary where session state and user state machine state use different key prefixes (e.g., "session/" and "user/")
- **FR-013**: Snapshot restoration MUST restore both the client-server session state and user state machine state from the shared dictionary
- **FR-014**: User state machines MUST NOT throw exceptions; errors must be modeled within the response payload that is returned to the client and cached for idempotency
- **FR-015**: System MUST NOT impose size limits on cached responses (for now)

#### State Machine Interface (Library Provides)
- **FR-016**: System MUST provide a state machine interface that accepts RaftAction events (ClientRequest, ServerRequestAck, SessionCreationConfirmed, SessionExpired) and returns state transitions
- **FR-017**: System MUST return responses that the user can send via ServerAction.SendResponse through the client-server library (library does NOT send responses directly)
- **FR-018**: System MUST return server-initiated requests that the user can send via ServerAction.SendServerRequest through the client-server library (library does NOT send requests directly)
- **FR-019**: System MUST process RaftAction.ClientRequest events by performing idempotency checking, invoking user state machine, caching responses, and returning the response
- **FR-020**: System MUST process RaftAction.ServerRequestAck events to remove acknowledged server-initiated requests from the session state machine; when processing an acknowledgment for request ID N, the system MUST remove all pending requests with ID ≤ N (cumulative acknowledgment)
- **FR-021**: System MUST allow user state machines to generate server-initiated requests as part of command processing, which are added to pending state and returned for the user to send

#### Server-Initiated Request Management
- **FR-022**: System MUST assign server-initiated request IDs starting at 1 for each new client session, with monotonically increasing IDs per session
- **FR-023**: Session state MUST track the last assigned server-initiated request ID for each session, even when the unacknowledged request list is empty
- **FR-024**: System MUST drop all pending server-initiated requests when a client session expires or is closed (expired sessions cannot return)
- **FR-025**: System MUST provide a "GetRequestsForRetry" command that atomically identifies requests needing resend, updates their lastSentAt timestamps, and returns the request list in the command response
- **FR-026**: Server-initiated request retry logic MUST be handled outside the state machine by a separate process that periodically sends the GetRequestsForRetry command (avoids separate query + update, keeps full responses out of Raft log)
- **FR-027**: Retry process SHOULD perform a dirty read of the state machine's unacknowledged request list and apply retry policy locally; only if the policy indicates requests need retrying should it send the GetRequestsForRetry command (optimization: dirty read bypasses Raft consensus for hint only, safe because command response through Raft is authoritative and dirty data is discarded; reduces unnecessary Raft log entries; worst case is one missed retry cycle if dirty read is stale)

### Key Entities

- **User State Machine**: Developer-defined business logic that processes commands. Contains:
  - State: The application-specific data it maintains
  - Command handler: Pure function that accepts (state, command) and returns (new state, response, optional server requests)
  
- **Session State Machine**: The client-server layer that wraps user state machines (Chapter 6.3 architecture). Contains:
  - Session tracking: Maps session ID to session metadata
  - Response cache: Maps (session ID, request ID) pairs to cached responses for idempotency (no size limit for now)
  - Sequence number tracking: Latest and set of pending sequence numbers per session
  - Pending server-initiated requests: Ordered list of server requests sent to each client, waiting for acknowledgment
  - Request ID assignment: Tracks last assigned server-initiated request ID per session (starts at 1, increments monotonically)
  - Request removal: When RaftAction.ServerRequestAck(sessionId, requestId) is processed, removes **all pending requests with ID ≤ requestId** (cumulative acknowledgment)
  - Session cleanup: Drops all pending server-initiated requests when session expires or closes
  
- **Client Session**: State maintained per connected client for linearizable semantics. Contains:
  - Session ID: Unique identifier assigned during RegisterClient
  - Latest sequence number: Highest request ID processed for this session
  - Pending responses: Set of (request ID, response) pairs for concurrent requests
  - Lowest pending request ID: Client-provided value indicating which responses can be discarded (NOTE: Currently not part of client-server protocol - see Known Gaps section)
  
- **Command**: An instruction from a client to modify state. Contains:
  - Session ID: Identifies which client sent the command
  - Request ID (sequence number): Unique serial number within the client's session
  - Payload: The actual command data to be processed by user state machine
  
- **Response Cache Entry**: Cached result of command execution for idempotency. Contains:
  - Session ID and Request ID: The key for identifying duplicates
  - Response payload: The result returned by the user state machine
  - Success/error indicator: Whether the command succeeded or failed
  
- **State Snapshot**: Point-in-time capture of entire replicated state for log compaction. Uses a single shared dictionary with key prefixes:
  - Session state (prefix "session/"): All client sessions, response cache, sequence numbers, last assigned server-initiated request IDs, and pending server-initiated requests
  - User state machine state (prefix "user/"): State data for the user state machine
  - Snapshot metadata: Log index, term, and configuration when snapshot was taken
  - Design rationale: Shared dictionary allows both state machines to contribute to same snapshot atomically
  
- **Server-Initiated Request Lifecycle** (State Machine + User Integration):
  1. User state machine returns server requests as part of command processing (library logic)
  2. Session state machine adds them to pending list for the target session (ordered, with IDs starting at 1, incrementing monotonically) (library logic)
  3. Session state machine tracks last assigned request ID per session (persisted even when unacked list is empty) (library logic)
  4. **State machine returns the requests to user** (library boundary)
  5. **User sends requests via ServerAction.SendServerRequest** through client-server library (user responsibility)
  6. Client receives request and sends ServerRequestAck (client-server library handles this)
  7. **User routes RaftAction.ServerRequestAck to state machine** (user responsibility)
  8. Session state machine processes acknowledgment and removes **all pending requests with ID ≤ requestId** (cumulative acknowledgment) (library logic)
  9. Pending requests are included in snapshots (in shared dictionary with "session/" prefix) (library logic)
  10. When session expires/closes, all pending requests for that session are dropped (library logic)
  
- **Server-Initiated Request Retry** (**User Responsibility** + State Machine Support):
  1. **User implements external process** that periodically performs a **dirty read** of the state machine's unacknowledged request list (bypasses Raft consensus)
  2. **User's process applies retry policy** locally to the dirty data (checks lastSentAt timestamps)
  3. If dirty read + policy indicates no requests need retry, skip sending command (optimization: avoids unnecessary Raft log entries)
  4. Otherwise, **user sends "GetRequestsForRetry" command** to state machine (authoritative, through Raft)
  5. State machine atomically (library logic):
     - Identifies requests needing resend (based on lastSentAt timestamp and retry policy)
     - Updates lastSentAt for those requests to current time
     - Returns the list of requests to resend (via command response)
  6. **User discards dirty read data** and uses only the command response (authoritative)
  7. **User sends requests to clients** via ServerAction.SendServerRequest through client-server library
  8. Benefits: Dirty read is safe (just optimization, command is authoritative), single Raft log entry when needed, atomic operation, responses stay in state (not written to log)
  9. This keeps retry policy logic separate from core state machine determinism and in user control

---

## Known Gaps in Client-Server Implementation

This feature depends on the existing client-server library. During specification, three missing capabilities and one performance optimization were identified that should be implemented as follow-up features:

### 1. Query Message (Read-Only Queries)
**Status**: Missing from current client-server implementation  
**Description**: Client-server protocol lacks a dedicated query message type for read-only operations. Unlike commands that modify state:
- Queries do NOT go through the state machine (read-only)
- Queries do NOT have a request ID (no idempotency needed for pure reads)
- Queries should use a correlation ID (UUID) for response matching
- Queries can be optimized as described in Chapter 6.4 of the Raft dissertation (bypassing the log)

**Impact on this feature**: User state machines may need to expose query operations separately from commands. This feature will initially focus on commands only, with query support to be added later.

### 2. Lowest Sequence Number Protocol (Response Cache Management)
**Status**: Missing from current client-server implementation  
**Description**: According to Chapter 6.3 of the Raft dissertation: "With each request, the client includes the lowest sequence number for which it has not yet received a response, and the state machine then discards all responses for lower sequence numbers."

**Current behavior**: Client-server protocol does not include this field in requests, so the server has no way to know which cached responses can be safely discarded.

**Required behavior**: 
- Client MUST include `lowestPendingSequenceNumber` field in every request
- Server MUST discard all cached responses for the session with sequence numbers lower than this value
- This allows deterministic, client-driven cache cleanup instead of relying on time-based or size-based eviction

**Impact on this feature**: Without this protocol:
- Response cache will grow unbounded for long-lived sessions
- Server-side eviction policies become necessary but are less safe
- Increases risk of cache misses requiring RequestError responses

### 3. RequestError Response (Evicted Cache Entry)
**Status**: Missing from current client-server implementation  
**Description**: When a client retries a request but the idempotency cache entry has already been evicted (due to space limits, time-based expiration, or premature eviction without the lowest sequence number protocol), the server has no way to inform the client that the request might have been executed but the response is no longer available.

**Current behavior**: Without this error type, a cache miss would cause re-execution of the command, potentially violating exactly-once semantics.

**Required behavior**: Server should return a `RequestError` indicating the cache entry was evicted, forcing the client to decide how to proceed (typically by establishing a new session).

**Impact on this feature**: The response cache implementation must handle eviction gracefully:
- If using lowest sequence number protocol (Feature #2): eviction should be rare, only for sessions that exceed other limits
- Without lowest sequence number protocol: eviction becomes common, making RequestError responses critical for correctness
- Both cases require communicating cache misses to clients via this error response

### 4. Cumulative Server-Initiated Request Acknowledgments (Performance Optimization)
**Status**: Future performance optimization (not yet implemented)  
**Description**: Currently, clients must send a ServerRequestAck for each server-initiated request individually. This optimization would allow clients to acknowledge only the last received request, using cumulative acknowledgment similar to TCP's ACK mechanism or Feature #2's lowest sequence number protocol.

**Current behavior**: 
- Client sends ServerRequestAck for each ServerRequest received
- Each acknowledgment goes through Raft log individually
- State machine removes requests one at a time as acks arrive

**Proposed optimized behavior**:
- Client piggybacks acknowledgment on subsequent messages (requests, keep-alives, etc.)
- Client only needs to acknowledge the **last** (highest) request ID received
- Server interprets this as: "I have received all requests up to and including this ID"
- State machine drops **all pending requests ≤ acknowledged request ID** in a single operation

**Benefits**:
- Reduces message overhead (no separate ack messages)
- Reduces Raft log entries (acks piggyback on other messages)
- Allows batch cleanup of pending requests
- Still maintains ordered delivery guarantee

**Implementation note**: Even with current implementation, the state machine **can already** drop all pending requests up to and including the acknowledged request ID when processing a ServerRequestAck, since Raft's ordering guarantees all lower-numbered requests were delivered first. The optimization only affects the client-side protocol and reduces message overhead.

**Impact on this feature**: The session state machine should be designed to support cumulative acknowledgment:
- When processing RaftAction.ServerRequestAck(sessionId, requestId), remove all pending requests for that session where `request.id <= requestId`
- This works correctly today even though clients send individual acks, and will support the optimization when added to the protocol

---

## Review & Acceptance Checklist
*GATE: Automated checks run during main() execution*

### Content Quality
- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

### Requirement Completeness
- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous  
- [x] Success criteria are measurable
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

---

## Execution Status
*Updated by main() during processing*

- [x] User description parsed
- [x] Key concepts extracted
- [x] Ambiguities resolved using Raft dissertation Chapter 6
- [x] User scenarios defined
- [x] Requirements generated based on linearizable semantics (Chapter 6.3)
- [x] Entities identified
- [x] Review checklist passed

---

## Architecture Reference

This specification is based on **Chapter 6 (Client interaction), Section 6.3 (Implementing linearizable semantics)** from the Raft dissertation by Diego Ongaro ([source](https://github.com/ongardie/dissertation/blob/master/clients/clients.tex)).

### Key Design Decisions (Resolved)

1. **Composition Strategy**: Sequential execution - session state machine runs BEFORE user state machine for idempotency checking, AFTER for response caching

2. **Error Handling**: User state machines MUST NOT throw exceptions. Errors are modeled within the response payload, which is cached for idempotency like any other response

3. **Atomicity**: Each command is atomic - the entire operation (idempotency check, user state machine execution, response caching) is atomic per Raft log entry

4. **Server-Initiated Request Management**: 
   - User state machines generate server requests during command processing
   - Session state machine assigns monotonically increasing IDs per session (starting at 1)
   - Tracks last assigned ID even when unacked list is empty
   - Uses cumulative acknowledgment: ack for ID N removes all ≤ N
   - Drops all pending requests when session expires/closes
   - Retry handled by external process via "GetRequestsForRetry" command that atomically retrieves + updates lastSentAt (efficient: single log entry, responses stay out of log)
   - Optimization: External process performs dirty read of unacked list + applies policy locally; only sends command if policy indicates retries needed (safe: dirty read is just hint, command response is authoritative; discards dirty data after decision)

5. **Snapshot Architecture**: Single shared dictionary with key prefixes:
   - Session state uses "session/" prefix
   - User state machine uses "user/" prefix  
   - Allows atomic snapshot capture of both state machines
   - No size limits on cached responses (for now)

6. **Out of Scope**: Commands during state machine initialization/shutdown are not addressed in this feature

---

## Scope and Responsibilities

### What This Library Provides (In Scope)

This library provides **state machine logic only**:

1. ✅ **State Machine Interface**: Accepts RaftAction events, returns state transitions and responses
2. ✅ **Session State Machine**: Idempotency checking, response caching, sequence number tracking
3. ✅ **User State Machine Composition**: Framework for defining and executing custom business logic
4. ✅ **Server-Initiated Request Management**: Tracks pending requests, assigns IDs, handles acknowledgments
5. ✅ **Snapshot Support**: Serializes/deserializes state to/from shared dictionary
6. ✅ **Cumulative Acknowledgment Logic**: Removes all requests ≤ acknowledged ID

### What the User Must Provide (Integration Glue)

The library user is responsible for:

1. ❌ **Client-Server Integration**: Wiring the state machine to the client-server library
2. ❌ **Event Routing**: 
   - Feed RaftAction.ClientRequest → state machine
   - Feed RaftAction.ServerRequestAck → state machine
   - Feed RaftAction.SessionCreationConfirmed → state machine
   - Feed RaftAction.SessionExpired → state machine
3. ❌ **Response Sending**: Take state machine responses and send via ServerAction.SendResponse
4. ❌ **Server Request Sending**: Take server-initiated requests and send via ServerAction.SendServerRequest
5. ❌ **Retry Process**: Implement external process that performs dirty reads and sends GetRequestsForRetry commands
6. ❌ **Raft Integration**: Connect state machine to Raft log application
7. ❌ **Transport**: The client-server library handles all ZeroMQ/network communication

### Design Rationale

This separation allows:
- **Flexibility**: Users can customize integration for their specific needs
- **Reusability**: State machine logic is independent of transport layer
- **Testability**: State machine can be tested without network/Raft infrastructure
- **Clear Boundaries**: State machine focuses on deterministic business logic

---

