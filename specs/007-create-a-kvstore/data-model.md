# Data Model: TypeScript KVStore CLI Client

**Feature**: 007-create-a-kvstore  
**Date**: 2026-01-13  
**Status**: Complete

## Overview

This document defines the data structures, validation rules, and type definitions for the TypeScript KVStore CLI client. All types are designed to be immutable and type-safe.

---

## Design Decisions

### Endpoint Configuration Handling

**Decision**: Endpoints are CLI-level configuration, not part of command data models.

**Rationale**: 
- **Separation of concerns**: Commands represent pure operation data (key, value), while endpoints are runtime configuration for establishing the cluster connection
- **Single responsibility**: Command models should only contain operation-specific data, not infrastructure concerns
- **Testability**: Commands can be tested in isolation without mock endpoint configurations
- **Reusability**: Same command data can be used against different clusters by changing client configuration

**Implementation**:
- Endpoints are parsed from CLI `--endpoints` flag at application entry point
- RaftClient is created once with endpoint configuration
- Command handlers receive only operation-specific data (key, value)
- Command models are pure data structures suitable for serialization and testing

---

## Core Entities

### 1. Command Models

#### SetCommand
Represents a request to store a key-value pair.

```typescript
interface SetCommand {
  readonly type: 'set';
  readonly key: string;        // UTF-8, max 256 bytes
  readonly value: string;      // UTF-8, max 1MB
}
```

**Validation Rules**:
- `key`: Non-empty, valid UTF-8, ≤256 bytes
- `value`: Non-empty, valid UTF-8, ≤1MB (1,048,576 bytes)

**Example**:
```typescript
{
  type: 'set',
  key: 'user:123:name',
  value: 'Alice'
}
```

**Note**: Endpoints are configured at the CLI/RaftClient level, not in the command model.

---

#### GetCommand
Represents a request to retrieve a value by key.

```typescript
interface GetCommand {
  readonly type: 'get';
  readonly key: string;        // UTF-8, max 256 bytes
}
```

**Validation Rules**:
- `key`: Non-empty, valid UTF-8, ≤256 bytes

**Example**:
```typescript
{
  type: 'get',
  key: 'user:123:name'
}
```

**Note**: Endpoints are configured at the CLI/RaftClient level, not in the command model.

---

#### WatchCommand
Represents a request to monitor updates to a key.

```typescript
interface WatchCommand {
  readonly type: 'watch';
  readonly key: string;        // UTF-8, max 256 bytes
}
```

**Validation Rules**:
- `key`: Non-empty, valid UTF-8, ≤256 bytes

**Example**:
```typescript
{
  type: 'watch',
  key: 'config:feature-flags'
}
```

**Note**: Endpoints are configured at the CLI/RaftClient level, not in the command model.

---

### 2. Configuration Models

#### EndpointConfig
Represents the cluster member endpoints for connection.

```typescript
interface EndpointConfig {
  readonly endpoints: ReadonlyMap<MemberId, Endpoint>;
}

type MemberId = string;  // e.g., "node-1", "node-2"
type Endpoint = string;  // e.g., "tcp://127.0.0.1:7001"
```

**Validation Rules**:
- At least one endpoint must be provided
- Each endpoint must match format: `tcp://host:port`
- `host`: valid hostname or IP address
- `port`: valid port number (1-65535)
- `MemberId`: non-empty alphanumeric string (may include hyphens, underscores)

**Parsing Format**:
Input string: `"node-1=tcp://127.0.0.1:7001,node-2=tcp://127.0.0.1:7002"`

**Example**:
```typescript
{
  endpoints: new Map([
    ['node-1', 'tcp://127.0.0.1:7001'],
    ['node-2', 'tcp://127.0.0.1:7002'],
    ['node-3', 'tcp://127.0.0.1:7003']
  ])
}
```

**Default**:
```typescript
{
  endpoints: new Map([
    ['node-1', 'tcp://127.0.0.1:7001']
  ])
}
```

**Usage Pattern**:
```typescript
// CLI handler level - uses KVClient wrapper for protocol handling
async function executeSetCommand(key: string, value: string, cliOptions: CLIOptions) {
  try {
    // 1. Validate inputs (fast-fail)
    validateKey(key);
    validateValue(value);
    
    // 2. Parse endpoints from CLI flag
    const endpointConfig = parseEndpoints(cliOptions.endpoints || DEFAULT_ENDPOINTS);
    
    // 3. Create KVClient (wraps RaftClient + protocol encoding/decoding)
    const client = new KVClient({
      endpoints: endpointConfig.endpoints,
      connectionTimeout: 5000,
      requestTimeout: 5000,
    });
    
    try {
      // 4. Connect (automatically starts event loop internally)
      await client.connect();
      
      // 5. Execute command - KVClient handles protocol encoding
      await client.set(key, value);
      
      // 6. Display success
      console.log('OK');
      
      // 7. Cleanup
      await client.disconnect();
      process.exit(0);
      
    } finally {
      // Ensure client is disconnected even on error
      await client.disconnect();
    }
    
  } catch (error) {
    // 8. Error handling with appropriate exit codes
    console.error(formatError(error));
    process.exit(getExitCode(error)); // 1=validation, 2=connection, 3=operational
  }
}
```

