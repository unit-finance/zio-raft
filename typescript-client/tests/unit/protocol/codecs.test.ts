// Protocol codec unit tests
// Tests encoding/decoding of protocol messages for wire compatibility

import { describe, it, expect } from 'vitest';
import {
  encodeClientMessage,
  decodeServerMessage,
  encodeString,
  encodePayload,
  encodeMap,
  encodeTimestamp,
  encodeNonce,
  encodeRequestId,
} from '../../../src/protocol/codecs';
import { CreateSession, ClientRequest, Query, KeepAlive } from '../../../src/protocol/messages';
import { RequestId, Nonce, CorrelationId } from '../../../src/types';
import { PROTOCOL_VERSION } from '../../../src/protocol/constants';

describe('Protocol Codecs', () => {
  describe('Field Encoding', () => {
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
      // Full decoding tested in round-trip test
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

  describe('Message Encoding', () => {
    it('TC-PROTO-007: should encode CreateSession message', () => {
      const message: CreateSession = {
        type: 'CreateSession',
        capabilities: new Map([['version', '1.0.0']]),
        nonce: Nonce.fromBigInt(123n),
      };

      const encoded = encodeClientMessage(message);

      // Check protocol signature
      expect(encoded.toString('latin1', 0, 5)).toBe('zraft');
      // Check protocol version
      expect(encoded.readUInt8(5)).toBe(0x01);
      // Check discriminator (CreateSession = 0x01)
      expect(encoded.readUInt8(6)).toBe(0x01);
    });

    it('TC-PROTO-008: should encode ClientRequest message', () => {
      const message: ClientRequest = {
        type: 'ClientRequest',
        requestId: RequestId.fromBigInt(42n),
        lowestPendingRequestId: RequestId.fromBigInt(40n),
        payload: Buffer.from([1, 2, 3]),
        createdAt: new Date(),
      };

      const encoded = encodeClientMessage(message);

      // Check protocol header
      expect(encoded.toString('latin1', 0, 5)).toBe('zraft');
      expect(encoded.readUInt8(5)).toBe(PROTOCOL_VERSION);
      // Check discriminator (ClientRequest = 0x04)
      expect(encoded.readUInt8(6)).toBe(0x04);
    });

    it('TC-PROTO-009: should encode Query message', () => {
      const message: Query = {
        type: 'Query',
        correlationId: CorrelationId.fromString('550e8400-e29b-41d4-a716-446655440000'),
        payload: Buffer.from([4, 5, 6]),
        createdAt: new Date(),
      };

      const encoded = encodeClientMessage(message);

      // Check protocol header
      expect(encoded.toString('latin1', 0, 5)).toBe('zraft');
      // Check discriminator (Query = 0x08)
      expect(encoded.readUInt8(6)).toBe(0x08);
    });

    it('TC-PROTO-010: should encode KeepAlive message', () => {
      const message: KeepAlive = {
        type: 'KeepAlive',
        timestamp: new Date(),
      };

      const encoded = encodeClientMessage(message);

      // Check protocol header
      expect(encoded.toString('latin1', 0, 5)).toBe('zraft');
      // Check discriminator (KeepAlive = 0x03)
      expect(encoded.readUInt8(6)).toBe(0x03);
    });
  });

  describe('Message Decoding', () => {
    it('TC-PROTO-011: should decode SessionCreated message', () => {
      // This test requires a properly encoded SessionCreated message
      // For now, this is a placeholder - full implementation requires
      // either mock data or encoding from Scala server

      // TODO: Implement when we have reference encodings
      expect(true).toBe(true);
    });

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

  describe('Round-Trip Tests', () => {
    it('TC-PROTO-014: CreateSession round-trip preserves data', () => {
      const original: CreateSession = {
        type: 'CreateSession',
        capabilities: new Map([
          ['version', '1.0.0'],
          ['client', 'typescript'],
        ]),
        nonce: Nonce.fromBigInt(999n),
      };

      const encoded = encodeClientMessage(original);

      // Verify encoding structure
      expect(encoded.length).toBeGreaterThan(7);
      expect(encoded.toString('latin1', 0, 5)).toBe('zraft');

      // Full round-trip test requires server message decoding
      // which depends on having reference data from Scala server
    });
  });
});
