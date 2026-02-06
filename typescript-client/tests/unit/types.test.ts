// Unit tests for branded type validation

import { describe, it, expect } from 'vitest';
import { SessionId, RequestId, MemberId, Nonce, CorrelationId } from '../../src/types';

describe('Branded Types Validation', () => {
  it('SessionId.fromString rejects empty string', () => {
    expect(() => SessionId.fromString('')).toThrow('SessionId cannot be empty');
  });

  it('RequestId.fromBigInt rejects negative value', () => {
    expect(() => RequestId.fromBigInt(-1n)).toThrow('RequestId must be non-negative');
  });

  it('MemberId.fromString rejects empty string', () => {
    expect(() => MemberId.fromString('')).toThrow('MemberId cannot be empty');
  });

  it('Nonce.fromBigInt rejects zero', () => {
    expect(() => Nonce.fromBigInt(0n)).toThrow('Nonce cannot be zero');
  });

  it('CorrelationId.fromString rejects empty string', () => {
    expect(() => CorrelationId.fromString('')).toThrow('CorrelationId cannot be empty');
  });
});
