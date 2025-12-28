# Data Model: TypeScript Client Library

**Date**: 2025-12-24  
**Feature**: TypeScript Client for ZIO Raft

## Overview
This document defines all data structures, types, and entities for the TypeScript client library, matching the protocol defined in the Scala client-server-protocol module.

---

## 1. Core Type Aliases (Branded Types)

TypeScript branded types provide newtype-like safety similar to Scala's Newtype pattern:

```typescript
// Session identification
export type SessionId = string & { readonly __brand: 'SessionId' };
export const SessionId = {
  fromString: (value: string): SessionId => {
    if (!value) throw new Error('SessionId cannot be empty');
    return value as SessionId;
  },
  unwrap: (id: SessionId): string => id as string
};

// Request identification (commands)
export type RequestId = bigint & { readonly __brand: 'RequestId' };
export const RequestId = {
  zero: 0n as RequestId,
  fromBigInt: (value: bigint): RequestId => {
    if (value < 0n) throw new Error('RequestId must be non-negative');
    return value as RequestId;
  },
  next: (id: RequestId): RequestId => (id + 1n) as RequestId,
  unwrap: (id: RequestId): bigint => id as bigint
};

// Cluster member identification
export type MemberId = string & { readonly __brand: 'MemberId' };
export const MemberId = {
  fromString: (value: string): MemberId => value as MemberId,
  unwrap: (id: MemberId): string => id as string
};

// Nonce for request/response correlation
export type Nonce = bigint & { readonly __brand: 'Nonce' };
export const Nonce = {
  generate: (): Nonce => {
    const value = BigInt(Math.floor(Math.random() * Number.MAX_SAFE_INTEGER) + 1);
    return value as Nonce;
  },
  fromBigInt: (value: bigint): Nonce => {
    if (value === 0n) throw new Error('Nonce cannot be zero');
    return value as Nonce;
  },
  unwrap: (nonce: Nonce): bigint => nonce as bigint
};

// Correlation ID for queries
export type CorrelationId = string & { readonly __brand: 'CorrelationId' };
export const CorrelationId = {
  generate: (): CorrelationId => {
    const uuid = crypto.randomUUID();
    return uuid as CorrelationId;
  },
  fromString: (value: string): CorrelationId => {
    if (!value) throw new Error('CorrelationId cannot be empty');
    return value as CorrelationId;
  },
  unwrap: (id: CorrelationId): string => id as string
};
```

---

## 2. Protocol Messages

### 2.1 Client Messages (Client → Server)

```typescript
// Base type for all client messages
export type ClientMessage =
  | CreateSession
  | ContinueSession
  | KeepAlive
  | ClientRequest
  | Query
  | ServerRequestAck
  | CloseSession
  | ConnectionClosed;

// Create a new durable session
export interface CreateSession {
  readonly type: 'CreateSession';
  readonly capabilities: Map<string, string>;
  readonly nonce: Nonce;
}

// Resume an existing session after reconnection
export interface ContinueSession {
  readonly type: 'ContinueSession';
  readonly sessionId: SessionId;
  readonly nonce: Nonce;
}

// Heartbeat to maintain session liveness
export interface KeepAlive {
  readonly type: 'KeepAlive';
  readonly timestamp: Date;  // Instant equivalent
}

// Generic request for read/write operations
export interface ClientRequest {
  readonly type: 'ClientRequest';
  readonly requestId: RequestId;
  readonly lowestPendingRequestId: RequestId;
  readonly payload: Buffer;
  readonly createdAt: Date;
}

// Read-only query
export interface Query {
  readonly type: 'Query';
  readonly correlationId: CorrelationId;
  readonly payload: Buffer;
  readonly createdAt: Date;
}

// Acknowledge server-initiated request
export interface ServerRequestAck {
  readonly type: 'ServerRequestAck';
  readonly requestId: RequestId;
}

// Explicit session termination
export interface CloseSession {
  readonly type: 'CloseSession';
  readonly reason: CloseReason;
}

// Connection closed notification
export interface ConnectionClosed {
  readonly type: 'ConnectionClosed';
}

// Close reason enum
export type CloseReason = 'ClientShutdown';
```

### 2.2 Server Messages (Server → Client)

