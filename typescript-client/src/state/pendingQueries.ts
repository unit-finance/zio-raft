// Pending queries tracker for managing in-flight read-only queries
// Uses immutable pattern - methods return new instances instead of mutating

import { CorrelationId } from '../types';

/**
 * Data for a pending query (immutable)
 */
export interface PendingQueryData {
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
  readonly createdAt: Date;
  readonly lastSentAt: Date;
}

/**
 * Result of resend operations - includes new instance and items to resend
 */
export interface QueryResendResult {
  readonly queries: PendingQueries;
  readonly toResend: ReadonlyArray<{ correlationId: CorrelationId; payload: Buffer }>;
}

/**
 * Immutable tracker for pending queries (read-only operations)
 * All mutating methods return a new PendingQueries instance
 */
export class PendingQueries {
  private readonly queries: ReadonlyMap<CorrelationId, PendingQueryData>;

  constructor(queries: ReadonlyMap<CorrelationId, PendingQueryData> = new Map()) {
    this.queries = queries;
  }

  /**
   * Add a new pending query - returns new instance
   */
  add(correlationId: CorrelationId, data: PendingQueryData): PendingQueries {
    const newMap = new Map(this.queries);
    newMap.set(correlationId, data);
    return new PendingQueries(newMap);
  }

  /**
   * Complete a pending query with success - returns new instance
   * Calls resolve callback if query exists
   */
  complete(correlationId: CorrelationId, result: Buffer): PendingQueries {
    const data = this.queries.get(correlationId);
    if (data === undefined) {
      return this;
    }

    const newMap = new Map(this.queries);
    newMap.delete(correlationId);
    data.resolve(result);
    return new PendingQueries(newMap);
  }

  /**
   * Fail a pending query with error - returns new instance
   * Calls reject callback if query exists
   */
  fail(correlationId: CorrelationId, error: Error): PendingQueries {
    const data = this.queries.get(correlationId);
    if (data === undefined) {
      return this;
    }

    const newMap = new Map(this.queries);
    newMap.delete(correlationId);
    data.reject(error);
    return new PendingQueries(newMap);
  }

  /**
   * Check if a correlation ID is pending
   */
  contains(correlationId: CorrelationId): boolean {
    return this.queries.has(correlationId);
  }

  /**
   * Get all pending correlation IDs
   */
  getPendingIds(): CorrelationId[] {
    return Array.from(this.queries.keys());
  }

  /**
   * Resend all pending queries - returns new instance with updated lastSentAt
   */
  resendAll(currentTime: Date): QueryResendResult {
    const toResend: Array<{ correlationId: CorrelationId; payload: Buffer }> = [];
    const newMap = new Map<CorrelationId, PendingQueryData>();

    for (const [correlationId, data] of this.queries) {
      // Create new data with updated lastSentAt
      const updatedData: PendingQueryData = {
        ...data,
        lastSentAt: currentTime,
      };
      newMap.set(correlationId, updatedData);
      toResend.push({ correlationId, payload: data.payload });
    }

    return {
      queries: new PendingQueries(newMap),
      toResend,
    };
  }

  /**
   * Resend expired queries - returns new instance with updated lastSentAt for expired items
   */
  resendExpired(currentTime: Date, timeoutMs: number): QueryResendResult {
    const toResend: Array<{ correlationId: CorrelationId; payload: Buffer }> = [];
    const newMap = new Map<CorrelationId, PendingQueryData>();

    for (const [correlationId, data] of this.queries) {
      const elapsed = currentTime.getTime() - data.lastSentAt.getTime();
      if (elapsed >= timeoutMs) {
        // Create new data with updated lastSentAt
        const updatedData: PendingQueryData = {
          ...data,
          lastSentAt: currentTime,
        };
        newMap.set(correlationId, updatedData);
        toResend.push({ correlationId, payload: data.payload });
      } else {
        // Keep existing data
        newMap.set(correlationId, data);
      }
    }

    return {
      queries: new PendingQueries(newMap),
      toResend,
    };
  }

  /**
   * Fail all pending queries - calls reject on all, returns empty instance
   */
  failAll(error: Error): PendingQueries {
    for (const [, data] of this.queries) {
      data.reject(error);
    }
    return new PendingQueries();
  }

  /**
   * Get the number of pending queries
   */
  size(): number {
    return this.queries.size;
  }

  /**
   * Clear all pending queries without failing them - returns empty instance
   */
  clear(): PendingQueries {
    return new PendingQueries();
  }
}
