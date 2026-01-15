# TypeScript Event Emitter Patterns - Comparison

## Summary Table

| Approach | Type Safety | Runtime Safety | DX | Maintenance | External Deps |
|----------|-------------|----------------|-----|-------------|---------------|
| 1. Method Overloads (current) | ⭐⭐⭐ | ⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ✅ None |
| 2. Typed Wrapper Class | ⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ✅ None |
| 3. Event Constants + Overloads | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ✅ None |
| 4. typed-emitter library | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ❌ External |
| 5. Custom EventEmitter | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ | ✅ None |

**Recommendation**: **Approach 3 (Event Constants + Overloads)** - Best balance of type safety, runtime safety, and zero dependencies.

---

## Approach 1: Method Overloads (Current)

### Code Example

```typescript
export class RaftClient extends EventEmitter {
  // Type-safe method overloads
  on(event: 'connected', listener: (evt: ConnectedEvent) => void): this;
  on(event: 'disconnected', listener: (evt: DisconnectedEvent) => void): this;
  on(event: 'reconnecting', listener: (evt: ReconnectingEvent) => void): this;
  on(event: 'sessionExpired', listener: (evt: SessionExpiredEvent) => void): this;
  on(event: 'error', listener: (err: Error) => void): this;
  on(event: string, listener: (...args: any[]) => void): this {
    return super.on(event, listener);
  }

  emit(event: 'connected', evt: ConnectedEvent): boolean;
  emit(event: 'disconnected', evt: DisconnectedEvent): boolean;
  emit(event: 'reconnecting', evt: ReconnectingEvent): boolean;
  emit(event: 'sessionExpired', evt: SessionExpiredEvent): boolean;
  emit(event: 'error', err: Error): boolean;
  emit(event: string, ...args: any[]): boolean {
    return super.emit(event, ...args);
  }
}
```

### Usage

```typescript
const client = new RaftClient(config);

// ✅ Type-safe
client.on('connected', (evt) => {
  console.log(evt.sessionId); // evt is typed as ConnectedEvent
});

// ❌ Typo not caught
client.on('conected', (evt) => {
  // TypeScript allows this! 'string' fallback catches it
});

// ❌ Can emit wrong event name
client.emit('connected', { wrong: 'data' } as any);
```

### Pros
- ✅ Simple, idiomatic TypeScript
- ✅ No dependencies
- ✅ Good IDE autocomplete for event names
- ✅ Typed event data in listeners

### Cons
- ❌ **String literals have no runtime protection** - typos pass through
- ❌ Can bypass types with `as any` or `as string`
- ❌ Must duplicate overloads for `on`, `once`, `emit`, `off`
- ❌ Easy to have inconsistencies between overloads

---

## Approach 2: Typed Wrapper Class (eventEmitter.ts)

### Code Example

```typescript
export interface RaftClientEventMap {
  connected: ConnectedEvent;
  disconnected: DisconnectedEvent;
  reconnecting: ReconnectingEvent;
  sessionExpired: SessionExpiredEvent;
  error: Error;
}

export class RaftClientEventEmitter extends EventEmitter {
  emit<K extends keyof RaftClientEventMap>(
    event: K,
    data: RaftClientEventMap[K]
  ): boolean {
    return super.emit(event, data);
  }

  on<K extends keyof RaftClientEventMap>(
    event: K,
    listener: (data: RaftClientEventMap[K]) => void
  ): this {
    return super.on(event, listener);
  }

  once<K extends keyof RaftClientEventMap>(
    event: K,
    listener: (data: RaftClientEventMap[K]) => void
  ): this {
    return super.once(event, listener);
  }

  off<K extends keyof RaftClientEventMap>(
    event: K,
    listener: (data: RaftClientEventMap[K]) => void
  ): this {
    return super.off(event, listener);
  }
}

export class RaftClient extends RaftClientEventEmitter {
  // Implementation
}
```

### Usage

```typescript
const client = new RaftClient(config);

// ✅ Type-safe with single source of truth
client.on('connected', (evt) => {
  console.log(evt.sessionId); // evt is ConnectedEvent
});

// ✅ Catches typos at compile time
client.on('conected', (evt) => {
  // ❌ Compile error: Argument of type '"conected"' is not assignable
});

// ⚠️ Still can bypass with 'as any'
client.emit('connected' as any, { wrong: 'data' } as any);
```