```typescript
// Base type for all server messages
export type ServerMessage =
  | SessionCreated
  | SessionContinued
  | SessionRejected
  | SessionClosed
  | KeepAliveResponse
  | ClientResponse
  | QueryResponse
  | ServerRequest
  | RequestError;

// Successful session creation
export interface SessionCreated {
  readonly type: 'SessionCreated';
  readonly sessionId: SessionId;
  readonly nonce: Nonce;
}

// Successful session resumption
export interface SessionContinued {
  readonly type: 'SessionContinued';
  readonly nonce: Nonce;
}

// Session rejected
export interface SessionRejected {
  readonly type: 'SessionRejected';
  readonly reason: RejectionReason;
  readonly nonce: Nonce;
  readonly leaderId?: MemberId;
}

// Server-initiated session termination
export interface SessionClosed {
  readonly type: 'SessionClosed';
  readonly reason: SessionCloseReason;
  readonly leaderId?: MemberId;
}

// Heartbeat acknowledgment
export interface KeepAliveResponse {
  readonly type: 'KeepAliveResponse';
  readonly timestamp: Date;
}

// Request execution result
export interface ClientResponse {
  readonly type: 'ClientResponse';
  readonly requestId: RequestId;
  readonly result: Buffer;
}

// Query result
export interface QueryResponse {
  readonly type: 'QueryResponse';
  readonly correlationId: CorrelationId;
  readonly result: Buffer;
}

// Server-initiated work dispatch
export interface ServerRequest {
  readonly type: 'ServerRequest';
  readonly requestId: RequestId;
  readonly payload: Buffer;
  readonly createdAt: Date;
}

// Request error notification
export interface RequestError {
  readonly type: 'RequestError';
  readonly requestId: RequestId;
  readonly reason: RequestErrorReason;
}

// Rejection reason enum
export type RejectionReason =
  | 'NotLeader'
  | 'SessionExpired'
  | 'InvalidCapabilities'
  | 'Other';

// Session close reason enum
export type SessionCloseReason =
  | 'Shutdown'
  | 'NotLeaderAnymore'
  | 'SessionError'
  | 'ConnectionClosed'
  | 'SessionExpired';

// Request error reason enum
export type RequestErrorReason = 'ResponseEvicted';
```

---

## 3. Client Configuration

```typescript
export interface ClientConfig {
  readonly clusterMembers: Map<MemberId, string>;  // memberId -> ZMQ address
  readonly capabilities: Map<string, string>;       // user-provided capabilities
  readonly connectionTimeout: number;               // milliseconds, default 5000
  readonly keepAliveInterval: number;               // milliseconds, default 30000
  readonly requestTimeout: number;                  // milliseconds, default 10000
}

export const ClientConfig = {
  DEFAULT_CONNECTION_TIMEOUT: 5000,
  DEFAULT_KEEP_ALIVE_INTERVAL: 30000,
  DEFAULT_REQUEST_TIMEOUT: 10000,
  
  validate: (config: ClientConfig): void => {
    if (config.clusterMembers.size === 0) {
      throw new Error('clusterMembers cannot be empty');
    }
    if (config.capabilities.size === 0) {
      throw new Error('capabilities cannot be empty');
    }
    if (config.connectionTimeout <= 0) {
      throw new Error('connectionTimeout must be positive');
    }
    if (config.keepAliveInterval <= 0) {
      throw new Error('keepAliveInterval must be positive');
    }
    if (config.requestTimeout <= 0) {
      throw new Error('requestTimeout must be positive');
    }
  }
};
```

---

## 4. Client State Machine

```typescript
// State machine states as discriminated union
export type ClientState =
  | DisconnectedState
  | ConnectingNewSessionState
  | ConnectingExistingSessionState
  | ConnectedState;

// Disconnected: Initial state
export interface DisconnectedState {
  readonly state: 'Disconnected';
  readonly config: ClientConfig;
}

// Connecting to create NEW session
export interface ConnectingNewSessionState {
  readonly state: 'ConnectingNewSession';
  readonly config: ClientConfig;
  readonly capabilities: Map<string, string>;
  readonly nonce: Nonce;
  readonly currentMemberId: MemberId;
  readonly createdAt: Date;
  readonly nextRequestId: RequestIdRef;
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
}

// Connecting to resume EXISTING session
export interface ConnectingExistingSessionState {
  readonly state: 'ConnectingExistingSession';
  readonly config: ClientConfig;
  readonly sessionId: SessionId;
  readonly capabilities: Map<string, string>;
  readonly nonce: Nonce;
  readonly currentMemberId: MemberId;
  readonly createdAt: Date;
  readonly serverRequestTracker: ServerRequestTracker;
  readonly nextRequestId: RequestIdRef;
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
}

// Connected: Active session
export interface ConnectedState {
  readonly state: 'Connected';
  readonly config: ClientConfig;
  readonly sessionId: SessionId;
  readonly capabilities: Map<string, string>;
  readonly createdAt: Date;
  readonly serverRequestTracker: ServerRequestTracker;
  readonly nextRequestId: RequestIdRef;
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
  readonly currentMemberId: MemberId;
}
```

---

## 5. Pending Request Tracking

```typescript
// Pending requests (commands) tracker
export interface PendingRequests {
  readonly requests: Map<RequestId, PendingRequestData>;
}

export interface PendingRequestData {
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
  readonly createdAt: Date;
  readonly lastSentAt: Date;
}

// Pending queries tracker
export interface PendingQueries {
  readonly queries: Map<CorrelationId, PendingQueryData>;
}

export interface PendingQueryData {
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
  readonly createdAt: Date;
  readonly lastSentAt: Date;
}

// Request ID reference (mutable counter)
export interface RequestIdRef {
  current: RequestId;
  next(): RequestId;
}
```

---

## 6. Server Request Tracking

