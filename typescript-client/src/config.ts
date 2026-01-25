// Client configuration with validation and defaults
// Follows idiomatic TypeScript: plain object configuration, NOT builder pattern

import { MemberId } from './types';

/**
 * Client configuration
 */
export interface ClientConfig {
  readonly clusterMembers: Map<MemberId, string>; // memberId -> ZMQ address
  readonly capabilities: Map<string, string>; // user-provided capabilities
  readonly connectionTimeout: number; // milliseconds, default 5000
  readonly keepAliveInterval: number; // milliseconds, default 30000
  readonly requestTimeout: number; // milliseconds, default 10000
}

/**
 * Default configuration values
 */
export const DEFAULT_CONNECTION_TIMEOUT = 5000; // 5 seconds
export const DEFAULT_KEEP_ALIVE_INTERVAL = 30000; // 30 seconds
export const DEFAULT_REQUEST_TIMEOUT = 10000; // 10 seconds

/**
 * Partial client configuration (user-provided)
 * All fields are optional except clusterMembers and capabilities
 */
export interface ClientConfigInput {
  readonly clusterMembers: Map<MemberId, string>;
  readonly capabilities: Map<string, string>;
  readonly connectionTimeout?: number;
  readonly keepAliveInterval?: number;
  readonly requestTimeout?: number;
}

/**
 * Validate client configuration
 * Throws synchronous error if invalid
 */
export function validateConfig(config: ClientConfig): void {
  if (config.clusterMembers.size === 0) {
    throw new Error('clusterMembers cannot be empty');
  }

  if (config.capabilities.size === 0) {
    throw new Error('capabilities cannot be empty');
  }

  if (config.connectionTimeout <= 0) {
    throw new Error('connectionTimeout must be positive');
  }

  if (config.keepAliveInterval <= 0) {
    throw new Error('keepAliveInterval must be positive');
  }

  if (config.requestTimeout <= 0) {
    throw new Error('requestTimeout must be positive');
  }

  // Validate cluster member addresses
  for (const [memberId, address] of config.clusterMembers.entries()) {
    if (!address || address.length === 0) {
      throw new Error(`Cluster member ${MemberId.unwrap(memberId)} has empty address`);
    }

    // Basic ZMQ address validation (tcp://host:port or ipc://path)
    if (!address.startsWith('tcp://') && !address.startsWith('ipc://')) {
      throw new Error(
        `Cluster member ${MemberId.unwrap(memberId)} has invalid address: ${address} (must start with tcp:// or ipc://)`
      );
    }
  }
}

/**
 * Create a complete ClientConfig from partial input
 * Applies defaults for missing values
 */
export function createConfig(input: ClientConfigInput): ClientConfig {
  const config: ClientConfig = {
    clusterMembers: input.clusterMembers,
    capabilities: input.capabilities,
    connectionTimeout: input.connectionTimeout ?? DEFAULT_CONNECTION_TIMEOUT,
    keepAliveInterval: input.keepAliveInterval ?? DEFAULT_KEEP_ALIVE_INTERVAL,
    requestTimeout: input.requestTimeout ?? DEFAULT_REQUEST_TIMEOUT,
  };

  // Validate before returning
  validateConfig(config);

  return config;
}
