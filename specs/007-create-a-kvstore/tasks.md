# Tasks: TypeScript KVStore CLI Client

**Feature Branch**: `007-create-a-kvstore`  
**Input**: Design documents from `/Users/keisar/Desktop/Projects/Unit/zio-raft/specs/007-create-a-kvstore/`  
**Prerequisites**: plan.md ✅, research.md ✅, data-model.md ✅, design.md ✅, tests.md ✅, quickstart.md ✅

## Overview

This task list implements a TypeScript CLI application for interacting with the KVStore distributed key-value store. The CLI provides three commands (set, get, watch) using the existing `@zio-raft/typescript-client` library with binary protocol encoding/decoding for KVStore operations.

**Target**: ~500-800 lines of production code, 65 test scenarios, comprehensive CLI tool

---

## Path Conventions

**Project Root**: `/Users/keisar/Desktop/Projects/Unit/zio-raft/kvstore-cli-ts/`

**Structure**:
```
kvstore-cli-ts/
├── src/
│   ├── index.ts              # CLI entry point
│   ├── kvClient.ts           # KVClient wrapper
│   ├── commands/
│   │   ├── set.ts            # Set command
│   │   ├── get.ts            # Get command
│   │   └── watch.ts          # Watch command
│   ├── validation.ts         # Input validation
│   ├── codecs.ts             # Protocol encoding/decoding
│   ├── formatting.ts         # Output formatting
│   └── types.ts              # Type definitions
├── tests/
│   ├── unit/
│   │   ├── validation.test.ts
│   │   ├── codecs.test.ts
│   │   └── formatting.test.ts
│   └── integration/
│       ├── set.test.ts
│       ├── get.test.ts
│       ├── watch.test.ts
│       ├── edge-cases.test.ts
│       └── errors.test.ts
├── package.json
├── tsconfig.json
├── vitest.config.ts
└── README.md
```

---

## Phase 3.1: Project Setup & Infrastructure

### T001: Create Project Directory and Package.json
**Files**: `kvstore-cli-ts/package.json`  
**Status**: ✅ COMPLETED  
**Description**: Initialize TypeScript CLI project with proper configuration
- Create `kvstore-cli-ts/` directory at repo root level
- Initialize package.json with:
  - `name`: "@zio-raft/kvstore-cli"
  - `version`: "0.1.0"
  - `bin`: { "kvstore": "./dist/index.js" }
  - `files`: ["dist"]
  - `engines`: { "node": ">=18.0.0" }
- Add production dependencies:
  - `@zio-raft/typescript-client`: "file:../typescript-client"
  - `commander`: "^11.1.0"
- Add dev dependencies:
  - `typescript`: "^5.3.3"
  - `@types/node`: "^18.19.3"
  - `vitest`: "^1.0.4"
  - `@vitest/coverage-v8`: "^1.0.4"
  - `tsx`: "^4.7.0"
- Add scripts:
  - `build`: "tsc"
  - `test`: "vitest"
  - `test:watch`: "vitest --watch"
  - `test:coverage`: "vitest --coverage"
  - `lint`: "tsc --noEmit"
  - `prepublishOnly`: "npm run build"

**Acceptance**: package.json created with correct metadata and dependencies

**References**: 
- plan.md lines 42-60 (Technical Context)
- research.md lines 267-292 (Dependencies)

---

### T002: Configure TypeScript with Strict Mode
**Files**: `kvstore-cli-ts/tsconfig.json`  
**Status**: ✅ COMPLETED  
**Description**: Configure TypeScript for strict type safety and Node.js CLI
- Set compiler options:
  - `strict`: true
  - `target`: "ES2022"
  - `module`: "commonjs"
  - `moduleResolution`: "node"
  - `outDir`: "dist"
  - `rootDir`: "src"
  - `declaration`: true
  - `declarationMap`: true
  - `esModuleInterop`: true
  - `skipLibCheck`: true
  - `forceConsistentCasingInFileNames`: true
- Set `include`: ["src/**/*"]
- Set `exclude`: ["node_modules", "dist", "tests"]

**Acceptance**: TypeScript compiles successfully with strict mode enabled

**References**: 
- plan.md lines 68-71 (Constitution Check - TypeScript strict mode)

---

### T003: Configure Vitest Test Framework
**Files**: `kvstore-cli-ts/vitest.config.ts`  
**Status**: ✅ COMPLETED  
**Description**: Create Vitest configuration for unit and integration tests
- Configure test environment: "node"
- Set up coverage with c8 provider:
  - Thresholds: 90% lines, 90% branches, 90% functions
  - Include: "src/**/*.ts"
  - Exclude: "src/index.ts" (entry point)
