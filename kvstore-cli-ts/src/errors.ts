/**
 * Error classes for KVStore CLI
 */

/**
 * Validation error - for client-side validation failures
 */
export class ValidationError extends Error {
  public readonly name = 'ValidationError';

  constructor(
    public readonly field: 'key' | 'value' | 'endpoints',
    message: string,
    public readonly actual?: string | number,
    public readonly expected?: string | number
  ) {
    super(message);
  }
}

/**
 * Operation error - for runtime errors during command execution
 */
export class OperationError extends Error {
  public readonly name = 'OperationError';

  constructor(
    public readonly operation: 'set' | 'get' | 'watch' | 'connect',
    public readonly reason: 'timeout' | 'connection' | 'protocol' | 'server_error',
    message: string,
    public readonly details?: unknown
  ) {
    super(message);
  }
}

/**
 * Protocol error - for binary protocol encoding/decoding failures
 */
export class ProtocolError extends Error {
  public readonly name = 'ProtocolError';

  constructor(
    message: string,
    public readonly details?: unknown
  ) {
    super(message);
  }
}
