// Branded types for type-safe identifiers
// These provide newtype-like safety similar to Scala's Newtype pattern

import { randomUUID } from 'crypto';

/**
 * Session identifier (string-based)
 */
export type SessionId = string & { readonly __brand: 'SessionId' };

export const SessionId = {
  fromString: (value: string): SessionId => {
    if (!value || value.length === 0) {
      throw new Error('SessionId cannot be empty');
    }
    return value as SessionId;
  },
  unwrap: (id: SessionId): string => id as string,
};

/**
 * Request identifier for commands (bigint-based, monotonically increasing)
 */
export type RequestId = bigint & { readonly __brand: 'RequestId' };

export const RequestId = {
  zero: 0n as RequestId,
  fromBigInt: (value: bigint): RequestId => {
    if (value < 0n) {
      throw new Error('RequestId must be non-negative');
    }
    return value as RequestId;
  },
  next: (id: RequestId): RequestId => RequestId.fromBigInt(RequestId.unwrap(id) + 1n),
  unwrap: (id: RequestId): bigint => id as bigint,
};

/**
 * Cluster member identifier (string-based)
 */
export type MemberId = string & { readonly __brand: 'MemberId' };

export const MemberId = {
  fromString: (value: string): MemberId => {
    if (!value || value.length === 0) {
      throw new Error('MemberId cannot be empty');
    }
    return value as MemberId;
  },
  unwrap: (id: MemberId): string => id as string,
};

/**
 * Nonce for request/response correlation (bigint-based, non-zero)
 */
export type Nonce = bigint & { readonly __brand: 'Nonce' };

export const Nonce = {
  generate: (): Nonce => {
    const value = BigInt(Math.floor(Math.random() * Number.MAX_SAFE_INTEGER) + 1);
    return value as Nonce;
  },
  fromBigInt: (value: bigint): Nonce => {
    if (value === 0n) {
      throw new Error('Nonce cannot be zero');
    }
    return value as Nonce;
  },
  unwrap: (nonce: Nonce): bigint => nonce as bigint,
};

/**
 * Correlation identifier for queries (UUID-based)
 */
export type CorrelationId = string & { readonly __brand: 'CorrelationId' };

export const CorrelationId = {
  generate: (): CorrelationId => {
    const uuid = randomUUID();
    return uuid as CorrelationId;
  },
  fromString: (value: string): CorrelationId => {
    if (!value || value.length === 0) {
      throw new Error('CorrelationId cannot be empty');
    }
    return value as CorrelationId;
  },
  unwrap: (id: CorrelationId): string => id as string,
};
