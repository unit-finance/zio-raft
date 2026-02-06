// Unit tests for PendingRequests — only the unique method lowestPendingRequestIdOr

import { describe, it, expect } from 'vitest';
import { PendingRequests, PendingRequestData } from '../../../src/state/pendingRequests';
import { RequestId } from '../../../src/types';

function makePendingRequest(): PendingRequestData {
  return {
    payload: Buffer.from('test'),
    resolve: () => {},
    reject: () => {},
    createdAt: new Date(),
    lastSentAt: new Date(),
  };
}

describe('PendingRequests', () => {
  describe('lowestPendingRequestIdOr', () => {
    it('should return default when no pending requests', () => {
      const pr = new PendingRequests();
      const defaultId = RequestId.fromBigInt(10n);

      const result = pr.lowestPendingRequestIdOr(defaultId);

      expect(result).toBe(defaultId);
    });

    it('should return the lowest request ID across multiple entries', () => {
      const pr = new PendingRequests()
        .add(RequestId.fromBigInt(5n), makePendingRequest())
        .add(RequestId.fromBigInt(2n), makePendingRequest())
        .add(RequestId.fromBigInt(8n), makePendingRequest());

      const result = pr.lowestPendingRequestIdOr(RequestId.fromBigInt(100n));

      expect(RequestId.unwrap(result)).toBe(2n);
    });
  });
});