- Configure test file patterns: "tests/**/*.test.ts"
- Enable watch mode and UI

**Acceptance**: `npm test` runs successfully (with 0 tests initially)

**References**: 
- research.md lines 220-248 (Testing Strategy)

---

## Phase 3.2: Core Type Definitions & Validation

### T004 [P]: Implement Core Type Definitions
**Files**: `kvstore-cli-ts/src/types.ts`  
**Status**: ✅ COMPLETED  
**Description**: Define core TypeScript types and interfaces
- Command types: `SetCommand`, `GetCommand`, `WatchCommand` (discriminated union)
- Configuration types: `EndpointConfig`, `MemberId`, `Endpoint`
- Result types: `CommandResult`, `SetResult`, `GetResult`, `WatchResult`
- Notification type: `WatchNotification` with timestamp, sequenceNumber, key, value, metadata
- Protocol message types: `KVClientRequest`, `KVQuery`, `KVServerRequest`
- All interfaces use `readonly` modifiers for immutability
- Add JSDoc comments for each type

**Acceptance**: All core types defined with proper TypeScript strict typing

**References**: 
- data-model.md lines 36-320 (Core Entities)

---

### T005 [P]: Implement Error Class Hierarchy
**Files**: `kvstore-cli-ts/src/errors.ts`  
**Status**: ✅ COMPLETED  
**Description**: Create custom error classes for CLI operations
- `ValidationError`: field, message, actual?, expected?
  - For client-side validation failures
- `OperationError`: type, operation, reason, message, details?
  - For runtime errors during command execution
- Each error extends Error with proper `name` property
- Typed fields for programmatic error handling

**Acceptance**: All error classes defined, instanceof checks work

**References**: 
- data-model.md lines 352-422 (Error Models)

---

### T006 [P]: Implement Key Validation Function
**Files**: `kvstore-cli-ts/src/validation.ts`  
**Status**: ✅ COMPLETED  
**Description**: Implement key validation logic
- `validateKey(key: string): void | throws ValidationError`
- Check: non-empty string
- Check: valid UTF-8 encoding
- Check: ≤256 bytes when encoded as UTF-8
- Use Node.js Buffer for byte-length calculation
- Verify UTF-8 round-trip integrity
- Throw descriptive ValidationError with actual/expected values

**Acceptance**: Validates keys correctly, rejects invalid inputs with clear messages

**References**: 
- data-model.md lines 427-449 (Key Validation)
- tests.md lines 19-52 (TC-001 to TC-006)

---

### T007 [P]: Implement Value Validation Function
**Files**: `kvstore-cli-ts/src/validation.ts`  
**Status**: ✅ COMPLETED  
**Description**: Implement value validation logic
- `validateValue(value: string): void | throws ValidationError`
- Check: non-empty string
- Check: valid UTF-8 encoding
- Check: ≤1MB (1,048,576 bytes) when encoded as UTF-8
- Use Node.js Buffer for byte-length calculation
- Verify UTF-8 round-trip integrity
- Throw descriptive ValidationError with actual/expected values

**Acceptance**: Validates values correctly, rejects invalid inputs with clear messages

**References**: 
- data-model.md lines 451-475 (Value Validation)
- tests.md lines 54-86 (TC-007 to TC-012)

---

### T008 [P]: Implement Endpoint Parsing and Validation
**Files**: `kvstore-cli-ts/src/validation.ts`  
**Status**: ✅ COMPLETED  
**Description**: Implement endpoint configuration parsing
- `parseEndpoints(input: string): EndpointConfig | throws ValidationError`
- Parse format: "memberId1=tcp://host1:port1,memberId2=tcp://host2:port2"
- Validate each endpoint matches regex: `^tcp://([^:]+):(\d+)$`
- Validate port range: 1-65535
- Validate at least one endpoint provided
- Trim whitespace from member IDs and endpoints
- Return Map<MemberId, Endpoint>
- Default value: "node-1=tcp://127.0.0.1:7001"

**Acceptance**: Parses valid endpoints, rejects invalid formats with clear errors

**References**: 
- data-model.md lines 477-539 (Endpoint Validation)
- tests.md lines 88-125 (TC-013 to TC-019)

---

## Phase 3.3: Protocol Codecs (Binary Encoding/Decoding)

