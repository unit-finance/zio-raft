# Quickstart Guide: TypeScript Client Library

**Feature**: TypeScript Client Library  
**Date**: 2025-12-29  
**Status**: Complete

## Purpose

This quickstart guide provides an end-to-end validation workflow for the TypeScript Raft client library. It demonstrates the complete client lifecycle from installation to production usage patterns.

---

## Prerequisites

- **Node.js**: >= 18.0.0
- **npm**: >= 9.0.0
- **ZIO Raft Server**: Running cluster (for integration testing)
- **TypeScript**: >= 5.0.0 (dev dependency)

---

## Installation

### Step 1: Install Package

```bash
npm install @zio-raft/typescript-client
```

### Step 2: Verify Installation

```bash
node -e "console.log(require('@zio-raft/typescript-client'))"
# Should output: { RaftClient, [other exports] }
```

---

## Basic Usage

### Example 1: Simple Command Submission

```typescript
import { RaftClient } from '@zio-raft/typescript-client';

// Create client (lazy initialization)
const client = new RaftClient({
  endpoints: ['tcp://localhost:5555', 'tcp://localhost:5556', 'tcp://localhost:5557'],
  capabilities: {
    version: '1.0.0',
    clientType: 'typescript-quickstart'
  }
});

// Register event handlers BEFORE connecting
client.on('connected', (evt) => {
  console.log(`Connected! Session ID: ${evt.sessionId}`);
});

client.on('disconnected', (evt) => {
  console.warn(`Disconnected: ${evt.reason}`);
});

client.on('sessionExpired', (evt) => {
  console.error(`Session expired: ${evt.sessionId}`);
  process.exit(1);
});

async function main() {
  try {
    // Explicitly connect
    await client.connect();
    console.log('Client connected successfully');
    
    // Submit a command (example: KV store SET operation)
    const commandPayload = encodeSetCommand('user:123', { name: 'Alice', age: 30 });
    const result = await client.submitCommand(commandPayload);
    console.log('Command result:', decodeResponse(result));
    
    // Submit a query (example: KV store GET operation)
    const queryPayload = encodeGetQuery('user:123');
    const queryResult = await client.submitQuery(queryPayload);
    console.log('Query result:', decodeResponse(queryResult));
    
    // Graceful shutdown
    await client.disconnect();
    console.log('Client disconnected');
  } catch (err) {
    console.error('Error:', err);
    process.exit(1);
  }
}

// Application-specific encoding functions
function encodeSetCommand(key: string, value: any): Buffer {
  const json = JSON.stringify({ type: 'SET', key, value });
  return Buffer.from(json, 'utf8');
}

function encodeGetQuery(key: string): Buffer {
  const json = JSON.stringify({ type: 'GET', key });
  return Buffer.from(json, 'utf8');
}

function decodeResponse(bytes: Buffer): any {
  const json = bytes.toString('utf8');
  return JSON.parse(json);
}

main();
```

**Expected Output**:
```
Connected! Session ID: 550e8400-e29b-41d4-a716-446655440000
Client connected successfully
Command result: { success: true }
Query result: { key: 'user:123', value: { name: 'Alice', age: 30 } }
Client disconnected
```

**Validation**:
- ✅ Client connects to cluster
- ✅ Session created with unique ID
- ✅ Command submitted and response received
- ✅ Query submitted and response received
- ✅ Graceful disconnection

---

### Example 2: High-Throughput Concurrent Requests

```typescript
import { RaftClient } from '@zio-raft/typescript-client';

async function highThroughputExample() {
  const client = new RaftClient({
    endpoints: ['tcp://localhost:5555'],
    capabilities: { version: '1.0.0' },
    requestTimeout: 30000 // 30 seconds
  });
  
  await client.connect();
  
  // Submit 1,000 commands concurrently
  const start = Date.now();
  const promises = [];
  
  for (let i = 0; i < 1000; i++) {
    const payload = encodeSetCommand(`key:${i}`, { index: i });
    promises.push(client.submitCommand(payload));
  }
  
  const results = await Promise.all(promises);
  const elapsed = Date.now() - start;
  
  console.log(`Completed ${results.length} commands in ${elapsed}ms`);
  console.log(`Throughput: ${(results.length / elapsed * 1000).toFixed(0)} req/s`);
  
  await client.disconnect();
}

highThroughputExample();
```

