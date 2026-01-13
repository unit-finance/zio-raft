# Design: TypeScript KVStore CLI Client

**Feature**: 007-create-a-kvstore  
**Date**: 2026-01-13  
**Status**: Complete

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     TypeScript KVStore CLI                    │
│                                                               │
│  ┌────────────┐      ┌─────────────┐      ┌──────────────┐  │
│  │  CLI Args  │─────▶│  Commander  │─────▶│   Command    │  │
│  │  (stdin)   │      │   Parser    │      │   Handlers   │  │
│  └────────────┘      └─────────────┘      └──────┬───────┘  │
│                                                   │          │
│                                    ┌──────────────▼───────┐  │
│                                    │    Validation        │  │
│                                    │    (keys, values,    │  │
│                                    │     endpoints)       │  │
│                                    └──────────────┬───────┘  │
│                                                   │          │
│                                    ┌──────────────▼───────┐  │
│                                    │     KVClient         │  │
│                                    │  (Protocol Wrapper)  │  │
│                                    │  • set(key, value)   │  │
│                                    │  • get(key)          │  │
│                                    │  • watch(key)        │  │
│                                    │  • notifications     │  │
│                                    └──────────────┬───────┘  │
│                                                   │          │
│                                    ┌──────────────▼───────┐  │
│                                    │  Protocol Codecs     │  │
│                                    │  (encode/decode)     │  │
│                                    └──────────────┬───────┘  │
│                                                   │          │
│  ┌──────────────────────────────────────────────▼────────┐  │
│  │         @zio-raft/typescript-client (RaftClient)      │  │
│  │  • Connection management                              │  │
│  │  • Command submission (submitCommand)                 │  │
│  │  • Query execution (query)                            │  │
│  │  • Server request streaming (serverRequests)          │  │
│  └───────────────────────────┬───────────────────────────┘  │
│                              │                               │
│  ┌───────────────────────────▼────────────────────────────┐ │
│  │             Output Formatting & Display               │ │
│  │   • Success messages                                  │ │
│  │   • Error messages                                    │ │
│  │   • Watch notifications (verbose format)              │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                              │
└──────────────────────────────────────────────────────────────┘
                                │
                                │ ZeroMQ (tcp://)
                                ▼
                    ┌───────────────────────┐
                    │   KVStore Cluster     │
                    │  (Scala, ZIO, Raft)   │
                    └───────────────────────┘
```

**Layered Architecture**:
```
CLI Handlers (set, get, watch)
      ↓
KVClient (protocol-specific wrapper)
      ↓
RaftClient (generic Raft client)
      ↓
ZeroMQ Transport
```

---

## Component Breakdown

### 1. CLI Entry Point (`src/index.ts`)

**Responsibility**: Bootstrap the CLI application

```typescript
#!/usr/bin/env node
import { Command } from 'commander';
import { setCommand } from './commands/set';
import { getCommand } from './commands/get';
import { watchCommand } from './commands/watch';

const program = new Command();

program
  .name('kvstore')
  .version('0.1.0')
  .description('KVStore CLI - interact with a distributed key-value store');

// Register commands
program.addCommand(setCommand);
program.addCommand(getCommand);
program.addCommand(watchCommand);

// Parse and execute
program.parse(process.argv);
```

**Key Decisions**:
- Single entry point for all commands
- Commander handles help text generation
- Version from package.json
- Subcommand pattern (kvstore set, kvstore get, kvstore watch)

---

### 2. KVClient Wrapper (`src/kvClient.ts`)

**Responsibility**: Protocol-specific wrapper around RaftClient for KVStore operations

```typescript
import { RaftClient } from '@zio-raft/typescript-client';
import { encodeSetRequest, encodeGetQuery, encodeWatchRequest, 
         decodeGetResult, decodeNotification } from './codecs';

export class KVClient {
  private raftClient: RaftClient;
  
  constructor(config: { endpoints: Map<string, string>, connectionTimeout: number, requestTimeout: number }) {
    this.raftClient = new RaftClient(config);
  }
  
  async connect(): Promise<void> {
    await this.raftClient.connect();
  }
  
  async disconnect(): Promise<void> {
    await this.raftClient.disconnect();
  }
  
  async set(key: string, value: string): Promise<void> {
    const payload = encodeSetRequest(key, value);
    const resultBuffer = await this.raftClient.submitCommand(payload);
    // Result is Unit, no decoding needed
  }
  
  async get(key: string): Promise<string | null> {
    const payload = encodeGetQuery(key);
    const resultBuffer = await this.raftClient.query(payload);
    return decodeGetResult(resultBuffer);
  }
  
  async watch(key: string): Promise<void> {
    const payload = encodeWatchRequest(key);
    await this.raftClient.submitCommand(payload);
    // Watch registered, notifications come via serverRequests()
  }
  
  async *notifications(): AsyncIterableIterator<{ key: string, value: string, timestamp: Date, sequenceNumber: bigint }> {
    for await (const serverRequest of this.raftClient.serverRequests()) {
      const notification = decodeNotification(serverRequest.payload);
      yield notification;
    }
  }
}
```

**Benefits**:
- **Clean API**: `client.set(key, value)` instead of manual encoding
- **Type Safety**: Methods return typed results, not raw buffers
- **Encapsulation**: Protocol details hidden from command handlers
- **Reusability**: Can be used by other TypeScript applications
- **Matches Scala**: Same architecture as Scala kvstore-cli

---

### 3. Command Handlers (`src/commands/`)

Each command is a separate module with consistent structure:

#### Set Command (`commands/set.ts`)

```typescript
import { Command } from 'commander';
import { RaftClient } from '@zio-raft/typescript-client';
import { validateKey, validateValue, parseEndpoints } from '../validation';
import { encodeSetRequest } from '../codecs';
import { formatError, formatSuccess } from '../formatting';

export const setCommand = new Command('set')
  .description('Set a key-value pair')
  .argument('<key>', 'Key to set')
  .argument('<value>', 'Value to set')
  .option('-e, --endpoints <endpoints>', 
    'Cluster endpoints (format: id=tcp://host:port,...)', 
    'node-1=tcp://127.0.0.1:7001')
  .action(async (key: string, value: string, options: { endpoints: string }) => {
    try {
      // 1. Validate inputs
      validateKey(key);  // throws ValidationError if invalid
      validateValue(value);
      const config = parseEndpoints(options.endpoints);
      
      // 2. Create client
      const client = new RaftClient({
        endpoints: config.endpoints,
        connectionTimeout: 5000,
        requestTimeout: 5000,
      });
      
      // 3. Connect
      await client.connect();
      
      // 4. Encode command
      const payload = encodeSetRequest(key, value);
      
      // 5. Submit command
      await client.submitCommand(payload);
      
      // 6. Display success
      console.log(formatSuccess('OK'));
      
      // 7. Cleanup
      await client.disconnect();
      process.exit(0);
      
    } catch (error) {
      console.error(formatError(error));
      process.exit(getExitCode(error));
    }
  });
```

**Flow**:
1. Parse arguments via Commander
2. Validate inputs (fast-fail)
3. Create and configure RaftClient
4. Connect to cluster (5s timeout)
5. Encode protocol message
6. Submit command via client
7. Display result and cleanup

---

#### Get Command (`commands/get.ts`)

```typescript
export const getCommand = new Command('get')
  .description('Get a value by key')
  .argument('<key>', 'Key to get')
  .option('-e, --endpoints <endpoints>', 
    'Cluster endpoints', 
    'node-1=tcp://127.0.0.1:7001')
  .action(async (key: string, options: { endpoints: string }) => {
    try {
      // Validation
      validateKey(key);
      const config = parseEndpoints(options.endpoints);
      
      // Client setup
      const client = new RaftClient({
        endpoints: config.endpoints,
        connectionTimeout: 5000,
        requestTimeout: 5000,
      });
      await client.connect();
      
      // Encode query
      const payload = encodeGetQuery(key);
      
      // Execute query (read-only, doesn't go through Raft log)
      const resultBuffer = await client.query(payload);
      
      // Decode result
      const value = decodeGetResult(resultBuffer);
      
      // Display result
      if (value !== null) {
        console.log(`${key} = ${value}`);
      } else {
        console.log(`${key} = <none>`);
      }
      
      // Cleanup
      await client.disconnect();
      process.exit(0);
      
    } catch (error) {
      console.error(formatError(error));
      process.exit(getExitCode(error));
    }
  });
```

**Key Difference**: Uses `client.query()` instead of `client.submitCommand()` - queries don't go through Raft consensus, providing lower latency for reads.

---

#### Watch Command (`commands/watch.ts`)

```typescript
export const watchCommand = new Command('watch')
  .description('Watch a key for updates')
  .argument('<key>', 'Key to watch')
  .option('-e, --endpoints <endpoints>', 
    'Cluster endpoints', 
    'node-1=tcp://127.0.0.1:7001')
  .action(async (key: string, options: { endpoints: string }) => {
    let client: RaftClient | null = null;
    
    // Signal handling for graceful shutdown
    const shutdown = async () => {
      console.error('\nShutting down...');
      if (client) {
        await client.disconnect();
      }
      process.exit(130);
    };
    
    process.on('SIGINT', shutdown);
    process.on('SIGTERM', shutdown);
    
    try {
      // Validation
      validateKey(key);
      const config = parseEndpoints(options.endpoints);
      
      // Client setup
      client = new RaftClient({
        endpoints: config.endpoints,
        connectionTimeout: 5000,
        keepAliveInterval: 2000,  // More frequent for watch
      });
      await client.connect();
      
      // Register watch on server
      const watchPayload = encodeWatchRequest(key);
      await client.submitCommand(watchPayload);
      
      console.log(`watching ${key} - press Ctrl+C to stop`);
      
      // Subscribe to server requests (notifications)
      for await (const serverRequest of client.serverRequests()) {
        const notification = decodeNotification(serverRequest.payload);
        
        if (notification.key === key) {
          const formatted = formatNotification(notification);
          console.log(formatted);
        }
      }
      
    } catch (error) {
      console.error(formatError(error));
      process.exit(getExitCode(error));
    }
  });
```

**Key Features**:
- **Long-running**: Doesn't exit until SIGINT
- **Signal handling**: Graceful shutdown on Ctrl+C
- **Streaming**: Uses `client.serverRequests()` async iterator
- **Reconnection**: Client handles reconnection transparently, watch re-registered automatically

**Reconnection Flow** (per clarification):
```
1. Client connected, watch registered
2. Network interruption detected by client
3. Client attempts reconnection (automatic)
4. Upon reconnection: client re-registers watch (transparent)
5. Notifications resume
6. User sees brief disconnection notice, but watch continues
```

---

### 3. Validation Layer (`src/validation.ts`)

**Responsibility**: Pre-validate all user inputs before network calls

```typescript
export class ValidationError extends Error {
  constructor(
    public readonly field: string,
    public readonly message: string,
    public readonly actual?: any,
    public readonly expected?: any
  ) {
    super(`Validation error (${field}): ${message}`);
    this.name = 'ValidationError';
  }
}

export function validateKey(key: string): void {
  if (key.length === 0) {
    throw new ValidationError('key', 'Key cannot be empty');
  }
  
  const buffer = Buffer.from(key, 'utf8');
  if (buffer.length > 256) {
    throw new ValidationError(
      'key',
      'Key exceeds maximum size',
      buffer.length,
      256
    );
  }
  
  // Verify UTF-8 round-trip
  if (buffer.toString('utf8') !== key) {
    throw new ValidationError('key', 'Key contains invalid UTF-8');
  }
}

export function validateValue(value: string): void {
  if (value.length === 0) {
    throw new ValidationError('value', 'Value cannot be empty');
  }
  
  const buffer = Buffer.from(value, 'utf8');
  const MAX_SIZE = 1024 * 1024; // 1MB
  
  if (buffer.length > MAX_SIZE) {
    throw new ValidationError(
      'value',
      'Value exceeds maximum size',
      buffer.length,
      MAX_SIZE
    );
  }
  
  if (buffer.toString('utf8') !== value) {
    throw new ValidationError('value', 'Value contains invalid UTF-8');
  }
}

export function parseEndpoints(input: string): EndpointConfig {
  // Parse "id1=tcp://host1:port1,id2=tcp://host2:port2"
  // Returns Map<MemberId, Endpoint>
  // Throws ValidationError if invalid format
}
```

**Benefits**:
- Fast-fail: No network call if inputs invalid
- Clear error messages: User knows exactly what's wrong
- Consistent: Same validation for all commands

---

### 4. Protocol Codecs (`src/codecs.ts`)

**Responsibility**: Binary encoding/decoding for KVStore protocol

```typescript
// Encode/decode utilities
function encodeUtf8_32(str: string): Buffer {
  const strBuffer = Buffer.from(str, 'utf8');
  const length = strBuffer.length;
  const lengthBuffer = Buffer.allocUnsafe(4);
  lengthBuffer.writeUInt32BE(length, 0);
  return Buffer.concat([lengthBuffer, strBuffer]);
}

function decodeUtf8_32(buffer: Buffer, offset: number): { value: string; bytesRead: number } {
  const length = buffer.readUInt32BE(offset);
  const value = buffer.toString('utf8', offset + 4, offset + 4 + length);
  return { value, bytesRead: 4 + length };
}

// Command encoders
export function encodeSetRequest(key: string, value: string): Buffer {
  const discriminator = Buffer.from('S', 'ascii');
  const encodedKey = encodeUtf8_32(key);
  const encodedValue = encodeUtf8_32(value);
  return Buffer.concat([discriminator, encodedKey, encodedValue]);
}

export function encodeGetQuery(key: string): Buffer {
  const discriminator = Buffer.from('G', 'ascii');
  const encodedKey = encodeUtf8_32(key);
  return Buffer.concat([discriminator, encodedKey]);
}

export function encodeWatchRequest(key: string): Buffer {
  const discriminator = Buffer.from('W', 'ascii');
  const encodedKey = encodeUtf8_32(key);
  return Buffer.concat([discriminator, encodedKey]);
}

// Response decoders
export function decodeGetResult(buffer: Buffer): string | null {
  // First byte: boolean (0 = None, 1 = Some)
  const hasValue = buffer.readUInt8(0);
  if (hasValue === 0) {
    return null;
  }
  const { value } = decodeUtf8_32(buffer, 1);
  return value;
}

export function decodeNotification(buffer: Buffer): Notification {
  const discriminator = buffer.toString('ascii', 0, 1);
  if (discriminator !== 'N') {
    throw new Error(`Unexpected discriminator: ${discriminator}`);
  }
  
  let offset = 1;
  const { value: key, bytesRead: keyBytes } = decodeUtf8_32(buffer, offset);
  offset += keyBytes;
  const { value, bytesRead: valueBytes } = decodeUtf8_32(buffer, offset);
  
  return {
    timestamp: new Date(),
    sequenceNumber: 0n, // TODO: Extract from metadata if available
    key,
    value,
  };
}
```

**Protocol Compatibility**:
- Matches Scala scodec format exactly
- Big-endian byte order (Java/Scala convention)
- UTF-8 encoding with 4-byte length prefix
- ASCII discriminators

**Testing Strategy**:
- Round-trip tests: encode → decode → verify
- Byte-level verification against Scala implementation
- Edge cases: empty strings, unicode, large values

---

### 5. Output Formatting (`src/formatting.ts`)

**Responsibility**: User-facing output formatting

```typescript
export function formatSuccess(message: string): string {
  return message;
}

export function formatError(error: unknown): string {
  if (error instanceof ValidationError) {
    return `Error: ${error.message}`;
  }
  
  if (error instanceof ConnectionError) {
    return `Error: Could not connect to cluster (timeout after 5s)`;
  }
  
  if (error instanceof TimeoutError) {
    return `Error: Operation timed out after 5s`;
  }
  
  return `Error: ${error}`;
}

export function formatNotification(notification: Notification): string {
  const timestamp = notification.timestamp.toISOString();
  const seq = notification.sequenceNumber;
  const { key, value } = notification;
  
  return `[${timestamp}] seq=${seq} key=${key} value=${value}`;
}
```

**Example Outputs**:
```
# Success
OK

# Get result
mykey = myvalue
nonexistent = <none>

# Watch notifications
watching mykey - press Ctrl+C to stop
[2026-01-13T10:15:23.456Z] seq=42 key=mykey value=newvalue
[2026-01-13T10:15:24.789Z] seq=43 key=mykey value=anothervalue

# Errors
Error: Key exceeds 256 bytes (actual: 312 bytes)
Error: Could not connect to cluster (timeout after 5s)
Error: Value contains invalid UTF-8
```

---

## Error Handling Flow

```
┌─────────────┐
│ User Input  │
└─────┬───────┘
      │
      ▼
┌─────────────┐     ValidationError
│ Validation  │─────────────────────┐
└─────┬───────┘                     │
      │                             ▼
      │                      ┌──────────────┐
      ▼                      │ Format Error │
┌─────────────┐              │ Exit Code 1  │
│ Connect     │              └──────────────┘
└─────┬───────┘                     ▲
      │ TimeoutError               │
      │ ConnectionError            │
      ├────────────────────────────┤
      │                            │
      ▼                            │
┌─────────────┐                    │
│ Execute Cmd │                    │
└─────┬───────┘                    │
      │ ProtocolError             │
      │ ServerError               │
      ├───────────────────────────┤
      │                           │
      ▼                           │
┌─────────────┐                   │
│ Format Out  │                   │
└─────┬───────┘                   │
      │                           │
      ▼                           │
┌─────────────┐                   │
│ Exit Code 0 │                   │
└─────────────┘                   │
                                  │
Any error caught here ────────────┘
```

**Exit Codes**:
- 0: Success
- 1: Validation error
- 2: Connection/timeout error
- 3: Protocol/server error
- 130: SIGINT (standard Unix convention)

---

## Integration with Existing Code

### typescript-client Library

**Used APIs**:
- `RaftClient` constructor
- `connect()`: Establish connection
- `submitCommand(payload: Buffer)`: Send command through Raft
- `query(payload: Buffer)`: Execute read-only query
- `serverRequests()`: Async iterator for server-initiated messages
- `disconnect()`: Clean shutdown

**Not Used** (future enhancements):
- Event emitters (connected, disconnected, etc.)
- Custom configuration beyond timeouts

### kvstore-protocol (Scala)

**Protocol Messages Implemented**:
- `KVClientRequest.Set`
- `KVClientRequest.Watch`
- `KVQuery.Get`
- `KVServerRequest.Notification`

**Encoding Format**:
- Scodec binary format
- Discriminated unions with ASCII tags
- Length-prefixed UTF-8 strings

---

## Testing Areas

### Unit Tests
1. **Validation**: Key/value/endpoint validation logic
2. **Codecs**: Encoding/decoding binary protocol
3. **Formatting**: Output formatting functions
4. **Parsing**: Endpoint string parsing

### Integration Tests (Mocked Client)
1. **Set Command**: Success and error scenarios
2. **Get Command**: Found, not found, errors
3. **Watch Command**: Notifications, reconnection, interruption

### Manual Testing (Real Cluster)
1. End-to-end workflow with running KVStore
2. Cross-verification with Scala kvstore-cli
3. Performance testing (throughput, latency)

---

## Future Enhancements

**Not in MVP scope**:
1. **Batch Operations**: Multiple keys in one command
2. **Interactive Mode**: REPL-style interface
3. **JSON Output**: Machine-readable format (--json flag)
4. **Watch Filters**: Pattern matching for keys
5. **Verbose Logging**: --verbose flag for debugging
6. **Config File**: ~/.kvstorerc for default endpoints

---

## Dependencies

### New Dependencies
- `commander`: ^11.1.0 (CLI framework)

### Existing Dependencies (via typescript-client)
- `zeromq`: ^6.0.0 (transport)
- `@zio-raft/typescript-client`: workspace:* (client library)

### Dev Dependencies
- `vitest`: ^1.0.4 (testing)
- `@types/node`: ^18.19.3 (type definitions)
- `typescript`: ^5.3.3 (compiler)

---

## Project Structure (Implemented)

```
zio-raft/
├── typescript-client/              # Existing client library
├── kvstore-cli/                    # Existing Scala CLI
└── kvstore-cli-ts/                 # New TypeScript CLI (root-level)
    ├── src/
    │   ├── index.ts                # Entry point, CLI setup
    │   ├── commands/
    │   │   ├── set.ts              # Set command handler
    │   │   ├── get.ts              # Get command handler
    │   │   └── watch.ts            # Watch command handler
    │   ├── validation.ts           # Input validation
    │   ├── codecs.ts               # Protocol encoding/decoding
    │   ├── formatting.ts           # Output formatting
    │   └── types.ts                # Type definitions
    ├── tests/
    │   ├── unit/
    │   │   ├── validation.test.ts
    │   │   ├── codecs.test.ts
    │   │   └── formatting.test.ts
    │   └── integration/
    │       ├── set.test.ts
    │       ├── get.test.ts
    │       └── watch.test.ts
    ├── package.json
    ├── tsconfig.json
    ├── vitest.config.ts
    └── README.md
```

---

**Status**: ✅ Design complete, ready for task breakdown
