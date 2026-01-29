# KVStore CLI Manual Test Plan - Server Control Tests

**Purpose:** Validate CLI behavior under failure scenarios, server restarts, and edge cases requiring server control.

**Prerequisites:**
- Commands to start/stop/restart the KVStore server (to be provided)
- CLI built: `npm run build`
- Run commands from `kvstore-cli-ts` directory
- Server default endpoint: `tcp://127.0.0.1:7002` (node-1)

**Note:** These tests require the ability to control the server lifecycle. They validate resilience, failure handling, and recovery scenarios that cannot be tested with a continuously running server.

---

## Test Scenario 13: Connection Failure - Server Not Running

**What we're testing:** CLI handles gracefully when server is not available

**Why this matters:** Validates our transport.connect() fix actually fails properly when there's no server

**Commands:**
```bash
# Ensure server is stopped
# (stop_server_command - to be provided)

# Attempt to connect
node dist/index.js set testkey testvalue

# Expected:
# - Connection timeout error (after 5 seconds)
# - Clear error message indicating server unreachable
# - Clean exit (no hanging process)
# - No crash or undefined behavior

# Start server for next tests
# (start_server_command - to be provided)
```

**Result:**
```
(To be filled after test execution)
```

---

## Test Scenario 14: Server Crashes During Set Operation

**What we're testing:** CLI handles server crash mid-operation

**Why this matters:** Tests the disconnect promise resolution we added - does cleanup happen correctly?

**Commands:**
```bash
# Ensure server is running
# (start_server_command if needed)

# Set initial value
node dist/index.js set crashkey initialvalue

# Start a set operation in background
node dist/index.js set crashkey newvalue > /tmp/crash_test.log 2>&1 &
SET_PID=$!

# Give it time to connect and start operation
sleep 1

# Kill server while operation is in progress
# (kill_server_command - to be provided)

# Wait for set command to complete
wait $SET_PID
SET_EXIT=$?

# Check result
echo "Exit code: $SET_EXIT"
cat /tmp/crash_test.log

# Restart server
# (start_server_command - to be provided)
sleep 2

# Expected:
# - Set command fails with connection error
# - Exit code indicates error (non-zero)
# - Client doesn't hang indefinitely
# - Error message is clear and actionable
```

**Result:**
```
(To be filled after test execution)
```

---

## Test Scenario 15: Watch During Server Restart

**What we're testing:** Watch behavior when server restarts

**Why this matters:** Tests if our event loop shutdown logic works when server goes away

**Commands:**
```bash
# Ensure server is running
# (start_server_command if needed)

# Start watch
node dist/index.js watch restartkey > /tmp/watch_restart.log 2>&1 &
WATCH_PID=$!
sleep 2

# Set value while server is running
node dist/index.js set restartkey value1
sleep 1

# Restart server
# (restart_server_command - to be provided)
sleep 3

# Try to set value after restart
node dist/index.js set restartkey value2
sleep 1

# Stop watch
kill -INT $WATCH_PID
wait $WATCH_PID 2>/dev/null

# Check output
echo "=== Watch Output ==="
cat /tmp/watch_restart.log

# Expected:
# - First notification received (value1)
# - Watch detects disconnection during restart
# - Either: watch reconnects and receives value2
# - Or: watch exits with connection error (depending on implementation)
# - No hanging or undefined behavior
```

**Result:**
```
(To be filled after test execution)
```

---

## Test Scenario 16: Session Expiry

**What we're testing:** Client behavior when session expires on server

**Why this matters:** Validates session lifecycle management

**Commands:**
```bash
# Ensure server is running with default session timeout
# (start_server_command if needed)

# Start watch (keeps session alive)
node dist/index.js watch expirykey > /tmp/expiry_test.log 2>&1 &
WATCH_PID=$!
sleep 2

# Configure server to expire sessions quickly (e.g., 5 seconds)
# (set_server_session_timeout 5s - to be provided)

# Wait for session to expire
sleep 10

# Try to update - should fail or trigger reconnection
node dist/index.js set expirykey value1
sleep 1

# Check watch behavior
kill -INT $WATCH_PID 2>/dev/null
wait $WATCH_PID 2>/dev/null

cat /tmp/expiry_test.log

# Reset server timeout to normal
# (reset_server_session_timeout - to be provided)

# Expected:
# - Session expiry detected
# - Client either reconnects automatically or fails with clear error
# - No silent data loss
# - Proper error messages
```

**Result:**
```
(To be filled after test execution)
```

---

