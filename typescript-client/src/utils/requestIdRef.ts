// Request ID reference counter for generating monotonically increasing request IDs

import { RequestId } from '../types';

/**
 * Mutable reference to the current request ID
 * Provides thread-safe (single-threaded in Node.js) incrementing
 */
export interface RequestIdRef {
  /** Current request ID value */
  current: RequestId;
  
  /** Get the next request ID (increments current) */
  next(): RequestId;
}

/**
 * Create a new RequestIdRef starting at 0
 */
export function createRequestIdRef(): RequestIdRef {
  let current: RequestId = RequestId.zero;
  
  return {
    get current(): RequestId {
      return current;
    },
    
    set current(value: RequestId) {
      current = value;
    },
    
    next(): RequestId {
      current = RequestId.next(current);
      return current;
    },
  };
}