```typescript
// Tracks server-initiated requests for deduplication
export interface ServerRequestTracker {
  readonly lastAcknowledgedRequestId: RequestId;
}

export type ServerRequestResult =
  | { type: 'Process' }           // New request, should process
  | { type: 'OldRequest' }        // Already processed, re-ack
  | { type: 'OutOfOrder' };       // Gap detected, drop
```

---

## 7. Event Types

```typescript
// Events emitted by client for observability
export type ClientEvent =
  | StateChangeEvent
  | ConnectionAttemptEvent
  | ConnectionSuccessEvent
  | ConnectionFailureEvent
  | MessageReceivedEvent
  | MessageSentEvent
  | RequestTimeoutEvent
  | QueryTimeoutEvent
  | SessionExpiredEvent
  | ServerRequestReceivedEvent;

export interface StateChangeEvent {
  readonly type: 'stateChange';
  readonly oldState: ClientState['state'];
  readonly newState: ClientState['state'];
}

export interface ConnectionAttemptEvent {
  readonly type: 'connectionAttempt';
  readonly memberId: MemberId;
  readonly address: string;
}

export interface ConnectionSuccessEvent {
  readonly type: 'connectionSuccess';
  readonly memberId: MemberId;
}

export interface ConnectionFailureEvent {
  readonly type: 'connectionFailure';
  readonly memberId: MemberId;
  readonly error: Error;
}

export interface MessageReceivedEvent {
  readonly type: 'messageReceived';
  readonly message: ServerMessage;
}

export interface MessageSentEvent {
  readonly type: 'messageSent';
  readonly message: ClientMessage;
}

export interface RequestTimeoutEvent {
  readonly type: 'requestTimeout';
  readonly requestId: RequestId;
}

export interface QueryTimeoutEvent {
  readonly type: 'queryTimeout';
  readonly correlationId: CorrelationId;
}

export interface SessionExpiredEvent {
  readonly type: 'sessionExpired';
}

export interface ServerRequestReceivedEvent {
  readonly type: 'serverRequestReceived';
  readonly request: ServerRequest;
}
```

---

## 8. Action Types (Internal)

```typescript
// Internal actions from user API calls
export type ClientAction =
  | ConnectAction
  | DisconnectAction
  | SubmitCommandAction
  | SubmitQueryAction;

export interface ConnectAction {
  readonly type: 'Connect';
}

export interface DisconnectAction {
  readonly type: 'Disconnect';
}

export interface SubmitCommandAction {
  readonly type: 'SubmitCommand';
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
}

export interface SubmitQueryAction {
  readonly type: 'SubmitQuery';
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
}
```

---

## 9. Stream Events (Internal)

```typescript
// Unified event stream events (internal to state machine)
export type StreamEvent =
  | ActionEvent
  | ServerMessageEvent
  | KeepAliveTickEvent
  | TimeoutCheckEvent;

export interface ActionEvent {
  readonly type: 'Action';
  readonly action: ClientAction;
}

export interface ServerMessageEvent {
  readonly type: 'ServerMsg';
  readonly message: ServerMessage;
}

export interface KeepAliveTickEvent {
  readonly type: 'KeepAliveTick';
}

export interface TimeoutCheckEvent {
  readonly type: 'TimeoutCheck';
}
```

---

## 10. Protocol Constants

```typescript
export const PROTOCOL_SIGNATURE = Buffer.from([0x7a, 0x72, 0x61, 0x66, 0x74]); // "zraft"
export const PROTOCOL_VERSION: number = 1;

// Client message type discriminators
export enum ClientMessageType {
  CreateSession = 1,
  ContinueSession = 2,
  KeepAlive = 3,
  ClientRequest = 4,
  ServerRequestAck = 5,
  CloseSession = 6,
  ConnectionClosed = 7,
  Query = 8
}

// Server message type discriminators
export enum ServerMessageType {
  SessionCreated = 1,
  SessionContinued = 2,
  SessionRejected = 3,
  SessionClosed = 4,
  KeepAliveResponse = 5,
  ClientResponse = 6,
  ServerRequest = 7,
  RequestError = 8,
  QueryResponse = 9
}

// Reason enum discriminators
export enum RejectionReasonCode {
  NotLeader = 1,
  SessionExpired = 2,
  InvalidCapabilities = 3,
  Other = 4
}

export enum SessionCloseReasonCode {
  Shutdown = 1,
  NotLeaderAnymore = 2,
  SessionError = 3,
  ConnectionClosed = 4,
  SessionExpired = 5
}

export enum CloseReasonCode {
  ClientShutdown = 1
}

export enum RequestErrorReasonCode {
  ResponseEvicted = 1
}
```

---

## Summary

This data model provides:
- **Type Safety**: Branded types prevent mixing incompatible IDs
- **Protocol Compatibility**: Message structures exactly match Scala definitions
- **State Machine**: Discriminated unions for states enable exhaustive pattern matching
- **Event System**: Rich event types for observability without built-in logging
- **Immutability**: All interfaces readonly for functional state management

All structures are designed to be:
1. Serializable for wire protocol compatibility
2. Type-safe for compile-time correctness
3. Immutable for predictable state transitions
4. Observable for application-level logging and monitoring