### T009 [P]: Implement UTF-8 Length-Prefixed String Codec
**Files**: `kvstore-cli-ts/src/codecs.ts`  
**Status**: ✅ COMPLETED  
**Description**: Implement low-level string encoding/decoding
- `encodeUtf8_32(str: string): Buffer`
  - Encode string as UTF-8 bytes
  - Prepend 4-byte big-endian length prefix
  - Return concatenated Buffer
- `decodeUtf8_32(buffer: Buffer, offset: number): { value: string; bytesRead: number }`
  - Read 4-byte big-endian length at offset
  - Extract UTF-8 bytes
  - Return decoded string and total bytes read
- Match Scala scodec format exactly (big-endian length)

**Acceptance**: Round-trip encoding/decoding preserves strings exactly

**References**: 
- data-model.md lines 240-248 (Binary Encoding)
- design.md lines 452-465 (Codec Implementation)

---

### T010 [P]: Implement Set Request Encoder
**Files**: `kvstore-cli-ts/src/codecs.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T009 (UTF-8 codec)  
**Description**: Encode KVClientRequest.Set messages
- `encodeSetRequest(key: string, value: string): Buffer`
- Format: [0x53 'S'][length:4][key bytes][length:4][value bytes]
- Discriminator: ASCII 'S' (0x53)
- Use `encodeUtf8_32` for key and value
- Concatenate buffers in order

**Acceptance**: Produces correctly formatted Set request buffer

**References**: 
- data-model.md lines 226-248 (KVClientRequest encoding)
- tests.md lines 130-156 (TC-020 to TC-024)

---

### T011 [P]: Implement Get Query Encoder
**Files**: `kvstore-cli-ts/src/codecs.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T009 (UTF-8 codec)  
**Description**: Encode KVQuery.Get messages
- `encodeGetQuery(key: string): Buffer`
- Format: [0x47 'G'][length:4][key bytes]
- Discriminator: ASCII 'G' (0x47)
- Use `encodeUtf8_32` for key

**Acceptance**: Produces correctly formatted Get query buffer

**References**: 
- data-model.md lines 253-269 (KVQuery encoding)
- tests.md lines 138-141 (TC-021)

---

### T012 [P]: Implement Watch Request Encoder
**Files**: `kvstore-cli-ts/src/codecs.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T009 (UTF-8 codec)  
**Description**: Encode KVClientRequest.Watch messages
- `encodeWatchRequest(key: string): Buffer`
- Format: [0x57 'W'][length:4][key bytes]
- Discriminator: ASCII 'W' (0x57)
- Use `encodeUtf8_32` for key

**Acceptance**: Produces correctly formatted Watch request buffer

**References**: 
- data-model.md lines 226-239 (KVClientRequest.Watch)
- tests.md lines 142-146 (TC-022)

---

### T013 [P]: Implement Get Result Decoder
**Files**: `kvstore-cli-ts/src/codecs.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T009 (UTF-8 codec)  
**Description**: Decode Get query results (Option[String])
- `decodeGetResult(buffer: Buffer): string | null`
- Format: [hasValue:1][length:4][value bytes] (if hasValue=1) or [0x00] (if None)
- First byte: 0=None, 1=Some
- If Some: decode UTF-8 string using `decodeUtf8_32`
- If None: return null

**Acceptance**: Correctly decodes both Some and None cases

**References**: 
- data-model.md lines 253-269 (KVQuery result)
- tests.md lines 158-170 (TC-025 to TC-026)

---

