# Analysis: Transport Injection for Testing

**Date**: 2026-01-15  
**Context**: Issues with mocking RaftClient for integration tests

---

## Current Problem

**No dependency injection for transport**:
```typescript
constructor(configInput: ClientConfigInput) {
  // Transport is hardcoded
  this.transport = new ZmqTransport();
}
```

**Current test workaround**:
```typescript
const client = new RaftClient(config);
// Hack: Replace private field
(client as any).transport = mockTransport;
```

**Problems**:
1. Brittle - breaks TypeScript encapsulation
2. No type safety
3. Can't control transport creation
4. Mocks must match exact internal structure
5. Integration tests require actual ZMQ sockets

---

## Proposed Solution: Constructor Injection

### Option 1: Optional Transport Parameter (Recommended)

```typescript
constructor(
  configInput: ClientConfigInput,
  transport?: ClientTransport  // Optional, for testing
) {
  super();
  this.config = createConfig(configInput);
  
  // Use provided transport, or default to production
  this.transport = transport ?? new ZmqTransport();
  
  // ... rest of initialization
}
```

**Pros**:
- ✅ Clean dependency injection
- ✅ Backwards compatible (optional parameter)
- ✅ Type-safe
- ✅ Easy to use: `new RaftClient(config)` (production)
- ✅ Easy to test: `new RaftClient(config, mockTransport)` (testing)

**Cons**:
- ⚠️ Exposes internal dependency (minor)

### Option 2: Transport Factory

```typescript
interface TransportFactory {
  create(config: ClientConfig): ClientTransport;
}

constructor(
  configInput: ClientConfigInput,
  transportFactory?: TransportFactory
) {
  this.config = createConfig(configInput);
  const factory = transportFactory ?? new ZmqTransportFactory();
  this.transport = factory.create(this.config);
}
```

**Pros**:
- ✅ More abstract
- ✅ Factory can have logic

**Cons**:
- ❌ More boilerplate
- ❌ Overkill for simple case

### Option 3: Config-Based

```typescript
interface ClientConfigInput {
  clusterMembers: Map<MemberId, string>;
  capabilities: Map<string, string>;
  transport?: ClientTransport;  // Hidden in config
}
```

**Pros**:
- ✅ Hides from main signature

**Cons**:
- ❌ Config pollution (mixing config with dependencies)
- ❌ Less discoverable

### **Recommendation: Option 1**

Simple, clean, idiomatic TypeScript. Used by many libraries (e.g., axios).

---

## Mock Transport Design

### Requirements

A good mock transport should:
1. **Implement ClientTransport interface** - drop-in replacement
2. **Control message flow** - inject messages on demand
3. **Simulate async behavior** - proper event loop integration
4. **Easy assertions** - verify sent messages
5. **Reusable** - shared across all tests

### Proposed Implementation

```typescript
/**
 * Mock transport for testing RaftClient
 * Allows precise control over message flow without network dependencies
 */
export class MockTransport implements ClientTransport {
  // Expose incoming messages queue (real transport hides this)
  public readonly incomingMessages: AsyncQueue<ServerMessage>;
  
  // Track sent messages for assertions
  public readonly sentMessages: ClientMessage[] = [];
  
  // Control connection state
  private _connected = false;
  
  constructor() {
    this.incomingMessages = new AsyncQueue();
  }
  
  async connect(): Promise<void> {
    this._connected = true;
  }
  
  async disconnect(): Promise<void> {
    this._connected = false;
    this.incomingMessages.close();
  }
  
  async sendMessage(message: ClientMessage): Promise<void> {
    if (!this._connected) {
      throw new Error('Transport not connected');
    }
    this.sentMessages.push(message);
    
    // Auto-respond to CreateSession (simulate server)
    if (message.type === 'CreateSession') {
      setTimeout(() => {
        this.incomingMessages.offer({
          type: 'SessionCreated',
          sessionId: 'mock-session-123',
          nonce: message.nonce,
        });
      }, 10);
    }
  }
  
  // Test helpers
  injectMessage(message: ServerMessage): void {
    if (!this._connected) {
      throw new Error('Cannot inject - not connected');
    }
    this.incomingMessages.offer(message);
  }
  
  getLastSentMessage(): ClientMessage | undefined {
    return this.sentMessages[this.sentMessages.length - 1];
  }
  
  getSentMessagesOfType<T extends ClientMessage['type']>(
    type: T
  ): Extract<ClientMessage, { type: T }>[] {
    return this.sentMessages.filter(m => m.type === type) as any;
  }
}
```

### Benefits of This Design

1. **Type-safe**: Implements `ClientTransport` interface
2. **Flexible**: Can inject any server message at any time
3. **Observable**: Can assert on sent messages
4. **Realistic**: Simulates async behavior
5. **Reusable**: One class for all tests

