# KVStore CLI (TypeScript)

A command-line interface for interacting with the KVStore distributed key-value store, built with TypeScript and the ZIO Raft client library.

## Features

- ✅ **Set**: Store key-value pairs in the cluster
- ✅ **Get**: Retrieve values by key
- ✅ **Watch**: Monitor real-time updates to keys
- ✅ **Binary Protocol**: Compatible with Scala KVStore implementation
- ✅ **Type-Safe**: Full TypeScript strict mode
- ✅ **Error Handling**: Clear error messages and appropriate exit codes
- ✅ **Signal Handling**: Graceful shutdown on Ctrl+C

## Prerequisites

- **Node.js**: 18.0.0 or higher
- **npm**: 8.0.0 or higher

## Installation

### From Source

```bash
cd kvstore-cli-ts
npm install
npm run build
npm link
```

### Verify Installation

```bash
kvstore --version
kvstore --help
```

## Quick Start

### 1. Set a Value

```bash
kvstore set mykey "Hello, World!"
# Output: OK
```

### 2. Get a Value

```bash
kvstore get mykey
# Output: mykey = Hello, World!
```

### 3. Watch for Updates

```bash
kvstore watch mykey
# Output: watching mykey - press Ctrl+C to stop
#
# [2026-01-13T10:15:23.456Z] seq=42 key=mykey value=Updated value
```

## Usage

### Set Command

Store a key-value pair in the cluster.

```bash
kvstore set <key> <value> [options]
```

**Options:**
- `-e, --endpoints <endpoints>`: Cluster endpoints (default: `node-1=tcp://127.0.0.1:7001`)

**Example:**
```bash
kvstore set config.feature "enabled"
kvstore set user:123:name "Alice" -e "node-1=tcp://10.0.1.5:7001"
```

### Get Command

Retrieve a value by key (read-only query).

```bash
kvstore get <key> [options]
```

**Options:**
- `-e, --endpoints <endpoints>`: Cluster endpoints (default: `node-1=tcp://127.0.0.1:7001`)

**Example:**
```bash
kvstore get config.feature
kvstore get nonexistent  # Output: nonexistent = <none>
```

### Watch Command

Monitor a key for real-time updates.

```bash
kvstore watch <key> [options]
```

**Options:**
- `-e, --endpoints <endpoints>`: Cluster endpoints (default: `node-1=tcp://127.0.0.1:7001`)

**Example:**
```bash
kvstore watch config.feature
# Press Ctrl+C to stop watching
```

## Configuration

### Default Endpoints

By default, the CLI connects to `tcp://127.0.0.1:7001`. To connect to a different cluster, use the `--endpoints` flag:

```bash
kvstore set mykey myvalue -e "node-1=tcp://prod1.example.com:7001,node-2=tcp://prod2.example.com:7001"
```

### Environment Variables

You can create shell aliases for frequently used clusters:

```bash
# In ~/.bashrc or ~/.zshrc
alias kvstore-dev="kvstore --endpoints 'node-1=tcp://dev.example.com:7001'"
alias kvstore-prod="kvstore --endpoints 'node-1=tcp://prod.example.com:7001'"
```

## Validation Rules

The CLI validates inputs before sending to the cluster:

- **Keys**: Non-empty, valid UTF-8, ≤256 bytes
- **Values**: Non-empty, valid UTF-8, ≤1MB (1,048,576 bytes)
- **Endpoints**: Format `tcp://host:port`, valid port (1-65535)

## Exit Codes

- `0`: Success
- `1`: Validation error (invalid input)
- `2`: Connection/timeout error
- `3`: Protocol/server error
- `130`: Interrupted by SIGINT (Ctrl+C)

## Error Messages

The CLI provides clear error messages:

```bash
# Validation error
$ kvstore set "" value
Error: Key cannot be empty

# Key too large
$ kvstore set $(python -c "print('a' * 300)") value
Error: Key exceeds maximum size (actual: 300, expected: 256)

# Connection error
$ kvstore set key value -e "node-1=tcp://invalid:9999"
Error: Could not connect to cluster (timeout after 5s)
```

## Development

### Build

```bash
npm run build
```

### Lint

```bash
npm run lint
```

### Test

```bash
npm test
npm run test:coverage
```

### Watch Mode

```bash
npm run test:watch
```

## Architecture

The CLI is structured in layers:

```
CLI Commands (set, get, watch)
       ↓
KVClient (protocol wrapper)
       ↓
RaftClient (@zio-raft/typescript-client)
       ↓
ZeroMQ Transport
```

### Key Components

- **Commands** (`src/commands/`): CLI command handlers using Commander.js
- **KVClient** (`src/kvClient.ts`): Protocol-specific wrapper around RaftClient
- **Codecs** (`src/codecs.ts`): Binary protocol encoding/decoding (scodec-compatible)
- **Validation** (`src/validation.ts`): Input validation (fast-fail before network)
- **Formatting** (`src/formatting.ts`): Output formatting and error messages

## Comparison with Scala CLI

The TypeScript implementation matches the Scala `kvstore-cli` behavior:

| Feature | Scala CLI | TypeScript CLI |
|---------|-----------|----------------|
| Set command | ✅ | ✅ |
| Get command | ✅ | ✅ |
| Watch command | ✅ | ✅ |
| Binary protocol | ✅ (scodec) | ✅ (compatible) |
| Custom endpoints | ✅ | ✅ |
| Signal handling | ✅ | ✅ |
| Reconnection | ✅ (automatic) | ✅ (automatic) |

## Troubleshooting

### Connection Timeout

**Error**: `Could not connect to cluster (timeout after 5s)`

**Solutions**:
1. Verify cluster is running: `nc -zv 127.0.0.1 7001`
2. Check endpoint format: `node-1=tcp://host:port`
3. Verify network connectivity

### Invalid Key/Value

**Error**: `Key exceeds maximum size (actual: 300, expected: 256)`

**Solutions**:
1. Shorten key names
2. Remember UTF-8 multi-byte characters count as multiple bytes
3. For large data, store reference/ID instead of full content

### Watch Not Showing Updates

**Solutions**:
1. Verify key matches exactly (case-sensitive)
2. Test by running `set` in another terminal
3. Check connection is active

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `npm test`
5. Submit a pull request

## License

Apache 2.0

## See Also

- [ZIO Raft](https://github.com/zio/zio-raft)
- [@zio-raft/typescript-client](../typescript-client)
- [Scala kvstore-cli](../kvstore-cli)
