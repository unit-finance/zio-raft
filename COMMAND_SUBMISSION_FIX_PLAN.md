# Command Submission Fix Plan

> **Note:** This plan uses simplified TypeScript approach with immutable `RequestId` values instead of `RequestIdRef` wrapper. See "Implementation Approach" section for details.

> **Critical Discovery:** The `ConnectingExistingSessionState` is missing the `nextRequestId` field. When reconnecting, we MUST preserve the request ID counter (not reset to 0) to avoid duplicate IDs with pending requests. See "Critical Insight: Request ID Preservation" section.

## Investigation Summary

### Issue Reproduced ✅

**Symptoms:**
- Client successfully connects to server
- Client creates session and maintains heartbeat with `KeepAlive` messages
- Client hangs indefinitely when calling `submitCommand()`
- Server never receives `ClientRequest` message
- Client logs show: "processEvent - no messages to send"

**Server logs confirmed:**
```
✅ Received CreateSession
✅ Session created: bbb195ba-af0c-4457-af58-d1c7a31aab76
✅ Receiving KeepAlive messages every 3 seconds
❌ NO ClientRequest messages
```

**Client logs confirmed:**
```
✅ Successfully transitions to Connected state
✅ Receives Action event (SubmitCommand)
❌ Returns no messages to send
🔄 Stuck in infinite loop of TimeoutCheck events
```

---

## Root Cause Analysis

### The Bug

Located in `typescript-client/src/state/clientState.ts`, lines 1051-1054:

```typescript
case 'SubmitCommand':
  // Commands are queued by RaftClient before calling state machine
  // State machine doesn't directly handle queuing
  return { newState: state };  // ❌ NO-OP - Does nothing!
```

The `Connected` state handler receives the `SubmitCommand` action but does **absolutely nothing**:
- No request ID generation
- No `ClientRequest` message creation
- No message sent to server
- No tracking in `pendingRequests`
- Promise hangs forever

### Misleading Comments

Found multiple misleading comments suggesting a different architecture:

**Line 404 (ConnectingNewSession handler):**
```typescript
// Note: We'll need requestId allocation here, but that's done by RaftClient
```

**Lines 1052-1053 (Connected handler):**
```typescript
// Commands are queued by RaftClient before calling state machine
// State machine doesn't directly handle queuing
```

**These comments are architecturally incorrect** and led to incomplete implementation.

---

## Architecture Comparison: Scala vs TypeScript

### Correct Architecture (Both Languages)

The pattern is identical in both implementations:

1. **RaftClient (Public API Layer)**
   - Validates payload
   - Creates action with promise/callbacks
   - Enqueues action to action queue
   - Returns promise to caller

2. **State Machine (Protocol Logic Layer)**
   - Receives action from queue
   - Generates request ID (monotonically increasing)
   - Creates protocol message (`ClientRequest`)
   - Tracks pending request with callbacks
   - Returns message to RaftClient

3. **RaftClient (Event Loop)**
   - Sends messages returned by state machine via transport
   - Waits for server response

### Scala Implementation (Reference)

**File:** `client-server-client/src/main/scala/zio/raft/client/RaftClient.scala`

**RaftClient.submitCommand (lines 54-60):**
```scala
def submitCommand(payload: ByteVector): UIO[ByteVector] =
  for {
    promise <- Promise.make[Nothing, ByteVector]
    action = ClientAction.SubmitCommand(payload, promise)
    _ <- actionQueue.offer(action)
    result <- promise.await
  } yield result
```

**Connected.handle - SubmitCommand case (lines 556-564):**
```scala
case StreamEvent.Action(ClientAction.SubmitCommand(payload, promise)) =>
  for {
    requestId <- nextRequestId.next
    now <- Clock.instant
    lowestPendingRequestId = pendingRequests.lowestPendingRequestIdOr(requestId)
    request = ClientRequest(requestId, lowestPendingRequestId, payload, now)
    _ <- transport.sendMessage(request).orDie
    newPending = pendingRequests.add(requestId, payload, promise, now)
  } yield copy(pendingRequests = newPending)
```

