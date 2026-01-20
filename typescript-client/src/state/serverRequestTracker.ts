// Server request tracker for deduplicating server-initiated requests

import { RequestId } from '../types';

/**
 * Result of evaluating whether to process a server request
 */
export type ServerRequestResult =
  | { readonly type: 'Process' } // New request, should process
  | { readonly type: 'OldRequest' } // Already processed, re-ack
  | { readonly type: 'OutOfOrder' }; // Gap detected, drop

/**
 * Tracker for server-initiated requests to handle deduplication
 */
export class ServerRequestTracker {
  private lastAcknowledgedRequestId: RequestId;

  constructor(initialRequestId: RequestId = RequestId.zero) {
    this.lastAcknowledgedRequestId = initialRequestId;
  }

  /**
   * Determine if a server request should be processed
   */
  shouldProcess(requestId: RequestId): ServerRequestResult {
    const current = RequestId.unwrap(this.lastAcknowledgedRequestId);
    const incoming = RequestId.unwrap(requestId);

    if (incoming === current + 1n) {
      // Next expected request
      return { type: 'Process' };
    } else if (incoming <= current) {
      // Old request (duplicate or replay)
      return { type: 'OldRequest' };
    } else {
      // Gap detected (incoming > current + 1)
      return { type: 'OutOfOrder' };
    }
  }

  /**
   * Create a copy with a new last acknowledged ID (immutable update)
   */
  withLastAcknowledged(requestId: RequestId): ServerRequestTracker {
    return new ServerRequestTracker(requestId);
  }

  /**
   * Get the last acknowledged request ID
   */
  getLastAcknowledgedRequestId(): RequestId {
    return this.lastAcknowledgedRequestId;
  }
}