**Key Points**:
- Validates inputs before network operations (fast-fail)
- KVClient wraps RaftClient and handles KVStore protocol encoding/decoding
- Clean API: `client.set(key, value)` instead of manual encoding
- Proper error handling and cleanup in finally block
- Displays output and returns appropriate exit codes
- Command model contains only operation data (no endpoints)

**Architecture**:
```
CLI Handler → KVClient → RaftClient → ZeroMQ Transport
              (protocol)  (session)    (network)
```

---

### 3. Protocol Message Models

#### KVClientRequest (Commands sent to server)

```typescript
type KVClientRequest = SetRequest | WatchRequest;

interface SetRequest {
  readonly type: 'Set';
  readonly key: string;
  readonly value: string;
}

interface WatchRequest {
  readonly type: 'Watch';
  readonly key: string;
}
```

**Binary Encoding** (scodec format):
- Discriminator: 1-byte ASCII ('S' for Set, 'W' for Watch)
- Strings: length-prefixed UTF-8 (4-byte big-endian length + bytes)

**Example Encoding** (Set "key" = "value"):
```
[0x53][0x00 0x00 0x00 0x03][0x6B 0x65 0x79][0x00 0x00 0x00 0x05][0x76 0x61 0x6C 0x75 0x65]
  'S'    len=3                 "key"          len=5                 "value"
```

---

#### KVQuery (Queries sent to server)

```typescript
interface GetQuery {
  readonly type: 'Get';
  readonly key: string;
}
```

**Binary Encoding**:
- Discriminator: 1-byte ASCII ('G' for Get)
- Key: length-prefixed UTF-8

**Example Encoding** (Get "key"):
```
[0x47][0x00 0x00 0x00 0x03][0x6B 0x65 0x79]
  'G'    len=3                 "key"
```

---

#### KVServerRequest (Server-initiated messages)

```typescript
interface Notification {
  readonly type: 'Notification';
  readonly key: string;
  readonly value: string;
}
```

**Binary Encoding**:
- Discriminator: 1-byte ASCII ('N' for Notification)
- Key and Value: length-prefixed UTF-8

**Example Encoding**:
```
[0x4E][len:4][key bytes][len:4][value bytes]
  'N'
```

---

### 4. Result Models

#### CommandResult
Represents the outcome of a command execution.

```typescript
type CommandResult = 
  | SetResult
  | GetResult
  | WatchResult;

interface SetResult {
  readonly type: 'set';
  readonly success: true;
}

interface GetResult {
  readonly type: 'get';
  readonly value: string | null;  // null if key not found
}

interface WatchResult {
  readonly type: 'watch';
  readonly notifications: WatchNotification[];
}
```

---

#### WatchNotification
Represents a single update notification from a watch.

```typescript
interface WatchNotification {
  readonly timestamp: Date;       // When notification received
  readonly sequenceNumber: bigint; // Server sequence number
  readonly key: string;
  readonly value: string;
  readonly metadata?: Record<string, unknown>;
}
```

**Display Format**:
```
[2026-01-13T10:15:23.456Z] seq=42 key=mykey value=newvalue
```

**Validation Rules**:
- `timestamp`: Valid ISO 8601 timestamp
- `sequenceNumber`: Non-negative integer
- `key`: Same constraints as command keys
- `value`: Same constraints as command values

---

### 5. Error Models

#### ValidationError
Represents client-side validation failures.

```typescript
interface ValidationError {
  readonly type: 'validation';
  readonly field: 'key' | 'value' | 'endpoints';
  readonly message: string;
  readonly actual?: string | number;
  readonly expected?: string | number;
}
```

**Examples**:
```typescript
{
  type: 'validation',
  field: 'key',
  message: 'Key exceeds maximum size',
  actual: 312,
  expected: 256
}

{
  type: 'validation',
  field: 'value',
  message: 'Value contains invalid UTF-8',
  actual: '<binary data>'
}

{
  type: 'validation',
  field: 'endpoints',
  message: 'Invalid endpoint format',
  actual: 'node-1=invalid'
}
```

---

#### OperationError
Represents errors during command execution.

```typescript
interface OperationError {
  readonly type: 'operation';
  readonly operation: 'set' | 'get' | 'watch' | 'connect';
  readonly reason: 'timeout' | 'connection' | 'protocol' | 'server_error';
  readonly message: string;
  readonly details?: unknown;
}
```