**Key observations:** 
- Scala's `Connected` state has `nextRequestId: RequestIdRef` field (mutable reference)
- TypeScript will use simpler approach: `nextRequestId: RequestId` field (immutable value, updated on each transition)

### TypeScript Implementation (Current State)

**File:** `typescript-client/src/client.ts`

**RaftClient.submitCommand (lines 193-212):**
```typescript
async submitCommand(payload: Buffer): Promise<Buffer> {
  // Validate payload
  if (!Buffer.isBuffer(payload)) {
    throw new ValidationError('Command payload must be Buffer');
  }
  
  if (payload.length === 0) {
    throw new ValidationError('Command payload cannot be empty');
  }
  
  // Enqueue action and return promise
  return new Promise<Buffer>((resolve, reject) => {
    this.actionQueue.offer({
      type: 'SubmitCommand',
      payload,
      resolve,
      reject,
    });
  });
}
```

**Connected.handleAction - SubmitCommand case (lines 1051-1054):**
```typescript
case 'SubmitCommand':
  // Commands are queued by RaftClient before calling state machine
  // State machine doesn't directly handle queuing
  return { newState: state };  // ❌ UNIMPLEMENTED
```

**Problem:** TypeScript state machine does nothing. The implementation was never completed.

---

## State Handler Comparison Table

| State | TypeScript Behavior | Correct? | Notes |
|-------|---------------------|----------|-------|
| **Disconnected** | Rejects with error | ✅ Yes | Correct - cannot submit when disconnected |
| **ConnectingNewSession** | Rejects with error | ⚠️ Maybe | Scala queues commands to send after connection. We reject for simplicity. |
| **ConnectingExistingSession** | Rejects with error | ✅ Yes | Correct - session reconnection in progress |
| **ConnectingExistingSession** | **Missing `nextRequestId` field** | ❌ **BUG** | **Must add to preserve across reconnections** |
| **Connected** | **NO-OP (hangs forever)** | ❌ **BUG** | **Must implement - this is the main bug** |

### Critical Insight: Request ID Preservation

**When reconnecting to an existing session, we MUST preserve `nextRequestId`:**

- **Why?** We have pending requests already sent with IDs like [5, 7, 9]
- **Why?** Server session is still alive and remembers these request IDs
- **What happens if we reset to 0?** We create duplicate request IDs → server confusion
- **Solution:** Preserve `nextRequestId` across all state transitions during reconnection

**State transition rules:**
- `ConnectingNewSession` → `Connected`: Start from `RequestId.zero` (new session)
- `ConnectingExistingSession` → `Connected`: Preserve existing value (resume session)
- `Connected` → `ConnectingExistingSession`: Preserve value (preparing to reconnect)

---

## Detailed Fix Plan

### Changes Required

**Single File:** `typescript-client/src/state/clientState.ts`

### Implementation Approach

