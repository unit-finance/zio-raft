# KVStore CLI Manual Test Plan

**Purpose:** Validate the TypeScript CLI works correctly against a running KVStore server, focusing on the transport.connect() fix.

**Prerequisites:**
- KVStore server running on default endpoint: `tcp://127.0.0.1:7002` (node-1)
- CLI built: `npm run build` (if needed)
- Run commands from `kvstore-cli-ts` directory

**Note on Watch Tests:**
- Watch commands run in background using `&` and their PID is captured with `$!`
- Output is redirected to `/tmp/watch_*.log` files
- Stopped by sending SIGINT signal: `kill -INT $PID` (simulates Ctrl+C)
- Each test waits for watch to cleanup with `wait $PID 2>/dev/null`

---

## Test Scenario 1: Basic Set Operation

**What we're testing:** CLI can connect to server and set a key-value pair

**Commands:**
```bash
# Test 1.1: Set a simple key (using default endpoint)
node dist/index.js set mykey myvalue

# Expected: "OK"
```

**Result:**
```
Exit code: 1
Error: Not connected. Call connect() first.
    at ZmqTransport.createMessageStream
    at createMessageStream.next (<anonymous>)
    at RaftClient.createServerMessageStream
    at mergeStreams
    at RaftClient.runEventLoop
    at RaftClient.connect
```

**Analysis:** The fix for calling transport.connect() is not working correctly. The createServerMessageStream() is being called before the transport connection is established, causing the error to be thrown when trying to create the message stream.

---

## Test Scenario 2: Basic Get Operation

**What we're testing:** CLI can retrieve a value that was previously set

**Commands:**
```bash
# Test 2.1: Get existing key (from Test 1.1)
node dist/index.js get mykey

# Expected: "mykey = myvalue"

# Test 2.2: Get non-existent key
node dist/index.js get nonexistent

# Expected: "nonexistent = <none>"
```

**Result:**
```
Test 2.1 and 2.2: Both failed with same error as Test 1
Exit code: 1
Error: Not connected. Call connect() first.
```

**Analysis:** Same root cause - transport not connected before createServerMessageStream is called.

---

## Test Scenario 3: Set and Get Round-trip

**What we're testing:** Full write-read cycle works correctly

**Commands:**
```bash
# Test 3.1: Set and immediately get
node dist/index.js set testkey testvalue && \
node dist/index.js get testkey

# Expected: "OK" then "testkey = testvalue"

# Test 3.2: Update existing key
node dist/index.js set testkey newvalue && \
node dist/index.js get testkey

# Expected: "OK" then "testkey = newvalue"
```

**Result:**
```
Test 3.1 and 3.2: Both failed with same connection error
Exit code: 1
Error: Not connected. Call connect() first.
```

**Analysis:** Same connection issue prevents any operations.

---

## Test Scenario 4: Watch Functionality (Single Update)

**What we're testing:** Watch receives notifications when key is updated

**Commands:**
```bash
# Start watch in background, capture PID and output
node dist/index.js watch watchkey > /tmp/watch_test4.log 2>&1 &
WATCH_PID=$!

# Give watch time to start and connect
sleep 2

# Update the key (first time)
node dist/index.js set watchkey value1
sleep 1

# Update again
node dist/index.js set watchkey value2
sleep 1

# Stop watch by sending SIGINT (simulates Ctrl+C)
kill -INT $WATCH_PID
wait $WATCH_PID 2>/dev/null

# Display captured output
echo "=== Watch Output ==="
cat /tmp/watch_test4.log

# Expected output:
# - "watching watchkey - press Ctrl+C to stop"
# - Notification for "watchkey" -> "value1"
# - Notification for "watchkey" -> "value2"
# - "Shutting down..."
```

**Result:**
```
NOT RUN - Skipped due to same connection issue affecting all commands.
Watch functionality depends on basic connect/set operations which are failing.
```

