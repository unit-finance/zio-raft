/**
 * Unit tests for output formatting
 */

import { describe, it, expect } from 'vitest';
import { formatSuccess, formatError, formatNotification, getExitCode } from '../../src/formatting.js';
import { ValidationError, OperationError, ProtocolError } from '../../src/errors.js';
import { WatchNotification } from '../../src/types.js';

describe('Output Formatting', () => {
  // TC-031: Success message formats correctly
  it('should format success message as-is', () => {
    expect(formatSuccess('OK')).toBe('OK');
    expect(formatSuccess('Operation completed')).toBe('Operation completed');
  });

  // TC-032: Validation error formats with details
  it('should format validation error with actual/expected values', () => {
    const error = new ValidationError('key', 'Key exceeds maximum size', 300, 256);
    const formatted = formatError(error);

    expect(formatted).toContain('Error:');
    expect(formatted).toContain('actual: 300');
    expect(formatted).toContain('expected: 256');
  });

  it('should format validation error without actual/expected', () => {
    const error = new ValidationError('key', 'Key cannot be empty');
    const formatted = formatError(error);

    expect(formatted).toBe('Error: Key cannot be empty');
  });

  // TC-033: Connection error formats clearly
  it('should format connection error clearly', () => {
    const error = new OperationError('connect', 'connection', 'Connection failed');
    const formatted = formatError(error);

    expect(formatted).toBe('Error: Could not connect to cluster (timeout after 5s)');
  });

  it('should format timeout error clearly', () => {
    const error = new OperationError('set', 'timeout', 'Operation timeout');
    const formatted = formatError(error);

    expect(formatted).toBe('Error: Operation timed out after 5s');
  });

  it('should format other operation errors', () => {
    const error = new OperationError('set', 'server_error', 'Server rejected command');
    const formatted = formatError(error);

    expect(formatted).toBe('Error: Server rejected command');
  });

  it('should format protocol error', () => {
    const error = new ProtocolError('Invalid discriminator');
    const formatted = formatError(error);

    expect(formatted).toContain('Error: Protocol error');
    expect(formatted).toContain('Invalid discriminator');
  });

  // TC-034: Generic error formats safely
  it('should format generic Error safely', () => {
    const error = new Error('Something went wrong');
    const formatted = formatError(error);

    expect(formatted).toBe('Error: Something went wrong');
  });

  it('should format non-Error values safely', () => {
    const formatted = formatError('string error');
    expect(formatted).toBe('Error: string error');
  });

  it('should format null/undefined safely', () => {
    expect(formatError(null)).toBe('Error: null');
    expect(formatError(undefined)).toBe('Error: undefined');
  });
});

describe('Notification Formatting', () => {
  // TC-035: Notification formats with all fields
  it('should format notification with all fields', () => {
    const notification: WatchNotification = {
      timestamp: new Date('2026-01-13T10:15:23.456Z'),
      sequenceNumber: 42n,
      key: 'mykey',
      value: 'myvalue',
    };

    const formatted = formatNotification(notification);

    expect(formatted).toBe('[2026-01-13T10:15:23.456Z] seq=42 key=mykey value=myvalue');
  });

  // TC-036: Timestamp formats as ISO 8601
  it('should format timestamp as ISO 8601', () => {
    const now = new Date();
    const notification: WatchNotification = {
      timestamp: now,
      sequenceNumber: 1n,
      key: 'key',
      value: 'value',
    };

    const formatted = formatNotification(notification);
    const isoTimestamp = now.toISOString();

    expect(formatted).toContain(`[${isoTimestamp}]`);
    // Verify ISO 8601 format pattern
    expect(isoTimestamp).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  });

  // TC-037: Long values are not truncated
  it('should not truncate long values', () => {
    const longValue = 'a'.repeat(1000);
    const notification: WatchNotification = {
      timestamp: new Date('2026-01-13T10:15:23.456Z'),
      sequenceNumber: 1n,
      key: 'key',
      value: longValue,
    };

    const formatted = formatNotification(notification);

    expect(formatted).toContain(longValue);
    expect(formatted.length).toBeGreaterThan(1000);
  });

  it('should handle large sequence numbers correctly', () => {
    const notification: WatchNotification = {
      timestamp: new Date('2026-01-13T10:15:23.456Z'),
      sequenceNumber: 9007199254740991n, // MAX_SAFE_INTEGER as bigint
      key: 'key',
      value: 'value',
    };

    const formatted = formatNotification(notification);

    expect(formatted).toContain('seq=9007199254740991');
  });

  it('should handle unicode keys and values', () => {
    const notification: WatchNotification = {
      timestamp: new Date('2026-01-13T10:15:23.456Z'),
      sequenceNumber: 1n,
      key: '键🔑',
      value: '值🎉',
    };

    const formatted = formatNotification(notification);

    expect(formatted).toContain('key=键🔑');
    expect(formatted).toContain('value=值🎉');
  });
});

describe('Exit Code Mapping', () => {
  it('should return 1 for ValidationError', () => {
    const error = new ValidationError('key', 'Invalid key');
    expect(getExitCode(error)).toBe(1);
  });

  it('should return 2 for connection errors', () => {
    const error = new OperationError('connect', 'connection', 'Connection failed');
    expect(getExitCode(error)).toBe(2);
  });

  it('should return 2 for timeout errors', () => {
    const error = new OperationError('set', 'timeout', 'Timeout');
    expect(getExitCode(error)).toBe(2);
  });

  it('should return 3 for operational errors', () => {
    const error = new OperationError('set', 'server_error', 'Server error');
    expect(getExitCode(error)).toBe(3);
  });

  it('should return 3 for protocol errors', () => {
    const error = new ProtocolError('Protocol error');
    expect(getExitCode(error)).toBe(3);
  });

  it('should return 3 for unknown errors (operational, not validation)', () => {
    // Unknown errors are operational failures, not user input errors
    expect(getExitCode(new Error('Unknown'))).toBe(3);
    expect(getExitCode('string error')).toBe(3);
    expect(getExitCode(null)).toBe(3);
  });
});
