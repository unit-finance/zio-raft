# TypeScript Client Implementation Gaps

**Created**: 2026-01-20  
**Status**: Active  
**Components**: `typescript-client`, `kvstore-cli-ts`

This document tracks identified functionality gaps between the specification requirements and current implementation.

---

## Summary

| Priority | Count | Description |
|----------|-------|-------------|
| 🔴 Critical | 3 | Core functionality broken or missing |
| 🟡 Moderate | 4 | Incomplete features or code quality issues |
| 🟢 Minor | 3 | Code quality improvements and cleanup |

---

## 🔴 Critical Gaps

### GAP-001: Incomplete Resend Logic After Reconnection

**Component:** `typescript-client`  
**Location:** `state/clientState.ts:503-509`, `state/clientState.ts:1446-1447`  
**Spec Requirements:** FR-008, FR-013

#### Problem

The state machine marks pending requests for resend via `resendAll()` and `resendExpired()`, but **returns `messagesToSend: []`**. The comments state "The actual resending will be handled by RaftClient" but `client.ts` has **no code to actually resend** these messages after reconnection.

#### Evidence

```typescript
// clientState.ts:498-513
// Mark pending requests for resend (client will handle actual sending)
const now = new Date();
state.pendingRequests.resendAll(now);
state.pendingQueries.resendAll(now);

return {
  newState,
  messagesToSend: [],  // ← Empty! Nothing actually resent
  eventsToEmit: [...],
};
```

#### Impact

- Requests submitted during disconnection may never be sent after reconnection
- Violates FR-008: "Promise MUST queue the request and wait for reconnection, then send the request"
- Violates FR-013: "client MUST maintain a queue of pending requests and ensure they are sent/retried upon reconnection"

#### Suggested Fix

Either:
1. Generate `ClientRequest`/`Query` messages in the state machine and return them in `messagesToSend`
2. Or have `client.ts` check pending requests after state transitions to Connected and resend them

---

### GAP-002: Multiple Consumer Race Condition in `onServerRequest()`

**Component:** `typescript-client`  
**Location:** `client.ts:268-285`  
**Spec Requirements:** FR-012

#### Problem

Each call to `onServerRequest()` spawns a **new async loop** consuming from the same queue. Multiple handlers race for messages instead of each receiving all messages.

#### Evidence

```typescript
// client.ts:268-285
onServerRequest(handler: (request: ServerRequest) => void): void {
  // Consume from server request queue in background
  (async () => {
    for await (const request of this.serverRequestQueue) {  // ← Each call creates new consumer
      try {
        handler(request);
      } catch (err) {
        this.emit(ClientEvents.ERROR, err instanceof Error ? err : new Error(String(err)));
      }
    }
  })().catch(...);
}
```

#### Impact

```typescript
client.onServerRequest(handler1);  // Spawns loop1
client.onServerRequest(handler2);  // Spawns loop2

// Server sends 3 requests:
// - req1 → handler1 (random)
// - req2 → handler2 (random)  
// - req3 → handler1 (random)
// Requests are randomly distributed, not delivered to all handlers!
```

#### Suggested Fix

Option A: Single Handler Pattern
```typescript
private serverRequestHandler: ((request: ServerRequest) => void) | null = null;

onServerRequest(handler: (request: ServerRequest) => void): void {
  if (this.serverRequestHandler !== null) {
    throw new Error('Handler already registered. Call removeServerRequestHandler() first.');
  }
  this.serverRequestHandler = handler;
}
```

Option B: EventEmitter Pattern
```typescript
// Use standard EventEmitter instead of custom queue
client.on('serverRequest', (request) => { ... });
```

---

### GAP-003: Watch Re-Registration Not Implemented

**Component:** `kvstore-cli-ts`  
**Location:** `kvClient.ts:138-152`, `commands/watch.ts`  
**Spec Requirements:** FR-022

#### Problem

The spec (FR-022) requires: "watch command MUST handle reconnection transparently, automatically re-registering the watch".

The current implementation calls `client.watch(key)` once at startup. If the underlying RaftClient reconnects (e.g., after leader change or network interruption), the watch is **not automatically re-registered** on the server.

#### Evidence

```typescript
// commands/watch.ts:64-74
// 4. Connect to cluster
await client.connect();

// 5. Register watch
await client.watch(key);  // ← Only registered once

// 7. Iterate over notifications
for await (const notification of client.notifications()) {
  // If reconnection happens, watch is lost on server
  // No reconnection handler to re-register!
}
```

#### Impact

- After network interruption and reconnection, user stops receiving notifications
- User must manually restart the watch command
- Violates FR-022: "handle reconnection transparently, automatically re-registering the watch"

#### Suggested Fix

Listen for reconnection events and re-register the watch:

```typescript
// In KVClient or watch.ts
this.raftClient.on(ClientEvents.CONNECTED, async () => {
  if (this.activeWatches.size > 0) {
    for (const key of this.activeWatches) {
      await this.watch(key);  // Re-register on reconnection
    }
  }
});
```

---

## 🟡 Moderate Gaps

### GAP-004: Notification `sequenceNumber` Always Zero

**Component:** `kvstore-cli-ts`  
**Location:** `codecs.ts:139`  
**Spec Requirements:** FR-020

#### Problem

The `decodeNotification` function hardcodes `sequenceNumber: 0n` instead of extracting it from the server message.

```typescript
return {
  timestamp: new Date(),
  sequenceNumber: 0n, // TODO: Extract from metadata if available
  key,
  value,
};
```

