# Tests: TypeScript KVStore CLI Client

**Feature**: 007-create-a-kvstore  
**Date**: 2026-01-13  
**Status**: Complete

## Overview

This document defines all test scenarios for the TypeScript KVStore CLI client. Tests are organized into unit tests (isolated logic) and integration tests (command execution with mocked client).

**Testing Framework**: Vitest  
**Test Organization**: By component and integration scenario  
**Coverage Goal**: >90% for critical paths

---

## Unit Tests

### 1. Validation Tests (`tests/unit/validation.test.ts`)

#### Test Suite: Key Validation

**TC-001: Valid key passes validation**
- **Given**: A valid UTF-8 key under 256 bytes
- **When**: `validateKey(key)` is called
- **Then**: No error is thrown

**TC-002: Empty key is rejected**
- **Given**: An empty string ""
- **When**: `validateKey("")` is called
- **Then**: ValidationError is thrown with message "Key cannot be empty"

**TC-003: Key exceeding 256 bytes is rejected**
- **Given**: A string that encodes to 300 bytes in UTF-8
- **When**: `validateKey(longKey)` is called
- **Then**: ValidationError thrown with actual=300, expected=256

**TC-004: Key at exactly 256 bytes passes**
- **Given**: A string that encodes to exactly 256 bytes
- **When**: `validateKey(key)` is called
- **Then**: No error is thrown

**TC-005: Unicode key is validated correctly**
- **Given**: A key with emoji/unicode characters (e.g., "key🔑")
- **When**: `validateKey(unicodeKey)` is called
- **Then**: Validation based on UTF-8 byte length, not character count

**TC-006: Invalid UTF-8 is rejected**
- **Given**: A string with invalid UTF-8 sequences
- **When**: `validateKey(invalidUtf8)` is called
- **Then**: ValidationError thrown with message "Key contains invalid UTF-8"

---

#### Test Suite: Value Validation

**TC-007: Valid value passes validation**
- **Given**: A valid UTF-8 value under 1MB
- **When**: `validateValue(value)` is called
- **Then**: No error is thrown

**TC-008: Empty value is rejected**
- **Given**: An empty string ""
- **When**: `validateValue("")` is called
- **Then**: ValidationError is thrown with message "Value cannot be empty"

**TC-009: Value exceeding 1MB is rejected**
- **Given**: A string that encodes to 1.5MB in UTF-8
- **When**: `validateValue(largeValue)` is called
- **Then**: ValidationError thrown with actual size and expected=1048576

**TC-010: Value at exactly 1MB passes**
- **Given**: A string that encodes to exactly 1,048,576 bytes
- **When**: `validateValue(value)` is called
- **Then**: No error is thrown

**TC-011: Large unicode value validated by bytes**
- **Given**: A value with many multi-byte UTF-8 characters
- **When**: `validateValue(unicodeValue)` is called
- **Then**: Validation based on byte length, passes if ≤1MB

**TC-012: Invalid UTF-8 value is rejected**
- **Given**: A string with invalid UTF-8 sequences
- **When**: `validateValue(invalidUtf8)` is called
- **Then**: ValidationError thrown

---

#### Test Suite: Endpoint Parsing

**TC-013: Valid single endpoint is parsed**
- **Given**: "node-1=tcp://127.0.0.1:7001"
- **When**: `parseEndpoints(input)` is called
- **Then**: Returns Map with one entry: ["node-1" → "tcp://127.0.0.1:7001"]

**TC-014: Multiple endpoints are parsed**
- **Given**: "node-1=tcp://127.0.0.1:7001,node-2=tcp://192.168.1.10:7002"
- **When**: `parseEndpoints(input)` is called
- **Then**: Returns Map with two entries

**TC-015: Default endpoint is used when empty**
- **Given**: "" (empty string or omitted)
- **When**: Default value applied
- **Then**: Returns Map with ["node-1" → "tcp://127.0.0.1:7001"]

**TC-016: Invalid endpoint format is rejected**
- **Given**: "node-1=invalid-format"
- **When**: `parseEndpoints(input)` is called
- **Then**: ValidationError thrown with message about format

**TC-017: Invalid port number is rejected**
- **Given**: "node-1=tcp://127.0.0.1:99999"
- **When**: `parseEndpoints(input)` is called
- **Then**: ValidationError thrown: "Invalid port: 99999"

