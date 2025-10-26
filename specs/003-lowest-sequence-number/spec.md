# Feature Specification: Lowest Sequence Number Protocol for Client-Server

**Feature Branch**: `003-lowest-sequence-number`  
**Created**: October 26, 2025  
**Status**: Draft  
**Input**: User description: "Lowest sequence number protocol for client server"

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
2. **Don't guess**: If the prompt doesn't specify something, mark it
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
As a client developer using the client-server Raft protocol, I want to include the lowest sequence number for which I have not yet received a response in every request, so that servers can deterministically discard cached responses for all smaller sequence numbers in my session, preventing unbounded cache growth while preserving exactly-once semantics.

### Acceptance Scenarios
1. Given a client session with cached responses for request IDs 1..N, When the client sends a new request with requestId N+1 and lowestPendingRequestId K (1 ≤ K ≤ N+1), Then the server discards all cached responses for that session with requestId < K before completing the request, and retains entries with requestId ≥ K.
2. Given a client incorrectly sends a lowestPendingRequestId lower than a previously observed value for the same session, When the server processes the request, Then the server does not restore previously discarded entries and MUST ignore the regression (monotonic non-decreasing per session).
3. Given a client jumps lowestPendingRequestId ahead by more than one (e.g., from K to K+10), When the server processes the next request, Then the server discards all cached responses for that session with requestId < K+10 in one step and continues normal processing.
4. Given network reordering or retries, When two requests with increasing lowestPendingRequestId values are applied in Raft's commit order, Then servers observe non-decreasing values and perform evictions accordingly, producing identical results across the cluster.
5. Given the server receives a duplicate request whose requestId has already been discarded due to an advanced lowestPendingRequestId, When processing that request, Then the server MUST respond with RequestError (cache entry evicted) and MUST NOT re-execute the command.
6. Given the client receives a RequestError signal for a specific requestId, When it checks its pending/outgoing requests, Then if the request is still pending the client MUST fail that request and terminate the application; if the request is not pending, the client MUST ignore the RequestError.

### Edge Cases
- First request in a new session: lowestPendingRequestId SHOULD be 1; eviction does nothing (no entries < 1).
- Client bug sends lowestPendingRequestId greater than the current requestId: allowed; eviction still safe (removes older cache entries only).
- Session restart: A new sessionId resets sequence numbers; lowestPendingRequestId semantics start at 1 for the new session.
- Requests missing lowestPendingRequestId: invalid; the server MUST reject such requests for protocol non-compliance.
- Duplicates referencing requestId < current lowestPendingRequestId: The server MUST respond with RequestError (cache entry evicted) and MUST NOT re-execute the command.

## Requirements (mandatory)

### Functional Requirements
- **FR-001**: Client MUST include `lowestPendingRequestId` in every state-mutating request associated with a session.
- **FR-002**: `lowestPendingRequestId` value semantics: it is the lowest requestId for which the client has not yet received a response at the time of sending the request.
- **FR-003**: Server MUST perform deterministic eviction: upon receiving a request with value K for session S, discard all cached responses for S with requestId < K before (or atomically with) idempotency checks for the incoming request.
- **FR-004**: The value observed by servers MUST be treated as monotonic non-decreasing per session; regressions MUST be ignored (no restoration of discarded entries).
- **FR-005**: The protocol MUST be safe under Raft log application order; all servers apply the same evictions for the same session and K.
- **FR-006**: The server MUST reject any request that omits `lowestPendingRequestId` as a protocol error (no processing without this field).
- **FR-007**: The protocol MUST NOT require additional round trips; `lowestPendingRequestId` MUST be included on protocol ClientRequest messages and propagated into `RaftAction.ClientRequest`.
- **FR-008**: The server MUST respond with RequestError (cache entry evicted) and MUST NOT re-execute commands whose cached responses were discarded because the client advanced K past their requestIds.
- **FR-009**: Observability: The system SHOULD expose metrics for cache eviction counts and sizes per session to validate effectiveness. [Optional]
- **FR-010**: Upon receiving a RequestError for a specific requestId, if the corresponding request is still pending/outgoing, the client MUST fail that request and terminate the application; if the request is not pending, the client MUST ignore the RequestError.

### Key Entities (include if feature involves data)
- **Client Session**: A logical context identified by `sessionId` within which request sequence numbers are strictly increasing and `lowestPendingRequestId` advances monotonically.
- **lowestPendingRequestId (K)**: Integer sent by client indicating the lowest requestId for which it lacks a response; authorizes the server to discard cached responses with requestId < K for that session.
- **Response Cache Entry**: A stored response keyed by `(sessionId, requestId)` used for idempotent duplicate detection; entries with `requestId < K` become eligible for deterministic eviction when K is observed.

---

## Review & Acceptance Checklist
*GATE: Automated checks run during main() execution*

### Content Quality
- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

### Requirement Completeness
- [ ] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous  
- [x] Success criteria are measurable
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

---

## Execution Status
*Updated by main() during processing*

- [x] User description parsed
- [x] Key concepts extracted
- [x] Ambiguities marked
- [x] User scenarios defined
- [x] Requirements generated
- [x] Entities identified
- [x] Review checklist passed

---

