# Quickstart: Lowest Pending Request Id

## Client: Sending Requests

- Include `lowestPendingRequestId` with every ClientRequest
- Maintain per-session requestId and lowestPendingRequestId

Example flow:

1. Create session S
2. Send requestId=1, lowestPendingRequestId=1
3. After receiving response for 1, next send requestId=2, lowestPendingRequestId=2 (evicts <2)
4. If retrying requestId=1 after eviction, expect `RequestError`

## Server: Expected Behavior (Informative)

- On apply of ClientRequest(S, R, K): evict cache entries where rId < K for S
- If R < K: return `RequestError`; never re-execute
- Else: execute, cache response at (S, R)


