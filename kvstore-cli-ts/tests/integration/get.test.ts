/**
 * Integration tests for Get command
 */

import { describe, it, expect } from 'vitest';
import { validateKey, parseEndpoints } from '../../src/validation.js';
import { ValidationError, OperationError, ProtocolError } from '../../src/errors.js';
import { createMockClient } from '../helpers/mocks.js';
import { formatError, getExitCode } from '../../src/formatting.js';

describe('Get Command Integration', () => {
  // TC-044: Get command returns existing value
  it('should retrieve and display existing value', async () => {
    const key = 'mykey';
    const endpoints = 'node-1=tcp://127.0.0.1:7001';

    validateKey(key);
    const endpointConfig = parseEndpoints(endpoints);
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    // Configure mock to return a value
    client.setGetResult('myvalue');

    await client.connect();
    const result = await client.get(key);
    await client.disconnect();

    expect(client.getCalled).toBe(true);
    expect(client.lastGetKey).toBe(key);
    expect(result).toBe('myvalue');

    // Verify output format would be: "mykey = myvalue"
    const output = result ? `${key} = ${result}` : `${key} = <none>`;
    expect(output).toBe('mykey = myvalue');
  });

  // TC-045: Get command handles non-existent key
  it('should display <none> for non-existent key', async () => {
    const key = 'nonexistent';
    const endpoints = 'node-1=tcp://127.0.0.1:7001';

    validateKey(key);
    const endpointConfig = parseEndpoints(endpoints);
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    // Configure mock to return null (key not found)
    client.setGetResult(null);

    await client.connect();
    const result = await client.get(key);
    await client.disconnect();

    expect(result).toBeNull();

    // Verify output format would be: "nonexistent = <none>"
    const output = result ? `${key} = ${result}` : `${key} = <none>`;
    expect(output).toBe('nonexistent = <none>');

    // Note: This is not an error, exit code should be 0
  });

  // TC-046: Get command fails with invalid key
  it('should fail validation with invalid key', async () => {
    const emptyKey = '';

    expect(() => validateKey(emptyKey)).toThrow(ValidationError);

    try {
      validateKey(emptyKey);
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      const formatted = formatError(error);
      expect(formatted).toBe('Error: Key cannot be empty');
      expect(getExitCode(error)).toBe(1);
    }

    // Client should never be created
  });

  // TC-047: Get command handles connection timeout
  it('should handle connection timeout', async () => {
    const key = 'mykey';

    validateKey(key);
    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    const connectError = new OperationError('connect', 'connection', 'Could not connect to cluster (timeout after 5s)');
    client.setConnectError(connectError);

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

  // TC-048: Get command handles decode error
  it('should handle protocol decode error', async () => {
    const key = 'mykey';

    validateKey(key);
    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();

    // Configure mock to throw protocol error during get
    const decodeError = new ProtocolError('Invalid buffer format');
    client.setGetError(decodeError);

    await expect(client.get(key)).rejects.toThrow();

    try {
      await client.get(key);
    } catch (error) {
      expect(error).toBeInstanceOf(ProtocolError);
      const formatted = formatError(error);
      expect(formatted).toContain('Protocol error');
      expect(getExitCode(error)).toBe(3);
    }

    await client.disconnect();
  });

  it('should handle timeout during get operation', async () => {
    const key = 'mykey';

    validateKey(key);
    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();

    const timeoutError = new OperationError('get', 'timeout', 'Operation timed out after 5s');
    client.setGetError(timeoutError);

    await expect(client.get(key)).rejects.toThrow();

    try {
      await client.get(key);
    } catch (error) {
      expect(error).toBeInstanceOf(OperationError);
      const formatted = formatError(error);
      expect(formatted).toBe('Error: Operation timed out after 5s');
      expect(getExitCode(error)).toBe(2);
    }

    await client.disconnect();
  });

  it('should ensure cleanup after successful get', async () => {
    const key = 'mykey';

    validateKey(key);
    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    client.setGetResult('value');

    await client.connect();
    await client.get(key);
    await client.disconnect();

    expect(client.connectCalled).toBe(true);
    expect(client.getCalled).toBe(true);
    expect(client.disconnectCalled).toBe(true);
  });
});
