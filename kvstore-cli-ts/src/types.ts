/**
 * Core type definitions for KVStore CLI
 */

/**
 * Command types (discriminated union)
 */
export type Command = SetCommand | GetCommand | WatchCommand;

/**
 * Set command - stores a key-value pair
 */
export interface SetCommand {
  readonly type: 'set';
  readonly key: string;
  readonly value: string;
}

/**
 * Get command - retrieves a value by key
 */
export interface GetCommand {
  readonly type: 'get';
  readonly key: string;
}

/**
 * Watch command - monitors updates to a key
 */
export interface WatchCommand {
  readonly type: 'watch';
  readonly key: string;
}

/**
 * Configuration types
 */
export type MemberId = string;
export type Endpoint = string;

export interface EndpointConfig {
  readonly endpoints: ReadonlyMap<MemberId, Endpoint>;
}

/**
 * Result types
 */
export type CommandResult = SetResult | GetResult | WatchResult;

export interface SetResult {
  readonly type: 'set';
  readonly success: true;
}

export interface GetResult {
  readonly type: 'get';
  readonly value: string | null;
}

export interface WatchResult {
  readonly type: 'watch';
  readonly notifications: WatchNotification[];
}

/**
 * Watch notification
 */
export interface WatchNotification {
  readonly timestamp: Date;
  readonly sequenceNumber: bigint;
  readonly key: string;
  readonly value: string;
  readonly metadata?: Record<string, unknown>;
}

/**
 * Protocol message types
 */
export type KVClientRequest = SetRequest | WatchRequest;

export interface SetRequest {
  readonly type: 'Set';
  readonly key: string;
  readonly value: string;
}

export interface WatchRequest {
  readonly type: 'Watch';
  readonly key: string;
}

/**
 * Query types
 */
export interface KVQuery {
  readonly type: 'Get';
  readonly key: string;
}

/**
 * Server request types
 */
export interface KVServerRequest {
  readonly type: 'Notification';
  readonly key: string;
  readonly value: string;
}
