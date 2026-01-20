# Cleanup Tasks

This document tracks temporary files, debug code, and other artifacts that should be removed once debugging is complete.

## Status: IN PROGRESS
Last updated: 2026-01-20 18:45

### Latest Updates:
- Added missing test file: `test-handshake-fix.js`
- Added Section 4: Debug logging cleanup needed in `client.ts` (~30+ log statements)
- Added Section 5: Server log files need to be added to `.gitignore`
- Added Section 9: Pending reconnection test from implementation plan
- Updated "What We Fixed" to reflect completed command submission implementation
- Updated status: Command submission feature is now complete (except reconnection test)

---

## 1. Temporary Test Scripts (DELETE)

These were created for debugging the ZMQ connection issue and can be safely deleted:

- [ ] `/typescript-client/test-zmq-simple.js`
- [ ] `/typescript-client/test-connect-only.js`
- [ ] `/typescript-client/test-probe-router.js`
- [ ] `/typescript-client/test-maitred-config.js`
- [ ] `/typescript-client/test-handshake-fix.js`

```bash
rm typescript-client/test-*.js
```

---

## 2. Investigation Documents (DELETE)

- [ ] `/LIBZMQ_JEROMQ_INCOMPATIBILITY.md` - Based on incorrect hypothesis that the issue was libzmq/JeroMQ version incompatibility. The actual issue was using DEALER instead of CLIENT socket.

```bash
rm LIBZMQ_JEROMQ_INCOMPATIBILITY.md
```

---

## 3. Debug Logging in zmqTransport.ts (CLEAN UP)

File: `typescript-client/src/transport/zmqTransport.ts`

Remove excessive debug logging added during investigation:

### Constructor debug logs (lines ~46-51):
```typescript
console.log('[DEBUG] ZmqTransport constructor - CLIENT socket created');
console.log('[DEBUG] Socket type: CLIENT (draft)');
console.log('[DEBUG] Socket immediate:', this.socket.immediate);
console.log('[DEBUG] Socket heartbeat interval:', this.socket.heartbeatInterval);
```

### Event listeners for debugging (lines ~27-46):
```typescript
this.socket.events.on('connect', (event: any) => {
  console.log('[DEBUG] Socket event: connect', event);
});
this.socket.events.on('connect:delay', (event: any) => {
  console.log('[DEBUG] Socket event: connect:delay', event);
});
this.socket.events.on('connect:retry', (event: any) => {
  console.log('[DEBUG] Socket event: connect:retry', event);
});
this.socket.events.on('disconnect', (event: any) => {
  console.log('[DEBUG] Socket event: disconnect', event);
});
this.socket.events.on('close', () => {
  console.log('[DEBUG] Socket event: close');
});
this.socket.events.on('end', () => {
  console.log('[DEBUG] Socket event: end');
});
```

### Throughout the file:
- [ ] Line ~65: `console.log('[DEBUG] ZmqTransport.connect() - connecting to:', address);`
- [ ] Line ~67: `console.log('[DEBUG] ZmqTransport.connect() completed');`
- [ ] Line ~70: `console.log('[DEBUG] ZmqTransport.connect() failed:', error);`
- [ ] Line ~83: `console.log('[DEBUG] ZmqTransport.disconnect() - disconnecting from:', this.currentAddress);`
- [ ] Line ~89: `console.log('[DEBUG] ZmqTransport.disconnect() completed');`
- [ ] Line ~106: `console.log('[DEBUG] ZmqTransport.sendMessage() called');`
- [ ] Line ~107: `console.log('[DEBUG] currentAddress:', this.currentAddress);`
- [ ] Line ~114: `console.log('[DEBUG] Encoding client message...');`
- [ ] Line ~120: `console.log('[DEBUG] Message encoded, size:', bytes.length, 'bytes');`
- [ ] Line ~123: `console.log('[DEBUG] Socket writable:', this.socket.writable);`
- [ ] Line ~124: `console.log('[DEBUG] Socket readable:', this.socket.readable);`
- [ ] Line ~125: `console.log('[DEBUG] Socket closed:', this.socket.closed);`
- [ ] Line ~127: `console.log('[DEBUG] Sending to socket...');`
- [ ] Line ~129: `console.log('[DEBUG] Socket.send() completed - message sent/queued');`
- [ ] Line ~132: `console.log('[DEBUG] ZmqTransport.sendMessage() failed:', error);`
- [ ] Line ~143: `console.log('[DEBUG] ZmqTransport.incomingMessages getter called');`
- [ ] Line ~146: `console.log('[DEBUG] ZmqTransport.incomingMessages Symbol.asyncIterator called');`
- [ ] Line ~157: `console.log('[DEBUG] ZmqTransport.createMessageStream() starting to read from socket');`
- [ ] Line ~161: `console.log(\`[DEBUG] Socket iteration \${iteration} - received data, buffer: \${message.length} bytes\`);`
- [ ] Line ~164: `console.log('[DEBUG] Decoding server message...');`
- [ ] Line ~170: `console.log('[DEBUG] Message decoded successfully, yielding to client');`
- [ ] Line ~173: `console.log('[DEBUG] Failed to decode message:', error);`
- [ ] Line ~180: `console.log('[DEBUG] ZmqTransport.close() called');`

