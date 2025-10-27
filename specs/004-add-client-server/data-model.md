# Data Model: Add client-server to kvstore, CLI, and watch command

## Entities

### Session
- Represents a client's active interaction window
- Attributes: `sessionId`
- Lifecycle: Active → Expired (on expire-session)

### Subscription
- Associates a `sessionId` with a `key`
- Removed when the session expires
- Multiple subscriptions per session allowed (unlimited)

### KeyValue
- The key and its current value
- Operations: create/update (deletes out of scope)

### Notification/Event
- Payload: `key`, `value`
- Emitted to all sessions subscribed to `key` at delivery time
- Delivery: all updates delivered, no coalescing; no cross-session ordering guarantee

## Relationships
- One Session ↔ many Subscriptions
- One KeyValue ↔ many Sessions (fan-out)

## Derived Views (conceptual)
- Subscriptions Map: `key -> Set[sessionId]`
- Session Index: `sessionId -> Set[key]`

## Validation Rules
- Duplicate watch requests (same session/key) are idempotent
- Watch requires active session (enforced by client-server libraries)

## Integration Mapping (conceptual)
- Server `RaftAction` → `SessionCommand`
- State machine responses → server `ServerAction`
- Retry job: check `hasPendingRequests` before `SessionCommand.GetRequestsForRetry`