## Test Scenario 17: Server Stops Gracefully During Active Watch

**What we're testing:** Watch handles graceful server shutdown

**Why this matters:** Tests the SIGTERM/SIGINT handling and proper disconnect flow

**Commands:**
```bash
# Ensure server is running
# (start_server_command if needed)

# Start watch
node dist/index.js watch shutdownkey > /tmp/shutdown_test.log 2>&1 &
WATCH_PID=$!
sleep 2

# Set a value
node dist/index.js set shutdownkey value1
sleep 1

# Gracefully stop server
# (graceful_stop_server_command - to be provided)

# Wait a bit
sleep 2

# Check if watch exited
if ps -p $WATCH_PID > /dev/null 2>&1; then
  echo "Watch still running - stopping it"
  kill -INT $WATCH_PID
  wait $WATCH_PID 2>/dev/null
else
  echo "Watch exited on server shutdown"
fi

echo "=== Watch Output ==="
cat /tmp/shutdown_test.log

# Restart server
# (start_server_command - to be provided)
sleep 2

# Expected:
# - Watch receives notification for value1
# - Server sends proper shutdown signal to client
# - Watch exits cleanly with appropriate message
# - No hanging processes
```

**Result:**
```
(To be filled after test execution)
```

---

## Test Scenario 18: Reconnection After Network Partition

**What we're testing:** Client reconnects after temporary network failure

**Why this matters:** Validates resilience in unstable network conditions

**Commands:**
```bash
# Ensure server is running
# (start_server_command if needed)

# Set initial value
node dist/index.js set networkkey value1

# Block network to server (simulate partition)
# (block_network_to_server - to be provided)

# Attempt to set value (should timeout)
timeout 10s node dist/index.js set networkkey value2 > /tmp/network_test.log 2>&1
echo "Blocked operation exit code: $?"
cat /tmp/network_test.log

# Restore network
# (restore_network_to_server - to be provided)

# Try again - should work
node dist/index.js set networkkey value3

# Verify value
node dist/index.js get networkkey

# Expected:
# - First set times out (network blocked)
# - Timeout error is clear and actionable
# - After restoration, operations work normally
# - Final value is "value3"
```

**Result:**
```
(To be filled after test execution)
```

---

## Test Scenario 19: Multiple Clients Competing

**What we're testing:** Multiple clients can operate concurrently

**Why this matters:** Would have caught concurrency bugs in session management

**Commands:**
```bash
# Ensure server is running
# (start_server_command if needed)

# Start 5 parallel set operations
for i in {1..5}; do
  node dist/index.js set "concurrent$i" "value$i" &
done

# Wait for all to complete
wait

# Verify all succeeded
echo "=== Verification ==="
for i in {1..5}; do
  node dist/index.js get "concurrent$i"
done

# Expected:
# - All 5 operations succeed
# - All 5 values correctly stored
# - No conflicts or errors
# - Operations complete in reasonable time
```

**Result:**
```
(To be filled after test execution)
```

---

## Test Scenario 20: Server Restart During Watch Registration

**What we're testing:** Watch registration handles server restart

**Why this matters:** Tests edge case where watch connects during server instability

**Commands:**
```bash
# Ensure server is running
# (start_server_command if needed)

# Start watch command
node dist/index.js watch regkey > /tmp/registration_test.log 2>&1 &
WATCH_PID=$!

# Immediately restart server (race condition)
sleep 0.5
# (restart_server_command - to be provided)

# Wait for server to come back
sleep 3

# Try to set value
node dist/index.js set regkey value1
sleep 1

# Stop watch
kill -INT $WATCH_PID 2>/dev/null
wait $WATCH_PID 2>/dev/null

echo "=== Watch Output ==="
cat /tmp/registration_test.log

# Expected:
# - Watch handles registration failure gracefully
# - Either: watch retries and succeeds
# - Or: watch fails with clear error message
# - No hanging or undefined behavior
```

**Result:**
```
(To be filled after test execution)
```

---

## Test Scenario 21: Load Test - Rapid Client Connections

**What we're testing:** Server handles many rapid connections

**Why this matters:** Stress tests our connection lifecycle - do we leak connections?

