/**
 * Input validation functions
 */

import { ValidationError } from './errors.js';
import { EndpointConfig } from './types.js';

/**
 * Validates a key according to KVStore constraints
 * @throws ValidationError if key is invalid
 */
export function validateKey(key: string): void {
  if (key.length === 0) {
    throw new ValidationError('key', 'Key cannot be empty');
  }

  const buffer = Buffer.from(key, 'utf8');
  
  if (buffer.length > 256) {
    throw new ValidationError(
      'key',
      `Key exceeds maximum size`,
      buffer.length,
      256
    );
  }

  // Verify valid UTF-8 round-trip
  if (buffer.toString('utf8') !== key) {
    throw new ValidationError('key', 'Key contains invalid UTF-8');
  }
}

/**
 * Validates a value according to KVStore constraints
 * @throws ValidationError if value is invalid
 */
export function validateValue(value: string): void {
  if (value.length === 0) {
    throw new ValidationError('value', 'Value cannot be empty');
  }

  const buffer = Buffer.from(value, 'utf8');
  const MAX_SIZE = 1024 * 1024; // 1MB

  if (buffer.length > MAX_SIZE) {
    throw new ValidationError(
      'value',
      `Value exceeds maximum size`,
      buffer.length,
      MAX_SIZE
    );
  }

  // Verify valid UTF-8 round-trip
  if (buffer.toString('utf8') !== value) {
    throw new ValidationError('value', 'Value contains invalid UTF-8');
  }
}

/**
 * Validates a single endpoint format
 * @throws ValidationError if endpoint is invalid
 */
export function validateEndpoint(endpoint: string): void {
  const regex = /^tcp:\/\/([^:]+):(\d+)$/;
  const match = endpoint.match(regex);

  if (!match) {
    throw new ValidationError(
      'endpoints',
      `Invalid endpoint format: ${endpoint}. Expected: tcp://host:port`
    );
  }

  const [, , portStr] = match;
  const port = parseInt(portStr, 10);

  if (port < 1 || port > 65535) {
    throw new ValidationError(
      'endpoints',
      `Invalid port: ${port}. Must be 1-65535`
    );
  }
}

/**
 * Parses endpoint configuration from string format
 * Format: "memberId1=tcp://host1:port1,memberId2=tcp://host2:port2"
 * @throws ValidationError if parsing fails
 */
export function parseEndpoints(input: string): EndpointConfig {
  // Use default if empty
  if (!input || input.trim().length === 0) {
    return {
      endpoints: new Map([['node-1', 'tcp://127.0.0.1:7001']]),
    };
  }

  const pairs = input.split(',').map(s => s.trim()).filter(s => s.length > 0);

  if (pairs.length === 0) {
    throw new ValidationError(
      'endpoints',
      'No endpoints provided'
    );
  }

  const endpoints = new Map<string, string>();

  for (const pair of pairs) {
    const [memberId, endpoint] = pair.split('=', 2);

    if (!memberId || !endpoint) {
      throw new ValidationError(
        'endpoints',
        `Invalid format: ${pair}. Expected: memberId=tcp://host:port`
      );
    }

    const trimmedMemberId = memberId.trim();
    const trimmedEndpoint = endpoint.trim();

    validateEndpoint(trimmedEndpoint);

    endpoints.set(trimmedMemberId, trimmedEndpoint);
  }

  return { endpoints };
}
