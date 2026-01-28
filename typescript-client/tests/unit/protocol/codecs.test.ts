// Protocol codec unit tests
// Tests primitive encoding functions and error handling
// Full message encoding/decoding is tested in compatibility.test.ts

import { describe, it, expect } from 'vitest';
import {
  decodeServerMessage,
  encodeString,
  encodePayload,
  encodeMap,
  encodeTimestamp,
  encodeNonce,
  encodeRequestId,
} from '../../../src/protocol/codecs';
import { RequestId, Nonce } from '../../../src/types';

describe('Protocol Codecs', () => {
  describe('Primitive Field Encoding', () => {
    it('TC-PROTO-001: should encode strings with length prefix', () => {
      const str = 'hello';
      const encoded = encodeString(str);

      // Check length prefix (2 bytes, big-endian)
      expect(encoded.readUInt16BE(0)).toBe(5);
      // Check UTF-8 content
      expect(encoded.toString('utf8', 2)).toBe('hello');
    });

    it('TC-PROTO-002: should encode timestamps as int64 epoch millis', () => {
      const date = new Date('2025-01-01T00:00:00.000Z');
      const encoded = encodeTimestamp(date);

      expect(encoded.length).toBe(8);
      expect(encoded.readBigInt64BE(0)).toBe(BigInt(date.getTime()));
    });

    it('TC-PROTO-003: should encode nonces as 8-byte bigint', () => {
      const nonce = Nonce.fromBigInt(12345n);
      const encoded = encodeNonce(nonce);

      expect(encoded.length).toBe(8);
      expect(encoded.readBigInt64BE(0)).toBe(12345n);
    });

    it('TC-PROTO-004: should encode request IDs as 8-byte bigint', () => {
      const requestId = RequestId.fromBigInt(9876n);
      const encoded = encodeRequestId(requestId);

      expect(encoded.length).toBe(8);
      expect(encoded.readBigInt64BE(0)).toBe(9876n);
    });

    it('TC-PROTO-005: should encode capabilities map', () => {
      const caps = new Map([
        ['version', '1.0.0'],
        ['client', 'typescript'],
      ]);
      const encoded = encodeMap(caps);

      // Check count (2 bytes)
      expect(encoded.readUInt16BE(0)).toBe(2);
    });

    it('TC-PROTO-006: should encode payload with length prefix', () => {
      const payload = Buffer.from('test payload');
      const encoded = encodePayload(payload);

      // Check length prefix (4 bytes, big-endian)
      expect(encoded.readInt32BE(0)).toBe(12);
      // Check payload content
      expect(encoded.subarray(4).toString()).toBe('test payload');
    });
  });

  describe('Error Handling', () => {
    it('TC-PROTO-012: should reject invalid protocol signature', () => {
      const invalidBuffer = Buffer.from('wrong signature');

      expect(() => decodeServerMessage(invalidBuffer)).toThrow('Invalid protocol signature');
    });

    it('TC-PROTO-013: should reject unsupported protocol version', () => {
      const buffer = Buffer.allocUnsafe(7);
      buffer.write('zraft', 0, 'latin1');
      buffer.writeUInt8(0xff, 5); // Invalid version
      buffer.writeUInt8(0x01, 6);

      expect(() => decodeServerMessage(buffer)).toThrow('Unsupported protocol version');
    });
  });
});
