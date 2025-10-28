# Feature Specification: Add client-server to kvstore, CLI, and watch command

**Feature Branch**: `004-add-client-server`  
**Created**: 2025-10-26  
**Status**: Draft  
**Input**: User description: "add client-server to kvstore example project, remove the existing HttpServer. Add a new kvstore-cli to demostrate the client. To demostrate the server request, let's add a watch command to kvstore, session will be able to watch a specific key, and for the life of that session will receive updates when that key is updated. Manage the subscriptions in the hmap, remove any subscription when the session expire."

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
As a developer using the kvstore example, I want to run a server that exposes a client-server interface (instead of HTTP) and use a `kvstore-cli` to set/get keys and to watch a specific key, so that during my session I receive real-time updates when that key changes, and the watch stops when my session ends.

### Acceptance Scenarios
1. Given the kvstore server is running and a client establishes a session, When the client issues a watch command for key "k1" and another client updates "k1", Then the watching client sees an update event for "k1" during the active session.
2. Given an active watch on key "k1" for a session, When the session expires or is closed, Then the watch stops and no further updates are delivered to that session.
3. Given the project previously exposed an HTTP server, When the feature is applied, Then the example uses only the client-server interface and the HTTP server is not present in the example run path and docs.
4. Given multiple clients watch the same key, When the key is updated, Then all active sessions with a watch on that key receive the update during their session lifetimes; delivery ordering is not guaranteed and fan-out includes all sessions found in the subscriptions map at delivery time.
5. Given a client starts a watch on a key that currently has a value, When the watch begins, Then the client receives the current value immediately and continues to receive subsequent changes.

### Edge Cases
- Deletes are out of scope for the example project and are not supported.
- If the same session attempts to watch the same key multiple times, subsequent watch requests have no effect (idempotent).
- Reconnection behavior is managed by client-server; watches persist while the session remains active. Upon receiving an expire-session, the session will not resume and all subscriptions are removed.
- If a key is updated many times rapidly, all updates are delivered (no coalescing). Ordering is not guaranteed across sessions.
- Unlimited concurrent watches per session and across the server.

## Requirements (mandatory)

### Functional Requirements
- **FR-001**: The kvstore example MUST use the client-server architecture; the existing HTTP server MUST be removed from the example path and documentation.
- **FR-002**: A `kvstore-cli` MUST be provided to demonstrate client interactions with the server.
- **FR-003**: The CLI MUST support a watch command that subscribes the current session to updates for a specific key for the duration of that session.
- **FR-004**: The watch feature MUST emit notifications for create and update events on the watched key.
- **FR-005**: When a session expires or is closed, the system MUST remove associated subscriptions so that no further updates are sent to that session.
- **FR-006**: Subscriptions MUST be tracked per session and per key to enable multiple simultaneous watches across sessions.
- **FR-008**: The CLI MUST display incoming watch updates in real-time while the watch is active.
- **FR-009**: The system MUST fan out updates to all sessions currently subscribed to the key; delivery ordering across sessions is not guaranteed.
- **FR-011**: Upon subscription, the watch MUST return the current value of the key (if any) immediately, then deliver subsequent changes.
 - **FR-012**: The system MUST allow a session to watch multiple keys concurrently and impose no feature-level limit on the number of concurrent watches per session or across the server.
 - **FR-013**: Duplicate watch requests from the same session for the same key MUST be idempotent and have no effect.
  - **FR-014**: The system MUST deliver all updates for a watched key without coalescing; ordering across sessions is not guaranteed.
  - **FR-016**: Implement a retry server-requests job that initiates `SessionCommand.GetRequestsForRetry`, and MUST first check `hasPendingRequests` (dirty read) to avoid spamming the Raft log.
  - **FR-017**: The system MUST map server library `RaftAction` to `SessionCommand`, and map state machine responses back to server `ServerAction` for delivery.

### Key Entities (include if feature involves data)
- **Session**: Represents a client's active interaction window; has an identity and a lifetime; governs eligibility to receive watch events.
- **Subscription**: Association between a `Session` and a specific `Key`; removed when the session ends; enables event delivery to the session.
- **KeyValue**: A key with its latest value; subject of watch subscriptions; may undergo create/update.
- **Notification/Event**: An update related to a watched `KeyValue` sent to all subscribed sessions during their lifetimes; payload includes the key and value.

---

## Dependencies & Assumptions
- Client-server capabilities are provided by libraries from Feature 001 (client-server) and are reused here. No new transports or protocols are introduced.
- Session lifecycle and expiry are governed by the session state machine from Feature 002, already integrated into `kvstore`.
- The example project does not include an HTTP server.

### CLI Configuration
- CLI discovers the Raft cluster endpoints via either:
  - Command-line flag `--endpoints` (comma-separated list), or
  - Environment variable `KVSTORE_ENDPOINTS` (comma-separated list)
- If neither provided, default to `localhost` development endpoints.

---

## Clarifications

### Session
- Initial snapshot: upon watch subscription, return the current value immediately.
- Fan-out and ordering: deliver to all subscribed sessions found at delivery time; ordering across sessions is not guaranteed.
- Reconnection/expiry: handled by client-server; on expire-session, the session will not resume and subscriptions are removed.
- Idempotency: duplicate watch requests from the same session for the same key have no effect.
- Concurrency limits: unlimited concurrent watches per session and across the server.
- Delivery semantics: all updates are delivered (no coalescing).
- Event payload: notification includes key and value.

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

