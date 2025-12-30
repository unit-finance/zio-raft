# Buffer vs Uint8Array Decision

**Decision Date**: 2025-12-30  
**Context**: TypeScript Client Library Implementation  
**Status**: FINAL

## Decision

**Use a hybrid approach:**
- **Public API (application-facing)**: `Uint8Array`
- **Internal protocol layer**: `Buffer`
- **Automatic conversion**: At the boundary between public API and protocol layer

## Rationale

### Why Uint8Array for Public API

1. **Standard JavaScript/TypeScript type**
   - `Uint8Array` is part of the JavaScript standard (ECMAScript TypedArray)
   - Works in browsers and Node.js without platform-specific dependencies
   - More portable if library is ever used in browser context

2. **Specification requirements**
   - Spec explicitly states: "Raw binary only - Always returns Uint8Array" (spec.md L146)
   - All quickstart examples use `Uint8Array` (quickstart.md L95-108)
   - Test cases validate `Uint8Array` payloads (tests.md L1245-1247)

3. **Idiomatic TypeScript**
   - Following ecosystem standards (similar to Web Crypto API, Fetch API)
   - Better type safety (Uint8Array is more specific than Buffer)
   - TextEncoder/TextDecoder work with Uint8Array natively

### Why Buffer for Internal Protocol Layer

1. **Performance benefits**
   - Buffer has optimized methods for binary manipulation (writeUInt16BE, writeBigInt64BE, etc.)
   - Zero-copy operations where possible
   - Pre-allocation with allocUnsafe() for hot paths

2. **Implementation efficiency**
   - Protocol encoding/decoding requires precise byte manipulation
   - Buffer provides direct methods for multi-byte writes (network byte order)
   - Easier to implement scodec-style encoding with Buffer

3. **Research decision alignment**
   - Research document states: "Use `Buffer` for binary manipulation (Node.js native, zero-copy)" (research.md L29)
   - Design document shows Buffer in codec examples (design.md L293-319)

4. **ZeroMQ compatibility**
   - ZeroMQ Node.js bindings work with Buffer natively
   - No conversion needed at transport layer

### Conversion Strategy

```typescript
// Public API (client.ts)
async submitCommand(payload: Uint8Array): Promise<Uint8Array> {
  // Validate as Uint8Array
  if (!(payload instanceof Uint8Array)) {
    throw new ValidationError('Payload must be Uint8Array');
  }
  
  // Convert to Buffer for internal use
  const buffer = Buffer.from(payload);
  
  // ... internal processing uses Buffer ...
  
  // Convert response back to Uint8Array
  return new Uint8Array(responseBuffer);
}

// Protocol layer (codecs.ts)
function encodePayload(payload: Buffer): Buffer {
  // Works with Buffer internally
  const length = Buffer.allocUnsafe(4);
  length.writeInt32BE(payload.length, 0);
  return Buffer.concat([length, payload]);
}
```

### Conversion Cost Analysis

- **Buffer.from(Uint8Array)**: O(1) when backed by same ArrayBuffer, O(n) copy otherwise
- **new Uint8Array(Buffer)**: O(1) view creation (no copy)
- For typical payloads (< 1MB), conversion overhead is negligible (< 1ms)
- Trade-off is worth it for clean public API and performant internal implementation

## Implementation Rules

1. **Protocol messages** (`protocol/messages.ts`):
   - Internal message types use `Uint8Array` to match public API
   - Codecs convert to/from `Buffer` internally

2. **Codec layer** (`protocol/codecs.ts`):
   - All encoding/decoding functions work with `Buffer`
   - Accept Uint8Array, convert to Buffer at entry
   - Return Buffer, caller converts to Uint8Array if needed

3. **State machine** (`state/clientState.ts`):
   - Works with `Uint8Array` in action types (public-facing)
   - Converts to Buffer when calling codecs

4. **Transport layer** (`transport/*.ts`):
   - ZMQ transport works with `Buffer` natively
   - No conversion needed

## References

- **Spec requirement**: spec.md L146 - "Raw binary only - Always returns Uint8Array"
- **Quickstart examples**: quickstart.md L95-108 - Uses Uint8Array throughout
- **Research decision**: research.md L29 - "Use Buffer for binary manipulation"
- **Test cases**: tests.md L1245 - Validates Uint8Array payloads
- **Design examples**: design.md L364 - "Payload (Uint8Array)"

## Alternatives Considered

### Option 1: Buffer everywhere (Rejected)
- **Pro**: No conversion overhead, simpler implementation
- **Con**: Non-standard API, breaks spec requirement, less portable
- **Why rejected**: Violates spec requirement for Uint8Array in public API

### Option 2: Uint8Array everywhere (Rejected)
- **Pro**: Consistent types, matches public API
- **Con**: Verbose binary manipulation, slower encoding/decoding, requires polyfills for Buffer methods
- **Why rejected**: Significant performance cost in protocol layer

### Option 3: Hybrid approach (Selected)
- **Pro**: Best of both worlds - clean public API, performant internals
- **Con**: Conversion at boundary (minimal overhead)
- **Why selected**: Balances spec compliance, performance, and maintainability

## Migration Notes

If Buffer was used in existing code:
1. Update protocol message types to use `Uint8Array`
2. Add conversion in public API methods
3. Keep codecs using Buffer internally
4. Update tests to validate Uint8Array

---

**Approved by**: Implementation team  
**Last updated**: 2025-12-30