### Pros
- ✅ **Single source of truth** - event map in one place
- ✅ Better type safety than overloads
- ✅ Reusable pattern across multiple emitters
- ✅ Catches typos at compile time

### Cons
- ❌ Still no runtime protection against typos
- ❌ Extra inheritance layer (composition might be better)
- ❌ Must override all EventEmitter methods you want typed
- ❌ややmore complex than simple overloads

---

## Approach 3: Event Constants + Overloads ⭐ RECOMMENDED

### Code Example

```typescript
// events/eventNames.ts
export const ClientEvents = {
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  RECONNECTING: 'reconnecting',
  SESSION_EXPIRED: 'sessionExpired',
  ERROR: 'error',
} as const;

// Type derived from constants
export type ClientEventName = typeof ClientEvents[keyof typeof ClientEvents];

// events/eventTypes.ts
export interface ConnectedEvent { /* ... */ }
export interface DisconnectedEvent { /* ... */ }
// ... other event types

export interface ClientEventMap {
  [ClientEvents.CONNECTED]: ConnectedEvent;
  [ClientEvents.DISCONNECTED]: DisconnectedEvent;
  [ClientEvents.RECONNECTING]: ReconnectingEvent;
  [ClientEvents.SESSION_EXPIRED]: SessionExpiredEvent;
  [ClientEvents.ERROR]: Error;
}

// client.ts
export class RaftClient extends EventEmitter {
  // Overloads using constants
  on(event: typeof ClientEvents.CONNECTED, listener: (evt: ConnectedEvent) => void): this;
  on(event: typeof ClientEvents.DISCONNECTED, listener: (evt: DisconnectedEvent) => void): this;
  on(event: typeof ClientEvents.RECONNECTING, listener: (evt: ReconnectingEvent) => void): this;
  on(event: typeof ClientEvents.SESSION_EXPIRED, listener: (evt: SessionExpiredEvent) => void): this;
  on(event: typeof ClientEvents.ERROR, listener: (err: Error) => void): this;
  on(event: string, listener: (...args: any[]) => void): this {
    return super.on(event, listener);
  }

  emit(event: typeof ClientEvents.CONNECTED, evt: ConnectedEvent): boolean;
  emit(event: typeof ClientEvents.DISCONNECTED, evt: DisconnectedEvent): boolean;
  emit(event: typeof ClientEvents.RECONNECTING, evt: ReconnectingEvent): boolean;
  emit(event: typeof ClientEvents.SESSION_EXPIRED, evt: SessionExpiredEvent): boolean;
  emit(event: typeof ClientEvents.ERROR, err: Error): boolean;
  emit(event: string, ...args: any[]): boolean {
    return super.emit(event, ...args);
  }

  // Internal emit helper with runtime checks
  private emitClientEvent(evt: any): void {
    switch (evt.type) {
      case 'connected':
        this.emit(ClientEvents.CONNECTED, {
          sessionId: evt.sessionId,
          endpoint: evt.endpoint,
          timestamp: new Date(),
        });
        break;
      // ... other cases
    }
  }
}
```

### Usage

```typescript
import { ClientEvents } from './events/eventNames';

const client = new RaftClient(config);

// ✅ Type-safe with autocomplete
client.on(ClientEvents.CONNECTED, (evt) => {
  console.log(evt.sessionId); // evt is ConnectedEvent
});

// ✅ Can't use wrong constant
client.on(ClientEvents.CONECTED, (evt) => {
  // ❌ Compile error: Property 'CONECTED' does not exist
});

// ✅ Refactor-safe - rename constant updates everywhere
// ✅ Can search/grep for "ClientEvents.CONNECTED" easily
// ✅ Constants can be exported for external use
```

### Pros
- ✅ **Best type safety + runtime safety combo**
- ✅ Single source of truth (constants)
- ✅ Refactor-safe - rename constant propagates everywhere
- ✅ Easy to search codebase for event usage
- ✅ Can export constants for library consumers
- ✅ IDE autocomplete for both constants and types
- ✅ No external dependencies
- ✅ Prevents typos at both compile and runtime

