# Data Model: Lowest Pending Request Id Protocol

## Entities

- ClientRequest (Protocol)
  - Fields (additive change):
    - lowestPendingRequestId: RequestId (mandatory)
  - Semantics:
    - K = lowest requestId for which client lacks a response at send time
    - Monotonic non-decreasing per session; regressions ignored by server

- RaftAction.ClientRequest (Server internal action)
  - Fields (additive change):
    - lowestPendingRequestId: RequestId (mandatory)
  - Mapping: Protocol ClientRequest → RaftAction.ClientRequest preserves the field


- RequestError (Server → Client)
  - Trigger: Duplicate request where requestId < current K (entry evicted)
  - Effect: Client must treat session as inconsistent with server’s cache; remediation outside this feature
  - Transport: New `ServerAction.SendRequestError`

- Client Pending Requests
  - Definition: Set of in-flight requestIds per session
  - Computation: `lowestPendingRequestId = min(pendingRequests)`

## Invariants
- For any session S, observed K values are non-decreasing in Raft apply order
- If requestId < K at time of apply, server MUST NOT re-execute; return RequestError
- Missing lowestPendingRequestId is protocol error; request rejected