**Expected Output**:
```
Completed 1000 commands in 987ms
Throughput: 1013 req/s
```

**Validation**:
- ✅ High concurrency (1,000+ concurrent requests)
- ✅ All promises resolve successfully
- ✅ Throughput meets target (>= 1,000 req/s)
- ✅ No memory leaks or crashes

---

### Example 3: Automatic Reconnection

```typescript
import { RaftClient } from '@zio-raft/typescript-client';

async function reconnectionExample() {
  const client = new RaftClient({
    endpoints: ['tcp://localhost:5555', 'tcp://localhost:5556'],
    capabilities: { version: '1.0.0' }
  });
  
  // Track connection events
  let reconnectCount = 0;
  
  client.on('disconnected', (evt) => {
    console.log(`Disconnected: ${evt.reason}`);
  });
  
  client.on('reconnecting', (evt) => {
    reconnectCount++;
    console.log(`Reconnecting (attempt ${evt.attempt}) to ${evt.endpoint}`);
  });
  
  client.on('connected', (evt) => {
    console.log(`Connected to ${evt.endpoint}, session: ${evt.sessionId}`);
  });
  
  await client.connect();
  
  // Submit command 1 (before disconnection)
  const result1 = await client.submitCommand(encodeSetCommand('key1', 'value1'));
  console.log('Command 1 completed:', decodeResponse(result1));
  
  // Simulate network failure (kill server connection externally)
  console.log('Simulating network failure (stop server now)...');
  await new Promise(resolve => setTimeout(resolve, 5000)); // Wait 5s
  
  // Submit command 2 (during disconnection - will queue and retry)
  console.log('Submitting command 2 (while disconnected)...');
  const promise2 = client.submitCommand(encodeSetCommand('key2', 'value2'));
  
  // Restart server (allow reconnection)
  console.log('Waiting for reconnection (start server now)...');
  const result2 = await promise2;
  console.log('Command 2 completed after reconnection:', decodeResponse(result2));
  
  console.log(`Total reconnection attempts: ${reconnectCount}`);
  
  await client.disconnect();
}

reconnectionExample();
```

**Expected Output**:
```
Connected to tcp://localhost:5555, session: 550e8400-...
Command 1 completed: { success: true }
Simulating network failure (stop server now)...
Submitting command 2 (while disconnected)...
Disconnected: network
Reconnecting (attempt 1) to tcp://localhost:5555
Reconnecting (attempt 2) to tcp://localhost:5556
Waiting for reconnection (start server now)...
Connected to tcp://localhost:5555, session: 550e8400-...
Command 2 completed after reconnection: { success: true }
Total reconnection attempts: 2
```

**Validation**:
- ✅ Disconnection detected automatically
- ✅ Client attempts reconnection across all endpoints
- ✅ Pending requests queued during disconnection
- ✅ Session resumed after reconnection (same sessionId)
- ✅ Queued requests completed after reconnection

---

### Example 4: Server-Initiated Requests

```typescript
import { RaftClient } from '@zio-raft/typescript-client';

async function serverRequestExample() {
  const client = new RaftClient({
    endpoints: ['tcp://localhost:5555'],
    capabilities: { version: '1.0.0', subscriptions: 'enabled' }
  });
  
  // Register handler for server-initiated requests
  client.onServerRequest((request) => {
    console.log(`Server request received (ID: ${request.requestId})`);
    const payload = decodeResponse(request.payload);
    console.log(`Server payload:`, payload);
    
    // Application logic to handle server request
    if (payload.type === 'NOTIFICATION') {
      console.log(`Notification: ${payload.message}`);
    }
    
    // Acknowledgment sent automatically
  });
  
  await client.connect();
  
  // Wait for server requests (server pushes notifications)
  console.log('Listening for server requests... (press Ctrl+C to exit)');
  await new Promise(() => {}); // Wait indefinitely
}

serverRequestExample();
```

