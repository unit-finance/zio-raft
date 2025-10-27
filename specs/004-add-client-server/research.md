# Phase 0 Research: Add client-server to kvstore, CLI, and watch command

## Decisions
- Event payload fields = key, value
  - Rationale: Minimal, sufficient for example; deletes out of scope
  - Alternatives: Include timestamps/versioning → deferred for simplicity
- Delivery semantics = deliver all updates, no coalescing; no cross-session ordering
  - Rationale: Matches client-server guarantees and example goals
  - Alternatives: Exactly-once/ordering → higher complexity, unnecessary for example
- Initial snapshot on watch = return current value immediately
  - Rationale: Better UX; aligns with watch semantics in many KV systems
  - Alternatives: Only subsequent changes → requires extra read in clients
- Reconnection/expiry = managed by client-server; expire-session is terminal
  - Rationale: Division of responsibilities; simplifies example logic
  - Alternatives: Session resumption → not required for example scope
- Concurrency limits = unlimited watches
  - Rationale: Keep example unbounded; real systems may enforce quotas
  - Alternatives: Configurable limits → deferred
- Retry job = check `hasPendingRequests` (dirty read) before `GetRequestsForRetry`
  - Rationale: Prevent Raft log spam; efficient retries
  - Alternatives: Periodic unconditional `GetRequestsForRetry` → wasteful
- Mapping = server `RaftAction` → `SessionCommand`; responses → server `ServerAction`
  - Rationale: Integrates Feature 001 and 002 cleanly
  - Alternatives: Duplicate pathways → violates reuse requirement

## Open Questions
- None (all clarifications captured in spec Clarifications section)