---

## Test Scenario 5: Watch Multiple Keys

**What we're testing:** Multiple watches work independently

**Commands:**
```bash
# Start watch for key1 in background
node dist/index.js watch key1 > /tmp/watch_key1.log 2>&1 &
WATCH1_PID=$!

# Start watch for key2 in background
node dist/index.js watch key2 > /tmp/watch_key2.log 2>&1 &
WATCH2_PID=$!

# Give both watches time to start
sleep 2

# Update key1 only
node dist/index.js set key1 value1
sleep 1

# Update key2 only
node dist/index.js set key2 value2
sleep 1

# Stop both watches
kill -INT $WATCH1_PID
kill -INT $WATCH2_PID
wait $WATCH1_PID 2>/dev/null
wait $WATCH2_PID 2>/dev/null

# Display both outputs
echo "=== Watch key1 Output ==="
cat /tmp/watch_key1.log
echo ""
echo "=== Watch key2 Output ==="
cat /tmp/watch_key2.log

# Expected:
# - watch_key1.log: Should contain notification for key1 ONLY (not key2)
# - watch_key2.log: Should contain notification for key2 ONLY (not key1)
```

**Result:**
```
NOT RUN - Skipped due to same connection issue affecting all commands.
```

---

## Test Scenario 6: Connection Errors

**What we're testing:** CLI handles connection failures gracefully

**Commands:**
```bash
# Test 6.1: Wrong port
node dist/index.js get testkey -e node-1=tcp://127.0.0.1:9999

# Expected: Connection timeout or connection refused error

# Test 6.2: Invalid address format
node dist/index.js get testkey -e invalid-address

# Expected: Validation error about invalid endpoint format
```

**Result:**
```
Test 6.1: Failed with same connection error (port 9999 never reached due to earlier failure)
Exit code: 1
Error: Not connected. Call connect() first.

Test 6.2: ✅ PASSED - Validation error caught correctly
Error: Invalid format: invalid-address. Expected: memberId=tcp://host:port
```

**Analysis:** Test 6.2 passed because validation happens before connection attempt. This shows the validation logic works correctly. Test 6.1 failed before attempting the wrong port.

---

## Test Scenario 7: Edge Cases - Special Characters

**What we're testing:** CLI handles special characters in keys/values

**Commands:**
```bash
# Test 7.1: Spaces in value
node dist/index.js set spacekey "value with spaces" && \
node dist/index.js get spacekey

# Expected: "OK" then "spacekey = value with spaces"

# Test 7.2: Special characters
node dist/index.js set specialkey "!@#$%^&*()" && \
node dist/index.js get specialkey

# Expected: "OK" then "specialkey = !@#$%^&*()"

# Test 7.3: Unicode
node dist/index.js set unicode "Hello 世界 🌍" && \
node dist/index.js get unicode

# Expected: "OK" then "unicode = Hello 世界 🌍"
```

**Result:**
```
All three tests (7.1, 7.2, 7.3) failed with same connection error
Exit code: 1
Error: Not connected. Call connect() first.
```

**Analysis:** Cannot test special character handling until connection issue is resolved.

---

## Test Scenario 8: Edge Cases - Empty Values

**What we're testing:** CLI handles empty/null values

**Commands:**
```bash
# Test 8.1: Empty string value
node dist/index.js set emptykey "" && \
node dist/index.js get emptykey

# Expected: "OK" then "emptykey = " (empty value)
```

**Result:**
```
Test 8.1 Set: Failed with validation error (different from connection error)
Error: Value cannot be empty

Test 8.1 Get: Failed with connection error
Error: Not connected. Call connect() first.
```

**Analysis:** The empty value validation is working - it correctly rejects empty strings before attempting connection. The get command fails with the standard connection error.

---

## Test Scenario 9: Sequential Operations (Stress Test)

**What we're testing:** Multiple sequential operations work reliably