**Simplified from Scala:** Instead of using a mutable `RequestIdRef` wrapper (similar to Scala's approach), TypeScript implementation will use immutable `RequestId` values directly:

- **Scala approach:** `nextRequestId: RequestIdRef` (mutable reference, call `.next()` to mutate and get new ID)
- **TypeScript approach:** `nextRequestId: RequestId` (immutable value, call `RequestId.next(id)` and return new state)

This is more idiomatic for TypeScript/functional programming and eliminates the need for the `RequestIdRef` wrapper class.

### Step 1: Update Imports (Top of File)

Add missing imports:

```typescript
import { PendingRequestData } from './pendingRequests';
import { PendingQueryData } from './pendingQueries';
```

Note: `RequestId` is already imported from `'../types'`

### Step 2: Update State Interfaces

#### 2a. Update `ConnectingExistingSessionState` Interface (~Line 53)

Add `nextRequestId` field (missing in current implementation):

```typescript
export interface ConnectingExistingSessionState {
  readonly state: 'ConnectingExistingSession';
  readonly config: ClientConfig;
  readonly sessionId: SessionId;
  readonly capabilities: Map<string, string>;
  readonly nonce: Nonce;
  readonly currentMemberId: MemberId;
  readonly createdAt: Date;
  readonly serverRequestTracker: ServerRequestTracker;
  readonly nextRequestId: RequestId;  // ← ADD THIS (preserve across reconnections)
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
}
```

#### 2b. Update `ConnectedState` Interface (~Line 69)

Add `nextRequestId` field:

```typescript
export interface ConnectedState {
  readonly state: 'Connected';
  readonly config: ClientConfig;
  readonly sessionId: SessionId;
  readonly capabilities: Map<string, string>;
  readonly createdAt: Date;
  readonly serverRequestTracker: ServerRequestTracker;
  readonly nextRequestId: RequestId;  // ← ADD THIS (stores current ID value)
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
  readonly currentMemberId: MemberId;
}
```

### Step 3: Initialize and Preserve `nextRequestId` Across State Transitions

#### 3a. Initialize when Creating NEW Session (~Line 483)

**ConnectingNewSession → Connected** (SessionCreated handler)

Start from zero for brand new sessions:

```typescript
const newState: ConnectedState = {
  state: 'Connected',
  config: state.config,
  sessionId: message.sessionId,
  capabilities: state.capabilities,
  createdAt: state.createdAt,
  serverRequestTracker: new ServerRequestTracker(),
  nextRequestId: RequestId.zero,  // ← ADD THIS (start from 0 for new session)
  pendingRequests: state.pendingRequests,
  pendingQueries: state.pendingQueries,
  currentMemberId: state.currentMemberId,
};
```

#### 3b. Preserve when Resuming EXISTING Session (~Line 791)

**ConnectingExistingSession → Connected** (SessionContinued handler)

**CRITICAL:** Must preserve existing value to avoid duplicate request IDs:

```typescript
const newState: ConnectedState = {
  state: 'Connected',
  config: state.config,
  sessionId: state.sessionId,
  capabilities: state.capabilities,
  createdAt: state.createdAt,
  serverRequestTracker: state.serverRequestTracker,
  nextRequestId: state.nextRequestId,  // ← PRESERVE existing value!
  pendingRequests: state.pendingRequests,
  pendingQueries: state.pendingQueries,
  currentMemberId: state.currentMemberId,
};
```

**Why preserve?** When reconnecting:
- We have pending requests with IDs like [5, 7, 9] already sent
- Server session is still alive and remembers these IDs
- Resetting to 0 would create duplicate IDs and confuse server
- Must continue from where we left off

#### 3c. Preserve when Transitioning to Reconnection (~Line 1288)

**Connected → ConnectingExistingSession** (reconnection on disconnect)

Add `nextRequestId` to state creation:

```typescript
const newState: ConnectingExistingSessionState = {
  state: 'ConnectingExistingSession',
  config: state.config,
  sessionId: state.sessionId,
  capabilities: state.capabilities,
  nonce,
  currentMemberId: targetMemberId,
  createdAt: new Date(),
  serverRequestTracker: state.serverRequestTracker,
  nextRequestId: state.nextRequestId,  // ← ADD THIS (preserve across reconnection)
  pendingRequests: state.pendingRequests,
  pendingQueries: state.pendingQueries,
};
```

#### 3d. Preserve in Other ConnectingExistingSession Transitions

**Within ConnectingExistingSession** (leader redirects, retry logic)

Locations ~Line 884 and ~Line 976 use spread operator, which will automatically preserve `nextRequestId`:

```typescript
const newState: ConnectingExistingSessionState = {
  ...state,  // ← Preserves nextRequestId automatically
  nonce,
  currentMemberId: leaderId,
  createdAt: new Date(),
};
```

### Step 4: Implement `SubmitCommand` Handler (~Line 1051)

Replace the no-op with full implementation:

```typescript
case 'SubmitCommand': {
  // Use current request ID and compute next one
  const requestId = state.nextRequestId;
  const nextId = RequestId.next(requestId);
  const lowestPendingRequestId = state.pendingRequests.lowestPendingRequestIdOr(requestId);
  const now = new Date();
  
  // Create ClientRequest protocol message
  const clientRequest: ClientRequest = {
    type: 'ClientRequest',
    requestId,
    lowestPendingRequestId,
    payload: action.payload,
    createdAt: now,
  };
  
  // Track pending request with callbacks
  const pendingData: PendingRequestData = {
    payload: action.payload,
    resolve: action.resolve,
    reject: action.reject,
    createdAt: now,
    lastSentAt: now,
  };
  
  state.pendingRequests.add(requestId, pendingData);
  
  // Return updated state with new nextRequestId and message to send
  return {
    newState: {
      ...state,
      nextRequestId: nextId,
    },
    messagesToSend: [clientRequest],
  };
}
```

### Step 5: Implement `SubmitQuery` Handler (~Line 1056)

Replace the no-op with full implementation:

```typescript
case 'SubmitQuery': {
  // Generate correlation ID for query
  const correlationId = CorrelationId.generate();
  const now = new Date();
  
  // Create Query protocol message
  const query: Query = {
    type: 'Query',
    correlationId,
    payload: action.payload,
    createdAt: now,
  };
  
  // Track pending query with callbacks
  const pendingData: PendingQueryData = {
    payload: action.payload,
    resolve: action.resolve,
    reject: action.reject,
    createdAt: now,
    lastSentAt: now,
  };
  
  state.pendingQueries.add(correlationId, pendingData);
  
  // Return message to send
  return {
    newState: state,
    messagesToSend: [query],
  };
}
```

### Step 6: Remove Misleading Comments

Delete or update the incorrect comments:
- Lines 1052-1053: Remove entirely (replaced by implementation)
- Line 1057: Remove "queued by RaftClient" comment

Note: Comments about requestIdRef have already been removed with the refactoring.

---

## Testing Strategy

### 1. Build TypeScript Client

```bash
cd typescript-client
npm run build
```

### 2. Build CLI

```bash
cd kvstore-cli-ts
npm run build
```

### 3. Test Set Command

```bash
node dist/index.js set mykey myvalue
```

**Expected behavior:**
- Client connects successfully
- Client sends `ClientRequest` to server
- Server logs show: `Received client message: ClientRequest(...)`
- Client receives `ClientResponse`
- Command completes with success message

**Verify in server logs:**
```bash
tail -f ../kvstore_server.log | grep ClientRequest
```

### 4. Test Get Query

```bash
node dist/index.js get mykey
```

**Expected behavior:**
- Client sends `Query` to server
- Server logs show: `Received client message: Query(...)`
- Client receives `QueryResponse`
- Query completes with result

### 5. Run Existing Tests

```bash
cd typescript-client
npm test
```

Ensure no regressions in existing test suite.

---

## Commit Strategy

### Commit Message

```
Fix command submission and request ID tracking in TypeScript client

The SubmitCommand and SubmitQuery handlers in Connected state were
unimplemented no-ops with misleading comments suggesting RaftClient
should handle this work. This caused clients to hang indefinitely when
submitting commands because no ClientRequest message was sent to server.

Additionally, ConnectingExistingSessionState was missing the nextRequestId
field, which would cause duplicate request IDs when reconnecting to an
existing session (server would receive duplicate IDs for different requests).

Investigation of the Scala implementation confirms that the state
machine's Connected handler must:
1. Generate request IDs (monotonically increasing)
2. Create protocol messages (ClientRequest/Query)
3. Send messages via transport
4. Track pending requests with callbacks
5. Preserve request ID counter across reconnections

Changes:
- Add nextRequestId field to ConnectingExistingSessionState interface
- Add nextRequestId field to ConnectedState interface (using RequestId directly)
- Initialize nextRequestId to RequestId.zero for new sessions
- Preserve nextRequestId when resuming existing sessions (critical!)
- Preserve nextRequestId when transitioning to reconnection state
- Implement SubmitCommand handler with immutable state updates
- Implement SubmitQuery handler with immutable state updates
- Remove misleading architectural comments

Implementation uses immutable RequestId values (not mutable RequestIdRef wrapper)
for more idiomatic TypeScript/functional style.

The correct architecture in both Scala and TypeScript:
1. RaftClient validates and enqueues actions
2. State machine handles actions and creates protocol messages
3. RaftClient sends messages returned by state machine
4. State machine resolves promises when responses received
5. Request ID counter preserved across session reconnections

Fixes issue where:
- Client connects but hangs when submitting commands/queries
- Reconnection would create duplicate request IDs
```

---

## Open Questions

### Question 1: ConnectingNewSession Command Queueing

**Current behavior:** `ConnectingNewSession.handleSubmitCommand()` (line 399) rejects commands with error.

**Scala behavior:** Commands submitted while connecting are queued and sent after connection established.

**Options:**
- **A) Keep rejecting** (current behavior, simpler, safer)
- **B) Queue commands** (match Scala, more complex, requires tracking during connection)

