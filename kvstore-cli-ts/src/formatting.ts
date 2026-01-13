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
export function getExitCode(error: unknown): number {
  if (error instanceof ValidationError) {
    return 1; // Validation error
  }

  if (error instanceof OperationError) {
    if (error.reason === 'connection' || error.reason === 'timeout') {
      return 2; // Connection/timeout error
    }
    return 3; // Operational error
  }

  if (error instanceof ProtocolError) {
    return 3; // Protocol error
  }

  return 1; // Unknown error
}
