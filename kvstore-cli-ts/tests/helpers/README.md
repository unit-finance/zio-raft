# Test Helpers

This directory contains utilities for E2E testing of the KVStore CLI.

## ServerManager

The `ServerManager` class provides automated lifecycle management for KVStore servers in E2E tests.

### Overview

ServerManager handles:
- Server process spawning and management
- Waiting for server to be ready
- Clean shutdown and cleanup
- Logging server output for debugging

**Note:** Currently uses default ports (7001, 7002) due to command-line argument parsing limitation in the KVStore server.

### Usage Example

```typescript
import { ServerManager } from '../helpers/serverManager.js';
import { beforeAll, afterAll, describe, it, expect } from 'vitest';

describe('My E2E Tests', () => {
  let server: ServerManager;

  beforeAll(async () => {
    server = new ServerManager();
    await server.start(); // Takes 30-60 seconds on first run
  }, 120000); // 2 minute timeout for server startup

  afterAll(async () => {
    await server.cleanup();
  });

  it('should connect to server', async () => {
    const endpoint = server.getEndpoint();
    // Use endpoint with CLI commands
    // Example: node dist/index.js set key value -e ${endpoint}
  });
});
```

### API Documentation

#### Constructor

```typescript
new ServerManager()
```

Creates a new ServerManager instance.

**Note:** Currently uses the server's default configuration due to a command-line argument parsing limitation in the KVStore server app. The server runs on:
- Server port: 7001
- Member port: 7002  
- Member ID: node-1

#### Methods

##### `async start(): Promise<void>`

Starts the server and waits for it to be ready.

- Uses default server configuration (member ID: "node-1", ports: 7001, 7002)
- Spawns `../run-kvstore.sh` with `--rebuild` flag
- Waits for server port (7001) to accept connections
- Adds 2 second buffer for full initialization
- **Timeout**: 60 seconds for port to become ready
- **Build**: Always rebuilds JAR with `--rebuild` flag

##### `async kill(): Promise<void>`

Force kills the server process using SIGKILL.

- Sends SIGKILL signal to server process
- Waits 500ms for cleanup

##### `getEndpoint(): string`

Returns the endpoint string for use with CLI `-e` flag.

- **Format**: `{memberId}=tcp://127.0.0.1:{serverPort}`
- **Example**: `node-1=tcp://127.0.0.1:7001`

##### `async cleanup(): Promise<void>`

Cleans up server resources (calls `kill()`).

### Important Notes

#### Startup Time

- **First run**: 30-60 seconds (includes JAR build)
- **Subsequent runs**: 30-60 seconds (always rebuilds with `--rebuild`)
- Always set `beforeAll` timeout to at least 120 seconds (2 minutes)

#### Port Allocation

**Current Implementation:**
- Uses server's default ports (7001, 7002) due to command-line argument parsing limitation
- Only one server instance can run at a time
- Tests must run sequentially, not in parallel

**Future Enhancement:**
- Once command-line arguments are fixed in the KVStore server, ports will be dynamically allocated
- This will allow parallel test execution
- Range: 8000-9000 (configurable)

#### Logging

All server output is logged with `[SERVER]` prefix:
```
[ServerManager] Starting server...
[SERVER] KVStore Server Runner
[SERVER] Starting KVStore server...
```

This helps debug startup issues and server behavior.

## CLI Runner Utility

Shared utility for running CLI commands in E2E tests.

### `runCli(...args): Promise<CliResult>`

Run CLI command and return result.

- **args**: CLI arguments (e.g., 'get', 'mykey')
- **timeout** (optional): First argument can be timeout in ms (default: 5000)
- **Returns**: Promise with `{ stdout, stderr, exitCode }`

**Example:**
```typescript
import { runCli } from '../helpers/cliRunner.js';

// Default timeout (5s)
const result = await runCli('get', 'mykey');

// Custom timeout (10s)
const result = await runCli(10000, 'set', 'key', 'value');
```

## Port Utilities

Low-level utilities for port management.

### `waitForPort(port, timeout?): Promise<void>`

Wait for a port to accept connections.

- **port**: Port number to wait for
- **timeout** (optional): Timeout in ms (default: 60000)
- **Returns**: Promise that resolves when port is ready
- **Throws**: Error if timeout reached

## Test Structure

```
tests/
  helpers/
    portUtils.ts      - Port management utilities
    serverManager.ts  - ServerManager class
    cliRunner.ts      - CLI runner utility
    README.md         - This file
  e2e/
    server-not-running.test.ts  - Test CLI behavior without server
    server-managed.test.ts      - Tests using ServerManager
    cli.test.ts                 - Manual server tests (legacy)
```

## Running Tests

```bash
# Test without server (fast)
npm test -- --run tests/e2e/server-not-running.test.ts

# Test with managed server (slow, 1-2 minutes)
npm test -- --run tests/e2e/server-managed.test.ts

# Run both new tests
npm run test:e2e:server
```