### T014 [P]: Implement Notification Decoder
**Files**: `kvstore-cli-ts/src/codecs.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T009 (UTF-8 codec)  
**Description**: Decode KVServerRequest.Notification messages
- `decodeNotification(buffer: Buffer): WatchNotification`
- Format: [0x4E 'N'][length:4][key bytes][length:4][value bytes]
- Verify discriminator is 'N' (0x4E), throw ProtocolError if not
- Decode key and value using `decodeUtf8_32`
- Return WatchNotification with:
  - timestamp: new Date()
  - sequenceNumber: 0n (TODO: extract from metadata if available)
  - key, value from decoded bytes

**Acceptance**: Correctly decodes notifications, throws on invalid discriminator

**References**: 
- data-model.md lines 274-292 (KVServerRequest)
- tests.md lines 172-180 (TC-027 to TC-028)

---

## Phase 3.4: KVClient Wrapper

### T015: Implement KVClient Protocol Wrapper
**Files**: `kvstore-cli-ts/src/kvClient.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T010, T011, T012, T013, T014 (all codecs)  
**Description**: Create protocol-specific wrapper around RaftClient
- `class KVClient`:
  - Constructor: accepts config (endpoints, timeouts)
  - `connect(): Promise<void>` - delegates to RaftClient
  - `disconnect(): Promise<void>` - delegates to RaftClient
  - `set(key: string, value: string): Promise<void>`
    - Encode using `encodeSetRequest`
    - Call `raftClient.submitCommand(payload)`
    - Result is Unit, no decoding needed
  - `get(key: string): Promise<string | null>`
    - Encode using `encodeGetQuery`
    - Call `raftClient.query(payload)`
    - Decode using `decodeGetResult`
  - `watch(key: string): Promise<void>`
    - Encode using `encodeWatchRequest`
    - Call `raftClient.submitCommand(payload)`
    - Watch registered, notifications come via `notifications()`
  - `notifications(): AsyncIterableIterator<WatchNotification>`
    - Iterate over `raftClient.serverRequests()`
    - Decode each using `decodeNotification`
    - Yield typed WatchNotification

**Acceptance**: KVClient provides clean typed API over RaftClient

**References**: 
- design.md lines 113-170 (KVClient Wrapper)
- data-model.md lines 159-218 (Usage Pattern)

---

## Phase 3.5: Output Formatting

### T016 [P]: Implement Output Formatting Functions
**Files**: `kvstore-cli-ts/src/formatting.ts`  
**Status**: ✅ COMPLETED  
**Description**: Create user-facing output formatting utilities
- `formatSuccess(message: string): string` - returns message as-is
- `formatError(error: unknown): string`
  - Handle ValidationError: "Error: {message}"
  - Handle TimeoutError: "Error: Operation timed out after 5s"
  - Handle ConnectionError: "Error: Could not connect to cluster (timeout after 5s)"
  - Handle generic errors: "Error: {error.toString()}"
- `formatNotification(notification: WatchNotification): string`
  - Format: "[{ISO timestamp}] seq={sequenceNumber} key={key} value={value}"
  - Use `notification.timestamp.toISOString()`
- `getExitCode(error: unknown): number`
  - ValidationError → 1
  - ConnectionError/TimeoutError → 2
  - ProtocolError/OperationError → 3
  - Unknown → 1

**Acceptance**: All formatting functions produce correct output strings

**References**: 
- design.md lines 533-584 (Output Formatting)
- tests.md lines 197-239 (TC-031 to TC-037)

---

## Phase 3.6: Command Implementations

### T017: Implement Set Command
**Files**: `kvstore-cli-ts/src/commands/set.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T004, T005, T006, T007, T008, T015, T016  
**Description**: Implement set command handler using Commander
- Create Command('set') with:
  - `.description('Set a key-value pair')`
  - `.argument('<key>', 'Key to set')`
  - `.argument('<value>', 'Value to set')`
  - `.option('-e, --endpoints <endpoints>', 'Cluster endpoints', 'node-1=tcp://127.0.0.1:7001')`
- Action handler:
  1. Validate key and value (fast-fail)
  2. Parse endpoints
  3. Create KVClient with 5s timeouts
  4. Connect to cluster
  5. Call `client.set(key, value)`
  6. Display "OK" on success
  7. Disconnect and exit(0)
  8. Catch errors, format, and exit with appropriate code
- Use try-finally for cleanup

**Acceptance**: Set command works end-to-end with proper validation and error handling

**References**: 
- design.md lines 176-229 (Set Command)
- tests.md lines 243-293 (TC-038 to TC-043)

---

### T018: Implement Get Command
**Files**: `kvstore-cli-ts/src/commands/get.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T004, T005, T006, T008, T015, T016  
**Description**: Implement get command handler using Commander
- Create Command('get') with:
  - `.description('Get a value by key')`
  - `.argument('<key>', 'Key to get')`
  - `.option('-e, --endpoints <endpoints>', 'Cluster endpoints', 'node-1=tcp://127.0.0.1:7001')`
- Action handler:
  1. Validate key
  2. Parse endpoints
  3. Create KVClient with 5s timeouts
  4. Connect to cluster
  5. Call `client.get(key)`
  6. Display "{key} = {value}" or "{key} = <none>" if null
  7. Disconnect and exit(0)
  8. Catch errors, format, and exit with appropriate code
- Note: Uses `client.query()` which doesn't go through Raft consensus