**Expected Output**:
```
Connected to tcp://localhost:5555, session: 550e8400-...
Listening for server requests... (press Ctrl+C to exit)
Server request received (ID: 1)
Server payload: { type: 'NOTIFICATION', message: 'Cluster status: healthy' }
Notification: Cluster status: healthy
Server request received (ID: 2)
Server payload: { type: 'NOTIFICATION', message: 'New leader elected' }
Notification: New leader elected
```

**Validation**:
- ✅ Handler registered successfully
- ✅ Server requests delivered to handler
- ✅ Consecutive request IDs (no gaps)
- ✅ Automatic acknowledgment sent
- ✅ No duplicate deliveries

---

### Example 5: Error Handling

```typescript
import { RaftClient, ValidationError, TimeoutError, SessionExpiredError } from '@zio-raft/typescript-client';

async function errorHandlingExample() {
  const client = new RaftClient({
    endpoints: ['tcp://localhost:5555'],
    capabilities: { version: '1.0.0' },
    requestTimeout: 5000 // 5 seconds
  });
  
  await client.connect();
  
  // Scenario 1: Validation error (synchronous throw)
  try {
    await client.submitCommand(null as any); // Invalid payload
  } catch (err) {
    if (err instanceof ValidationError) {
      console.log('✅ Validation error caught:', err.message);
    }
  }
  
  // Scenario 2: Request timeout (Promise rejection)
  try {
    // Submit command to slow endpoint (simulated timeout)
    const slowPayload = encodeSetCommand('slow-key', { delay: 10000 });
    await client.submitCommand(slowPayload);
  } catch (err) {
    if (err instanceof TimeoutError) {
      console.log('✅ Timeout error caught:', err.message);
      console.log(`   Request ID: ${err.requestId}`);
      
      // Manual retry
      console.log('Retrying manually...');
      const retryPayload = encodeSetCommand('slow-key', { delay: 0 });
      const result = await client.submitCommand(retryPayload);
      console.log('✅ Retry successful:', decodeResponse(result));
    }
  }
  
  // Scenario 3: Session expiry (terminal event)
  client.on('sessionExpired', (evt) => {
    console.log('❌ Session expired:', evt.sessionId);
    console.log('   Application should terminate or reconnect with new session');
    process.exit(1);
  });
  
  await client.disconnect();
}

errorHandlingExample();
```

**Expected Output**:
```
✅ Validation error caught: Invalid payload: expected Buffer
✅ Timeout error caught: Request timeout after 5000ms
   Request ID: 42
Retrying manually...
✅ Retry successful: { success: true }
```

**Validation**:
- ✅ Validation errors thrown synchronously
- ✅ Timeout errors reject promises with TimeoutError
- ✅ Manual retry works after timeout
- ✅ Session expiry emits event (not shown in output)

---

## Configuration Options

### Full Configuration Example

```typescript
const client = new RaftClient({
  // Required
  endpoints: [
    'tcp://raft-node-1:5555',
    'tcp://raft-node-2:5555',
    'tcp://raft-node-3:5555'
  ],
  capabilities: {
    version: '1.0.0',
    clientType: 'my-app',
    features: 'compression,encryption'
  },
  
  // Optional (with defaults)
  connectionTimeout: 5000,         // 5s - timeout for initial connection
  requestTimeout: 30000,           // 30s - timeout for individual requests
  keepAliveInterval: 30000,        // 30s - heartbeat interval
  queuedRequestTimeout: 60000,     // 60s - timeout for queued requests during disconnection
  maxReconnectAttempts: Infinity,  // Infinite retries
  reconnectInterval: 1000          // 1s - delay between reconnection attempts
});
```

**Configuration Validation**:
- Endpoints: Non-empty array of valid ZMQ endpoint strings
- Capabilities: Non-empty object with string key-value pairs
- Timeouts: Positive integers (milliseconds)
- Max reconnect attempts: Positive integer or Infinity

---

## Integration Testing

### Step 1: Start ZIO Raft Test Cluster