**Recommendation:** Keep rejecting for now (Option A). Can enhance later if needed.

### Question 2: Session Reconnection Command Handling

**Current behavior:** `ConnectingExistingSession` rejects new commands during reconnection.

**Question:** Should we queue these or continue rejecting?

**Recommendation:** Continue rejecting. Session reconnection should be fast, and it's simpler.

---

## Risk Assessment

### Low Risk Changes ✅
- Adding `nextRequestId` field - straightforward addition
- Initializing field in state transitions - safe, follows Scala pattern
- Implementing handlers - direct translation from Scala

### Testing Coverage
- Manual testing with CLI covers the main use case
- Existing unit tests should still pass
- Integration tests exercise the fixed code path

### Rollback Plan
If issues arise:
1. Revert single commit
2. Client behavior returns to hanging (known state)
3. No data corruption risk (client-side only change)

---

## Success Criteria

1. ✅ Client can submit commands and receive responses
2. ✅ Client can submit queries and receive responses
3. ✅ Server logs show `ClientRequest` and `Query` messages
4. ✅ No regressions in existing tests
5. ✅ Manual CLI testing succeeds for both set/get operations

---

## Critical Implementation Notes for AI

### 🚨 Request ID Preservation Rule (EASY TO GET WRONG)

**DO NOT reset `nextRequestId` to zero in ALL locations:**

