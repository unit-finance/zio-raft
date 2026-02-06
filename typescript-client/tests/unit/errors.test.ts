// Unit tests for custom error classes

import { describe, it, expect } from 'vitest';
import { RaftClientError, TimeoutError } from '../../src/errors';
import { RequestId, CorrelationId } from '../../src/types';

describe('Error Classes', () => {
  describe('RaftClientError', () => {
    it('should maintain instanceof chain through Object.setPrototypeOf', () => {
      const error = new RaftClientError('test error');

      expect(error).toBeInstanceOf(Error);
      expect(error).toBeInstanceOf(RaftClientError);
      expect(error.name).toBe('RaftClientError');
      expect(error.message).toBe('test error');
    });
  });

  describe('TimeoutError', () => {
    it('should store RequestId when given a bigint id', () => {
      const requestId = RequestId.fromBigInt(42n);
      const error = new TimeoutError('request timed out', requestId);

      expect(error.requestId).toBe(requestId);
      expect(error.correlationId).toBeUndefined();
      expect(error.name).toBe('TimeoutError');
    });

    it('should store CorrelationId when given a string id', () => {
      const correlationId = CorrelationId.fromString('corr-123');
      const error = new TimeoutError('query timed out', correlationId);

      expect(error.correlationId).toBe(correlationId);
      expect(error.requestId).toBeUndefined();
      expect(error.name).toBe('TimeoutError');
    });
  });
});