**TC-018: Missing member ID is rejected**
- **Given**: "=tcp://127.0.0.1:7001"
- **When**: `parseEndpoints(input)` is called
- **Then**: ValidationError thrown

**TC-019: Whitespace is trimmed**
- **Given**: "  node-1 = tcp://127.0.0.1:7001  ,  node-2=tcp://host:7002  "
- **When**: `parseEndpoints(input)` is called
- **Then**: Parses correctly with whitespace removed

---

### 2. Codec Tests (`tests/unit/codecs.test.ts`)

#### Test Suite: Encoding

**TC-020: Set request encodes correctly**
- **Given**: key="test", value="data"
- **When**: `encodeSetRequest(key, value)` is called
- **Then**: Returns Buffer with [0x53][4-byte len][key bytes][4-byte len][value bytes]

**TC-021: Get query encodes correctly**
- **Given**: key="test"
- **When**: `encodeGetQuery(key)` is called
- **Then**: Returns Buffer with [0x47][4-byte len][key bytes]

**TC-022: Watch request encodes correctly**
- **Given**: key="test"
- **When**: `encodeWatchRequest(key)` is called
- **Then**: Returns Buffer with [0x57][4-byte len][key bytes]

**TC-023: Unicode key encodes with correct byte length**
- **Given**: key="key🔑" (UTF-8 multi-byte)
- **When**: `encodeSetRequest(key, "value")` is called
- **Then**: Length prefix matches Buffer.byteLength, not string.length

**TC-024: Large value encodes correctly**
- **Given**: value of 500KB
- **When**: `encodeSetRequest("key", largeValue)` is called
- **Then**: Returns valid buffer with correct length prefix

---

#### Test Suite: Decoding

**TC-025: Get result with value decodes correctly**
- **Given**: Buffer [0x01][len][value bytes] (Some case)
- **When**: `decodeGetResult(buffer)` is called
- **Then**: Returns the decoded string value

**TC-026: Get result without value decodes to null**
- **Given**: Buffer [0x00] (None case)
- **When**: `decodeGetResult(buffer)` is called
- **Then**: Returns null

**TC-027: Notification decodes correctly**
- **Given**: Buffer [0x4E][len][key][len][value]
- **When**: `decodeNotification(buffer)` is called
- **Then**: Returns Notification object with key and value

**TC-028: Invalid discriminator throws error**
- **Given**: Buffer with invalid discriminator (e.g., 0xFF)
- **When**: `decodeNotification(buffer)` is called
- **Then**: Error thrown: "Unexpected discriminator"

---

#### Test Suite: Round-Trip

**TC-029: Set request round-trips correctly**
- **Given**: key="test", value="data"
- **When**: Encode then decode
- **Then**: Decoded values match original

**TC-030: Unicode round-trips correctly**
- **Given**: key="键", value="值🎉"
- **When**: Encode then decode
- **Then**: Unicode preserved exactly

---

### 3. Formatting Tests (`tests/unit/formatting.test.ts`)

#### Test Suite: Output Formatting

**TC-031: Success message formats correctly**
- **Given**: "OK"
- **When**: `formatSuccess("OK")` is called
- **Then**: Returns "OK"

**TC-032: Validation error formats with details**
- **Given**: ValidationError(field='key', message='Too long', actual=300, expected=256)
- **When**: `formatError(error)` is called
- **Then**: Returns "Error: Key too long (actual: 300, expected: 256)" or similar

**TC-033: Connection error formats clearly**
- **Given**: ConnectionError with timeout
- **When**: `formatError(error)` is called
- **Then**: Returns "Error: Could not connect to cluster (timeout after 5s)"

**TC-034: Generic error formats safely**
- **Given**: Unknown error type
- **When**: `formatError(error)` is called
- **Then**: Returns "Error: {error.toString()}"

---

#### Test Suite: Notification Formatting

**TC-035: Notification formats with all fields**
- **Given**: Notification{ timestamp, seq=42, key="k", value="v" }
- **When**: `formatNotification(notification)` is called
- **Then**: Returns "[ISO timestamp] seq=42 key=k value=v"

**TC-036: Timestamp formats as ISO 8601**
- **Given**: Notification with Date object
- **When**: `formatNotification(notification)` is called
- **Then**: Timestamp is valid ISO 8601 format

**TC-037: Long values are not truncated**
- **Given**: Notification with value of 1000 characters
- **When**: `formatNotification(notification)` is called
- **Then**: Full value displayed

---

## Integration Tests (Mocked Client)

