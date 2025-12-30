// Custom error classes for the Raft client
// Extends Error with type-safe error hierarchy

import { RequestId, CorrelationId, SessionId } from './types';

/**
 * Base error class for all Raft client errors
 */
export class RaftClientError extends Error {
  public readonly name = 'RaftClientError';

  constructor(message: string) {
    super(message);
    // Maintain proper prototype chain for instanceof checks
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

/**
 * Validation error - thrown synchronously for invalid input
 */
export class ValidationError extends RaftClientError {
  public readonly name = 'ValidationError';

  constructor(message: string) {
    super(message);
  }
}

/**
 * Timeout error - promise rejection for request/query timeout
 */
export class TimeoutError extends RaftClientError {
  public readonly name = 'TimeoutError';
  public readonly requestId?: RequestId;
  public readonly correlationId?: CorrelationId;

  constructor(message: string, id?: RequestId | CorrelationId) {
    super(message);

    // Distinguish between RequestId (bigint) and CorrelationId (string)
    if (id !== undefined) {
      if (typeof id === 'bigint') {
        this.requestId = id as RequestId;
      } else if (typeof id === 'string') {
        this.correlationId = id as CorrelationId;
      }
    }
  }
}

/**
 * Connection error - transport-level failure
 */
export class ConnectionError extends RaftClientError {
  public readonly name = 'ConnectionError';
  public readonly endpoint?: string;
  public readonly cause?: Error;

  constructor(message: string, endpoint?: string, cause?: Error) {
    super(message);
    this.endpoint = endpoint;
    this.cause = cause;
  }
}

/**
 * Session expired error - session terminated by server (terminal)
 */
export class SessionExpiredError extends RaftClientError {
  public readonly name = 'SessionExpiredError';
  public readonly sessionId: SessionId;

  constructor(sessionId: SessionId) {
    super(`Session expired: ${sessionId}`);
    this.sessionId = sessionId;
  }
}

/**
 * Protocol error - wire protocol violation
 */
export class ProtocolError extends RaftClientError {
  public readonly name = 'ProtocolError';

  constructor(message: string) {
    super(`Protocol error: ${message}`);
  }
}

