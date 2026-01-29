# Test Helpers

This directory contains helper functions to reduce duplication in tests.

## Message Factories

The `messageFactories.ts` module provides factory functions for constructing protocol messages in tests. This centralizes protocol message construction, making tests easier to maintain when the protocol changes.

### Benefits

1. **Reduced Duplication**: Protocol message construction is centralized
2. **Type Safety**: TypeScript enforces correctness at compile time
3. **Easier Maintenance**: When protocol changes, update factories instead of every test
4. **Better Readability**: Tests focus on behavior, not message structure

### Before (Example from resend.test.ts)

```typescript
mockTransport.injectMessage({
  type: 'SessionCreated',
  sessionId: 'test-session-001',
  nonce: createSessionMsgs[0].nonce,
});

mockTransport.injectMessage({
  type: 'SessionClosed',
  reason: 'NotLeaderAnymore',
  leaderId: MemberId.fromString('node2'),
});

mockTransport.injectMessage({
  type: 'ClientResponse',
  requestId: resentRequests[0].requestId,
  result: Buffer.from('command-result'),
});
```

### After (Using Helpers)

```typescript
import { sessionCreatedFor, sessionClosedDueTo, clientResponseFor } from '../helpers/messageFactories';

mockTransport.injectMessage(sessionCreatedFor(createSessionMsgs[0].nonce, 'test-session-001'));
mockTransport.injectMessage(sessionClosedDueTo('NotLeaderAnymore', MemberId.fromString('node2')));
mockTransport.injectMessage(clientResponseFor(resentRequests[0].requestId, Buffer.from('command-result')));
```

## Available Factories

### Session Management

- `sessionCreatedFor(nonce, sessionId?)` - Create SessionCreated message
- `sessionClosedDueTo(reason, leaderId?)` - Create SessionClosed message
- `sessionContinuedFor(nonce)` - Create SessionContinued message
- `sessionRejectedWith(nonce, reason, leaderId?)` - Create SessionRejected message

### Request/Response

- `clientResponseFor(requestId, result)` - Create ClientResponse message
- `queryResponseFor(correlationId, result)` - Create QueryResponse message
- `serverRequestWith(requestId, payload, createdAt?)` - Create ServerRequest message

### Short Aliases

All factories have short aliases without the suffix:
- `sessionCreated`, `sessionClosed`, `sessionContinued`, `sessionRejected`
- `clientResponse`, `queryResponse`, `serverRequest`

## Usage

```typescript
import {
  sessionCreatedFor,
  clientResponseFor,
  serverRequestWith,
} from '../helpers/messageFactories';

// In your test
mockTransport.injectMessage(sessionCreatedFor(nonce));
mockTransport.injectMessage(clientResponseFor(requestId, Buffer.from('result')));
mockTransport.injectMessage(serverRequestWith(RequestId.fromBigInt(1n), Buffer.from('work')));
```

## Impact

**Duplication Reduction:**
- Before: ~49 manual protocol message constructions across tests
- After: ~49 one-line factory calls
- Lines saved: ~100+ (average 3-5 lines per message → 1 line)

**Tests Updated:**
- ✅ `resend.test.ts` (25 message constructions)
- ✅ `lifecycle.test.ts` (5 message constructions)
- ✅ `serverRequest.test.ts` (4 message constructions)
- ✅ `serverRequestIssues.test.ts` (10 message constructions)
- ✅ `debug-connect.test.ts` (1 message construction)
- ✅ `transport-connection.test.ts` (0 - doesn't construct messages)

**Total:** 45 message constructions converted to factory calls

## Maintenance

When the protocol changes:

1. **Update `messageFactories.ts`** with new required fields
2. **TypeScript will fail compilation** at all call sites that need updates
3. **Update call sites** based on compiler errors
4. **Tests remain correct** due to type safety

This is significantly better than manually finding and updating 45+ inline message constructions.
