# Research: TypeScript KVStore CLI Client

**Feature**: 007-create-a-kvstore  
**Date**: 2026-01-13  
**Status**: Complete

## Research Areas

### 1. CLI Framework Selection

**Decision**: Use `commander` (v11.x)

**Rationale**:
- Industry standard for Node.js CLI applications
- Excellent TypeScript support with type definitions
- Clean, declarative API for defining commands and options
- Built-in help generation and error handling
- Widely used (30M+ weekly downloads on npm)
- Supports subcommands, required/optional arguments, and custom validation

**Alternatives Considered**:
- `yargs`: More complex API, heavier weight
- `oclif`: Over-engineered for simple 3-command CLI
- `minimist`: Too low-level, requires manual help/validation
- Built-in `process.argv` parsing: Reinventing the wheel

**References**:
- Scala kvstore-cli uses `zio-cli` which maps well to commander's model
- Commander patterns: https://github.com/tj/commander.js

---

### 2. Argument Parsing and Validation

**Decision**: Pre-validate before calling RaftClient

**Rationale**:
- Fast-fail principle: catch errors before network operations
- Better user experience: immediate feedback without cluster round-trip
- Matches clarification: "reject invalid keys or values...before attempting cluster operations"
- Separation of concerns: validation layer independent of client library

**Implementation Pattern**:
```typescript
// Validation functions
function validateKey(key: string): Result<string, ValidationError>
function validateValue(value: string): Result<string, ValidationError>
function validateEndpoints(endpoints: string): Result<EndpointConfig, ValidationError>

// Used in command handlers before RaftClient calls
```

**Edge Cases Handled**:
- UTF-8 validation (Node.js Buffer.from with error handling)
- Size limits (Buffer.byteLength for accurate byte counting)
- Endpoint format parsing (regex: `memberId=tcp://host:port`)
- Empty/whitespace-only inputs

---

### 3. KVStore Protocol Integration

**Decision**: Use scodec-like encoding/decoding in TypeScript

**Rationale**:
- KVStore protocol defined in kvstore-protocol (Scala scodec)
- Must match binary format exactly for compatibility
- TypeScript needs equivalent codec implementations

**Protocol Messages** (from kvstore-protocol/ClientApi.scala):

**KVClientRequest** (Commands):
- `Set(key: String, value: String)` - discriminator "S" + utf8_32 key + utf8_32 value
- `Watch(key: String)` - discriminator "W" + utf8_32 key

**KVQuery** (Queries):
- `Get(key: String)` - discriminator "G" + utf8_32 key

**KVServerRequest** (Server-initiated):
- `Notification(key: String, value: String)` - discriminator "N" + utf8_32 key + utf8_32 value

**Codec Implementation**:
```typescript
// Discriminator: 1-byte ASCII character
// utf8_32: 4-byte length prefix (big-endian) + UTF-8 bytes

// Example for Set command:
// [0x53] [len:4] [key bytes] [len:4] [value bytes]
//  'S'    BE uint32           BE uint32
```

**Alternatives Considered**:
- JSON encoding: Not compatible with Scala scodec binary format
- Protocol Buffers: Different wire format
- Custom ad-hoc encoding: Reinventing scodec poorly

---

### 4. Error Handling and User Feedback

**Decision**: Three-tier error handling

**Rationale**:
- **Validation Errors**: Immediate, user-facing, actionable messages
- **Client Errors**: Network/timeout/protocol errors from RaftClient
- **Operational Errors**: Server rejections, session expiry

**Error Message Patterns**:
```
Validation: "Error: Key exceeds 256 bytes (actual: 312 bytes)"
Connection: "Error: Could not connect to cluster (timeout after 5s)"
Operational: "Error: Key 'mykey' not found"
```

**Exit Codes**:
- 0: Success
- 1: Validation error
- 2: Connection error
- 3: Operational error
- 130: SIGINT (Ctrl+C) - standard Unix convention

---

### 5. Output Formatting for Watch Streams

**Decision**: Structured verbose format per clarification

**Rationale**:
- Clarification specified: "Verbose: include timestamp, sequence number, and metadata"
- Reference implementation goal: demonstrate observability best practices
- Operational debugging: timestamps critical for distributed system troubleshooting

**Format Design**:
```
watching mykey - press Ctrl+C to stop

[2026-01-13T10:15:23.456Z] seq=42 key=mykey value=newvalue
[2026-01-13T10:15:24.789Z] seq=43 key=mykey value=anothervalue
^C
Disconnected. Exiting.
```

**Implementation**:
- ISO 8601 timestamps (sortable, timezone-aware)
- Sequence number from notification metadata
- One notification per line (grep-friendly)
- Graceful shutdown message on SIGINT

**Alternatives Considered**:
- JSON output: Less human-readable for interactive use
- Minimal format: Rejected by clarification decision
- Configurable verbosity: YAGNI for MVP