**Acceptance**: Get command retrieves values correctly, handles not-found case

**References**: 
- design.md lines 241-292 (Get Command)
- tests.md lines 295-333 (TC-044 to TC-048)

---

### T019: Implement Watch Command with Signal Handling
**Files**: `kvstore-cli-ts/src/commands/watch.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T004, T005, T006, T008, T015, T016  
**Description**: Implement watch command handler with graceful shutdown
- Create Command('watch') with:
  - `.description('Watch a key for updates')`
  - `.argument('<key>', 'Key to watch')`
  - `.option('-e, --endpoints <endpoints>', 'Cluster endpoints', 'node-1=tcp://127.0.0.1:7001')`
- Action handler:
  1. Register SIGINT/SIGTERM handlers for graceful shutdown
  2. Validate key
  3. Parse endpoints
  4. Create KVClient with keepAlive settings
  5. Connect to cluster
  6. Call `client.watch(key)` to register watch
  7. Display "watching {key} - press Ctrl+C to stop"
  8. Iterate over `client.notifications()`
  9. Filter notifications by key (only display matching key)
  10. Format and display each notification
  11. On SIGINT: disconnect, display "Shutting down...", exit(130)
- Signal handler should properly cleanup client connection

**Acceptance**: Watch displays notifications in real-time, Ctrl+C exits gracefully

**References**: 
- design.md lines 294-370 (Watch Command)
- tests.md lines 335-384 (TC-049 to TC-054)

---

### T020: Implement CLI Entry Point
**Files**: `kvstore-cli-ts/src/index.ts`  
**Status**: ✅ COMPLETED  
**Dependencies**: T017, T018, T019  
**Description**: Create main CLI entry point that registers all commands
- Add shebang: `#!/usr/bin/env node`
- Import Commander and all command modules
- Create main Program:
  - `.name('kvstore')`
  - `.version('0.1.0')`
  - `.description('KVStore CLI - interact with a distributed key-value store')`
- Register commands: `program.addCommand(setCommand)`, etc.
- Parse arguments: `program.parse(process.argv)`
- Handle no command case: show help

**Acceptance**: CLI runs, shows help, dispatches to commands correctly

**References**: 
- design.md lines 78-110 (CLI Entry Point)

---

## Phase 3.7: Unit Tests - Validation

### T021 [P]: Implement Key Validation Unit Tests
**Files**: `kvstore-cli-ts/tests/unit/validation.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T006  
**Description**: Test key validation logic (TC-001 to TC-006)
- Valid key passes (TC-001)
- Empty key rejected (TC-002)
- Key >256 bytes rejected with actual/expected (TC-003)
- Key at exactly 256 bytes passes (TC-004)
- Unicode key validated by byte length (TC-005)
- Invalid UTF-8 rejected (TC-006)

**Acceptance**: All 6 test cases pass

**References**: 
- tests.md lines 19-52 (TC-001 to TC-006)

---

### T022 [P]: Implement Value Validation Unit Tests
**Files**: `kvstore-cli-ts/tests/unit/validation.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T007  
**Description**: Test value validation logic (TC-007 to TC-012)
- Valid value passes (TC-007)
- Empty value rejected (TC-008)
- Value >1MB rejected with actual/expected (TC-009)
- Value at exactly 1MB passes (TC-010)
- Large unicode value validated by bytes (TC-011)
- Invalid UTF-8 rejected (TC-012)

**Acceptance**: All 6 test cases pass

**References**: 
- tests.md lines 54-86 (TC-007 to TC-012)

---

### T023 [P]: Implement Endpoint Parsing Unit Tests
**Files**: `kvstore-cli-ts/tests/unit/validation.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T008  
**Description**: Test endpoint parsing logic (TC-013 to TC-019)
- Single endpoint parsed (TC-013)
- Multiple endpoints parsed (TC-014)
- Default endpoint used when empty (TC-015)
- Invalid format rejected (TC-016)
- Invalid port rejected (TC-017)
- Missing member ID rejected (TC-018)
- Whitespace trimmed (TC-019)

**Acceptance**: All 7 test cases pass

**References**: 
- tests.md lines 88-125 (TC-013 to TC-019)

---

## Phase 3.8: Unit Tests - Codecs

### T024 [P]: Implement Codec Encoding Unit Tests
**Files**: `kvstore-cli-ts/tests/unit/codecs.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T010, T011, T012  
**Description**: Test encoding functions (TC-020 to TC-024)
- Set request encodes correctly with discriminator and fields (TC-020)
- Get query encodes correctly (TC-021)
- Watch request encodes correctly (TC-022)
- Unicode key encodes with correct byte length (TC-023)
- Large value (500KB) encodes correctly (TC-024)

