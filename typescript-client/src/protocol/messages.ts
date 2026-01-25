// Protocol message type definitions for client-server communication
// These match the Scala protocol definitions exactly

import { SessionId, RequestId, MemberId, Nonce, CorrelationId } from '../types';

// ============================================================================
// Client Messages (Client → Server)
// ============================================================================

/**
 * Base type for all client messages (discriminated union)
 */
export type ClientMessage =
  | CreateSession
  | ContinueSession
  | KeepAlive
  | ClientRequest
  | Query
  | ServerRequestAck
  | CloseSession
  | ConnectionClosed;

/**
 * Create a new durable session
 */
export interface CreateSession {
  readonly type: 'CreateSession';
  readonly capabilities: Map<string, string>;
  readonly nonce: Nonce;
}

/**
 * Resume an existing session after reconnection
 */
export interface ContinueSession {
  readonly type: 'ContinueSession';
  readonly sessionId: SessionId;
  readonly nonce: Nonce;
}

/**
 * Heartbeat to maintain session liveness
 */
export interface KeepAlive {
  readonly type: 'KeepAlive';
  readonly timestamp: Date;
}

/**
 * Generic request for read/write operations
 */
export interface ClientRequest {
  readonly type: 'ClientRequest';
  readonly requestId: RequestId;
  readonly lowestPendingRequestId: RequestId;
  readonly payload: Buffer;
  readonly createdAt: Date;
}

/**
 * Read-only query
 */
export interface Query {
  readonly type: 'Query';
  readonly correlationId: CorrelationId;
  readonly payload: Buffer;
  readonly createdAt: Date;
}

/**
 * Acknowledge server-initiated request
 */
export interface ServerRequestAck {
  readonly type: 'ServerRequestAck';
  readonly requestId: RequestId;
}

/**
 * Explicit session termination
 */
export interface CloseSession {
  readonly type: 'CloseSession';
  readonly reason: CloseReason;
}

/**
 * Connection closed notification
 */
export interface ConnectionClosed {
  readonly type: 'ConnectionClosed';
}

/**
 * Close reason (client-initiated)
 */
export type CloseReason = 'ClientShutdown';

// ============================================================================
// Server Messages (Server → Client)
// ============================================================================

/**
 * Base type for all server messages (discriminated union)
 */
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

/**
 * Successful session creation
 */
export interface SessionCreated {
  readonly type: 'SessionCreated';
  readonly sessionId: SessionId;
  readonly nonce: Nonce;
}

/**
 * Successful session resumption
 */
export interface SessionContinued {
  readonly type: 'SessionContinued';
  readonly nonce: Nonce;
}

/**
 * Session rejected
 */
export interface SessionRejected {
  readonly type: 'SessionRejected';
  readonly reason: RejectionReason;
  readonly nonce: Nonce;
  readonly leaderId?: MemberId;
}

/**
 * Server-initiated session termination
 */
export interface SessionClosed {
  readonly type: 'SessionClosed';
  readonly reason: SessionCloseReason;
  readonly leaderId?: MemberId;
}

/**
 * Heartbeat acknowledgment
 */
export interface KeepAliveResponse {
  readonly type: 'KeepAliveResponse';
  readonly timestamp: Date;
}

/**
 * Request execution result
 */
export interface ClientResponse {
  readonly type: 'ClientResponse';
  readonly requestId: RequestId;
  readonly result: Buffer;
}

/**
 * Query result
 */
export interface QueryResponse {
  readonly type: 'QueryResponse';
  readonly correlationId: CorrelationId;
  readonly result: Buffer;
}

/**
 * Server-initiated work dispatch
 */
export interface ServerRequest {
  readonly type: 'ServerRequest';
  readonly requestId: RequestId;
  readonly payload: Buffer;
  readonly createdAt: Date;
}

/**
 * Request error notification
 */
export interface RequestError {
  readonly type: 'RequestError';
  readonly requestId: RequestId;
  readonly reason: RequestErrorReason;
}

// ============================================================================
// Reason Types
// ============================================================================

/**
 * Rejection reason (discriminated union)
 */
export type RejectionReason = 'NotLeader' | 'SessionExpired' | 'InvalidCapabilities' | 'Other';

/**
 * Session close reason (discriminated union)
 */
export type SessionCloseReason =
  | 'Shutdown'
  | 'NotLeaderAnymore'
  | 'SessionError'
  | 'ConnectionClosed'
  | 'SessionExpired';

/**
 * Request error reason (discriminated union)
 */
export type RequestErrorReason = 'ResponseEvicted';
