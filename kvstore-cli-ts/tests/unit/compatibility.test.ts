/**
 * Cross-language compatibility tests for KVStore protocol
 *
 * These tests verify that TypeScript and Scala implementations encode/decode messages identically.
 * Fixtures are stored in hex files (source of truth in Scala protocol module).
 */

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { join } from 'path';
import { encodeSetRequest, encodeGetQuery, encodeWatchRequest, decodeNotification } from '../../src/codecs.js';
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

/**
 * Read hex fixture from Scala protocol module
 */
function readFixture(filename: string): string {
  const fixturePath = join(
    __dirname,
    '../..',
    '..',
    'kvstore-protocol',
    'src',
    'test',
    'resources',
    'fixtures',
    filename
  );
  return readFileSync(fixturePath, 'utf8').trim();
}

/**
 * Convert Buffer to hex string for comparison with Scala fixtures
 */
function toHex(buffer: Buffer): string {
  return buffer.toString('hex');
}

/**
 * Convert hex string to Buffer for decoding tests
 */
function fromHex(hex: string): Buffer {
  return Buffer.from(hex, 'hex');
}

describe('Scala Compatibility', () => {
  describe('Client Request Encoding', () => {
    it('should encode Set request matching Scala output', () => {
      const buffer = encodeSetRequest('test-key', 'test-value');
      const hex = toHex(buffer);

      const expectedHex = readFixture('Set.hex');

      expect(hex).toBe(expectedHex);
    });

    it('should encode Watch request matching Scala output', () => {
      const buffer = encodeWatchRequest('test-key');
      const hex = toHex(buffer);

      const expectedHex = readFixture('Watch.hex');

      expect(hex).toBe(expectedHex);
    });
  });

  describe('Query Encoding', () => {
    it('should encode Get query matching Scala output', () => {
      const buffer = encodeGetQuery('test-key');
      const hex = toHex(buffer);

      const expectedHex = readFixture('Get.hex');

      expect(hex).toBe(expectedHex);
    });
  });

  describe('Server Request Decoding', () => {
    it('should decode Notification matching Scala encoding', () => {
      const expectedHex = readFixture('Notification.hex');

      const buffer = fromHex(expectedHex);
      const serverRequest = createMockServerRequest(1n, buffer);
      const notification = decodeNotification(serverRequest);

      expect(notification.key).toBe('test-key');
      expect(notification.value).toBe('test-value');
    });
  });
});
