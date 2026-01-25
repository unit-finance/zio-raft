/**
 * Output formatting utilities
 */

import { ValidationError, OperationError, ProtocolError } from './errors.js';
import { WatchNotification } from './types.js';

/**
 * Format a success message
 */
export function formatSuccess(message: string): string {
  return message;
}

/**
 * Format an error for display
 */
export function formatError(error: unknown): string {
  if (error instanceof ValidationError) {
    if (error.actual !== undefined && error.expected !== undefined) {
      return `Error: ${error.message} (actual: ${error.actual}, expected: ${error.expected})`;
    }
    return `Error: ${error.message}`;
  }

  if (error instanceof OperationError) {
    if (error.reason === 'timeout') {
      return `Error: Operation timed out after 5s`;
    }
    if (error.reason === 'connection') {
      return `Error: Could not connect to cluster (timeout after 5s)`;
    }
    return `Error: ${error.message}`;
  }

  if (error instanceof ProtocolError) {
    return `Error: Protocol error - ${error.message}`;
  }

  if (error instanceof Error) {
    return `Error: ${error.message}`;
  }

  return `Error: ${String(error)}`;
}

/**
 * Format a watch notification for display
 * Format: [ISO timestamp] seq=N key=K value=V
 */
export function formatNotification(notification: WatchNotification): string {
  const timestamp = notification.timestamp.toISOString();
  const seq = notification.sequenceNumber.toString();
  const key = notification.key;
  const value = notification.value;

  return `[${timestamp}] seq=${seq} key=${key} value=${value}`;
}

/**
 * Get appropriate exit code for error
 */
/**
 * Get appropriate exit code for error
 *
 * Exit codes follow Unix conventions:
 * - 0: Success (not handled here)
 * - 1: Validation error (bad user input)
 * - 2: Connection/timeout error (network issues)
 * - 3: Operational error (server errors, protocol errors, unexpected failures)
 * - 130: SIGINT (handled by watch command directly)
 */
export function getExitCode(error: unknown): number {
  // Exit 1: User validation errors only (bad input)
  if (error instanceof ValidationError) {
    return 1;
  }

  // Exit 2: Network-related errors (connection, timeout)
  if (error instanceof OperationError) {
    if (error.reason === 'connection' || error.reason === 'timeout') {
      return 2;
    }
    return 3; // Other operational errors (server_error, protocol)
  }

  // Exit 3: Protocol errors
  if (error instanceof ProtocolError) {
    return 3;
  }

  // Exit 3: Unknown/unexpected errors are operational, not validation
  // Rationale: Exit 1 is reserved for user input mistakes. Unexpected errors
  // (bugs, unhandled exceptions, etc.) are operational failures.
  return 3;
}