❌ **WRONG:**
```typescript
// SessionContinued handler (~line 791)
nextRequestId: RequestId.zero,  // BUG! Creates duplicate IDs
```

✅ **CORRECT:**
```typescript
// SessionContinued handler (~line 791)  
nextRequestId: state.nextRequestId,  // Preserve existing value
```

**Why this matters:** 
- Client had requests [5, 7, 9] pending when disconnected
- Server session still alive, remembers those IDs
- Resetting to 0 creates duplicate IDs → server confusion
- Must continue from where we left off

**Rule:**
- New session → Initialize to `RequestId.zero`
- Resume session → Preserve `state.nextRequestId`
- Reconnecting → Preserve `state.nextRequestId`

### 📍 Multiple Locations Need `nextRequestId`

Search for ALL places creating `ConnectingExistingSessionState`:
```bash
grep -n "ConnectingExistingSessionState = {" typescript-client/src/state/clientState.ts
```

You'll find **3 locations** (~884, ~976, ~1288):
- Two use spread `...state` (automatic preservation)
- One is explicit object literal (must add field manually)

### 🔧 PendingRequests Mutation Behavior

`pendingRequests.add()` mutates in place:
```typescript
state.pendingRequests.add(requestId, pendingData);  // Mutates the Map
// No need to reassign - but still return new state object for immutability pattern
return {
  newState: { ...state, nextRequestId: nextId },  // Spread to new object
  messagesToSend: [clientRequest],
};
```

---

## Implementation Checklist

### Code Changes
- [ ] Update imports in clientState.ts (PendingRequestData, PendingQueryData)
- [ ] Add nextRequestId to ConnectingExistingSessionState interface
- [ ] Add nextRequestId to ConnectedState interface
- [ ] Initialize nextRequestId to zero in SessionCreated handler (new session)
- [ ] Preserve nextRequestId in SessionContinued handler (existing session)
- [ ] Preserve nextRequestId when transitioning Connected → ConnectingExistingSession
- [ ] Implement SubmitCommand handler in Connected state
- [ ] Implement SubmitQuery handler in Connected state
- [ ] Remove misleading comments

### Testing
- [ ] Build typescript-client (`npm run build`)
- [ ] Build kvstore-cli-ts (`npm run build`)
- [ ] Test set command manually
- [ ] Test get query manually
- [ ] Verify server logs show correct messages
- [ ] Run npm test (verify no regressions)
- [ ] Test reconnection scenario (kill/restart server mid-session)

### Finalization
- [ ] Commit changes with detailed message
