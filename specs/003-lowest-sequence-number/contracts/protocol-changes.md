# Contracts: Client-Server Protocol Changes

## Client → Server: ClientRequest (Add Field)

- Add field: `lowestPendingRequestId: RequestId` (mandatory)
- Semantics: lowest requestId for which client lacks a response at send time
- Backward compatibility: Not supported; requests omitting the field are rejected

## Server → Client: RequestError (Protocol message)

- Purpose: Inform client that a retried request's cached response was deterministically evicted (requestId < lowestPendingRequestId)
- Fields:
  - `requestId`: RequestId
  - `reason`: enum { ResponseEvicted }

## Client Handling of Incoming RequestError (Informative)

- If the corresponding pending request exists on the client: fail it and terminate the application
- If no corresponding pending request exists: ignore

## Server Internal Action Mapping

- Protocol ClientRequest → RaftAction.ClientRequest includes `lowestPendingRequestId`
- New internal action for error: `ServerAction.SendRequestError`
- `ServerAction.SendRequestError` → protocol `RequestError` (serialization/mapping)


