# Quickstart Guide: TypeScript Client Library

**Date**: 2025-12-24  
**Feature**: TypeScript Client for ZIO Raft

## Overview
This quickstart guide demonstrates how to use the TypeScript client library to connect to a ZIO Raft cluster and perform basic operations.

---

## Prerequisites

1. **Node.js 18+** installed
2. **ZIO Raft cluster** running (e.g., kvstore example from the Scala project)
3. **TypeScript client library** installed:
   ```bash
   npm install @zio-raft/typescript-client
   ```

---

## Quick Example

### 1. Basic Setup

```typescript
import { RaftClient, MemberId, ClientConfig } from '@zio-raft/typescript-client';

// Define cluster members
const clusterMembers = new Map<MemberId, string>([
  [MemberId.fromString('node1'), 'tcp://localhost:5555'],
  [MemberId.fromString('node2'), 'tcp://localhost:5556'],
  [MemberId.fromString('node3'), 'tcp://localhost:5557']
]);

// Define client capabilities (application-specific)
const capabilities = new Map<string, string>([
  ['client-type', 'typescript'],
  ['version', '1.0.0']
]);

// Create client configuration
const config: ClientConfig = {
  clusterMembers,
  capabilities,
  connectionTimeout: 5000,      // 5 seconds
  keepAliveInterval: 30000,     // 30 seconds
  requestTimeout: 10000         // 10 seconds
};

// Create client instance
const client = new RaftClient(config);
```

### 2. Connect to Cluster

```typescript
async function main() {
  try {
    // Connect to cluster
    await client.connect();
    console.log('Connected to Raft cluster');
    
    // Submit a command (write operation)
    const commandPayload = Buffer.from(JSON.stringify({
      op: 'set',
      key: 'user:123',
      value: { name: 'Alice', age: 30 }
    }));
    
    const commandResult = await client.submitCommand(commandPayload);
    console.log('Command result:', commandResult.toString());
    
    // Submit a query (read operation)
    const queryPayload = Buffer.from(JSON.stringify({
      op: 'get',
      key: 'user:123'
    }));
    
    const queryResult = await client.query(queryPayload);
    console.log('Query result:', queryResult.toString());
    
  } catch (error) {
    console.error('Error:', error);
  } finally {
    // Clean disconnect
    await client.disconnect();
    console.log('Disconnected from Raft cluster');
  }
}

main().catch(console.error);
```

---

## Observing Client Events

The client emits events for observability without built-in logging:

```typescript
// State changes
client.on('stateChange', (event) => {
  console.log(`State changed: ${event.oldState} → ${event.newState}`);
});

// Connection attempts and results
client.on('connectionAttempt', (event) => {
  console.log(`Attempting connection to ${event.memberId} at ${event.address}`);
});

client.on('connectionSuccess', (event) => {
  console.log(`Connected to ${event.memberId}`);
});

client.on('connectionFailure', (event) => {
  console.error(`Connection to ${event.memberId} failed:`, event.error);
});

// Session expiry (terminal event)
client.on('sessionExpired', () => {
  console.error('Session expired - client will terminate');
  process.exit(1);
});

// Message events (verbose logging)
client.on('messageSent', (event) => {
  console.debug('Sent:', event.message.type);
});

client.on('messageReceived', (event) => {
  console.debug('Received:', event.message.type);
});
```

---

## Handling Server-Initiated Requests

The server can send work to the client:

```typescript
// Start processing server requests
(async () => {
  for await (const serverRequest of client.serverRequests()) {
    console.log('Received server request:', serverRequest.requestId);
    
    // Process the request payload
    const payload = serverRequest.payload;
    console.log('Payload:', payload.toString());
    
    // Client automatically acknowledges the request
    // No manual ack needed - handled internally
  }
})();

// Alternative: event-based approach
client.on('serverRequestReceived', (event) => {
  const { request } = event;
  console.log('Server request:', request.requestId);
  
  // Process request...
  const payload = request.payload;
  console.log('Processing:', payload.toString());
  
  // Ack is automatic
});
```

---

## Error Handling

### Synchronous Validation Errors

```typescript
try {
  // Invalid config: empty capabilities
  const invalidConfig = {
    clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
    capabilities: new Map()  // ❌ Empty!
  };
  
  const client = new RaftClient(invalidConfig);
} catch (error) {
  // Throws synchronously during construction
  console.error('Validation error:', error.message);
}
```

### Asynchronous Operation Errors

```typescript
try {
  const result = await client.submitCommand(payload);
} catch (error) {
  if (error instanceof NetworkError) {
    console.error('Network failure:', error.message);
  } else if (error instanceof SessionError) {
    console.error('Session error:', error.reason);
  } else if (error instanceof TimeoutError) {
    console.error('Request timeout:', error.requestId);
  } else {
    console.error('Unknown error:', error);
  }
}
```

---

## Advanced: Multiple Concurrent Operations

```typescript
async function parallelOperations() {
  await client.connect();
  
  // Submit 100 commands concurrently
  const commandPromises = Array.from({ length: 100 }, (_, i) => {
    const payload = Buffer.from(JSON.stringify({
      op: 'set',
      key: `user:${i}`,
      value: { id: i }
    }));
    return client.submitCommand(payload);
  });
  
  // Wait for all commands to complete
  const results = await Promise.all(commandPromises);
  console.log(`Completed ${results.length} commands`);
  
  // Submit 100 queries concurrently
  const queryPromises = Array.from({ length: 100 }, (_, i) => {
    const payload = Buffer.from(JSON.stringify({
      op: 'get',
      key: `user:${i}`
    }));
    return client.query(payload);
  });
  
  const queryResults = await Promise.all(queryPromises);
  console.log(`Completed ${queryResults.length} queries`);
  
  await client.disconnect();
}
```

