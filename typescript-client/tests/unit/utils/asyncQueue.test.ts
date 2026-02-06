// Unit tests for AsyncQueue

import { describe, it, expect } from 'vitest';
import { AsyncQueue } from '../../../src/utils/asyncQueue';

describe('AsyncQueue', () => {
  it('should throw when offering to a closed queue', () => {
    const q = new AsyncQueue<number>();
    q.close();

    expect(() => q.offer(1)).toThrow('Queue is closed');
  });

  it('should deliver directly to a waiting taker, bypassing the buffer', async () => {
    const q = new AsyncQueue<number>();

    // Start take() first — it will wait since queue is empty
    const takePromise = q.take();

    // Offer an item — should deliver directly to the waiting take()
    q.offer(42);

    const result = await takePromise;
    expect(result).toBe(42);
  });

  it('should wait on take() and resolve when an item is offered later', async () => {
    const q = new AsyncQueue<number>();

    let resolved = false;
    const takePromise = q.take().then((v) => {
      resolved = true;
      return v;
    });

    // Not resolved yet — no items available
    await Promise.resolve(); // flush microtasks
    expect(resolved).toBe(false);

    q.offer(99);

    const result = await takePromise;
    expect(result).toBe(99);
    expect(resolved).toBe(true);
  });

  it('should reject take() on a closed empty queue', async () => {
    const q = new AsyncQueue<number>();
    q.close();

    await expect(q.take()).rejects.toThrow('Queue is closed and empty');
  });

  it('should signal all waiting consumers when closed', async () => {
    const q = new AsyncQueue<number>();

    // Two consumers waiting
    const take1 = q.take();
    const take2 = q.take();

    q.close();

    await expect(take1).rejects.toThrow('Queue is closed and empty');
    await expect(take2).rejects.toThrow('Queue is closed and empty');
  });

  it('should yield items and terminate via for-await-of on close', async () => {
    const q = new AsyncQueue<number>();
    q.offer(1);
    q.offer(2);
    q.offer(3);
    q.close(); // close immediately — iterator should yield buffered items then stop

    const collected: number[] = [];
    for await (const item of q) {
      collected.push(item);
    }

    expect(collected).toEqual([1, 2, 3]);
  });
});
