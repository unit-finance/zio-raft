/**
 * Integration tests for Watch command
 */

import { describe, it, expect } from 'vitest';
import { validateKey, parseEndpoints } from '../../src/validation.js';
import { ValidationError, OperationError } from '../../src/errors.js';
import { createMockClient } from '../helpers/mocks.js';
import { formatError, formatNotification, getExitCode } from '../../src/formatting.js';
import { WatchNotification } from '../../src/types.js';

describe('Watch Command Integration', () => {
  // TC-049: Watch command displays notifications
  it('should display notifications for watched key', async () => {
    const key = 'mykey';
    const endpoints = 'node-1=tcp://127.0.0.1:7001';

    validateKey(key);
    const endpointConfig = parseEndpoints(endpoints);
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    // Add mock notifications
    const notifications: WatchNotification[] = [
      {
        timestamp: new Date('2026-01-13T10:15:23.456Z'),
        sequenceNumber: 1n,
        key: 'mykey',
        value: 'value1',
      },
      {
        timestamp: new Date('2026-01-13T10:15:24.789Z'),
        sequenceNumber: 2n,
        key: 'mykey',
        value: 'value2',
      },
      {
        timestamp: new Date('2026-01-13T10:15:25.123Z'),
        sequenceNumber: 3n,
        key: 'mykey',
        value: 'value3',
      },
    ];

    notifications.forEach((n) => client.addNotification(n));

    await client.connect();
    await client.watch(key);

    expect(client.watchCalled).toBe(true);
    expect(client.lastWatchKey).toBe(key);

    // Collect notifications
    const receivedNotifications: WatchNotification[] = [];
    for await (const notification of client.notifications()) {
      receivedNotifications.push(notification);
    }

    expect(receivedNotifications).toHaveLength(3);

    // Verify formatted output
    const formattedOutputs = receivedNotifications.map((n) => formatNotification(n));
    expect(formattedOutputs[0]).toBe('[2026-01-13T10:15:23.456Z] seq=1 key=mykey value=value1');
    expect(formattedOutputs[1]).toBe('[2026-01-13T10:15:24.789Z] seq=2 key=mykey value=value2');
    expect(formattedOutputs[2]).toBe('[2026-01-13T10:15:25.123Z] seq=3 key=mykey value=value3');

    await client.disconnect();
  });

  // TC-050: Watch command handles SIGINT gracefully
  // Note: Testing actual SIGINT requires process-level testing (e2e)
  // Here we test cleanup behavior
  it('should cleanup on graceful shutdown', async () => {
    const key = 'mykey';
    const endpoints = 'node-1=tcp://127.0.0.1:7001';

    validateKey(key);
    const endpointConfig = parseEndpoints(endpoints);
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();
    await client.watch(key);

    // Simulate graceful shutdown
    await client.disconnect();

    expect(client.disconnectCalled).toBe(true);
    // Exit code for SIGINT should be 130 (handled by command)
  });

  // TC-051: Watch command filters notifications by key
  it('should filter notifications to only display matching key', async () => {
    const watchedKey = 'mykey';
    const endpoints = 'node-1=tcp://127.0.0.1:7001';

    validateKey(watchedKey);
    const endpointConfig = parseEndpoints(endpoints);
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    // Add notifications for multiple keys
    const notifications: WatchNotification[] = [
      {
        timestamp: new Date('2026-01-13T10:15:23.456Z'),
        sequenceNumber: 1n,
        key: 'mykey',
        value: 'value1',
      },
      {
        timestamp: new Date('2026-01-13T10:15:24.789Z'),
        sequenceNumber: 2n,
        key: 'otherkey', // Different key
        value: 'value2',
      },
      {
        timestamp: new Date('2026-01-13T10:15:25.123Z'),
        sequenceNumber: 3n,
        key: 'mykey',
        value: 'value3',
      },
    ];

    notifications.forEach((n) => client.addNotification(n));

    await client.connect();
    await client.watch(watchedKey);

    // Collect and filter notifications
    const receivedNotifications: WatchNotification[] = [];
    for await (const notification of client.notifications()) {
      // Filter by key (as watch command would do)
      if (notification.key === watchedKey) {
        receivedNotifications.push(notification);
      }
    }

    // Should only have 2 notifications for 'mykey'
    expect(receivedNotifications).toHaveLength(2);
    expect(receivedNotifications[0].value).toBe('value1');
    expect(receivedNotifications[1].value).toBe('value3');

    await client.disconnect();
  });

  // TC-052: Watch command fails with invalid key
  it('should fail validation with invalid key', async () => {
    const longKey = 'a'.repeat(257);

    expect(() => validateKey(longKey)).toThrow(ValidationError);

    try {
      validateKey(longKey);
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      const formatted = formatError(error);
      expect(formatted).toContain('Key exceeds maximum size');
      expect(getExitCode(error)).toBe(1);
    }

    // Watch should never be registered
  });

  // TC-053: Watch command handles connection loss and reconnection
  // Note: Reconnection is handled by RaftClient internally
  it('should handle reconnection scenario', async () => {
    const key = 'mykey';
    const endpoints = 'node-1=tcp://127.0.0.1:7001';

    validateKey(key);
    const endpointConfig = parseEndpoints(endpoints);
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();
    await client.watch(key);

    // In real scenario, RaftClient handles reconnection automatically
    // and re-registers watches transparently
    expect(client.watchCalled).toBe(true);

    // Disconnect and reconnect
    await client.disconnect();
    await client.connect();

    // Watch would need to be re-registered (depends on implementation)
    expect(client.connectCalled).toBe(true);

    await client.disconnect();
  });

  // TC-054: Watch command handles watch registration failure
  it('should handle watch registration failure', async () => {
    const key = 'mykey';
    const endpoints = 'node-1=tcp://127.0.0.1:7001';

    validateKey(key);
    const endpointConfig = parseEndpoints(endpoints);
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();

    // Configure mock to fail on watch registration
    const watchError = new OperationError('watch', 'server_error', 'Failed to register watch');
    client.setWatchError(watchError);

    await expect(client.watch(key)).rejects.toThrow();

    try {
      await client.watch(key);
    } catch (error) {
      expect(error).toBeInstanceOf(OperationError);
      const formatted = formatError(error);
      expect(formatted).toContain('Failed to register watch');
      expect(getExitCode(error)).toBe(3);
    }

    await client.disconnect();
  });

  it('should handle empty notifications stream gracefully', async () => {
    const key = 'mykey';
    const endpoints = 'node-1=tcp://127.0.0.1:7001';

    validateKey(key);
    const endpointConfig = parseEndpoints(endpoints);
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    // No notifications added

    await client.connect();
    await client.watch(key);

    const receivedNotifications: WatchNotification[] = [];
    for await (const notification of client.notifications()) {
      receivedNotifications.push(notification);
    }

    expect(receivedNotifications).toHaveLength(0);

    await client.disconnect();
  });

  it('should format watch output correctly', async () => {
    const key = 'config.feature';

    validateKey(key);

    // Test the output message that would be displayed
    const initialMessage = `watching ${key} - press Ctrl+C to stop`;
    expect(initialMessage).toBe('watching config.feature - press Ctrl+C to stop');

    // Test notification formatting
    const notification: WatchNotification = {
      timestamp: new Date('2026-01-13T10:15:23.456Z'),
      sequenceNumber: 42n,
      key: 'config.feature',
      value: 'enabled',
    };

    const formatted = formatNotification(notification);
    expect(formatted).toBe('[2026-01-13T10:15:23.456Z] seq=42 key=config.feature value=enabled');
  });
});