### Cons
- ❌ Slightly more verbose than string literals
- ❌ Still need to maintain overload consistency
- ❌ Requires discipline to use constants everywhere

---

## Approach 4: typed-emitter Library

### Code Example

```typescript
// Install: npm install typed-emitter

import TypedEmitter from 'typed-emitter';

interface ClientEvents {
  connected: (evt: ConnectedEvent) => void;
  disconnected: (evt: DisconnectedEvent) => void;
  reconnecting: (evt: ReconnectingEvent) => void;
  sessionExpired: (evt: SessionExpiredEvent) => void;
  error: (err: Error) => void;
}

export class RaftClient extends (EventEmitter as new () => TypedEmitter<ClientEvents>) {
  // Implementation - all methods are typed automatically!
}
```

### Usage

```typescript
const client = new RaftClient(config);

// ✅ Fully type-safe
client.on('connected', (evt) => {
  console.log(evt.sessionId); // evt is ConnectedEvent
});

// ❌ Compile error on typos
client.on('conected', (evt) => {
  // Error: Argument of type '"conected"' is not assignable
});

// ✅ emit() is also fully typed
client.emit('connected', validConnectedEvent);
```

### Pros
- ✅ **Best type safety** - automatic for all methods
- ✅ Minimal boilerplate
- ✅ Single source of truth (interface)
- ✅ Catches typos at compile time
- ✅ Popular, well-maintained library

### Cons
- ❌ **External dependency** (adds ~10KB)
- ❌ Still string literals (no runtime protection)
- ❌ Weird TypeScript casting syntax
- ❌ Not idiomatic Node.js EventEmitter

---

## Approach 5: Custom Type-Safe EventEmitter

### Code Example

```typescript
// events/typedEmitter.ts
export type EventMap = Record<string, any>;

export class TypedEventEmitter<Events extends EventMap> {
  private listeners = new Map<keyof Events, Set<Function>>();

  on<K extends keyof Events>(event: K, listener: (data: Events[K]) => void): this {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event)!.add(listener);
    return this;
  }

  emit<K extends keyof Events>(event: K, data: Events[K]): void {
    const eventListeners = this.listeners.get(event);
    if (eventListeners) {
      eventListeners.forEach(listener => listener(data));
    }
  }

  off<K extends keyof Events>(event: K, listener: (data: Events[K]) => void): this {
    const eventListeners = this.listeners.get(event);
    if (eventListeners) {
      eventListeners.delete(listener);
    }
    return this;
  }

  removeAllListeners<K extends keyof Events>(event?: K): this {
    if (event) {
      this.listeners.delete(event);
    } else {
      this.listeners.clear();
    }
    return this;
  }
}

// client.ts
interface ClientEventMap {
  connected: ConnectedEvent;
  disconnected: DisconnectedEvent;
  reconnecting: ReconnectingEvent;
  sessionExpired: SessionExpiredEvent;
  error: Error;
}

export class RaftClient extends TypedEventEmitter<ClientEventMap> {
  // Fully typed automatically!
}
```

### Usage

```typescript
const client = new RaftClient(config);

// ✅ Fully type-safe
client.on('connected', (evt) => {
  console.log(evt.sessionId); // evt is ConnectedEvent
});

// ❌ Compile error on typos
client.on('conected', (evt) => {
  // Error: Argument of type '"conected"' is not assignable
});
```

### Pros
- ✅ **Perfect type safety**
- ✅ No external dependencies
- ✅ Full control over implementation
- ✅ Can add custom features (async handlers, errors, etc.)
- ✅ Catches typos at compile time

### Cons
- ❌ **Not Node.js EventEmitter** - breaks ecosystem compatibility
- ❌ Missing features (once, prependListener, eventNames, etc.)
- ❌ Need to implement/maintain everything yourself
- ❌ May have subtle bugs vs battle-tested EventEmitter

---

## Detailed Comparison

### Type Safety at Compile Time