**Strategy**: Consider replacing with proper logging framework or remove entirely. Keep only essential error logging.

---

## 4. Debug Logging in client.ts (CLEAN UP)

File: `typescript-client/src/client.ts`

Remove debug logging added during investigation:

- [ ] Line ~164: `console.log('[DEBUG] RaftClient.connect() called, currentState:', this.currentState.state);`
- [ ] Line ~168: `console.log('[DEBUG] Already connected/connecting, returning early');`
- [ ] Line ~174: `console.log('[DEBUG] Starting event loop...');`
- [ ] Line ~179: `console.log('[DEBUG] Enqueuing Connect action to action queue');`

Plus many more debug logs in the `runEventLoop()` method that were useful for debugging but should be removed for production.

**Total**: ~30+ debug log statements in client.ts

---

## 5. Server Log Files (ADD TO .gitignore & DELETE)

- [ ] `/kvstore_server.log` (343KB) - Server runtime logs that shouldn't be in git

Add to `.gitignore`:
```
*.log
kvstore_server.log
```

Then delete:
```bash
rm kvstore_server.log
```

---

## 6. Server Debug Logging (OPTIONAL - REVIEW)

File: `client-server-server/src/main/scala/zio/raft/server/ServerTransport.scala`

Currently has one debug log at line 57:
```scala
ZIO.logDebug(s"[incomingMessages] Received message: $msg") *>
```

This is actually useful for production debugging, so probably keep it.

---

## 7. Documentation to Update

Once cleanup is complete:

- [ ] Update main README if it references old DEALER socket approach
- [ ] Document that CLIENT/SERVER (draft) sockets are required
- [ ] Add note about zeromq.js draft API warning being expected

---

## 8. Dependencies (VERIFY FINAL STATE)

Current state in `typescript-client/package.json`:
```json
"zeromq": "^6.0.0"
```

This is correct (resolves to 6.5.0 which has draft socket support). No changes needed.

---

## 9. Pending Tests from COMMAND_SUBMISSION_FIX_PLAN.md

From the implementation plan, one test scenario is still pending:

- [ ] **Test reconnection scenario** (kill/restart server mid-session)
  - Verify that `nextRequestId` is preserved across reconnections
  - Verify pending requests are retried
  - Verify no duplicate request IDs are sent

This should be completed before marking the command submission feature as fully tested.

---

## 10. Test Files to Review

Check if any test files reference DEALER socket and need updating:

- [ ] `typescript-client/tests/integration/lifecycle.test.ts`
- [ ] `typescript-client/tests/integration/transport-connection.test.ts`
- [ ] `typescript-client/tests/unit/protocol/compatibility.test.ts`

---

## Notes

### What We Fixed
1. **ZMQ Socket Issue**:
   - **Issue**: Used `Dealer` socket to connect to `SERVER` socket (incompatible)
   - **Fix**: Changed to `Client` socket (draft API) in `zmqTransport.ts`
   - **Result**: Connection now works, session creation works, heartbeat works

2. **Command Submission Issue** (COMPLETED):
   - **Issue**: Command/query submission hanging in `Connected` state
   - **Fix**: Implemented `SubmitCommand` and `SubmitQuery` handlers with proper `nextRequestId` tracking
   - **Additional Fix**: Fixed protocol encoding mismatch (0xFF vs 0x01 for Option[String])
   - **Result**: SET and GET operations work correctly
   - **See**: `COMMAND_SUBMISSION_FIX_PLAN.md` for detailed implementation notes

### What Still Needs Fixing
- Reconnection test scenario (kill/restart server mid-session) - see section 9 above

### Cleanup Checklist
- [ ] Delete temporary test scripts
- [ ] Delete incorrect investigation document
- [ ] Remove debug logging from zmqTransport.ts
- [ ] Review and update test files
- [ ] Update documentation
- [ ] Final test run to ensure everything works
- [ ] Delete this cleanup document itself
