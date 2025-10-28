# Research: Client-server Query support (+ kvstore GET via Query)

## Decisions
- Query is read-only, leader-served, linearizable.
- CorrelationId is opaque, client-generated, preserved across retries.
- No auth for Query (same as current ClientRequest behavior).
- Retry/backoff/timeouts and payload/perf configs are identical to ClientRequest.
- kvstore example replaces GET with Query-based GET.

## Rationale
- Leader-served preserves linearizable semantics with minimal change to architecture.
- Opaque correlationId avoids coupling to UUID specifics and keeps protocol flexible.
- Matching ClientRequest retry/perf simplifies client behavior and tuning.
- Using Query for kvstore GET demonstrates intended read path and avoids state-machine involvement.

## Alternatives Considered
- Follower-served reads with quorum checks: rejected (out of scope, higher complexity).
- Allow user-provided correlationId: rejected (misuse risk, harder invariants).
- Separate retry policy for Query: rejected (adds config surface without clear benefit).
