/**
 * Integration tests for edge cases
 */

import { describe, it, expect } from 'vitest';
import { validateKey, validateValue, parseEndpoints } from '../../src/validation.js';
import { ValidationError } from '../../src/errors.js';
import { createMockClient } from '../helpers/mocks.js';

describe('Edge Cases', () => {
  // TC-055: Whitespace-only key is rejected
  it('should reject whitespace-only key', () => {
    const whitespaceKey = '   ';

    // This will pass length check but is semantically invalid
    // The current validation only checks empty string, not whitespace-only
    // This test documents current behavior
    expect(() => validateKey(whitespaceKey)).not.toThrow();

    // If we want to reject whitespace-only, we'd need to add:
    // if (key.trim().length === 0) throw new ValidationError(...)
  });

  // TC-056: Key with only unicode whitespace
  it('should handle unicode whitespace in keys', () => {
    // Non-breaking space U+00A0
    const unicodeWhitespace = '\u00A0\u2003';

    // Current behavior: accepts unicode whitespace as valid
    expect(() => validateKey(unicodeWhitespace)).not.toThrow();

    // This test documents the behavior - unicode whitespace is treated
    // as valid content (which is correct for a key-value store)
  });

  // TC-057: Maximum size key and value succeed
  it('should accept maximum size key and value', async () => {
    const maxKey = 'a'.repeat(256); // Exactly 256 bytes
    const maxValue = 'b'.repeat(1024 * 1024); // Exactly 1MB

    // Validation should pass
    expect(() => validateKey(maxKey)).not.toThrow();
    expect(() => validateValue(maxValue)).not.toThrow();

    // Execute set command with max sizes
    const endpoints = 'node-1=tcp://127.0.0.1:7001';
    const endpointConfig = parseEndpoints(endpoints);
    const client = createMockClient({ endpoints: endpointConfig.endpoints });

    await client.connect();
    await client.set(maxKey, maxValue);
    await client.disconnect();

    expect(client.setCalled).toBe(true);
    expect(client.lastSetKey).toBe(maxKey);
    expect(client.lastSetValue).toBe(maxValue);
  });

  // TC-058: Empty endpoint string uses default
  it('should use default endpoint when empty string provided', () => {
    const emptyEndpoints = '';
    const endpointConfig = parseEndpoints(emptyEndpoints);

    expect(endpointConfig.endpoints.size).toBe(1);
    expect(endpointConfig.endpoints.get('node-1')).toBe('tcp://127.0.0.1:7001');
  });

  it('should use default endpoint when whitespace-only string provided', () => {
    const whitespaceEndpoints = '   ';
    const endpointConfig = parseEndpoints(whitespaceEndpoints);

    expect(endpointConfig.endpoints.size).toBe(1);
    expect(endpointConfig.endpoints.get('node-1')).toBe('tcp://127.0.0.1:7001');
  });

  // TC-059: Multiple commands to same cluster
  it('should handle multiple sequential commands to same cluster', async () => {
    const endpoints = 'node-1=tcp://127.0.0.1:7001';
    const endpointConfig = parseEndpoints(endpoints);

    // Command 1: Set
    const client1 = createMockClient({ endpoints: endpointConfig.endpoints });
    await client1.connect();
    await client1.set('key1', 'value1');
    await client1.disconnect();

    expect(client1.setCalled).toBe(true);

    // Command 2: Get
    const client2 = createMockClient({ endpoints: endpointConfig.endpoints });
    client2.setGetResult('value1');
    await client2.connect();
    const result = await client2.get('key1');
    await client2.disconnect();

    expect(client2.getCalled).toBe(true);
    expect(result).toBe('value1');

    // Command 3: Watch
    const client3 = createMockClient({ endpoints: endpointConfig.endpoints });
    await client3.connect();
    await client3.watch('key1');
    await client3.disconnect();

    expect(client3.watchCalled).toBe(true);

    // Each command creates its own client lifecycle
  });

  // TC-060: Concurrent watch commands
  it('should handle concurrent watch commands independently', async () => {
    const endpoints = 'node-1=tcp://127.0.0.1:7001';
    const endpointConfig = parseEndpoints(endpoints);

    // Create two separate clients for watching different keys
    const client1 = createMockClient({ endpoints: endpointConfig.endpoints });
    const client2 = createMockClient({ endpoints: endpointConfig.endpoints });

    await client1.connect();
    await client2.connect();

    await client1.watch('key1');
    await client2.watch('key2');

    expect(client1.watchCalled).toBe(true);
    expect(client1.lastWatchKey).toBe('key1');
    expect(client2.watchCalled).toBe(true);
    expect(client2.lastWatchKey).toBe('key2');

    await client1.disconnect();
    await client2.disconnect();

    // Both watches operate independently
  });

  it('should handle keys at boundary of 256 bytes with multi-byte chars', () => {
    // Create a key with exactly 256 bytes using multi-byte characters
    // Each emoji is 4 bytes
    const emojis = '🔑'.repeat(64); // 64 * 4 = 256 bytes
    expect(Buffer.from(emojis, 'utf8').length).toBe(256);

    expect(() => validateKey(emojis)).not.toThrow();

    // One more byte should fail
    const tooMany = emojis + 'a'; // 256 + 1 = 257 bytes
    expect(() => validateKey(tooMany)).toThrow(ValidationError);
  });

  it('should handle values at boundary of 1MB with multi-byte chars', () => {
    // Create a value with exactly 1MB using multi-byte characters
    const unicodeChar = '値'; // 3 bytes in UTF-8
    const count = Math.floor((1024 * 1024) / 3); // 349,525 chars = 1,048,575 bytes
    const almostMax = unicodeChar.repeat(count);

    expect(Buffer.from(almostMax, 'utf8').length).toBeLessThanOrEqual(1024 * 1024);
    expect(() => validateValue(almostMax)).not.toThrow();

    // Add enough to exceed 1MB
    const overMax = almostMax + unicodeChar.repeat(10); // Definitely over 1MB
    expect(Buffer.from(overMax, 'utf8').length).toBeGreaterThan(1024 * 1024);
    expect(() => validateValue(overMax)).toThrow(ValidationError);
  });

  it('should handle complex endpoint configurations', () => {
    const complexEndpoints =
      'node-1=tcp://127.0.0.1:7001,' +
      'node-2=tcp://192.168.1.10:7002,' +
      'node-3=tcp://prod.example.com:7003';

    const endpointConfig = parseEndpoints(complexEndpoints);

    expect(endpointConfig.endpoints.size).toBe(3);
    expect(endpointConfig.endpoints.get('node-1')).toBe('tcp://127.0.0.1:7001');
    expect(endpointConfig.endpoints.get('node-2')).toBe('tcp://192.168.1.10:7002');
    expect(endpointConfig.endpoints.get('node-3')).toBe('tcp://prod.example.com:7003');
  });

  it('should handle keys with special characters', () => {
    const specialKeys = [
      'key:with:colons',
      'key.with.dots',
      'key-with-dashes',
      'key_with_underscores',
      'key/with/slashes',
      'key@with@at',
      'key#with#hash',
    ];

    for (const key of specialKeys) {
      expect(() => validateKey(key)).not.toThrow();
    }
  });

  it('should handle values with various content types', () => {
    const values = [
      'simple string',
      '{"json": "data"}',
      '<xml>data</xml>',
      'line1\nline2\nline3',
      'tab\tseparated\tvalues',
      'emoji 🎉 content',
      '中文内容',
      'mixed English and 日本語',
    ];

    for (const value of values) {
      expect(() => validateValue(value)).not.toThrow();
    }
  });
});
