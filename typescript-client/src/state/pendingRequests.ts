// Pending requests tracker for managing in-flight client requests (commands)
// Uses immutable pattern - methods return new instances instead of mutating

import { RequestId } from '../types';

/**
 * Data for a pending request (immutable)
 */
export interface PendingRequestData {
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
  readonly createdAt: Date;
  readonly lastSentAt: Date;
}

/**
 * Result of resend operations - includes new instance and items to resend
 */
export interface ResendResult {
  readonly requests: PendingRequests;
  readonly toResend: ReadonlyArray<{ requestId: RequestId; payload: Buffer }>;
}

/**
 * Immutable tracker for pending client requests (commands)
 * All mutating methods return a new PendingRequests instance
 */
export class PendingRequests {
  private readonly requests: ReadonlyMap<RequestId, PendingRequestData>;

  constructor(requests: ReadonlyMap<RequestId, PendingRequestData> = new Map()) {
    this.requests = requests;
  }

  /**
   * Add a new pending request - returns new instance
   */
  add(requestId: RequestId, data: PendingRequestData): PendingRequests {
    const newMap = new Map(this.requests);
    newMap.set(requestId, data);
    return new PendingRequests(newMap);
  }

  /**
   * Complete a pending request with success - returns new instance
   * Calls resolve callback if request exists
   */
  complete(requestId: RequestId, result: Buffer): PendingRequests {
    const data = this.requests.get(requestId);
    if (data === undefined) {
      return this;
    }

    const newMap = new Map(this.requests);
    newMap.delete(requestId);
    data.resolve(result);
    return new PendingRequests(newMap);
  }

  /**
   * Fail a pending request with error - returns new instance
   * Calls reject callback if request exists
   */
  fail(requestId: RequestId, error: Error): PendingRequests {
    const data = this.requests.get(requestId);
    if (data === undefined) {
      return this;
    }

    const newMap = new Map(this.requests);
    newMap.delete(requestId);
    data.reject(error);
    return new PendingRequests(newMap);
  }

  /**
   * Check if a request ID is pending
   */
  contains(requestId: RequestId): boolean {
    return this.requests.has(requestId);
  }

  /**
   * Get the lowest pending request ID, or return the provided default
   */
  lowestPendingRequestIdOr(defaultId: RequestId): RequestId {
    if (this.requests.size === 0) {
      return defaultId;
    }

    let lowest = defaultId;
    for (const [requestId] of this.requests) {
      if (RequestId.unwrap(requestId) < RequestId.unwrap(lowest)) {
        lowest = requestId;
      }
    }
    return lowest;
  }

  /**
   * Get all pending request IDs
   */
  getPendingIds(): RequestId[] {
    return Array.from(this.requests.keys());
  }

  /**
   * Resend all pending requests - returns new instance with updated lastSentAt
   */
  resendAll(currentTime: Date): ResendResult {
    const toResend: Array<{ requestId: RequestId; payload: Buffer }> = [];
    const newMap = new Map<RequestId, PendingRequestData>();

    for (const [requestId, data] of this.requests) {
      // Create new data with updated lastSentAt
      const updatedData: PendingRequestData = {
        ...data,
        lastSentAt: currentTime,
      };
      newMap.set(requestId, updatedData);
      toResend.push({ requestId, payload: data.payload });
    }

    return {
      requests: new PendingRequests(newMap),
      toResend,
    };
  }

  /**
   * Resend expired requests - returns new instance with updated lastSentAt for expired items
   */
  resendExpired(currentTime: Date, timeoutMs: number): ResendResult {
    const toResend: Array<{ requestId: RequestId; payload: Buffer }> = [];
    const newMap = new Map<RequestId, PendingRequestData>();

    for (const [requestId, data] of this.requests) {
      const elapsed = currentTime.getTime() - data.lastSentAt.getTime();
      if (elapsed >= timeoutMs) {
        // Create new data with updated lastSentAt
        const updatedData: PendingRequestData = {
          ...data,
          lastSentAt: currentTime,
        };
        newMap.set(requestId, updatedData);
        toResend.push({ requestId, payload: data.payload });
      } else {
        // Keep existing data
        newMap.set(requestId, data);
      }
    }

    return {
      requests: new PendingRequests(newMap),
      toResend,
    };
  }

  /**
   * Fail all pending requests - calls reject on all, returns empty instance
   */
  failAll(error: Error): PendingRequests {
    for (const [, data] of this.requests) {
      data.reject(error);
    }
    return new PendingRequests();
  }

  /**
   * Get the number of pending requests
   */
  size(): number {
    return this.requests.size;
  }

  /**
   * Clear all pending requests without failing them - returns empty instance
   */
  clear(): PendingRequests {
    return new PendingRequests();
  }
}
