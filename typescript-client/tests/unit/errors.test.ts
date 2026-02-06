// Unit tests for custom error classes

import { describe, it, expect } from 'vitest';
import {
  TimeoutError,
  ConnectionError,
  SessionExpiredError,
  ProtocolError,
} from '../../src/errors';
import { RequestId, CorrelationId, SessionId } from '../../src/types';

describe('Error Classes', () => {
  describe('TimeoutError', () => {
    it('should store RequestId when given a bigint id', () => {
      const requestId = RequestId.fromBigInt(42n);
      const error = new TimeoutError('request timed out', requestId);

      expect(error.requestId).toBe(requestId);
      expect(error.correlationId).toBeUndefined();
    });

    it('should store CorrelationId when given a string id', () => {
      const correlationId = CorrelationId.fromString('corr-123');
      const error = new TimeoutError('query timed out', correlationId);

      expect(error.correlationId).toBe(correlationId);
      expect(error.requestId).toBeUndefined();
    });

    it('should leave both fields undefined when no id is provided', () => {
      const error = new TimeoutError('timed out');

      expect(error.requestId).toBeUndefined();
      expect(error.correlationId).toBeUndefined();
    });
  });

  describe('ConnectionError', () => {
    it('should store endpoint and cause', () => {
      const cause = new Error('ECONNREFUSED');
      const error = new ConnectionError('connection failed', 'tcp://localhost:5555', cause);

      expect(error.endpoint).toBe('tcp://localhost:5555');
      expect(error.cause).toBe(cause);
    });
  });

  describe('SessionExpiredError', () => {
    it('should store sessionId and format message', () => {
      const sessionId = SessionId.fromString('sess-123');
      const error = new SessionExpiredError(sessionId);

      expect(error.sessionId).toBe(sessionId);
      expect(error.message).toContain('sess-123');
    });
  });

  describe('ProtocolError', () => {
    it('should prefix message with "Protocol error:"', () => {
      const error = new ProtocolError('invalid frame');

      expect(error.message).toBe('Protocol error: invalid frame');
    });
  });
});