### 4. Set Command Tests (`tests/integration/set.test.ts`)

**TC-038: Set command succeeds with valid inputs**
- **Given**: Mock RaftClient that returns successfully
- **When**: Command executed: `kvstore set mykey myvalue`
- **Then**: 
  - `validateKey` and `validateValue` called
  - `client.connect()` called
  - `client.submitCommand()` called with encoded payload
  - Output: "OK"
  - Exit code: 0

**TC-039: Set command fails with invalid key**
- **Given**: Key exceeding 256 bytes
- **When**: Command executed with long key
- **Then**:
  - Validation fails before client creation
  - Output: "Error: Key exceeds 256 bytes..."
  - Exit code: 1
  - Client never created

**TC-040: Set command fails with invalid value**
- **Given**: Value exceeding 1MB
- **When**: Command executed with large value
- **Then**:
  - Validation fails
  - Error message displayed
  - Exit code: 1

**TC-041: Set command handles connection timeout**
- **Given**: Mock client that throws TimeoutError on connect()
- **When**: Command executed
- **Then**:
  - Output: "Error: Could not connect to cluster (timeout after 5s)"
  - Exit code: 2

**TC-042: Set command handles server error**
- **Given**: Mock client that rejects submitCommand()
- **When**: Command executed
- **Then**:
  - Error message displayed
  - Exit code: 3

**TC-043: Set command with custom endpoints**
- **Given**: Custom endpoints via --endpoints flag
- **When**: Command executed with -e "node-1=tcp://custom:7001"
- **Then**:
  - Client created with parsed endpoints
  - Command succeeds

---

### 5. Get Command Tests (`tests/integration/get.test.ts`)

**TC-044: Get command returns existing value**
- **Given**: Mock client that returns encoded "myvalue"
- **When**: Command executed: `kvstore get mykey`
- **Then**:
  - `client.query()` called with encoded Get query
  - Output: "mykey = myvalue"
  - Exit code: 0

**TC-045: Get command handles non-existent key**
- **Given**: Mock client returns None (null)
- **When**: Command executed for non-existent key
- **Then**:
  - Output: "mykey = <none>"
  - Exit code: 0 (not an error)

**TC-046: Get command fails with invalid key**
- **Given**: Empty key
- **When**: Command executed: `kvstore get ""`
- **Then**:
  - Validation error
  - Exit code: 1

**TC-047: Get command handles connection timeout**
- **Given**: Mock client throws TimeoutError
- **When**: Command executed
- **Then**:
  - Error message with timeout info
  - Exit code: 2

**TC-048: Get command handles decode error**
- **Given**: Mock client returns invalid buffer
- **When**: Command executed and decode fails
- **Then**:
  - Error message displayed
  - Exit code: 3

---

### 6. Watch Command Tests (`tests/integration/watch.test.ts`)

**TC-049: Watch command displays notifications**
- **Given**: Mock client that emits 3 notifications
- **When**: Command executed: `kvstore watch mykey`
- **Then**:
  - Initial message: "watching mykey - press Ctrl+C to stop"
  - 3 formatted notifications displayed
  - Watch continues until interrupted

**TC-050: Watch command handles SIGINT gracefully**
- **Given**: Mock client watching
- **When**: SIGINT signal sent (Ctrl+C)
- **Then**:
  - Shutdown message displayed
  - `client.disconnect()` called
  - Exit code: 130

**TC-051: Watch command filters notifications by key**
- **Given**: Mock client emits notifications for multiple keys
- **When**: Watching "mykey"
- **Then**:
  - Only notifications for "mykey" displayed
  - Other keys' notifications ignored

**TC-052: Watch command fails with invalid key**
- **Given**: Invalid key (too long)
- **When**: Command executed
- **Then**:
  - Validation error before watch registered
  - Exit code: 1

**TC-053: Watch command handles connection loss and reconnection**
- **Given**: Mock client simulates disconnection then reconnection
- **When**: Watch active during disconnection
- **Then**:
  - Client reconnects automatically
  - Watch re-registered transparently
  - Notifications resume
  - (User may see brief disconnection notice)

**TC-054: Watch command handles watch registration failure**
- **Given**: Mock client rejects watch registration (submitCommand fails)
- **When**: Command executed
- **Then**:
  - Error message displayed
  - Exit code: 3

---

## Edge Case Tests

### 7. Edge Cases (`tests/integration/edge-cases.test.ts`)