**Commands:**
```bash
# Test 9.1: 10 sequential sets
for i in {1..10}; do
  node dist/index.js set "key$i" "value$i"
done

# Expected: 10 "OK" messages

# Test 9.2: Verify all 10 keys
for i in {1..10}; do
  node dist/index.js get "key$i"
done

# Expected: "key1 = value1", "key2 = value2", ..., "key10 = value10"
```

**Result:**
```
Test 9.1 (10 sets): All 10 failed with connection error
Test 9.2 (10 gets): All 10 failed with connection error
Exit code: 1 for all
Error: Not connected. Call connect() first.
```

**Analysis:** Sequential operations cannot be tested until connection issue is resolved.

---

## Test Scenario 10: Transport Connection Verification (THE BUG FIX)

**What we're testing:** CRITICAL - Verify transport.connect() is actually called before sending messages

**Why this matters:** This was the bug we just fixed. Before the fix, messages would be sent without establishing connection.

**Commands:**
```bash
# Test 10.1: Fresh connection attempt
# This validates the transport.connect() fix
node dist/index.js set connecttest value

# Expected: 
# - "OK" (no errors)
# - Connection established successfully (no "socket not connected" errors)
# - Before our fix, this would have failed with connection errors

# Test 10.2: Multiple commands (each creates new connection)
node dist/index.js set key1 val1 && \
node dist/index.js set key2 val2 && \
node dist/index.js get key1 && \
node dist/index.js get key2

# Expected: 
# - "OK", "OK", "key1 = val1", "key2 = val2"
# - Each command connects, operates, disconnects cleanly
# - No connection errors
```

**Result:**
```
Test 10.1 and 10.2: Both failed with connection error
Exit code: 1
Error: Not connected. Call connect() first.
```

**Analysis - CRITICAL:** This is the exact bug that was supposed to be fixed. The `handleTransportConnection()` method exists in the compiled code and should call `transport.connect()` when transitioning to ConnectingNewSession/ConnectingExistingSession states. 

However, the error occurs earlier in the flow: `runEventLoop()` creates all stream generators including `createServerMessageStream()` at the start. When the unified stream begins iterating, it tries to get the first value from the server message stream, which accesses `transport.incomingMessages`, which calls `createMessageStream()`, which checks if connected and throws before any state transitions happen.

The timing issue: Stream generators are created → Iteration starts → Server stream tries to read → Checks connection → Throws error → No state transition happens → transport.connect() never called.

---

## Test Scenario 11: Watch Before Set

**What we're testing:** Watch can be registered before key exists

**Commands:**
```bash
# Start watch on non-existent key
node dist/index.js watch newkey > /tmp/watch_test11.log 2>&1 &
WATCH_PID=$!

# Give watch time to start
sleep 2

# Create the key for the first time
node dist/index.js set newkey initialvalue
sleep 1

# Update the key
node dist/index.js set newkey updatedvalue
sleep 1

# Stop watch
kill -INT $WATCH_PID
wait $WATCH_PID 2>/dev/null

# Display output
echo "=== Watch Output ==="
cat /tmp/watch_test11.log

# Expected:
# - "watching newkey - press Ctrl+C to stop"
# - Notification for initial value "initialvalue"
# - Notification for update "updatedvalue"
```

**Result:**
```
NOT RUN - Watch functionality depends on basic operations which are failing.
```

---

## Test Scenario 12: Rapid Updates (Watch Stress)

**What we're testing:** Watch handles rapid updates without dropping notifications

**Commands:**
```bash
# Start watch
node dist/index.js watch rapidkey > /tmp/watch_test12.log 2>&1 &
WATCH_PID=$!

# Give watch time to start
sleep 2

# Perform 5 rapid updates
for i in {1..5}; do
  node dist/index.js set rapidkey "update$i"
  sleep 0.5
done

# Give final notification time to arrive
sleep 1

# Stop watch
kill -INT $WATCH_PID
wait $WATCH_PID 2>/dev/null

# Display output
echo "=== Watch Output ==="
cat /tmp/watch_test12.log

# Expected:
# - "watching rapidkey - press Ctrl+C to stop"
# - 5 notifications, one for each update (update1, update2, update3, update4, update5)
# - All notifications should be present (no drops)
```