---

### 6. Signal Handling (SIGINT/SIGTERM)

**Decision**: Graceful shutdown with cleanup

**Rationale**:
- Watch command runs indefinitely until interrupted
- Must clean up client connections properly
- Unix signal handling best practice
- Matches FR-027: "clean up connections properly when...interrupted"

**Implementation Pattern**:
```typescript
process.on('SIGINT', async () => {
  console.error('\nShutting down...');
  await client.disconnect();
  process.exit(130); // Standard SIGINT exit code
});
```

**Behavior**:
- `set`/`get`: Already short-lived, minimal signal handling
- `watch`: Register SIGINT handler, call `client.disconnect()`, exit cleanly

---

### 7. TypeScript CLI Packaging and Distribution

**Decision**: npm package with bin entry point

**Rationale**:
- Standard Node.js CLI distribution mechanism
- npm installs symlink to executable
- Cross-platform (Windows, macOS, Linux)
- Integrated with existing typescript-client package structure

**Package.json Configuration**:
```json
{
  "name": "@zio-raft/kvstore-cli",
  "version": "0.1.0",
  "bin": {
    "kvstore": "./dist/index.js"
  },
  "files": ["dist"],
  "scripts": {
    "build": "tsc",
    "prepublishOnly": "npm run build"
  }
}
```

**Shebang**: `#!/usr/bin/env node` in compiled dist/index.js

**Installation**:
```bash
cd kvstore-cli-ts
npm install
npm run build
npm link  # Global installation for testing
kvstore set mykey myvalue
```

---

### 8. Testing Strategy

**Decision**: Unit tests + Integration tests with mocked RaftClient

**Rationale**:
- Unit tests: Validation, parsing, formatting logic (fast, isolated)
- Integration tests: Command execution flow with mock client (realistic, no cluster needed)
- No end-to-end tests in CLI: Those belong in kvstore server test suite
- Vitest for consistency with typescript-client

**Test Structure**:
```
tests/
├── unit/
│   ├── validation.test.ts   # Key/value/endpoint validation
│   ├── formatting.test.ts   # Output formatting
│   └── codecs.test.ts       # Protocol encoding/decoding
└── integration/
    ├── set.test.ts          # Set command with mock client
    ├── get.test.ts          # Get command with mock client
    └── watch.test.ts        # Watch command with mock client
```

**Mock Strategy**:
- Mock RaftClient interface
- Simulate success/error scenarios
- Verify correct client method calls
- Validate output formatting

---

## Technical Decisions Summary

| Area | Decision | Key Factor |
|------|----------|------------|
| CLI Framework | commander v11 | Industry standard, TypeScript support |
| Validation | Pre-validate inputs | Fast-fail, better UX |
| Protocol | Binary scodec format | Compatibility with Scala kvstore |
| Error Handling | Three-tier (validation/client/operational) | Clear user feedback |
| Watch Output | Verbose structured format | Clarification requirement |
| Signal Handling | Graceful shutdown | Best practice, clean resource cleanup |
| Packaging | npm package with bin entry | Standard Node.js CLI distribution |
| Testing | Unit + Integration (mocked) | Fast, isolated, realistic coverage |

---

## Dependencies

### Production Dependencies
```json
{
  "@zio-raft/typescript-client": "file:../typescript-client",
  "commander": "^11.1.0"
}
```

**Note**: Uses relative file path to reference sibling typescript-client directory

### Dev Dependencies
```json
{
  "@types/node": "^18.19.3",
  "typescript": "^5.3.3",
  "vitest": "^1.0.4",
  "@vitest/coverage-v8": "^1.0.4",
  "tsx": "^4.7.0"
}
```

### Transitive Dependencies (via typescript-client)
- `zeromq`: ^6.0.0

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Protocol encoding mismatch | High - incompatibility with server | Thorough codec testing, byte-level verification |
| Watch stream interruption | Medium - poor UX | Proper signal handling, reconnection via client library |
| Large value performance | Low - 1MB limit is manageable | Stream-based encoding for future optimization |
| Cross-platform compatibility | Low - Node.js abstracts OS | Test on multiple platforms during validation |

---

## Open Questions (Resolved in Clarifications)

All research questions were resolved during the clarification phase:
1. ✅ Watch reconnection behavior → Automatic re-registration
2. ✅ Operation timeout → 5 seconds
3. ✅ Watch output format → Verbose with timestamp/sequence/metadata
4. ✅ Key/value constraints → 256B keys, 1MB values, UTF-8 only

---

## Next Steps

Research complete. Ready for Phase 1 (Design):
1. Create data-model.md (entities, validation rules)
2. Create design.md (architecture, components, flows)
3. Create tests.md (test scenarios from requirements)
4. Create quickstart.md (usage examples, installation)
5. Update agent context file

---

**Status**: ✅ All research complete, no blockers