**Acceptance**: All 5 test cases pass, byte-level verification

**References**: 
- tests.md lines 130-156 (TC-020 to TC-024)

---

### T025 [P]: Implement Codec Decoding Unit Tests
**Files**: `kvstore-cli-ts/tests/unit/codecs.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T013, T014  
**Description**: Test decoding functions (TC-025 to TC-028)
- Get result with value decodes correctly (TC-025)
- Get result without value (None) decodes to null (TC-026)
- Notification decodes correctly (TC-027)
- Invalid discriminator throws error (TC-028)

**Acceptance**: All 4 test cases pass

**References**: 
- tests.md lines 158-180 (TC-025 to TC-028)

---

### T026 [P]: Implement Codec Round-Trip Unit Tests
**Files**: `kvstore-cli-ts/tests/unit/codecs.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T010, T013, T014  
**Description**: Test encoding/decoding round-trips (TC-029 to TC-030)
- Set request round-trips correctly (TC-029)
- Unicode values preserve exactly through round-trip (TC-030)

**Acceptance**: All 2 test cases pass

**References**: 
- tests.md lines 182-195 (TC-029 to TC-030)

---

## Phase 3.9: Unit Tests - Formatting

### T027 [P]: Implement Output Formatting Unit Tests
**Files**: `kvstore-cli-ts/tests/unit/formatting.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T016  
**Description**: Test output formatting functions (TC-031 to TC-034)
- Success message formats correctly (TC-031)
- Validation error formats with details (TC-032)
- Connection error formats clearly (TC-033)
- Generic error formats safely (TC-034)

**Acceptance**: All 4 test cases pass

**References**: 
- tests.md lines 197-220 (TC-031 to TC-034)

---

### T028 [P]: Implement Notification Formatting Unit Tests
**Files**: `kvstore-cli-ts/tests/unit/formatting.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T016  
**Description**: Test notification formatting (TC-035 to TC-037)
- Notification formats with all fields (TC-035)
- Timestamp formats as ISO 8601 (TC-036)
- Long values not truncated (TC-037)

**Acceptance**: All 3 test cases pass

**References**: 
- tests.md lines 222-239 (TC-035 to TC-037)

---

## Phase 3.10: Integration Tests - Commands

### T029 [P]: Implement Set Command Integration Tests
**Files**: `kvstore-cli-ts/tests/integration/set.test.ts`, `tests/helpers/mocks.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T017  
**Description**: Test set command with mocked KVClient (TC-038 to TC-043)
- Create mock KVClient implementation
- Set succeeds with valid inputs (TC-038)
- Set fails with invalid key (TC-039)
- Set fails with invalid value (TC-040)
- Set handles connection timeout (TC-041)
- Set handles server error (TC-042)
- Set with custom endpoints (TC-043)

**Acceptance**: All 6 test cases pass

**References**: 
- tests.md lines 243-293 (TC-038 to TC-043)

---

### T030 [P]: Implement Get Command Integration Tests
**Files**: `kvstore-cli-ts/tests/integration/get.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T018  
**Description**: Test get command with mocked KVClient (TC-044 to TC-048)
- Get returns existing value (TC-044)
- Get handles non-existent key (displays "<none>") (TC-045)
- Get fails with invalid key (TC-046)
- Get handles connection timeout (TC-047)
- Get handles decode error (TC-048)

**Acceptance**: All 5 test cases pass

**References**: 
- tests.md lines 295-333 (TC-044 to TC-048)

---

### T031 [P]: Implement Watch Command Integration Tests
**Files**: `kvstore-cli-ts/tests/integration/watch.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T019  
**Description**: Test watch command with mocked KVClient (TC-049 to TC-054)
- Watch displays notifications (TC-049)
- Watch handles SIGINT gracefully (TC-050)
- Watch filters notifications by key (TC-051)
- Watch fails with invalid key (TC-052)
- Watch handles reconnection (TC-053)
- Watch handles registration failure (TC-054)

**Acceptance**: All 6 test cases pass

**References**: 
- tests.md lines 335-384 (TC-049 to TC-054)

---

### T032 [P]: Implement Edge Case Integration Tests
**Files**: `kvstore-cli-ts/tests/integration/edge-cases.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T017, T018, T019  
**Description**: Test edge cases across commands (TC-055 to TC-060)
- Whitespace-only key rejected (TC-055)
- Unicode whitespace key handling (TC-056)
- Maximum size key and value succeed (TC-057)
- Empty endpoint string uses default (TC-058)
- Multiple sequential commands (TC-059)
- Concurrent watch commands (TC-060)