| Approach | Event Name Typos | Event Data Types | Autocomplete |
|----------|------------------|------------------|--------------|
| 1. Overloads | ⚠️ Partial (fallback to string) | ✅ Yes | ✅ Yes |
| 2. Wrapper | ✅ Yes | ✅ Yes | ✅ Yes |
| 3. Constants + Overloads | ✅✅ Best | ✅ Yes | ✅✅ Best |
| 4. typed-emitter | ✅ Yes | ✅ Yes | ✅ Yes |
| 5. Custom | ✅✅ Perfect | ✅ Yes | ✅ Yes |

### Runtime Safety

| Approach | String Typo Protection | Type Coercion Protection |
|----------|------------------------|-------------------------|
| 1. Overloads | ❌ None | ❌ None |
| 2. Wrapper | ❌ None | ❌ None |
| 3. Constants + Overloads | ✅✅ Yes (use constants) | ⚠️ Partial |
| 4. typed-emitter | ❌ None | ❌ None |
| 5. Custom | ✅ Yes (runtime type checks) | ✅ Yes (can add validation) |

### Developer Experience

| Approach | Learning Curve | Boilerplate | Debugging | Refactoring |
|----------|----------------|-------------|-----------|-------------|
| 1. Overloads | ⭐⭐⭐⭐⭐ Easy | ⭐⭐⭐ Medium | ⭐⭐⭐⭐ Good | ⭐⭐⭐ OK |
| 2. Wrapper | ⭐⭐⭐⭐ Easy | ⭐⭐⭐⭐ Low | ⭐⭐⭐ OK | ⭐⭐⭐⭐ Good |
| 3. Constants + Overloads | ⭐⭐⭐⭐ Easy | ⭐⭐⭐ Medium | ⭐⭐⭐⭐⭐ Best | ⭐⭐⭐⭐⭐ Best |
| 4. typed-emitter | ⭐⭐⭐⭐⭐ Easy | ⭐⭐⭐⭐⭐ Minimal | ⭐⭐⭐⭐ Good | ⭐⭐⭐⭐ Good |
| 5. Custom | ⭐⭐ Hard | ⭐ High | ⭐⭐ Harder | ⭐⭐⭐⭐ Good |

### Maintenance & Ecosystem

| Approach | External Dependencies | Node.js Compat | Maintenance Burden |
|----------|----------------------|----------------|-------------------|
| 1. Overloads | ✅ None | ✅ Perfect | ⭐⭐⭐ Medium |
| 2. Wrapper | ✅ None | ✅ Perfect | ⭐⭐ Medium-High |
| 3. Constants + Overloads | ✅ None | ✅ Perfect | ⭐⭐⭐⭐ Low |
| 4. typed-emitter | ❌ 1 dependency | ✅ Good | ⭐⭐⭐⭐⭐ Very Low |
| 5. Custom | ✅ None | ❌ Incompatible | ⭐ High |

---

## Recommendation: Approach 3 (Constants + Overloads)

### Why?

1. **Best Balance**: Type safety + runtime safety + zero dependencies
2. **Refactor-Safe**: Renaming a constant updates all usages
3. **Searchable**: Easy to find all usages with grep/search
4. **Library-Friendly**: Can export constants for consumers
5. **Idiomatic**: Standard Node.js EventEmitter with TypeScript sugar
6. **No Breaking Changes**: Drop-in replacement for current approach

### Implementation Plan

1. Create `events/eventNames.ts` with constants
2. Update `client.ts` overloads to use constants
3. Update internal emit calls to use constants
4. Export constants in public API for library consumers
5. Add ESLint rule to enforce constant usage
6. Delete unused `eventEmitter.ts`

### Example ESLint Rule

```javascript
// .eslintrc.js
{
  rules: {
    "no-restricted-syntax": [
      "error",
      {
        "selector": "CallExpression[callee.property.name=/^(on|once|emit|off)$/] > Literal[value=/^(connected|disconnected|reconnecting|sessionExpired)$/]",
        "message": "Use ClientEvents constants instead of string literals for event names"
      }
    ]
  }
}
```

---

## Alternative: Approach 4 (typed-emitter) if External Deps OK

If you're okay with one small dependency, `typed-emitter` is excellent:
- Minimal code
- Maximum type safety
- Well-maintained
- Popular in TypeScript community

But for a low-level library like this, I'd still choose **Approach 3** for zero dependencies.
