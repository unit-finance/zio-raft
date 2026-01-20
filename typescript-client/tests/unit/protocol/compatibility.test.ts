/**
 * Cross-language compatibility tests for ZIO Raft client-server protocol
 * 
 * These tests verify that TypeScript and Scala implementations encode/decode messages identically.
 * Fixtures are stored in hex files (source of truth in Scala protocol module).
 * 
 * The tests use fixed test data matching the Scala tests for reproducible binary output.
 */

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { join } from 'path';
import {
  encodeClientMessage,
  decodeServerMessage,
} from '../../../src/protocol/codecs';
import {
  SessionId,
  RequestId,
  MemberId,
  Nonce,
  CorrelationId,
} from '../../../src/types';

// Fixed test data matching Scala tests
const testTimestamp = new Date(1705680000000); // 2024-01-19 12:00:00 UTC
const testSessionId = SessionId.fromString('test-session-123');
const testMemberId = MemberId.fromString('node-1');
const testNonce = Nonce.fromBigInt(42n);
const testRequestId = RequestId.fromBigInt(100n);
const testCorrelationId = CorrelationId.fromString('corr-123');
const testPayload = Buffer.from('test-payload', 'utf8');
const testCapabilities = new Map([
  ['capability1', 'value1'],
  ['capability2', 'value2'],
]);

/**
 * Read hex fixture from Scala protocol module
 */
