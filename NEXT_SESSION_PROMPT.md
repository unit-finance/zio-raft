# Next Session: Fix Command Submission in TypeScript Client

## Context

The ZMQ connection issue has been resolved. The TypeScript client now successfully:
- ✅ Connects to the server using CLIENT/SERVER (draft) sockets
- ✅ Creates a session (`CreateSession` → `SessionCreated`)
- ✅ Maintains heartbeat with `KeepAlive` messages
- ✅ No premature disconnections

However, the client **hangs indefinitely** when trying to submit commands/queries because the state machine doesn't implement command submission in the `Connected` state.

## The Issue

**Location**: `typescript-client/src/state/clientState.ts`

In the `Connected` state handler (around line 1051-1054):
```typescript
case 'SubmitCommand':
  // Commands are queued by RaftClient before calling state machine
  // State machine doesn't directly handle queuing
  return { newState: state };  // ❌ NO-OP - Does nothing!
```

When `client.submitCommand(...)` is called:
1. Client transitions to `Connected` state ✅
2. `SubmitCommand` action is dispatched ✅
3. State machine receives action but does nothing ❌
4. No `ClientRequest` message is sent to server ❌
5. Client waits forever for `ClientResponse` ❌

## Expected Behavior

The `SubmitCommand` action handler in `Connected` state should:
1. Generate a new `RequestId`
2. Create a `ClientRequest` message with the command payload
3. Track the pending request in `state.pendingRequests`
4. Return the message to be sent to the server
5. Set up timeout handling

Similar logic needed for `SubmitQuery` action.

## Investigation Approach

### Step 1: Verify the Issue
Run the CLI and observe the behavior:

```bash
# Terminal 1: Server should already be running, check logs
tail -f kvstore_server.log

# Terminal 2: Run the client
cd kvstore-cli-ts
node dist/index.js set mykey myvalue
```

**Expected observation**:
- Client logs: Reaches `Connected` state, then hangs with repeated `TimeoutCheck` events
- Server logs: Receives `CreateSession` and `KeepAlive`, but NO `ClientRequest`

### Step 2: Examine the Protocol

Look at the message types:
- `typescript-client/src/protocol/messages.ts` - Review `ClientRequest` structure
- `client-server-protocol/src/main/scala/zio/raft/protocol/Messages.scala` - Server-side equivalent

Key fields in `ClientRequest`:
```typescript
{
  type: 'ClientRequest',
  requestId: RequestId,
  lowestPendingRequestId: RequestId,
  payload: Buffer,
  createdAt: Date
}
```

### Step 3: Look at Reference Implementation

The Scala client likely has this implemented correctly:
- `client-server-client/src/main/scala/zio/raft/client/ClientTransport.scala`
- `client-server-client/src/main/scala/zio/raft/client/RaftClient.scala`

Check how the Scala client handles command submission.

### Step 4: Examine State Machine Structure

Files to review:
- `typescript-client/src/state/clientState.ts` - State machine logic
- `typescript-client/src/state/pendingRequests.ts` - Request tracking
- `typescript-client/src/client.ts` - RaftClient that calls state machine

Understand:
- How `pendingRequests` tracking works
- How `RequestId` generation works
- How timeouts are managed
- How `lowestPendingRequestId` is calculated

### Step 5: Implement the Fix

Modify the `Connected` state handler in `clientState.ts`:

1. **For `SubmitCommand`**:
   - Generate `requestId` (likely using a counter in state)
   - Create `ClientRequest` message
   - Add to `state.pendingRequests`
   - Return message in `messagesToSend`

2. **For `SubmitQuery`**:
   - Similar logic but create `ClientQuery` message
   - Add to `state.pendingQueries`

3. **Update state interface** if needed:
   - Add `nextRequestId` counter to `ConnectedState`?
   - Or check if `PendingRequests` provides ID generation

### Step 6: Test the Fix

```bash
# Rebuild
cd typescript-client && npm run build
cd ../kvstore-cli-ts && npm run build

# Test set command
node dist/index.js set mykey myvalue

# Check server logs for ClientRequest
tail -f ../kvstore_server.log | grep ClientRequest

# If successful, test get command
node dist/index.js get mykey
```

**Expected behavior after fix**:
- Client sends `ClientRequest` message
- Server logs show: `Received client message: ClientRequest(...)`
- Client receives `ClientResponse`
- Command completes successfully

## Suggested Implementation Direction

Based on code structure, the fix likely involves:

1. **In `ConnectedState` interface** (around line 200):
   - Add `nextRequestId: number` field

2. **In `handleSubmitCommand()` method** (create this):
   ```typescript
   private async handleSubmitCommand(
     state: ConnectedState,
     command: { payload: Buffer; promise: Deferred<Buffer> }
   ): Promise<StateTransitionResult> {
     const requestId = state.nextRequestId;
     const lowestPendingRequestId = state.pendingRequests.getLowestRequestId() ?? requestId;
     
     const clientRequest: ClientRequest = {
       type: 'ClientRequest',
       requestId,
       lowestPendingRequestId,
       payload: command.payload,
       createdAt: new Date(),
     };
     
     state.pendingRequests.add(requestId, command.promise);
     
     return {
       newState: {
         ...state,
         nextRequestId: requestId + 1,
       },
       messagesToSend: [clientRequest],
     };
   }
   ```

3. **Wire it up** in the `Connected` case statement

## Testing Checklist

After implementing the fix:
- [ ] `set key value` command works
- [ ] `get key` query works
- [ ] Multiple commands in sequence work
- [ ] Timeouts are handled correctly
- [ ] Error responses are handled correctly
- [ ] Server logs show correct message flow

## Files You'll Need to Modify

Primary:
- `typescript-client/src/state/clientState.ts` - Implement command/query submission

Possibly:
- `typescript-client/src/state/pendingRequests.ts` - If ID generation needs adjustment
- `typescript-client/src/client.ts` - If command queueing needs changes

## Notes

- The connection infrastructure is now working correctly (CLIENT/SERVER sockets)
- Focus only on the state machine logic for command submission
- Use the server logs as your source of truth for what messages are being sent
- The debug logging in zmqTransport.ts (while verbose) is useful for seeing message flow
