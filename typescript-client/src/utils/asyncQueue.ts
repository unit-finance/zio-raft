// Async queue for handling user actions
// Follows idiomatic TypeScript: simple class with private queue, Promise-based async methods

/**
 * Async queue for handling items asynchronously
 * Used for queuing user actions (connect, disconnect, submit)
 * Implements AsyncIterable for consumption via for-await-of
 */
export class AsyncQueue<T> implements AsyncIterable<T> {
  private queue: T[] = [];
  private waiting: Array<(item: T | IteratorResult<T>) => void> = [];
  private closed = false;

  /**
   * Offer an item to the queue (non-blocking)
   * Resolves immediately - item is queued
   */
  offer(item: T): void {
    if (this.closed) {
      throw new Error('Queue is closed');
    }

    const waiter = this.waiting.shift();
    if (waiter) {
      // Someone is waiting for an item - give it to them immediately
      waiter(item);
    } else {
      // No one waiting - queue the item
      this.queue.push(item);
    }
  }

  /**
   * Put an item into the queue
   * Resolves immediately if there's a waiting taker, otherwise queues the item
   */
  async put(item: T): Promise<void> {
    this.offer(item);
  }

  /**
   * Take an item from the queue
   * Returns immediately if an item is available, otherwise waits for one
   */
  async take(): Promise<T> {
    if (this.queue.length > 0) {
      // Item available - return it immediately
      const item = this.queue.shift();
      if (item === undefined) {
        throw new Error('Unexpected: queue item is undefined');
      }
      return item;
    }

    if (this.closed) {
      throw new Error('Queue is closed and empty');
    }

    // No items available - wait for one
    return new Promise<T>((resolve) => {
      this.waiting.push((item) => {
        if (typeof item === 'object' && item !== null && 'done' in item) {
          // IteratorResult - queue is closed
          throw new Error('Queue is closed and empty');
        }
        resolve(item as T);
      });
    });
  }

  /**
   * Async iterator implementation for for-await-of support
   */
  async *[Symbol.asyncIterator](): AsyncIterator<T> {
    while (true) {
      if (this.queue.length > 0) {
        const item = this.queue.shift();
        if (item !== undefined) {
          yield item;
        }
      } else if (this.closed) {
        // Queue is closed and empty - stop iteration
        return;
      } else {
        // Wait for next item
        const result = await new Promise<T | IteratorResult<T>>((resolve) => {
          this.waiting.push(resolve);
        });
        
        if (typeof result === 'object' && result !== null && 'done' in result && result.done) {
          // IteratorResult with done=true - stop iteration
          return;
        }
        
        yield result as T;
      }
    }
  }

  /**
   * Try to take an item from the queue without waiting
   * Returns undefined if no items are available
   */
  tryTake(): T | undefined {
    return this.queue.shift();
  }

  /**
   * Check if the queue is empty
   */
  isEmpty(): boolean {
    return this.queue.length === 0;
  }

  /**
   * Get the current size of the queue
   */
  size(): number {
    return this.queue.length;
  }

  /**
   * Close the queue
   * After closing, put() will throw an error
   * Pending take() calls will receive done signal
   */
  close(): void {
    this.closed = true;
    
    // Signal all waiting consumers that queue is closed
    const doneResult: IteratorResult<T> = { done: true, value: undefined };
    for (const waiter of this.waiting) {
      waiter(doneResult);
    }
    this.waiting = [];
  }

  /**
   * Check if the queue is closed
   */
  isClosed(): boolean {
    return this.closed;
  }

  /**
   * Clear all items from the queue
   */
  clear(): void {
    this.queue = [];
  }
}