---

## Impact on Testing Strategy

### Before (Current)

```
Unit Tests          Integration Tests       E2E Tests
│                   │                       │
├─ State machine    ├─ Full RaftClient      ├─ Real cluster
├─ Codecs           │  with hacks           │  + real network
└─ Utils            └─ (brittle mocks)      └─ (slow, complex)
                           ↑
                    Tests are here
                    (not great)
```

### After (With Transport Injection)

```
Unit Tests          Integration Tests       E2E Tests
│                   │                       │
├─ State machine    ├─ RaftClient           ├─ Real cluster
├─ Codecs           │  + MockTransport      │  (rarely needed)
├─ Utils            │  (type-safe, clean)   │
└─ emitClientEvent  └─ (most testing here)  └─ (only for final validation)
                           ↑
                    Tests move here
                    (much better!)
```

### What This Enables

**Integration tests can now**:
- ✅ Test full RaftClient without network
- ✅ Inject server messages precisely
- ✅ Assert on client messages sent
- ✅ Test race conditions reliably
- ✅ Test timeout handling
- ✅ Test reconnection logic
- ✅ Run fast (no network I/O)

**E2E tests are now optional**:
- Only needed for final system validation
- Most behavior tested at integration level
- Can be slow/complex since rarely run

---

## Migration Path

### Step 1: Add Transport Parameter (Non-Breaking)

```typescript
// Existing code keeps working
const client = new RaftClient(config);

// Tests can now use mock
const client = new RaftClient(config, mockTransport);
```

### Step 2: Create MockTransport

```typescript
// typescript-client/src/testing/MockTransport.ts
export class MockTransport implements ClientTransport { ... }
```

### Step 3: Update Tests

```typescript
// Old (brittle):
const client = new RaftClient(config);
(client as any).transport = mockTransport;

// New (clean):
const mockTransport = new MockTransport();
const client = new RaftClient(config, mockTransport);
```

### Step 4: Export for User Testing

```typescript
// src/index.ts
export { MockTransport } from './testing/MockTransport';

// Users can now test their own code:
import { MockTransport, RaftClient } from '@zio-raft/typescript-client';
```

---

## Comparison: Integration vs E2E

| Aspect | Integration (w/ Mock) | E2E (Real Cluster) |
|--------|----------------------|-------------------|
| **Speed** | Fast (<100ms) | Slow (seconds) |
| **Setup** | None | Start cluster |
| **Reliability** | Deterministic | Flaky |
| **Debugging** | Easy | Hard |
| **Coverage** | High | Medium |
| **Realism** | Simulated | Real |
| **CI/CD** | Every commit | Pre-release |

**Recommendation**: 
- 90% integration tests (fast feedback)
- 10% E2E tests (final validation)

---

## Alternatives Considered

### Alternative 1: Test Harness with Real Server

**Approach**: Start a minimal Raft server for tests

**Pros**:
- Real network behavior
- Real message serialization

**Cons**:
- Slow (network I/O)
- Complex setup
- Flaky (timing issues)
- Hard to test edge cases

**Verdict**: ❌ Too heavy for most tests

### Alternative 2: Record/Replay

**Approach**: Record real traffic, replay in tests

**Pros**:
- Real message sequences
- Good for regression

**Cons**:
- Can't test new scenarios
- Brittle (format changes break)
- Hard to understand

**Verdict**: ❌ Not flexible enough

### Alternative 3: Keep Current Approach

**Approach**: Continue with `(client as any).transport = mock`

**Pros**:
- No changes needed

**Cons**:
- Type-unsafe
- Brittle
- Limits what we can test

**Verdict**: ❌ Technical debt

---

## Recommendation

**Implement Option 1 (Constructor Injection) + MockTransport**

### Rationale

1. **Minimal change**: Optional parameter, backwards compatible
2. **High value**: Enables much better testing
3. **Low risk**: Doesn't affect production code
4. **Industry standard**: Common pattern in TypeScript
5. **Future-proof**: Makes future testing easier

### Implementation Effort

- 🟢 Small: ~2 hours
  - Add optional parameter (5 min)
  - Create MockTransport (1 hour)
  - Update existing tests (30 min)
  - Add documentation (30 min)

### Benefits

- ✅ Tests become type-safe
- ✅ Tests become reliable
- ✅ Tests become fast
- ✅ Tests become readable
- ✅ Users can test their code

---

## Conclusion

**Transport injection is a significant improvement** that aligns with testing best practices. It's a small change that enables much better testing without affecting production code.

The combination of:
1. Constructor injection for transport
2. Shared MockTransport implementation
3. Focus on integration tests

...provides the best balance of speed, reliability, and coverage.

**Next step**: Implement and demonstrate with failing tests for Issues 2 & 3.
