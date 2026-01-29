// Utility to merge multiple async iterables into a single stream
// Yields items from any stream as they arrive (race semantics)

/**
 * Merge multiple async iterables into a single async iterable
 * Items are yielded as they arrive from any source stream
 */
export async function* mergeStreams<T>(...iterables: AsyncIterable<T>[]): AsyncIterable<T> {
  if (iterables.length === 0) {
    return;
  }

  // Create an array of promises, one for each iterable
  const iterators = iterables.map((iterable) => iterable[Symbol.asyncIterator]());
  const pending = new Map<number, Promise<{ index: number; result: IteratorResult<T> }>>();

  // Initialize promises for all iterators
  for (let i = 0; i < iterators.length; i++) {
    pending.set(
      i,
      iterators[i]!.next().then((result) => ({ index: i, result }))
    );
  }

  // Race all pending promises, yield results, and continue
  while (pending.size > 0) {
    const { index, result } = await Promise.race(pending.values());

    if (result.done === true) {
      // Iterator is exhausted, remove from pending
      pending.delete(index);
    } else {
      // Yield the value
      yield result.value;

      // Queue the next value from this iterator
      pending.set(
        index,
        iterators[index]!.next().then((r) => ({ index, result: r }))
      );
    }
  }
}
