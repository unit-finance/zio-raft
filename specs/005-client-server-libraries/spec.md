# Feature Specification: Add Query support to client-server libraries

**Feature Branch**: `005-client-server-libraries`  
**Created**: 2025-10-28  
**Status**: Draft  
**Input**: User description: "client-server libraries are missing support for query. Query doesn't have request id, they are not idempotent, and not process by the same machine. Instead of request id they should have correlation id, which is UUID (represneted as string in the protocol). Client should retry queries same way it retries ClientRequest. Server should add new action for Query and allow to answer them with QueryResponse."

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

## User Scenarios & Testing *(mandatory)*

### Primary User Story
As an application developer, I need a read-only Query operation that never goes through the replicated state machine, makes no changes to replicated state, and is served by the current leader to provide linearizable read semantics. It uses a correlationId (opaque string, e.g., UUID) for matching responses and follows the same retry behavior as ClientRequest, so reads are consistent, low-latency, and resilient without risking state mutations.

### Acceptance Scenarios
1. Given replicated state S and a running cluster, When a client issues a Query and it completes, Then S remains unchanged and no state mutation occurs due to the Query.
2. Given a running cluster with a current leader, When a client issues a Query, Then the Query is sent to and handled by the current leader, and the result reflects linearizable read semantics.
3. Given an application issues a Query, When the client prepares the request, Then the client generates a new correlationId (opaque string, e.g., UUID) and includes it in the request.
4. Given the server receives a Query with a correlationId, When the server completes processing, Then it returns a QueryResponse that includes the same correlationId so the client can match the response to the originating Query.
5. Given a transient failure or timeout during Query execution, When the client's retry policy triggers, Then the client retries the Query using the same policy as ClientRequest and preserves the original correlationId across retries.
6. Given multiple in-flight Queries from the same client, When QueryResponses arrive out of order and possibly from different machines, Then the client matches each response to the correct originating Query using the correlationId.
7. Given a Query completes after the client has already timed out or cancelled the operation, When a QueryResponse with the correlationId arrives, Then the client does not surface a duplicate completion to the caller and discards late responses.

### Edge Cases
- Queries are served by the current leader; if leadership changes during processing, the client retries against the new leader; responses may arrive after retry, but only one completion is delivered.
- Duplicate processing may occur due to retries; application semantics must tolerate duplicates (no idempotency is assumed for Query).
- If duplicate QueryResponses arrive with the same correlationId, the client must deliver at most one completion to the caller and ignore extras.
- Queries MUST NOT change the replicated state and are not processed by the state machine.
- Retry/backoff/timeouts: identical to ClientRequest configuration.
- Payload size limits and performance targets: identical to ClientRequest.

## Requirements *(mandatory)*

### Functional Requirements
- **FR-001**: The system MUST introduce a Query action in the client-server libraries so clients can submit queries to the server.
- **FR-002**: The system MUST introduce a QueryResponse that carries the outcome of processing a Query.
- **FR-003**: Queries MUST NOT use a requestId; instead, each Query MUST carry a correlationId used solely for matching a QueryResponse to its Query.
- **FR-004**: The correlationId MUST be represented in the protocol as an opaque string; UUID is recommended but not required. No canonical formatting is enforced.
- **FR-005**: The client MUST generate a correlationId for every Query and include it with the request; user-supplied correlationIds MUST NOT be accepted and MUST be ignored or rejected by the client API.
- **FR-006**: The client MUST retry Queries using the same retry policy as ClientRequest (including backoff and limits) and MUST preserve the same correlationId across retries.
- **FR-007**: Queries MUST be served by the current leader to ensure linearizable read semantics.
 - **FR-008**: The client MUST match a QueryResponse to the originating Query using the correlationId, even if responses arrive out of order across retries or redirects.
- **FR-009**: The system MUST NOT rely on Query idempotency; duplicate processing MAY occur under retries, and the client MUST deliver at most one completion to the caller per correlationId.
- **FR-010**: The server MUST be able to answer Queries by producing a QueryResponse, and clients MUST expose an API to await/receive the corresponding QueryResponse.
 - **FR-011**: Queries MUST NOT change the replicated state and MUST NOT be processed by the state machine.
 - **FR-012**: Followers MUST NOT accept client connections for Queries; clients send Queries only to the current leader.
 - **FR-013**: Queries MUST provide linearizable read semantics with respect to ClientRequest operations.
 - **FR-014**: Query operations require no authentication or authorization (consistent with current ClientRequest behavior).
 - **FR-015**: The default client retry policy for Query MUST equal ClientRequest defaults.
 

### Key Entities *(include if feature involves data)*
- **Query**: A client-initiated operation that carries a correlationId (opaque string, e.g., UUID) and a request payload; is executed by the current leader and may be retried upon leader changes.
- **QueryResponse**: The server's response to a Query; includes the same correlationId and a result payload or error information.
- **CorrelationId**: An opaque string (e.g., UUID) generated by the client and used to tie a Query to its QueryResponse across retries and out-of-order delivery.
- **Retry Policy**: The behavior governing Query retries; mirrors ClientRequest retry policy parameters and configurations.

---

## Clarifications

- **ClientRequest vs Query**:
  - ClientRequest changes the replicated state by going through the state machine and is idempotent.
  - Query does not change the replicated state, is not processed by the state machine, and is not idempotent. It uses a correlationId (opaque string, e.g., UUID) for response matching instead of a requestId.

- **Connection model**:
  - Unchanged: clients connect only to the current leader; followers do not accept client connections for Queries.

- **CorrelationId generation**:
  - Always generated by the client; callers cannot provide or override the value; preserved across retries.

### Session 2025-10-28
- Q: What authentication/authorization policy should apply to Query operations? → A: No auth
- Q: What should the default client retry policy for Query be? → A: Same as ClientRequest defaults

---

## Review & Acceptance Checklist
*GATE: Automated checks run during main() execution*

### Content Quality
- [ ] No implementation details (languages, frameworks, APIs)
- [ ] Focused on user value and business needs
- [ ] Written for non-technical stakeholders
- [ ] All mandatory sections completed

### Requirement Completeness
- [ ] No [NEEDS CLARIFICATION] markers remain
- [ ] Requirements are testable and unambiguous  
- [ ] Success criteria are measurable
- [ ] Scope is clearly bounded
- [ ] Dependencies and assumptions identified

---

## Execution Status
*Updated by main() during processing*

- [ ] User description parsed
- [ ] Key concepts extracted
- [ ] Ambiguities marked
- [ ] User scenarios defined
- [ ] Requirements generated
- [ ] Entities identified
- [ ] Review checklist passed

---