**Acceptance**: All 6 test cases pass

**References**: 
- tests.md lines 386-420 (TC-055 to TC-060)

---

### T033 [P]: Implement Error Handling Integration Tests
**Files**: `kvstore-cli-ts/tests/integration/errors.test.ts`  
**Status**: ❌ NOT STARTED  
**Dependencies**: T017, T018, T019  
**Description**: Test error scenarios (TC-061 to TC-065)
- Network timeout during connect (TC-061)
- Network timeout during operation (TC-062)
- Protocol error from invalid server response (TC-063)
- Invalid discriminator in notification (TC-064)
- Signal handling during command execution (TC-065)

**Acceptance**: All 5 test cases pass

**References**: 
- tests.md lines 422-450 (TC-061 to TC-065)

---

## Phase 3.11: Documentation

### T034: Create README with Usage Examples
**Files**: `kvstore-cli-ts/README.md`  
**Status**: ✅ COMPLETED  
**Description**: Create comprehensive README
- Project overview and purpose
- Installation instructions (from source, from npm)
- Quick start examples (set, get, watch)
- Command reference (all options)
- Configuration (default endpoints, environment variables)
- Exit codes and error handling
- Troubleshooting common issues
- Comparison with Scala kvstore-cli
- Contributing guidelines
- Link to quickstart.md for detailed examples

**Acceptance**: README is clear, complete, and helpful for new users

**References**: 
- quickstart.md (entire file - usage examples)

---

### T035: Create Examples Directory
**Files**: `kvstore-cli-ts/examples/*.sh`  
**Status**: ⏭️ SKIPPED (examples in README sufficient for MVP)  
**Description**: Create example shell scripts demonstrating CLI usage
- `basic-usage.sh`: Simple set/get/watch workflow
- `multi-node-cluster.sh`: Production cluster with multiple endpoints
- `watch-configuration.sh`: Config change monitoring pattern
- `batch-operations.sh`: Scripted bulk operations
- Each script should be executable and well-commented

**Acceptance**: All example scripts run successfully

**References**: 
- quickstart.md lines 520-531 (Examples Repository)

---

### T036: Update Build Configuration for Executable
**Files**: `kvstore-cli-ts/package.json`, `kvstore-cli-ts/tsconfig.json`  
**Status**: ✅ COMPLETED  
**Description**: Ensure compiled CLI is executable
- Verify shebang is preserved in compiled dist/index.js
- Add post-build script to make dist/index.js executable (chmod +x)
- Test `npm link` for global installation
- Test `npx kvstore` execution
- Verify cross-platform compatibility (Linux, macOS, Windows)

**Acceptance**: `npm link && kvstore --help` works after build

**References**: 
- research.md lines 180-217 (TypeScript CLI Packaging)

---

## Phase 3.12: Build Verification & Final Testing

### T037: Run All Tests and Verify Coverage
**Files**: All test files  
**Status**: ❌ NOT STARTED  
**Dependencies**: T021-T033  
**Description**: Execute full test suite and verify coverage
- Run `npm test` - all 65 test cases should pass
- Run `npm run test:coverage` - verify >90% coverage on src/
- Check coverage reports for critical paths (100% on validation, codecs)
- Address any gaps in test coverage
- Verify no TypeScript errors with `npm run lint`

**Acceptance**: All tests pass, coverage meets requirements, no linter errors

**References**: 
- tests.md lines 452-497 (Test Execution)

---

### T038: Manual End-to-End Testing with Real Cluster
**Files**: None (manual testing)  
**Status**: ❌ NOT STARTED  
**Dependencies**: T037  
**Description**: Perform manual testing against running KVStore cluster
- Start a local KVStore cluster (use Scala implementation)
- Test set command: store multiple keys
- Test get command: retrieve stored values and non-existent keys
- Test watch command: monitor key updates in real-time
- Test error scenarios: invalid cluster endpoints, connection timeouts
- Test signal handling: Ctrl+C during watch command
- Verify output formatting matches specification
- Compare behavior with Scala kvstore-cli (reference implementation)

**Acceptance**: All manual tests pass, behavior matches Scala CLI

