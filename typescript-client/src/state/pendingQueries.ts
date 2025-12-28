// Pending queries tracker for managing in-flight read-only queries

import { CorrelationId } from '../types';

/**
 * Data for a pending query
 */
export interface PendingQueryData {
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
  readonly createdAt: Date;
  lastSentAt: Date; // Mutable for resend tracking
}

/**
 * Tracker for pending queries (read-only operations)
 */
export class PendingQueries {
  private queries: Map<CorrelationId, PendingQueryData> = new Map();

  /**
   * Add a new pending query
   */
  add(correlationId: CorrelationId, data: PendingQueryData): void {
    this.queries.set(correlationId, data);
  }

  /**
   * Complete a pending query with success
   */
  complete(correlationId: CorrelationId, result: Buffer): boolean {
    const data = this.queries.get(correlationId);
    if (data === undefined) {
      return false;
    }
    
    this.queries.delete(correlationId);
    data.resolve(result);
    return true;
  }

  /**
   * Fail a pending query with error
   */
  fail(correlationId: CorrelationId, error: Error): boolean {
    const data = this.queries.get(correlationId);
    if (data === undefined) {
      return false;
    }
    
    this.queries.delete(correlationId);
    data.reject(error);
    return true;
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
   * Resend all pending queries (returns payload and correlation ID pairs)
   */
  resendAll(currentTime: Date): Array<{ correlationId: CorrelationId; payload: Buffer }> {
    const toResend: Array<{ correlationId: CorrelationId; payload: Buffer }> = [];
    
    for (const [correlationId, data] of this.queries) {
      data.lastSentAt = currentTime;
      toResend.push({ correlationId, payload: data.payload });
    }
    
    return toResend;
  }

  /**
   * Resend expired queries (where lastSentAt + timeout < current time)
   */
  resendExpired(currentTime: Date, timeoutMs: number): Array<{ correlationId: CorrelationId; payload: Buffer }> {
    const toResend: Array<{ correlationId: CorrelationId; payload: Buffer }> = [];
    
    for (const [correlationId, data] of this.queries) {
      const elapsed = currentTime.getTime() - data.lastSentAt.getTime();
      if (elapsed >= timeoutMs) {
        data.lastSentAt = currentTime;
        toResend.push({ correlationId, payload: data.payload });
      }
    }
    
    return toResend;
  }

  /**
   * Fail all pending queries (used on session termination)
   */
  dieAll(error: Error): void {
    for (const [correlationId, data] of this.queries) {
      data.reject(error);
    }
    this.queries.clear();
  }

  /**
   * Get the number of pending queries
   */
  size(): number {
    return this.queries.size;
  }

  /**
   * Clear all pending queries without failing them (for testing)
   */
  clear(): void {
    this.queries.clear();
  }
}