```bash
# Start 3-node cluster locally
sbt "raft/runMain zio.raft.examples.KVStoreApp --port 5555 --id node1" &
sbt "raft/runMain zio.raft.examples.KVStoreApp --port 5556 --id node2" &
sbt "raft/runMain zio.raft.examples.KVStoreApp --port 5557 --id node3" &
```

### Step 2: Run TypeScript Client Test

```bash
cd typescript-client
npm test
```

**Expected Test Output**:
```
✓ TC-INT-001: Full Connect → Command → Disconnect Cycle (123ms)
✓ TC-INT-002: Multiple Commands Sequentially (234ms)
✓ TC-INT-003: Multiple Commands Concurrently (456ms)
✓ TC-COMPAT-001: TypeScript Client → Scala Server CreateSession (78ms)
✓ TC-COMPAT-002: TypeScript Client → Scala Server ClientRequest (91ms)
...

Test Suites: 15 passed, 15 total
Tests:       95 passed, 95 total
Time:        12.345s
```

### Step 3: Verify Wire Protocol Compatibility

```bash
# Capture network traffic during test
tcpdump -i lo0 -w raft-client.pcap port 5555 &

# Run client test
npm test

# Analyze captured traffic
# Verify protocol signature: 0x7a 0x72 0x61 0x66 0x74 ("zraft")
# Verify protocol version: 0x01
# Compare byte-for-byte with Scala client
```

---

## Production Usage Patterns

### Pattern 1: Singleton Client (Shared Session)

```typescript
// client.ts (singleton module)
import { RaftClient } from '@zio-raft/typescript-client';

let _client: RaftClient | null = null;

export function getClient(): RaftClient {
  if (!_client) {
    _client = new RaftClient({
      endpoints: process.env.RAFT_ENDPOINTS!.split(','),
      capabilities: { version: '1.0.0', app: 'my-app' }
    });
    
    // Global error handling
    _client.on('sessionExpired', () => {
      console.error('Raft session expired - restarting application');
      process.exit(1);
    });
  }
  
  return _client;
}

export async function connectClient(): Promise<void> {
  const client = getClient();
  await client.connect();
}
```

**Usage**:
```typescript
import { getClient, connectClient } from './client';

await connectClient(); // At app startup

// Use throughout application
const client = getClient();
await client.submitCommand(payload);
```

---

### Pattern 2: Multiple Sessions (Worker Pool)

```typescript
// Create multiple client instances for parallel sessions
const workers = [];

for (let i = 0; i < 4; i++) {
  const client = new RaftClient({
    endpoints: ['tcp://localhost:5555'],
    capabilities: { version: '1.0.0', workerId: `worker-${i}` }
  });
  
  await client.connect();
  workers.push(client);
}

// Distribute work across workers (round-robin)
let nextWorker = 0;

async function submitWork(payload: Buffer): Promise<Buffer> {
  const worker = workers[nextWorker];
  nextWorker = (nextWorker + 1) % workers.length;
  return await worker.submitCommand(payload);
}
```

---

### Pattern 3: Graceful Shutdown

```typescript
import { RaftClient } from '@zio-raft/typescript-client';

const client = new RaftClient({ /* config */ });
await client.connect();

// Handle shutdown signals
process.on('SIGTERM', async () => {
  console.log('Received SIGTERM, shutting down gracefully...');
  
  try {
    await client.disconnect();
    console.log('Client disconnected successfully');
    process.exit(0);
  } catch (err) {
    console.error('Error during shutdown:', err);
    process.exit(1);
  }
});

process.on('SIGINT', async () => {
  console.log('Received SIGINT, shutting down gracefully...');
  await client.disconnect();
  process.exit(0);
});
```

---

## Troubleshooting

### Issue 1: Connection Timeout

**Symptom**:
```
Error: Connection timeout after 5000ms
```

**Diagnosis**:
- Server not running or unreachable
- Incorrect endpoint URLs
- Firewall blocking ZMQ traffic

**Solution**:
```bash
# Verify server is running
netstat -an | grep 5555

# Test ZMQ connectivity
telnet localhost 5555

# Check firewall rules
# Increase connection timeout in config
```

