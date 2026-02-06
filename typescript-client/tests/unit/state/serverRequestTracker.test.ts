// Unit tests for ServerRequestTracker

import { describe, it, expect } from 'vitest';
import { ServerRequestTracker } from '../../../src/state/serverRequestTracker';
import { RequestId } from '../../../src/types';

describe('ServerRequestTracker', () => {
  it('shouldProcess returns OldRequest for duplicate (incoming <= current)', () => {
    // Fresh tracker has lastAcknowledged = 0n
    const tracker = new ServerRequestTracker();

    const result = tracker.shouldProcess(RequestId.fromBigInt(0n));

    expect(result.type).toBe('OldRequest');
  });

  it('shouldProcess returns OutOfOrder for gap (incoming > current + 1)', () => {
    const tracker = new ServerRequestTracker();

    const result = tracker.shouldProcess(RequestId.fromBigInt(3n));

    expect(result.type).toBe('OutOfOrder');
  });

  it('withLastAcknowledged creates updated tracker that correctly processes next request', () => {
    const tracker = new ServerRequestTracker();

    // Advance to 2n
    const updated = tracker.withLastAcknowledged(RequestId.fromBigInt(2n));

    // 3n is now the next expected
    expect(updated.shouldProcess(RequestId.fromBigInt(3n)).type).toBe('Process');
    // 2n is now old
    expect(updated.shouldProcess(RequestId.fromBigInt(2n)).type).toBe('OldRequest');
    // Original tracker is unchanged (immutable)
    expect(tracker.shouldProcess(RequestId.fromBigInt(1n)).type).toBe('Process');
  });
});