---

## Configuration Options

### Default Values

```typescript
const defaultConfig: ClientConfig = {
  clusterMembers: new Map([...]),      // Required, no default
  capabilities: new Map([...]),        // Required, no default
  connectionTimeout: 5000,             // 5 seconds
  keepAliveInterval: 30000,            // 30 seconds
  requestTimeout: 10000                // 10 seconds
};
```

### Tuning for High Throughput

```typescript
const highThroughputConfig: ClientConfig = {
  clusterMembers: clusterMembers,
  capabilities: capabilities,
  connectionTimeout: 3000,       // Faster failover
  keepAliveInterval: 60000,      // Less frequent heartbeats
  requestTimeout: 30000          // Longer timeout for high load
};

const client = new RaftClient(highThroughputConfig);
```

### Tuning for Low Latency

```typescript
const lowLatencyConfig: ClientConfig = {
  clusterMembers: clusterMembers,
  capabilities: capabilities,
  connectionTimeout: 2000,       // Quick connection
  keepAliveInterval: 15000,      // Frequent heartbeats
  requestTimeout: 5000           // Short timeout
};

const client = new RaftClient(lowLatencyConfig);
```

---

## Running Against kvstore Example

If you have the ZIO Raft kvstore example running:

```typescript
import { RaftClient, MemberId } from '@zio-raft/typescript-client';

async function kvstoreExample() {
  // KVStore cluster configuration
  const clusterMembers = new Map([
    [MemberId.fromString('node1'), 'tcp://localhost:5555'],
    [MemberId.fromString('node2'), 'tcp://localhost:5556'],
    [MemberId.fromString('node3'), 'tcp://localhost:5557']
  ]);
  
  const capabilities = new Map([
    ['client-type', 'kvstore-ts-client'],
    ['protocol-version', '1']
  ]);
  
  const client = new RaftClient({ clusterMembers, capabilities });
  
  await client.connect();
  console.log('Connected to kvstore cluster');
  
  // Set a key
  const setPayload = Buffer.from(JSON.stringify({
    type: 'Set',
    key: 'greeting',
    value: 'Hello from TypeScript!'
  }));
  
  await client.submitCommand(setPayload);
  console.log('Key set successfully');
  
  // Get the key
  const getPayload = Buffer.from(JSON.stringify({
    type: 'Get',
    key: 'greeting'
  }));
  
  const result = await client.query(getPayload);
  const value = JSON.parse(result.toString());
  console.log('Retrieved value:', value);
  
  await client.disconnect();
}

kvstoreExample().catch(console.error);
```

---

## Validation Steps

To verify the quickstart works correctly:

1. **Start ZIO Raft cluster** (e.g., kvstore example):
   ```bash
   sbt "kvstore/run node1"  # Terminal 1
   sbt "kvstore/run node2"  # Terminal 2
   sbt "kvstore/run node3"  # Terminal 3
   ```

2. **Run TypeScript client**:
   ```bash
   ts-node quickstart.ts
   ```

3. **Expected output**:
   ```
   State changed: Disconnected → ConnectingNewSession
   Attempting connection to node1 at tcp://localhost:5555
   Connected to node1
   State changed: ConnectingNewSession → Connected
   Connected to Raft cluster
   Command result: {"status":"ok"}
   Query result: {"value":{"name":"Alice","age":30}}
   Disconnected from Raft cluster
   ```

4. **Verify in Scala server logs**:
   - Session created message
   - ClientRequest received
   - Query received
   - Session closed message

---

## Troubleshooting

### "Connection timeout" error

**Problem**: Client cannot connect to cluster.

**Solution**:
- Verify cluster is running
- Check addresses are correct
- Ensure firewall allows connections
- Try increasing `connectionTimeout`

### "Session expired" event

**Problem**: Client received SessionExpired, all requests failed.

**Solution**:
- This is terminal - client cannot recover
- Reduce `keepAliveInterval` if sessions expire too quickly
- Investigate network issues causing missed heartbeats

### "Not connected" error

**Problem**: Tried to submit command/query before connecting.

**Solution**:
- Call `await client.connect()` first
- Ensure connection successful before operations

### High latency

**Problem**: Operations taking longer than expected.

**Solution**:
- Check network latency to cluster
- Reduce `requestTimeout` to retry faster
- Ensure cluster is not overloaded
- Consider batching if submitting many operations

---

## Next Steps

1. **Read the API documentation** for advanced features
2. **Implement application-specific payload encoding** (JSON, Protocol Buffers, etc.)
3. **Add structured logging** by observing client events
4. **Implement error recovery** strategies for your use case
5. **Performance test** with your expected workload

---

## Summary

This quickstart demonstrated:
- ✅ Creating and configuring a RaftClient
- ✅ Connecting to a Raft cluster
- ✅ Submitting commands (write operations)
- ✅ Submitting queries (read operations)
- ✅ Observing client events for logging
- ✅ Handling server-initiated requests
- ✅ Error handling patterns
- ✅ Advanced concurrent operations
- ✅ Configuration tuning

The TypeScript client provides a type-safe, high-performance way to interact with ZIO Raft clusters from Node.js applications.
