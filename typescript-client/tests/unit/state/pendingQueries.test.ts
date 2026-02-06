// Unit tests for PendingQueries

import { describe, it, expect } from 'vitest';
import { PendingQueries, PendingQueryData } from '../../../src/state/pendingQueries';
import { CorrelationId } from '../../../src/types';

function makePendingQuery(
  overrides?: Partial<{ resolve: (r: Buffer) => void; reject: (e: Error) => void; lastSentAt: Date }>
): PendingQueryData {
  return {
    payload: Buffer.from('test-payload'),
    resolve: overrides?.resolve ?? (() => {}),
    reject: overrides?.reject ?? (() => {}),
    createdAt: new Date(),
    lastSentAt: overrides?.lastSentAt ?? new Date(),
  };
}

describe('PendingQueries', () => {
  it('complete() should call resolve and remove the entry', () => {
    let resolvedWith: Buffer | null = null;
    const id = CorrelationId.fromString('q1');
    const pq = new PendingQueries().add(
      id,
      makePendingQuery({
        resolve: (r) => {
          resolvedWith = r;
        },
      })
    );

    const result = pq.complete(id, Buffer.from('response'));

    expect(resolvedWith).not.toBeNull();
    expect(resolvedWith!.toString()).toBe('response');
    expect(result.size()).toBe(0);
  });

  it('complete() on non-existent ID should return same instance', () => {
    const pq = new PendingQueries();
    const unknownId = CorrelationId.fromString('unknown');

    const result = pq.complete(unknownId, Buffer.from('data'));

    expect(result).toBe(pq); // same reference — no copy made
  });

  it('fail() should call reject and remove the entry', () => {
    let rejectedWith: Error | null = null;
    const id = CorrelationId.fromString('q1');
    const pq = new PendingQueries().add(
      id,
      makePendingQuery({
        reject: (e) => {
          rejectedWith = e;
        },
      })
    );

    const result = pq.fail(id, new Error('test error'));

    expect(rejectedWith).not.toBeNull();
    expect(rejectedWith!.message).toBe('test error');
    expect(result.size()).toBe(0);
  });

  it('resendExpired() should only resend queries past the timeout', () => {
    const now = new Date();
    const oldTime = new Date(now.getTime() - 10000); // 10 seconds ago
    const recentTime = new Date(now.getTime() - 100); // 100ms ago

    const pq = new PendingQueries()
      .add(CorrelationId.fromString('old'), makePendingQuery({ lastSentAt: oldTime }))
      .add(CorrelationId.fromString('recent'), makePendingQuery({ lastSentAt: recentTime }));

    const result = pq.resendExpired(now, 5000); // 5 second timeout

    expect(result.toResend).toHaveLength(1);
    expect(CorrelationId.unwrap(result.toResend[0].correlationId)).toBe('old');
    // Both queries should still be tracked (resend doesn't remove)
    expect(result.queries.size()).toBe(2);
  });

  it('failAll() should reject all pending queries and return empty instance', () => {
    const rejections: string[] = [];
    const pq = new PendingQueries()
      .add(CorrelationId.fromString('q1'), makePendingQuery({ reject: (e) => rejections.push(e.message) }))
      .add(CorrelationId.fromString('q2'), makePendingQuery({ reject: (e) => rejections.push(e.message) }));

    const result = pq.failAll(new Error('session closed'));

    expect(rejections).toEqual(['session closed', 'session closed']);
    expect(result.size()).toBe(0);
  });
});