**TC-055: Whitespace-only key is rejected**
- **Given**: key="   "
- **When**: Any command executed
- **Then**: Validation error

**TC-056: Key with only unicode whitespace rejected**
- **Given**: key="\u00A0\u2003" (non-breaking spaces)
- **When**: Command executed
- **Then**: Should be validated - decide if allowed or rejected

**TC-057: Maximum size key and value succeed**
- **Given**: key at 256 bytes, value at 1MB
- **When**: Set command executed
- **Then**: Succeeds without error

**TC-058: Empty endpoint string uses default**
- **Given**: --endpoints ""
- **When**: Command executed
- **Then**: Default endpoint used

**TC-059: Multiple commands to same cluster**
- **Given**: Execute set, then get, then watch sequentially
- **When**: All commands to same endpoints
- **Then**: Each creates new client, all succeed

**TC-060: Concurrent watch commands**
- **Given**: Two watch commands on different keys
- **When**: Both running simultaneously
- **Then**: Each operates independently

---

## Error Scenario Tests

### 8. Error Handling (`tests/integration/errors.test.ts`)

**TC-061: Network timeout during connect**
- **Given**: Client configured with 5s timeout
- **When**: Connection takes >5s
- **Then**: TimeoutError, clear error message, exit code 2

**TC-062: Network timeout during operation**
- **Given**: Connected client, operation takes >5s
- **When**: Command executed
- **Then**: TimeoutError, clear message

**TC-063: Protocol error (invalid server response)**
- **Given**: Server returns malformed data
- **When**: Decode attempted
- **Then**: ProtocolError, informative message

**TC-064: Invalid discriminator in notification**
- **Given**: Server sends message with wrong discriminator
- **When**: Watch command decodes message
- **Then**: Error logged, watch continues (or exits cleanly)

**TC-065: Signal handling during command execution**
- **Given**: Set or get command in progress
- **When**: SIGINT received
- **Then**: Command completes or aborts gracefully, exit code 130

---

## Test Coverage Requirements

### Critical Paths (Must be 100%)
- [ ] All validation functions
- [ ] All codec encode/decode functions
- [ ] Error handling in command handlers
- [ ] Signal handling in watch command

### Important Paths (Target >90%)
- [ ] Command execution flows
- [ ] Output formatting
- [ ] Endpoint parsing

### Nice-to-Have (Target >70%)
- [ ] Edge cases
- [ ] Error formatting
- [ ] Helper utilities

---

## Test Execution

### Running Tests
```bash
cd kvstore-cli-ts

# All tests
npm test

# Unit tests only
npm test tests/unit

# Integration tests only
npm test tests/integration

# Specific test file
npm test tests/unit/validation.test.ts

# Watch mode
npm test -- --watch

# Coverage report
npm test -- --coverage
```

### Test Organization
```
tests/
├── unit/
│   ├── validation.test.ts    # TC-001 to TC-019
│   ├── codecs.test.ts         # TC-020 to TC-030
│   └── formatting.test.ts     # TC-031 to TC-037
├── integration/
│   ├── set.test.ts            # TC-038 to TC-043
│   ├── get.test.ts            # TC-044 to TC-048
│   ├── watch.test.ts          # TC-049 to TC-054
│   ├── edge-cases.test.ts     # TC-055 to TC-060
│   └── errors.test.ts         # TC-061 to TC-065
└── helpers/
    └── mocks.ts               # Mock RaftClient implementations
```

---

## Test Data

### Valid Test Cases
- Keys: "key", "user:123", "config.db.host", "キー" (unicode)
- Values: "value", "complex value with spaces", JSON strings, long text
- Endpoints: Single node, multiple nodes, custom ports

### Invalid Test Cases
- Keys: "", "   ", 257+ bytes, invalid UTF-8
- Values: "", 1MB+1 byte, invalid UTF-8
- Endpoints: Missing protocol, invalid port, malformed

---

## Manual Testing Checklist

After automated tests pass, manually verify:
- [ ] Install globally (`npm link`) and run from any directory
- [ ] Test against real KVStore cluster
- [ ] Cross-verify output with Scala kvstore-cli
- [ ] Test on Linux, macOS, Windows
- [ ] Ctrl+C interruption works smoothly
- [ ] Error messages are clear and actionable
- [ ] Help text is accurate (`kvstore --help`)

---

**Status**: ✅ All test scenarios defined, ready for implementation
**Total Test Cases**: 65
**Estimated Test Code**: ~1000-1200 lines