#### Impact

- FR-020 requires "verbose details including: timestamp, sequence number"
- Sequence numbers are always displayed as 0, losing ordering/debugging information

---

### GAP-005: Dead Code in `decodeGetResult`

**Component:** `kvstore-cli-ts`  
**Location:** `codecs.ts:105-106`

#### Problem

Unreachable code after a throw statement:

```typescript
throw new ProtocolError(`Invalid hasValue byte: ${hasValue} (expected 0x00 or 0xFF)`);
// TODO (eran): Dead code - this line is unreachable after the throw above. Remove it.
throw new ProtocolError(`Invalid hasValue byte: ${hasValue} (expected 0 or 1)`);
```

#### Impact

- Code clutter, potential confusion

---

### GAP-006: Unit Tests Incomplete

**Component:** `typescript-client`  
**Location:** `tests/unit/state/clientState.test.ts:83-103`

#### Problem

Multiple state machine test cases are placeholders with empty implementations:

```typescript
it('TC-STATE-003: ConnectingNew handles SessionCreated', () => {
  // TODO: Implement full test
  expect(true).toBe(true);
});

it('TC-STATE-004: ConnectingNew handles SessionRejected retry', () => {
  // TODO: Implement full test
  expect(true).toBe(true);
});

it('TC-STATE-005: Connected handles SubmitCommand', () => {
  // TODO: Implement full test
  expect(true).toBe(true);
});

it('TC-STATE-006: Connected handles ClientResponse', () => {
  // TODO: Implement full test
  expect(true).toBe(true);
});
```

#### Impact

- Critical state transitions not tested
- Regressions may go unnoticed

---

### GAP-007: Type Safety Issue - `as any` Cast

**Component:** `typescript-client`  
**Location:** `state/clientState.ts` (action handling)

#### Problem

The `ConnectAction` interface doesn't include `resolve`/`reject` callbacks but they're used at runtime, requiring unsafe casts:

```typescript
// "as any" cast indicates type system mismatch
connectResolve: (action as any).resolve, 
connectReject: (action as any).reject,
```

#### Impact

- Type safety bypassed
- Runtime errors possible if action structure changes

---

## 🟢 Minor Gaps

### GAP-008: Error Swallowing Issues

**Component:** Both  
**Locations:**
- `client.ts:275-279` - `onServerRequest` handler errors emitted as events but may be silently lost
- `kvClient.ts:85-93` - `disconnect()` errors are logged but swallowed

#### Problem

```typescript
// client.ts - Errors only emitted, may be lost if no listener
catch (err) {
  // TODO (eran): Error swallowing issue - user handler errors are only emitted as events.
  // If nobody listens to ERROR, bugs are silently swallowed.
  this.emit(ClientEvents.ERROR, err instanceof Error ? err : new Error(String(err)));
}

// kvClient.ts - Errors logged and swallowed
catch (err) {
  // TODO (eran): Disconnect errors are swallowed - callers can't know if disconnect
  // actually succeeded.
  console.error('Warning: Failed to disconnect from cluster:', err);
  this.isConnected = false;
}
```

---

### GAP-009: Code Duplication in Commands

**Component:** `kvstore-cli-ts`  
**Location:** `commands/set.ts`, `commands/get.ts`, `commands/watch.ts`

#### Problem

All three command files have nearly identical boilerplate for:
- Client creation
- Connect/disconnect
- Error handling

```typescript
// TODO (eran): Code duplication - set.ts, get.ts, and watch.ts have nearly identical
// boilerplate: client creation, connect/disconnect, error handling. Consider extracting
// a helper like: withKVClient(endpoints, async (client) => { ... }) to reduce duplication.
```

---

### GAP-010: No Handler Cleanup for `onServerRequest`

**Component:** `typescript-client`  
**Location:** `client.ts:268-285`

#### Problem

Handlers registered via `onServerRequest()` spawn background loops that are never cleaned up. Potential memory leak if the API is called multiple times.

```typescript
// Each call spawns a loop that runs until queue closes
// No way to remove individual handlers
// No cleanup when handler is no longer needed
```

---

## Status Tracking

| Gap ID | Priority | Status | Assigned | Notes |
|--------|----------|--------|----------|-------|
| GAP-001 | 🔴 Critical | Open | - | Resend logic incomplete |
| GAP-002 | 🔴 Critical | Open | - | Race condition in handler |
| GAP-003 | 🔴 Critical | Open | - | Watch re-registration |
| GAP-004 | 🟡 Moderate | Open | - | sequenceNumber extraction |
| GAP-005 | 🟡 Moderate | Open | - | Dead code removal |
| GAP-006 | 🟡 Moderate | Open | - | Unit test completion |
| GAP-007 | 🟡 Moderate | Open | - | Type safety fix |
| GAP-008 | 🟢 Minor | Open | - | Error handling review |
| GAP-009 | 🟢 Minor | Open | - | Code deduplication |
| GAP-010 | 🟢 Minor | Open | - | Handler cleanup |

---

## Related Documents

- [TypeScript Client Architecture](./typescript-client-architecture.md)
- [Component Analysis Report](./component-analysis-report.md)
- [Spec 006: TypeScript Client](../specs/006-we-want-to/spec.md)
- [Spec 007: KVStore CLI](../specs/007-create-a-kvstore/spec.md)
- [Server Request Bug Analysis](../typescript-client/ANALYSIS-serverRequest-bug.md)