---

### Issue 2: Session Expired

**Symptom**:
```
SessionExpiredError: Session expired: 550e8400-...
```

**Diagnosis**:
- Keep-alive interval too long
- Network latency causing missed heartbeats
- Server session timeout too short

**Solution**:
```typescript
// Reduce keep-alive interval
const client = new RaftClient({
  keepAliveInterval: 15000, // 15s instead of 30s
  // ...
});
```

---

### Issue 3: Request Timeout

**Symptom**:
```
TimeoutError: Request timeout after 30000ms
```

**Diagnosis**:
- Slow Raft consensus (large cluster, high latency)
- Server overloaded
- Request payload too large

**Solution**:
```typescript
// Increase request timeout
const client = new RaftClient({
  requestTimeout: 60000, // 60s instead of 30s
  // ...
});

// Or manually retry
try {
  await client.submitCommand(payload);
} catch (err) {
  if (err instanceof TimeoutError) {
    // Retry with same or modified payload
    await client.submitCommand(payload);
  }
}
```

---

### Issue 4: High Memory Usage

**Symptom**: Memory usage grows continuously over time

**Diagnosis**:
- Event listener leak (handlers not removed)
- Pending request accumulation (timeout too long)
- Large payloads not garbage collected

**Solution**:
```typescript
// Remove event listeners when done
const handler = (evt) => { /* ... */ };
client.on('connected', handler);
// Later:
client.off('connected', handler);

// Monitor pending request count
// Reduce request timeout to fail faster
// Profile with Node.js heap snapshots
```

---

## Performance Tuning

### Tip 1: Batch Requests for Throughput

```typescript
// Instead of sequential:
for (const item of items) {
  await client.submitCommand(encodeItem(item)); // Slow, one at a time
}

// Use concurrent batch:
const promises = items.map(item => client.submitCommand(encodeItem(item)));
const results = await Promise.all(promises); // Fast, all at once
```

---

### Tip 2: Reuse Buffers for Encoding

```typescript
// Avoid repeated allocations
function encodeCommand(data: any): Buffer {
  const json = JSON.stringify(data);
  return Buffer.from(json, 'utf8');
}
}
```

---

### Tip 3: Monitor Event Loop Lag

```typescript
import { performance } from 'perf_hooks';

setInterval(() => {
  const start = performance.now();
  setImmediate(() => {
    const lag = performance.now() - start;
    if (lag > 50) {
      console.warn(`Event loop lag: ${lag.toFixed(2)}ms`);
    }
  });
}, 1000);
```

---

## Success Criteria

This quickstart is considered successful when:

✅ **Installation**: Package installs without errors  
✅ **Basic Usage**: Example 1 runs and completes successfully  
✅ **High Throughput**: Example 2 achieves >= 1,000 req/s  
✅ **Reconnection**: Example 3 demonstrates automatic reconnection  
✅ **Server Requests**: Example 4 receives and handles server requests  
✅ **Error Handling**: Example 5 demonstrates all error scenarios  
✅ **Integration Tests**: All 95 tests pass  
✅ **Wire Compatibility**: Protocol validation passes against Scala server  
✅ **Production Patterns**: All 3 patterns demonstrated successfully

---

## Next Steps

After completing this quickstart:

1. **Review Documentation**: Read full API documentation
2. **Explore Examples**: Check `examples/` directory for more use cases
3. **Run Benchmarks**: Execute performance tests to validate NFRs
4. **Integration**: Integrate client into your application
5. **Monitoring**: Set up observability via event handlers
6. **Production Deployment**: Deploy with proper configuration and monitoring

---

## Resources

- **GitHub Repository**: https://github.com/zio/zio-raft (monorepo)
- **TypeScript Client**: `/typescript-client` subdirectory
- **API Documentation**: Generated TypeDoc in `/typescript-client/docs`
- **Scala Server**: `/raft`, `/client-server-protocol` modules
- **Examples**: `/typescript-client/examples`

---

**Quickstart Complete**: All examples demonstrate core functionality and validate acceptance scenarios.