function readFixture(filename: string): string {
  const fixturePath = join(__dirname, '../../../..', 'client-server-protocol', 'src', 'test', 'resources', 'fixtures', filename);
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
  describe('Client Message Encoding', () => {
    it('should encode CreateSession matching Scala output', () => {
      const message = {
        type: 'CreateSession' as const,
        capabilities: testCapabilities,
        nonce: testNonce,
      };
      
      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);
      
      const expectedHex = readFixture('CreateSession.hex');
      
      expect(hex).toBe(expectedHex);
    });

    it('should encode ContinueSession matching Scala output', () => {
      const message = {
        type: 'ContinueSession' as const,
        sessionId: testSessionId,
        nonce: testNonce,
      };
      
      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);
      
      const expectedHex = readFixture('ContinueSession.hex');
      
      expect(hex).toBe(expectedHex);
    });

    it('should encode KeepAlive matching Scala output', () => {
      const message = {
        type: 'KeepAlive' as const,
        timestamp: testTimestamp,
      };
      
      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);
      
      const expectedHex = readFixture('KeepAlive.hex');
      
      expect(hex).toBe(expectedHex);
    });

    it('should encode ClientRequest matching Scala output', () => {
      const message = {
        type: 'ClientRequest' as const,
        requestId: testRequestId,
        lowestPendingRequestId: testRequestId,
        payload: testPayload,
        createdAt: testTimestamp,
      };
      
      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);
      
      const expectedHex = readFixture('ClientRequest.hex');
      
      expect(hex).toBe(expectedHex);
    });

    it('should encode Query matching Scala output', () => {
      const message = {
        type: 'Query' as const,
        correlationId: testCorrelationId,
        payload: testPayload,
        createdAt: testTimestamp,
      };
      
      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);
      
      const expectedHex = readFixture('Query.hex');
      
      expect(hex).toBe(expectedHex);
    });

    it('should encode ServerRequestAck matching Scala output', () => {
      const message = {
        type: 'ServerRequestAck' as const,
        requestId: testRequestId,
      };
      
      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);
      
      const expectedHex = readFixture('ServerRequestAck.hex');
      
      expect(hex).toBe(expectedHex);
    });

    it('should encode CloseSession matching Scala output', () => {
      const message = {
        type: 'CloseSession' as const,
        reason: 'ClientShutdown' as const,
      };
      
      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);
      
      const expectedHex = readFixture('CloseSession.hex');
      
      expect(hex).toBe(expectedHex);
    });

    it('should encode ConnectionClosed matching Scala output', () => {
      const message = {
        type: 'ConnectionClosed' as const,
      };
      
      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);
      
      const expectedHex = readFixture('ConnectionClosed.hex');
      
      expect(hex).toBe(expectedHex);
    });
  });

  describe('Server Message Decoding', () => {
    it('should decode SessionCreated matching Scala encoding', () => {
      const expectedHex = readFixture('SessionCreated.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('SessionCreated');
      if (message.type === 'SessionCreated') {
        expect(SessionId.unwrap(message.sessionId)).toBe('test-session-123');
        expect(Nonce.unwrap(message.nonce)).toBe(42n);
      }
    });

    it('should decode SessionContinued matching Scala encoding', () => {
      const expectedHex = readFixture('SessionContinued.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('SessionContinued');
      if (message.type === 'SessionContinued') {
        expect(Nonce.unwrap(message.nonce)).toBe(42n);
      }
    });

    it('should decode SessionRejected matching Scala encoding', () => {
      const expectedHex = readFixture('SessionRejected.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('SessionRejected');
      if (message.type === 'SessionRejected') {
        expect(message.reason).toBe('NotLeader');
        expect(Nonce.unwrap(message.nonce)).toBe(42n);
        expect(message.leaderId).toBeDefined();
        if (message.leaderId) {
          expect(MemberId.unwrap(message.leaderId)).toBe('node-1');
        }
      }
    });

    it('should decode SessionRejected with no leaderId', () => {
      const expectedHex = readFixture('SessionRejectedNoLeader.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('SessionRejected');
      if (message.type === 'SessionRejected') {
        expect(message.reason).toBe('InvalidCapabilities');
        expect(Nonce.unwrap(message.nonce)).toBe(42n);
        expect(message.leaderId).toBeUndefined();
      }
    });

    it('should decode SessionClosed matching Scala encoding', () => {
      const expectedHex = readFixture('SessionClosed.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('SessionClosed');
      if (message.type === 'SessionClosed') {
        expect(message.reason).toBe('Shutdown');
        expect(message.leaderId).toBeDefined();
        if (message.leaderId) {
          expect(MemberId.unwrap(message.leaderId)).toBe('node-1');
        }
      }
    });

    it('should decode KeepAliveResponse matching Scala encoding', () => {
      const expectedHex = readFixture('KeepAliveResponse.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('KeepAliveResponse');
      if (message.type === 'KeepAliveResponse') {
        expect(message.timestamp.getTime()).toBe(testTimestamp.getTime());
      }
    });

    it('should decode ClientResponse matching Scala encoding', () => {
      const expectedHex = readFixture('ClientResponse.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('ClientResponse');
      if (message.type === 'ClientResponse') {
        expect(RequestId.unwrap(message.requestId)).toBe(100n);
        expect(message.result.toString('utf8')).toBe('test-payload');
      }
    });

    it('should decode QueryResponse matching Scala encoding', () => {
      const expectedHex = readFixture('QueryResponse.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('QueryResponse');
      if (message.type === 'QueryResponse') {
        expect(CorrelationId.unwrap(message.correlationId)).toBe('corr-123');
        expect(message.result.toString('utf8')).toBe('test-payload');
      }
    });

    it('should decode ServerRequest matching Scala encoding', () => {
      const expectedHex = readFixture('ServerRequest.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('ServerRequest');
      if (message.type === 'ServerRequest') {
        expect(RequestId.unwrap(message.requestId)).toBe(100n);
        expect(message.payload.toString('utf8')).toBe('test-payload');
        expect(message.createdAt.getTime()).toBe(testTimestamp.getTime());
      }
    });

    it('should decode RequestError matching Scala encoding', () => {
      const expectedHex = readFixture('RequestError.hex');
      const buffer = fromHex(expectedHex);
      
      const message = decodeServerMessage(buffer);
      
      expect(message.type).toBe('RequestError');
      if (message.type === 'RequestError') {
        expect(RequestId.unwrap(message.requestId)).toBe(100n);
        expect(message.reason).toBe('ResponseEvicted');
      }
    });
  });
});