**References**: 
- tests.md lines 532-541 (Manual Testing Checklist)
- quickstart.md lines 64-136 (Quick Start - 3 Commands)

---

### T039: Cross-Platform Testing
**Files**: None (manual testing)  
**Status**: ❌ NOT STARTED  
**Dependencies**: T038  
**Description**: Verify CLI works on multiple platforms
- Test on Linux (Ubuntu/Debian)
- Test on macOS (Intel and Apple Silicon if available)
- Test on Windows (WSL and native Node.js)
- Verify installation works (`npm link`, `npm install -g`)
- Verify executable permissions preserved
- Verify signal handling (Ctrl+C) works on all platforms

**Acceptance**: CLI works correctly on all tested platforms

**References**: 
- research.md lines 293-302 (Risks and Mitigations)

---

### T040: Performance and Stress Testing
**Files**: None (manual testing)  
**Status**: ❌ NOT STARTED  
**Dependencies**: T038  
**Description**: Verify performance characteristics
- Test set/get operations with various key/value sizes
- Test watch command with high-frequency updates
- Verify 5-second timeout works correctly
- Test with maximum size inputs (256B keys, 1MB values)
- Measure operation latency (should be <10ms for set/get)
- Verify throughput meets expectations (1K-10K ops/sec)

**Acceptance**: Performance meets requirements from plan.md

**References**: 
- plan.md lines 49-56 (Performance Goals)
- quickstart.md lines 472-480 (Performance Notes)

---

## Parallel Execution Guide

### Phase 3.1: Setup (Sequential)
```bash
# Must run sequentially
T001 → T002 → T003
```

### Phase 3.2: Core Types & Validation (Parallel)
```bash
# Can run in parallel [P]
npm run task T004 &
npm run task T005 &
npm run task T006 &
npm run task T007 &
npm run task T008 &
wait
```

### Phase 3.3: Codecs (Parallel after T009)
```bash
# T009 first (UTF-8 codec base)
npm run task T009
# Then parallel
npm run task T010 &
npm run task T011 &
npm run task T012 &
npm run task T013 &
npm run task T014 &
wait
```

### Phase 3.4-3.5: Client & Formatting (Parallel)
```bash
npm run task T015 &
npm run task T016 &
wait
```

### Phase 3.6: Commands (Sequential - shared types)
```bash
# Sequential due to shared types and error handling
T017 → T018 → T019 → T020
```

### Phase 3.7-3.9: Unit Tests (Parallel)
```bash
# All unit tests can run in parallel [P]
npm run task T021 &
npm run task T022 &
npm run task T023 &
npm run task T024 &
npm run task T025 &
npm run task T026 &
npm run task T027 &
npm run task T028 &
wait
```

### Phase 3.10: Integration Tests (Parallel)
```bash
# All integration tests can run in parallel [P]
npm run task T029 &
npm run task T030 &
npm run task T031 &
npm run task T032 &
npm run task T033 &
wait
```

### Phase 3.11-3.12: Documentation & Verification (Sequential)
```bash
# Documentation and final testing sequential
T034 → T035 → T036 → T037 → T038 → T039 → T040
```

---

## Success Criteria

- ✅ All 40 tasks completed
- ✅ All 65 automated test cases passing
- ✅ Test coverage >90% for src/
- ✅ TypeScript compiles with strict mode, no errors
- ✅ CLI installed globally via `npm link`
- ✅ Manual testing passes against real KVStore cluster
- ✅ Cross-platform compatibility verified
- ✅ Performance requirements met
- ✅ Documentation complete and accurate

---

## Estimated Effort

- **Total Tasks**: 40
- **Production Code**: ~500-800 lines
- **Test Code**: ~1,000-1,200 lines
- **Documentation**: ~300-500 lines
- **Time Estimate**: 15-20 hours (assuming experienced TypeScript developer)

---

## Notes

- Tasks marked `[P]` can be executed in parallel with other `[P]` tasks in the same phase
- Tasks without `[P]` must be executed sequentially within their phase
- All validation occurs before network operations (fast-fail principle)
- Protocol encoding must exactly match Scala scodec format for compatibility
- Signal handling (SIGINT) is critical for watch command user experience
- Exit codes follow Unix conventions (0=success, 1=validation, 2=connection, 3=operational, 130=SIGINT)

---

**Status**: ✅ Task breakdown complete, ready for implementation  
**Generated**: 2026-01-13  
**Based on**: plan.md, research.md, data-model.md, design.md, tests.md, quickstart.md