**Result:**
```
NOT RUN - Cannot test rapid updates until basic set/get operations work.
```

---

## Summary

**Total Test Scenarios:** 12
**Tests Run:** 10 (Tests 4, 5, 11, 12 skipped - watch-dependent)
**Tests Passed:** 2 (6.2 validation, 8.1 validation partial)
**Tests Failed:** 8

**Critical Tests (must pass):**
- ❌ Test 1: Basic Set - FAILED (connection error)
- ❌ Test 2: Basic Get - FAILED (connection error)
- ❌ Test 3: Set/Get Round-trip - FAILED (connection error)
- ⊘ Test 4: Watch Single Update - NOT RUN
- ❌ Test 10: Transport Connection (THE FIX) - FAILED (connection error)

**Important Tests:**
- ⊘ Test 5: Multiple Watches - NOT RUN
- ⚠️ Test 6: Connection Errors - PARTIAL (6.1 failed, 6.2 passed validation)
- ⊘ Test 11: Watch Before Set - NOT RUN

**Nice-to-have Tests:**
- ❌ Test 7: Special Characters - FAILED (connection error)
- ⚠️ Test 8: Empty Values - PARTIAL (validation works, connection fails)
- ❌ Test 9: Sequential Operations - FAILED (connection error)
- ⊘ Test 12: Rapid Updates - NOT RUN

**Legend:** ✅ Passed | ❌ Failed | ⚠️ Partial | ⊘ Not Run

---

## Overall Test Results - January 19, 2026

**BUILD STATUS:** ✅ Both projects built successfully
- typescript-client: Built successfully
- kvstore-cli-ts: Built successfully  

**SERVER STATUS:** ✅ KVStore server running on tcp://127.0.0.1:7002

**ROOT CAUSE ANALYSIS:**

The core issue is a **timing/ordering problem** in the event loop initialization:

1. ✅ The `handleTransportConnection()` fix exists and is compiled correctly
2. ✅ It correctly calls `await this.transport.connect(address)` on state transitions  
3. ❌ BUT the error occurs BEFORE any state transitions happen

**The Problem Flow:**
```
runEventLoop() starts
  ↓
Creates all stream generators (including createServerMessageStream)
  ↓
Starts iterating unified stream (mergeStreams)
  ↓
mergeStreams tries to get first value from each stream
  ↓
Server message stream accesses transport.incomingMessages
  ↓
transport.createMessageStream() checks if connected
  ↓
❌ THROWS: "Not connected. Call connect() first."
  ↓
Error thrown BEFORE Connect action is processed
  ↓  
State never transitions to ConnectingNewSession
  ↓
transport.connect() never gets called
```

**What Works:**
- ✅ Validation logic (endpoint format validation, empty value validation)
- ✅ handleTransportConnection() implementation
- ✅ Build process and dependency linking

**What Doesn't Work:**
- ❌ All operations requiring server connection (set, get, watch)
- ❌ The transport.connect() happens too late in the flow

**Potential Solutions (Ideas, Not Implemented):**
1. Delay creating `serverMessageStream` until after connection established
2. Make `createMessageStream()` not throw if disconnected, instead wait/yield nothing
3. Connect transport before starting runEventLoop
4. Use a lazy/conditional stream that only starts after connected state

---

## Notes

- Each test should be run independently (restart CLI between tests unless testing connection reuse)
- Watch tests require multiple terminal windows
- If any test fails, note the exact error message in the Result section
- Pay special attention to Test 10 - this validates our transport.connect() fix
