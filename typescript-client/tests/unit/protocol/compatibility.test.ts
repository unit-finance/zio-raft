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
import { encodeClientMessage, decodeServerMessage } from '../../../src/protocol/codecs';
import { SessionId, RequestId, MemberId, Nonce, CorrelationId } from '../../../src/types';
import {
  CreateSession,
  ContinueSession,
  KeepAlive,
  ClientRequest,
  Query,
  ServerRequestAck,
  CloseSession,
  ConnectionClosed,
  SessionCreated,
  SessionContinued,
  SessionRejected,
  SessionClosed,
  KeepAliveResponse,
  ClientResponse,
  QueryResponse,
  ServerRequest,
  RequestError,
} from '../../../src/protocol/messages';

// Fixed test data matching Scala tests
const testTimestamp = new Date(1705680000000); // 2024-01-19 12:00:00 UTC
const testSessionId = SessionId.fromString('test-session-123');
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
  const fixturePath = join(
    __dirname,
    '../../../..',
    'client-server-protocol',
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
  describe('Client Message Encoding', () => {
    it('should encode CreateSession matching Scala output', () => {
      const message: CreateSession = {
        type: 'CreateSession',
        capabilities: testCapabilities,
        nonce: testNonce,
      };

      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);

      const expectedHex = readFixture('CreateSession.hex');

      expect(hex).toBe(expectedHex);
    });

    it('should encode ContinueSession matching Scala output', () => {
      const message: ContinueSession = {
        type: 'ContinueSession',
        sessionId: testSessionId,
        nonce: testNonce,
      };

      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);

      const expectedHex = readFixture('ContinueSession.hex');

      expect(hex).toBe(expectedHex);
    });

    it('should encode KeepAlive matching Scala output', () => {
      const message: KeepAlive = {
        type: 'KeepAlive',
        timestamp: testTimestamp,
      };

      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);

      const expectedHex = readFixture('KeepAlive.hex');

      expect(hex).toBe(expectedHex);
    });

    it('should encode ClientRequest matching Scala output', () => {
      const message: ClientRequest = {
        type: 'ClientRequest',
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
      const message: Query = {
        type: 'Query',
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
      const message: ServerRequestAck = {
        type: 'ServerRequestAck',
        requestId: testRequestId,
      };

      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);

      const expectedHex = readFixture('ServerRequestAck.hex');

      expect(hex).toBe(expectedHex);
    });

    it('should encode CloseSession matching Scala output', () => {
      const message: CloseSession = {
        type: 'CloseSession',
        reason: 'ClientShutdown',
      };

      const buffer = encodeClientMessage(message);
      const hex = toHex(buffer);

      const expectedHex = readFixture('CloseSession.hex');

      expect(hex).toBe(expectedHex);
    });

    it('should encode ConnectionClosed matching Scala output', () => {
      const message: ConnectionClosed = {
        type: 'ConnectionClosed',
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
      const msg = message as SessionCreated;
      expect(SessionId.unwrap(msg.sessionId)).toBe('test-session-123');
      expect(Nonce.unwrap(msg.nonce)).toBe(42n);
    });

    it('should decode SessionContinued matching Scala encoding', () => {
      const expectedHex = readFixture('SessionContinued.hex');
      const buffer = fromHex(expectedHex);

      const message = decodeServerMessage(buffer);

      expect(message.type).toBe('SessionContinued');
      const msg = message as SessionContinued;
      expect(Nonce.unwrap(msg.nonce)).toBe(42n);
    });

    it('should decode SessionRejected matching Scala encoding', () => {
      const expectedHex = readFixture('SessionRejected.hex');
      const buffer = fromHex(expectedHex);

      const message = decodeServerMessage(buffer);

      expect(message.type).toBe('SessionRejected');
      const msg = message as SessionRejected;
      expect(msg.reason).toBe('NotLeader');
      expect(Nonce.unwrap(msg.nonce)).toBe(42n);
      expect(msg.leaderId).toBeDefined();
      expect(MemberId.unwrap(msg.leaderId!)).toBe('node-1');
    });

    it('should decode SessionRejected with no leaderId', () => {
      const expectedHex = readFixture('SessionRejectedNoLeader.hex');
      const buffer = fromHex(expectedHex);

      const message = decodeServerMessage(buffer);

      expect(message.type).toBe('SessionRejected');
      const msg = message as SessionRejected;
      expect(msg.reason).toBe('InvalidCapabilities');
      expect(Nonce.unwrap(msg.nonce)).toBe(42n);
      expect(msg.leaderId).toBeUndefined();
    });

    it('should decode SessionClosed matching Scala encoding', () => {
      const expectedHex = readFixture('SessionClosed.hex');
      const buffer = fromHex(expectedHex);

      const message = decodeServerMessage(buffer);

      expect(message.type).toBe('SessionClosed');
      const msg = message as SessionClosed;
      expect(msg.reason).toBe('Shutdown');
      expect(msg.leaderId).toBeDefined();
      expect(MemberId.unwrap(msg.leaderId!)).toBe('node-1');
    });

    it('should decode KeepAliveResponse matching Scala encoding', () => {
      const expectedHex = readFixture('KeepAliveResponse.hex');
      const buffer = fromHex(expectedHex);

      const message = decodeServerMessage(buffer);

      expect(message.type).toBe('KeepAliveResponse');
      const msg = message as KeepAliveResponse;
      expect(msg.timestamp.getTime()).toBe(testTimestamp.getTime());
    });

    it('should decode ClientResponse matching Scala encoding', () => {
      const expectedHex = readFixture('ClientResponse.hex');
      const buffer = fromHex(expectedHex);

      const message = decodeServerMessage(buffer);

      expect(message.type).toBe('ClientResponse');
      const msg = message as ClientResponse;
      expect(RequestId.unwrap(msg.requestId)).toBe(100n);
      expect(msg.result.toString('utf8')).toBe('test-payload');
    });

    it('should decode QueryResponse matching Scala encoding', () => {
      const expectedHex = readFixture('QueryResponse.hex');
      const buffer = fromHex(expectedHex);

      const message = decodeServerMessage(buffer);

      expect(message.type).toBe('QueryResponse');
      const msg = message as QueryResponse;
      expect(CorrelationId.unwrap(msg.correlationId)).toBe('corr-123');
      expect(msg.result.toString('utf8')).toBe('test-payload');
    });

    it('should decode ServerRequest matching Scala encoding', () => {
      const expectedHex = readFixture('ServerRequest.hex');
      const buffer = fromHex(expectedHex);

      const message = decodeServerMessage(buffer);

      expect(message.type).toBe('ServerRequest');
      const msg = message as ServerRequest;
      expect(RequestId.unwrap(msg.requestId)).toBe(100n);
      expect(msg.payload.toString('utf8')).toBe('test-payload');
      expect(msg.createdAt.getTime()).toBe(testTimestamp.getTime());
    });

    it('should decode RequestError matching Scala encoding', () => {
      const expectedHex = readFixture('RequestError.hex');
      const buffer = fromHex(expectedHex);

      const message = decodeServerMessage(buffer);

      expect(message.type).toBe('RequestError');
      const msg = message as RequestError;
      expect(RequestId.unwrap(msg.requestId)).toBe(100n);
      expect(msg.reason).toBe('ResponseEvicted');
    });
  });
});
