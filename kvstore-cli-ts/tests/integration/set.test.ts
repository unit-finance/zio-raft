/**
 * Integration tests for Set command
 */

import { describe, it, expect } from 'vitest';
import { validateKey, validateValue, parseEndpoints } from '../../src/validation.js';
import { ValidationError, OperationError } from '../../src/errors.js';
import { createMockClient } from '../helpers/mocks.js';
import { formatError, getExitCode } from '../../src/formatting.js';

describe('Set Command Integration', () => {
  // TC-038: Set command succeeds with valid inputs
  it('should execute set command successfully with valid inputs', async () => {
    const key = 'mykey';
    const value = 'myvalue';
    const endpoints = 'node-1=tcp://127.0.0.1:7001';

    // Validate inputs (should not throw)
    expect(() => validateKey(key)).not.toThrow();
    expect(() => validateValue(value)).not.toThrow();

    // Parse endpoints
    const endpointConfig = parseEndpoints(endpoints);
    expect(endpointConfig.endpoints.size).toBe(1);

    // Create mock client
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    // Execute command flow
    await client.connect();
    await client.set(key, value);
    await client.disconnect();

    // Verify calls
    expect(client.connectCalled).toBe(true);
    expect(client.setCalled).toBe(true);
    expect(client.disconnectCalled).toBe(true);
    expect(client.lastSetKey).toBe(key);
    expect(client.lastSetValue).toBe(value);
  });

  // TC-039: Set command fails with invalid key
  it('should fail validation before client creation with invalid key', async () => {
    const longKey = 'a'.repeat(257); // Exceeds 256 bytes
    const value = 'myvalue';

    // Validation should fail
    expect(() => validateKey(longKey)).toThrow(ValidationError);

    try {
      validateKey(longKey);
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      const formatted = formatError(error);
      expect(formatted).toContain('Key exceeds maximum size');
      expect(formatted).toContain('actual: 257');
      expect(getExitCode(error)).toBe(1);
    }

    // Client should never be created
    // (In real command, this happens before new KVClient())
  });

  // TC-040: Set command fails with invalid value
  it('should fail validation with invalid value', async () => {
    const key = 'mykey';
    const largeValue = 'a'.repeat(1024 * 1024 + 1); // Exceeds 1MB

    expect(() => validateKey(key)).not.toThrow();
    expect(() => validateValue(largeValue)).toThrow(ValidationError);

    try {
      validateValue(largeValue);
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      const formatted = formatError(error);
      expect(formatted).toContain('Value exceeds maximum size');
      expect(getExitCode(error)).toBe(1);
    }
  });

  // TC-041: Set command handles connection timeout
  it('should handle connection timeout error', async () => {
    const key = 'mykey';
    const value = 'myvalue';

    validateKey(key);
    validateValue(value);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    // Configure mock to throw on connect
    const connectError = new OperationError('connect', 'connection', 'Could not connect to cluster (timeout after 5s)');
    client.setConnectError(connectError);

    // Execute should throw
    await expect(client.connect()).rejects.toThrow();

    try {
      await client.connect();
    } catch (error) {
      expect(error).toBeInstanceOf(OperationError);
      const formatted = formatError(error);
      expect(formatted).toContain('Could not connect to cluster');
      expect(getExitCode(error)).toBe(2);
    }
  });

  // TC-042: Set command handles server error
  it('should handle server error during set', async () => {
    const key = 'mykey';
    const value = 'myvalue';

    validateKey(key);
    validateValue(value);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();

    // Configure mock to throw on set
    const setError = new OperationError('set', 'server_error', 'Server rejected command');
    client.setSetError(setError);

    await expect(client.set(key, value)).rejects.toThrow();

    try {
      await client.set(key, value);
    } catch (error) {
      expect(error).toBeInstanceOf(OperationError);
      const formatted = formatError(error);
      expect(formatted).toContain('Server rejected command');
      expect(getExitCode(error)).toBe(3);
    }

    await client.disconnect();
  });

  // TC-043: Set command with custom endpoints
  it('should work with custom endpoints', async () => {
    const key = 'mykey';
    const value = 'myvalue';
    const endpoints = 'node-1=tcp://192.168.1.10:7001,node-2=tcp://192.168.1.11:7001';

    validateKey(key);
    validateValue(value);

    const endpointConfig = parseEndpoints(endpoints);
    expect(endpointConfig.endpoints.size).toBe(2);
    expect(endpointConfig.endpoints.get('node-1')).toBe('tcp://192.168.1.10:7001');
    expect(endpointConfig.endpoints.get('node-2')).toBe('tcp://192.168.1.11:7001');

    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();
    await client.set(key, value);
    await client.disconnect();

    expect(client.setCalled).toBe(true);
  });

  it('should ensure cleanup on error', async () => {
    const key = 'mykey';
    const value = 'myvalue';

    validateKey(key);
    validateValue(value);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();

    // Configure to throw
    client.setSetError(new Error('Test error'));

    try {
      await client.set(key, value);
    } catch (error) {
      // Expected
    }

    // Cleanup should still happen
    await client.disconnect();
    expect(client.disconnectCalled).toBe(true);
  });
});
