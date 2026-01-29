// Custom error classes for the Raft client
// Extends Error with type-safe error hierarchy

import { RequestId, CorrelationId, SessionId } from './types';

/**
 * Base error class for all Raft client errors
 */
export class RaftClientError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'RaftClientError';
    // Maintain proper prototype chain for instanceof checks
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

/**
 * Validation error - thrown synchronously for invalid input
 */
export class ValidationError extends RaftClientError {
  constructor(message: string) {
    super(message);
    this.name = 'ValidationError';
  }
}

/**
 * Timeout error - promise rejection for request/query timeout
 */
export class TimeoutError extends RaftClientError {
  public readonly requestId?: RequestId;
  public readonly correlationId?: CorrelationId;

  constructor(message: string, id?: RequestId | CorrelationId) {
    super(message);
    this.name = 'TimeoutError';

    // Distinguish between RequestId (bigint) and CorrelationId (string)
    if (id !== undefined) {
      if (typeof id === 'bigint') {
        this.requestId = id;
      } else if (typeof id === 'string') {
        this.correlationId = id;
      }
    }
  }
}

/**
 * Connection error - transport-level failure
 */
export class ConnectionError extends RaftClientError {
  public readonly endpoint?: string;
  public readonly cause?: Error;

  constructor(message: string, endpoint?: string, cause?: Error) {
    super(message);
    this.name = 'ConnectionError';
    this.endpoint = endpoint;
    this.cause = cause;
  }
}

/**
 * Session expired error - session terminated by server (terminal)
 */
export class SessionExpiredError extends RaftClientError {
  public readonly sessionId: SessionId;

  constructor(sessionId: SessionId) {
    super(`Session expired: ${sessionId}`);
    this.name = 'SessionExpiredError';
    this.sessionId = sessionId;
  }
}

/**
 * Protocol error - wire protocol violation
 */
export class ProtocolError extends RaftClientError {
  constructor(message: string) {
    super(`Protocol error: ${message}`);
    this.name = 'ProtocolError';
  }
}
