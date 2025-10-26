# Research: Lowest Pending Request Id Protocol

**Feature**: 003-lowest-sequence-number  
**Date**: October 26, 2025  
**Status**: Complete

## Overview
Deterministic server-side eviction via `lowestPendingRequestId` carried on ClientRequest to bound response cache growth while preserving exactly-once semantics.

## Key Decisions
1. Field name and carrier
   - Decision: `lowestPendingRequestId: RequestId` on ClientRequest
   - Rationale: Aligns with Raft dissertation 6.3 (lowest sequence number protocol); single hop, no extra round trips
   - Alternatives: Time/size-based eviction (rejected as nondeterministic)

2. Mandatory protocol (no legacy support)
   - Decision: Requests missing the field are rejected
   - Rationale: Ensures deterministic eviction and prevents unbounded growth

3. Duplicate below K behavior
   - Decision: Respond with RequestError; never re-execute
   - Rationale: Preserves exactly-once despite eviction

4. Server handling of RequestError received
   - Decision: If corresponding request still pending/outgoing → fail and terminate app; else ignore
   - Rationale: Surface irrecoverable correctness breach ASAP; ignore stale errors

5. Safety and ordering
   - Decision: Treat K as monotonic non-decreasing per session; ignore regressions
   - Rationale: Raft apply order ensures consistent eviction across replicas

## References
- Raft Dissertation §6.3 (Implementing linearizable semantics)
- Existing ZIO Raft client-server modules (protocol/server)