**Examples**:
```typescript
{
  type: 'operation',
  operation: 'connect',
  reason: 'timeout',
  message: 'Could not connect to cluster (timeout after 5s)'
}

{
  type: 'operation',
  operation: 'set',
  reason: 'server_error',
  message: 'Server rejected command',
  details: { code: 'INVALID_SESSION' }
}
```

---

## Validation Functions

### Key Validation
```typescript
function validateKey(key: string): ValidationResult {
  if (key.length === 0) {
    return { valid: false, error: 'Key cannot be empty' };
  }
  
  const buffer = Buffer.from(key, 'utf8');
  if (buffer.length > 256) {
    return { 
      valid: false, 
      error: `Key exceeds 256 bytes (actual: ${buffer.length} bytes)` 
    };
  }
  
  // Verify valid UTF-8 round-trip
  if (buffer.toString('utf8') !== key) {
    return { valid: false, error: 'Key contains invalid UTF-8' };
  }
  
  return { valid: true };
}
```

### Value Validation
```typescript
function validateValue(value: string): ValidationResult {
  if (value.length === 0) {
    return { valid: false, error: 'Value cannot be empty' };
  }
  
  const buffer = Buffer.from(value, 'utf8');
  const MAX_SIZE = 1024 * 1024; // 1MB
  
  if (buffer.length > MAX_SIZE) {
    return { 
      valid: false, 
      error: `Value exceeds 1MB (actual: ${buffer.length} bytes)` 
    };
  }
  
  // Verify valid UTF-8 round-trip
  if (buffer.toString('utf8') !== value) {
    return { valid: false, error: 'Value contains invalid UTF-8' };
  }
  
  return { valid: true };
}
```

### Endpoint Validation
```typescript
function validateEndpoint(endpoint: string): ValidationResult {
  const regex = /^tcp:\/\/([^:]+):(\d+)$/;
  const match = endpoint.match(regex);
  
  if (!match) {
    return { 
      valid: false, 
      error: `Invalid endpoint format: ${endpoint}. Expected: tcp://host:port` 
    };
  }
  
  const [, host, portStr] = match;
  const port = parseInt(portStr, 10);
  
  if (port < 1 || port > 65535) {
    return { 
      valid: false, 
      error: `Invalid port: ${port}. Must be 1-65535` 
    };
  }
  
  return { valid: true };
}

function parseEndpoints(input: string): Result<EndpointConfig, ValidationError> {
  const pairs = input.split(',').map(s => s.trim()).filter(s => s.length > 0);
  
  if (pairs.length === 0) {
    return { 
      ok: false, 
      error: { type: 'validation', field: 'endpoints', message: 'No endpoints provided' } 
    };
  }
  
  const endpoints = new Map<string, string>();
  
  for (const pair of pairs) {
    const [memberId, endpoint] = pair.split('=', 2);
    
    if (!memberId || !endpoint) {
      return { 
        ok: false, 
        error: { 
          type: 'validation', 
          field: 'endpoints', 
          message: `Invalid format: ${pair}. Expected: memberId=tcp://host:port` 
        } 
      };
    }
    
    const validation = validateEndpoint(endpoint);
    if (!validation.valid) {
      return { ok: false, error: { type: 'validation', field: 'endpoints', message: validation.error } };
    }
    
    endpoints.set(memberId.trim(), endpoint.trim());
  }
  
  return { ok: true, value: { endpoints } };
}
```

---

## State Transitions

### Watch Command Lifecycle

```
┌─────────┐
│ Created │
└────┬────┘
     │ validate()
     ▼
┌──────────┐
│Validated │
└────┬─────┘
     │ connect()
     ▼
┌───────────┐     reconnect
│ Connected │◄────────────┐
└────┬──────┘             │
     │ watch()            │
     ▼                    │
┌──────────┐              │
│ Watching │──────────────┘
└────┬─────┘   disconnect
     │
     │ SIGINT / error
     ▼
┌────────────┐
│ Terminated │
└────────────┘
```

---

## Data Constraints Summary

| Entity | Field | Type | Min | Max | Encoding |
|--------|-------|------|-----|-----|----------|
| Command | key | string | 1 byte | 256 bytes | UTF-8 |
| Command | value | string | 1 byte | 1MB | UTF-8 |
| EndpointConfig | endpoints | Map | 1 entry | unlimited | N/A |
| EndpointConfig | endpoint | string | N/A | N/A | tcp://host:port |
| WatchNotification | sequenceNumber | bigint | 0 | unlimited | N/A |
| WatchNotification | timestamp | Date | N/A | N/A | ISO 8601 |

---

## Type Safety Guarantees

1. **Immutability**: All interfaces use `readonly` modifiers
2. **No null/undefined confusion**: Use explicit `| null` for optional values
3. **Discriminated unions**: All command/result types have `type` discriminator
4. **Validation at boundaries**: All user input validated before use
5. **Type-safe codecs**: Binary encoding/decoding preserves type information

---

**Status**: ✅ All entities defined, validation rules specified
