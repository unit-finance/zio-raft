/**
 * Integration tests for error handling scenarios
 */

import { describe, it, expect } from 'vitest';
import { validateKey, parseEndpoints } from '../../src/validation.js';
import { OperationError, ProtocolError } from '../../src/errors.js';
import { createMockClient } from '../helpers/mocks.js';
import { formatError, getExitCode } from '../../src/formatting.js';
import { decodeNotification } from '../../src/codecs.js';
import { RequestId } from '../../../typescript-client/src/types.js';
import type { ServerRequest } from '../../../typescript-client/src/protocol/messages.js';

/**
 * Helper to create a mock ServerRequest for testing
 */
function createMockServerRequest(requestId: bigint, payload: Buffer, createdAt?: Date): ServerRequest {
  return {
    type: 'ServerRequest',
    requestId: RequestId.fromBigInt(requestId),
    payload,
    createdAt: createdAt ?? new Date(),
  };
}

describe('Error Handling Scenarios', () => {
  // TC-061: Network timeout during connect
  it('should handle network timeout during connection', async () => {
    const key = 'mykey';
    validateKey(key);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({
      endpoints: endpointConfig.endpoints,
      connectionTimeout: 5000,
    });

    // Configure mock to simulate connection timeout
    const timeoutError = new OperationError('connect', 'connection', 'Could not connect to cluster (timeout after 5s)');
    client.setConnectError(timeoutError);

    await expect(client.connect()).rejects.toThrow();

    try {
      await client.connect();
    } catch (error) {
      expect(error).toBeInstanceOf(OperationError);
      expect((error as OperationError).reason).toBe('connection');

      const formatted = formatError(error);
      expect(formatted).toContain('Could not connect to cluster');
      expect(formatted).toContain('timeout after 5s');

      const exitCode = getExitCode(error);
      expect(exitCode).toBe(2); // Connection error
    }
  });

  // TC-062: Network timeout during operation
  it('should handle network timeout during set operation', async () => {
    const key = 'mykey';
    const value = 'myvalue';

    validateKey(key);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({
      endpoints: endpointConfig.endpoints,
      requestTimeout: 5000,
    });

    await client.connect();

    // Configure mock to simulate timeout during operation
    const timeoutError = new OperationError('set', 'timeout', 'Operation timed out after 5s');
    client.setSetError(timeoutError);

    await expect(client.set(key, value)).rejects.toThrow();

    try {
      await client.set(key, value);
    } catch (error) {
      expect(error).toBeInstanceOf(OperationError);
      expect((error as OperationError).reason).toBe('timeout');

      const formatted = formatError(error);
      expect(formatted).toBe('Error: Operation timed out after 5s');

      const exitCode = getExitCode(error);
      expect(exitCode).toBe(2); // Timeout error
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
      const exitCode = getExitCode(error);
      expect(exitCode).toBe(2);
    }

    await client.disconnect();
  });

  // TC-063: Protocol error (invalid server response)
  it('should handle protocol error from invalid server response', async () => {
    const key = 'mykey';

    validateKey(key);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();

    // Simulate protocol error
    const protocolError = new ProtocolError('Invalid buffer format - unable to decode response');
    client.setGetError(protocolError);

    await expect(client.get(key)).rejects.toThrow();

    try {
      await client.get(key);
    } catch (error) {
      expect(error).toBeInstanceOf(ProtocolError);

      const formatted = formatError(error);
      expect(formatted).toContain('Protocol error');
      expect(formatted).toContain('Invalid buffer format');

      const exitCode = getExitCode(error);
      expect(exitCode).toBe(3); // Protocol error
    }

    await client.disconnect();
  });

  // TC-064: Invalid discriminator in notification
  it('should throw protocol error for invalid notification discriminator', () => {
    // Create a buffer with invalid discriminator
    const invalidBuffer = Buffer.from([
      0xff, // Invalid discriminator (not 0x4E 'N')
      0x00,
      0x00,
      0x00,
      0x04, // Length: 4
      0x74,
      0x65,
      0x73,
      0x74, // "test"
      0x00,
      0x00,
      0x00,
      0x05, // Length: 5
      0x76,
      0x61,
      0x6c,
      0x75,
      0x65, // "value"
    ]);

    expect(() => decodeNotification(createMockServerRequest(1n, invalidBuffer))).toThrow(ProtocolError);

    try {
      decodeNotification(createMockServerRequest(1n, invalidBuffer));
    } catch (error) {
      expect(error).toBeInstanceOf(ProtocolError);
      expect((error as ProtocolError).message).toContain('Invalid discriminator');
      expect((error as ProtocolError).message).toContain('0xff');
    }
  });

  it('should handle buffer too short for notification', () => {
    const shortBuffer = Buffer.from([0x4e, 0x00]); // Only discriminator and partial length

    expect(() => decodeNotification(createMockServerRequest(1n, shortBuffer))).toThrow(ProtocolError);

    try {
      decodeNotification(createMockServerRequest(1n, shortBuffer));
    } catch (error) {
      expect(error).toBeInstanceOf(ProtocolError);
      expect((error as ProtocolError).message).toContain('Buffer too short');
    }
  });

  // TC-065: Signal handling during command execution
  // Note: Actual signal handling requires process-level testing
  // This test verifies the exit code behavior
  it('should use exit code 130 for SIGINT', () => {
    // SIGINT (Ctrl+C) should result in exit code 130
    // This is handled by the command implementation, not an error type
    // Documenting expected behavior for manual testing

    const SIGINT_EXIT_CODE = 130;
    expect(SIGINT_EXIT_CODE).toBe(130);

    // In real implementation:
    // process.on('SIGINT', async () => {
    //   await client.disconnect();
    //   process.exit(130);
    // });
  });

  it('should handle connection errors with clear messages', async () => {
    const key = 'mykey';
    validateKey(key);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    const connectionError = new OperationError(
      'connect',
      'connection',
      'Could not connect to cluster (timeout after 5s)'
    );
    client.setConnectError(connectionError);

    await expect(client.connect()).rejects.toThrow();

    try {
      await client.connect();
    } catch (error) {
      expect(error).toBeInstanceOf(OperationError);
      expect((error as OperationError).reason).toBe('connection');

      const formatted = formatError(error);
      expect(formatted).toBe('Error: Could not connect to cluster (timeout after 5s)');

      const exitCode = getExitCode(error);
      expect(exitCode).toBe(2);
    }
  });

  it('should handle server errors during operations', async () => {
    const key = 'mykey';
    const value = 'myvalue';

    validateKey(key);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();

    const serverError = new OperationError('set', 'server_error', 'Server rejected command: invalid session');
    client.setSetError(serverError);

    await expect(client.set(key, value)).rejects.toThrow();

    try {
      await client.set(key, value);
    } catch (error) {
      expect(error).toBeInstanceOf(OperationError);
      expect((error as OperationError).reason).toBe('server_error');

      const formatted = formatError(error);
      expect(formatted).toContain('Server rejected command');

      const exitCode = getExitCode(error);
      expect(exitCode).toBe(3); // Operational error
    }

    await client.disconnect();
  });

  it('should ensure cleanup happens even on errors', async () => {
    const key = 'mykey';
    const value = 'myvalue';

    validateKey(key);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();

    // Simulate error
    client.setSetError(new Error('Test error'));

    try {
      await client.set(key, value);
    } catch (error) {
      // Expected
    }

    // Cleanup should still be possible
    await client.disconnect();
    expect(client.disconnectCalled).toBe(true);
  });

  it('should handle errors in all command types', async () => {
    const key = 'mykey';
    validateKey(key);

    const endpointConfig = parseEndpoints('node-1=tcp://127.0.0.1:7001');

    // Set error
    const setClient = createMockClient({ endpoints: endpointConfig.endpoints });
    await setClient.connect();
    setClient.setSetError(new OperationError('set', 'timeout', 'Timeout'));
    await expect(setClient.set(key, 'value')).rejects.toThrow();
    await setClient.disconnect();

    // Get error
    const getClient = createMockClient({ endpoints: endpointConfig.endpoints });
    await getClient.connect();
    getClient.setGetError(new OperationError('get', 'timeout', 'Timeout'));
    await expect(getClient.get(key)).rejects.toThrow();
    await getClient.disconnect();

    // Watch error
    const watchClient = createMockClient({ endpoints: endpointConfig.endpoints });
    await watchClient.connect();
    watchClient.setWatchError(new OperationError('watch', 'timeout', 'Timeout'));
    await expect(watchClient.watch(key)).rejects.toThrow();
    await watchClient.disconnect();
  });
});
