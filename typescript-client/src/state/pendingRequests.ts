// Pending requests tracker for managing in-flight client requests (commands)

import { RequestId } from '../types';

/**
 * Data for a pending request
 */
export interface PendingRequestData {
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
  readonly createdAt: Date;
  lastSentAt: Date; // Mutable for resend tracking
}

/**
 * Tracker for pending client requests (commands)
 */
export class PendingRequests {
  private requests: Map<RequestId, PendingRequestData> = new Map();

  /**
   * Add a new pending request
   */
  add(requestId: RequestId, data: PendingRequestData): void {
    this.requests.set(requestId, data);
  }

  /**
   * Complete a pending request with success
   */
  complete(requestId: RequestId, result: Buffer): boolean {
    const data = this.requests.get(requestId);
    if (data === undefined) {
      return false;
    }
    
    this.requests.delete(requestId);
    data.resolve(result);
    return true;
  }

  /**
   * Fail a pending request with error
   */
  fail(requestId: RequestId, error: Error): boolean {
    const data = this.requests.get(requestId);
    if (data === undefined) {
      return false;
    }
    
    this.requests.delete(requestId);
    data.reject(error);
    return true;
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
   * Resend all pending requests (returns payload and request ID pairs)
   */
  resendAll(currentTime: Date): Array<{ requestId: RequestId; payload: Buffer }> {
    const toResend: Array<{ requestId: RequestId; payload: Buffer }> = [];
    
    for (const [requestId, data] of this.requests) {
      data.lastSentAt = currentTime;
      toResend.push({ requestId, payload: data.payload });
    }
    
    return toResend;
  }

  /**
   * Resend expired requests (where lastSentAt + timeout < current time)
   */
  resendExpired(currentTime: Date, timeoutMs: number): Array<{ requestId: RequestId; payload: Buffer }> {
    const toResend: Array<{ requestId: RequestId; payload: Buffer }> = [];
    
    for (const [requestId, data] of this.requests) {
      const elapsed = currentTime.getTime() - data.lastSentAt.getTime();
      if (elapsed >= timeoutMs) {
        data.lastSentAt = currentTime;
        toResend.push({ requestId, payload: data.payload });
      }
    }
    
    return toResend;
  }

  /**
   * Fail all pending requests (used on session termination)
   */
  failAll(error: Error): void {
    for (const [_requestId, data] of this.requests) {
      data.reject(error);
    }
    this.requests.clear();
  }

  /**
   * Get the number of pending requests
   */
  size(): number {
    return this.requests.size;
  }

  /**
   * Clear all pending requests without failing them (for testing)
   */
  clear(): void {
    this.requests.clear();
  }
}