**Commands:**
```bash
# Ensure server is running
# (start_server_command if needed)

# Rapid fire 50 set operations (each opens new connection)
echo "Starting load test..."
time (
  for i in {1..50}; do
    node dist/index.js set "load$i" "value$i" > /dev/null 2>&1
    if [ $((i % 10)) -eq 0 ]; then
      echo "Completed $i operations..."
    fi
  done
)

# Verify server is still responsive
echo "Checking server responsiveness..."
node dist/index.js set testkey testvalue
node dist/index.js get testkey

# Check how many succeeded
echo "Verifying stored values..."
SUCCESS=0
for i in {1..50}; do
  if node dist/index.js get "load$i" 2>/dev/null | grep -q "value$i"; then
    SUCCESS=$((SUCCESS + 1))
  fi
done
echo "Successful operations: $SUCCESS / 50"

# Expected:
# - Most/all operations succeed (45+/50)
# - Server remains responsive
# - No resource leaks
# - Reasonable performance (< 30 seconds for 50 ops)
```

**Result:**
```
(To be filled after test execution)
```

---

## Test Scenario 22: Server Logs Validation

**What we're testing:** Server properly logs client operations

**Why this matters:** Validates observability and debugging capabilities

**Commands:**
```bash
# Ensure server is running
# (start_server_command if needed)

# Clear server logs
# (clear_server_logs - to be provided)

# Perform operations
node dist/index.js set logtest value1
sleep 1
node dist/index.js get logtest
sleep 1
node dist/index.js set logtest value2
sleep 1

# Check server logs
echo "=== Server Logs ==="
# (cat_server_logs - to be provided)

# Expected in logs:
# - Client connection established
# - CreateSession message received
# - ClientRequest for "set logtest value1"
# - Query for "get logtest"
# - ClientRequest for "set logtest value2"
# - Client disconnection
# - No errors or warnings
```

**Result:**
```
(To be filled after test execution)
```

---

## Summary

**Total Test Scenarios:** 10 (Tests 13-22)

**Critical Tests (Must Pass):**
- ✅/❌ Test 13: Server Not Running
- ✅/❌ Test 14: Server Crash Mid-Operation
- ✅/❌ Test 15: Watch During Restart
- ✅/❌ Test 17: Graceful Shutdown

**Important Tests:**
- ✅/❌ Test 19: Multiple Clients
- ✅/❌ Test 21: Load Test

**Nice-to-Have Tests:**
- ✅/❌ Test 16: Session Expiry
- ✅/❌ Test 18: Network Partition
- ✅/❌ Test 20: Registration Race Condition
- ✅/❌ Test 22: Server Logs

---

## Automation Status

These test scenarios are being automated as part of the E2E test infrastructure.  
See: `E2E_TYPESCRIPT_MANAGED_PLAN.md` for implementation details.

| Test | Priority | Automation Phase | Status |
|------|----------|------------------|--------|
| Test 13 | Critical | Phase 3 | ⏳ Planned |
| Test 14 | Critical | Phase 3 | ⏳ Planned |
| Test 15 | Critical | Phase 3 | ⏳ Planned |
| Test 17 | Critical | Phase 3 | ⏳ Planned |
| Test 19 | Important | Phase 3 | ⏳ Planned |
| Test 21 | Important | Phase 3 | ⏳ Planned |
| Test 16 | Nice-to-have | Phase 5 | 📋 Backlog |
| Test 18 | Nice-to-have | Phase 5 | 📋 Backlog |
| Test 20 | Nice-to-have | Phase 5 | 📋 Backlog |
| Test 22 | Nice-to-have | Phase 5 | 📋 Backlog |

**Target:** 6/10 tests automated by Phase 3, 10/10 by Phase 5

---

## Server Control Commands Needed

To execute these tests, we need the following server control commands:

1. `start_server_command` - Start the KVStore server
2. `stop_server_command` - Stop the server gracefully
3. `kill_server_command` - Kill the server forcefully (simulates crash)
4. `restart_server_command` - Restart the server (stop + start)
5. `graceful_stop_server_command` - Stop with proper cleanup/notification
6. `set_server_session_timeout <duration>` - Configure session timeout
7. `reset_server_session_timeout` - Reset to default timeout
8. `block_network_to_server` - Simulate network partition (optional)
9. `restore_network_to_server` - Restore network (optional)
10. `clear_server_logs` - Clear server log files
11. `cat_server_logs` - Display server logs

**Note:** These commands will be provided by the user for test execution.

---

## Notes

- These tests validate **failure scenarios** that the basic test plan cannot cover
- They ensure the transport.connect() fix works correctly under adverse conditions
- They validate resilience, error handling, and recovery mechanisms
- Server control is essential for realistic failure testing
- These tests will be executed after the basic test plan (MANUAL_TEST_PLAN.md) passes
