# Test Helpers

This directory contains utilities for E2E testing of the KVStore CLI.

## ServerManager

The `ServerManager` class provides automated lifecycle management for KVStore servers in E2E tests.

### Overview

ServerManager handles:
- Automatic port allocation (avoids conflicts)
- Server process spawning and management
- Waiting for server to be ready
- Clean shutdown and cleanup
- Logging server output for debugging

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

- Allocates two available ports (server port and member port)
- Spawns `../run-kvstore.sh` with appropriate arguments
- Waits for server port to accept connections
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
- **Example**: `test-node-1738223456789=tcp://127.0.0.1:8123`
- **Throws**: Error if called before `start()`

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

## Port Utilities

Low-level utilities for port management. Generally you don't need to use these directly - ServerManager uses them internally.

### `findAvailablePort(start?, end?): Promise<number>`

Find an available port in the given range.

- **start** (optional): Start of range (default: 8000)
- **end** (optional): End of range (default: 9000)
- **Returns**: First available port in range
- **Throws**: Error if no port available

### `waitForPort(port, timeout?): Promise<void>`

Wait for a port to accept connections.

- **port**: Port number to wait for
- **timeout** (optional): Timeout in ms (default: 60000)
- **Returns**: Promise that resolves when port is ready
- **Throws**: Error if timeout reached

### Internal: `isPortFree(port): Promise<boolean>`

Checks if a port is available (not exported).

## Test Structure

```
tests/
  helpers/
    portUtils.ts      - Port management utilities
    serverManager.ts  - ServerManager class
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
npm run test:e2e:new
```
