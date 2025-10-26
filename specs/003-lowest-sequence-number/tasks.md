# Tasks: Lowest Pending Request Id Protocol

## Phase 0: Research (Complete)
- [x] R001 Document protocol decisions and rationale

## Phase 1: Design (Complete)
- [x] D001 Data model for lowestPendingRequestId and RequestError semantics
- [x] D002 Contracts for protocol changes (ClientRequest, RequestError, server handling)
- [x] D003 Quickstart showing client progression of K

## Phase 2: Implementation

### Protocol (client-server-protocol)
- [x] P001 Add `lowestPendingRequestId: RequestId` to protocol `ClientRequest` (wire format + codec)
- [x] P002 Add new protocol message `RequestError` with fields { requestId, reason=ResponseEvicted }
- [x] P003 Update protocol docs and comments to mark field/message mandatory/semantics

### Server (client-server-server / RaftServer)
- [x] S001 Map protocol `ClientRequest.lowestPendingRequestId` → `RaftAction.ClientRequest.lowestPendingRequestId`
- [x] S002 Support sending new server action `ServerAction.SendRequestError` to clients (forwarding/path wiring)

### Client (client-server-client)
- [x] C001 Compute and send `lowestPendingRequestId = min(pendingRequests)` with each protocol `ClientRequest`
- [x] C002 Handle `RequestError`: if request still pending → fail it and terminate app; else ignore

### Tests
- [x] T001 Mapping preserves `lowestPendingRequestId` protocol ↔ action
- [x] T002 Client computes K as min(pendingRequests) and includes it on requests
- [x] T003 Server emits `ServerAction.SendRequestError` and client receives protocol `RequestError`

### Docs
- [ ] D001 Update docs referencing new field and server message behavior

## Dependencies
P001–P003 → S001–S002 → C001–C002 → T001–T003 → D001


