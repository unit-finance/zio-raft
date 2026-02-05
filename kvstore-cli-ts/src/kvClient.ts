/**
 * KVClient - Protocol-specific wrapper around RaftClient
 * Provides typed API for KVStore operations
 */

import {
  RaftClient,
  ZmqTransport,
  TimeoutError,
  ConnectionError,
  ProtocolError,
  SessionExpiredError,
} from '@zio-raft/typescript-client';
import type { MemberId } from '@zio-raft/typescript-client';
import { encodeSetRequest, encodeGetQuery, encodeWatchRequest, decodeGetResult, decodeNotification } from './codecs.js';
import { WatchNotification } from './types.js';
import { OperationError } from './errors.js';

/**
 * Configuration for KVClient
 */
export interface KVClientConfig {
  readonly endpoints: ReadonlyMap<string, string>;
  readonly connectionTimeout?: number;
  readonly requestTimeout?: number;
}

/**
 * KVClient - typed wrapper for KVStore operations
 */
export class KVClient {
  private readonly raftClient: RaftClient;
  // Stored separately because RaftClient.config is private and doesn't expose timeout values.
  // We need these for user-friendly error messages that include timeout durations.
  private readonly connectionTimeout: number;
  private readonly requestTimeout: number;
  private isConnected = false;

  constructor(config: KVClientConfig) {
    this.connectionTimeout = config.connectionTimeout ?? 5000;
    this.requestTimeout = config.requestTimeout ?? 5000;
    // Convert endpoints to RaftClient format with branded MemberId type
    const clusterMembers = new Map<MemberId, string>();
    for (const [memberId, endpoint] of config.endpoints) {
      clusterMembers.set(memberId as MemberId, endpoint);
    }

    this.raftClient = new RaftClient(
      {
        clusterMembers,
        capabilities: new Map([
          ['protocol', 'kvstore'],
          ['version', '1.0.0'],
        ]),
        connectionTimeout: this.connectionTimeout,
        requestTimeout: this.requestTimeout,
        keepAliveInterval: 3000,
      },
      new ZmqTransport()
    );
  }

  /**
   * Map a caught error to the appropriate OperationError reason
   */
  private mapErrorReason(err: unknown): OperationError['reason'] {
    if (err instanceof TimeoutError) return 'timeout';
    if (err instanceof ConnectionError) return 'connection';
    if (err instanceof SessionExpiredError) return 'connection';
    if (err instanceof ProtocolError) return 'protocol';
    return 'server_error';
  }

  /**
   * Connect to the KVStore cluster
   */
  async connect(): Promise<void> {
    try {
      await this.raftClient.connect();
      this.isConnected = true;
    } catch (err) {
      const reason = this.mapErrorReason(err);
      throw new OperationError('connect', reason, `Could not connect to cluster (reason=${reason})`, err);
    }
  }

  /**
   * Disconnect from the cluster
   *
   * Note: Disconnect errors are logged but not thrown to prevent
   * cleanup failures from propagating. This is a best-effort operation.
   */
  async disconnect(): Promise<void> {
    if (!this.isConnected) {
      return;
    }

    try {
      await this.raftClient.disconnect();
      this.isConnected = false;
    } catch (err) {
      console.error('Warning: Failed to disconnect from cluster:', err);
      // Mark as disconnected even if the call failed
      this.isConnected = false;
    }
  }

  /**
   * Set a key-value pair (write operation)
   */
  async set(key: string, value: string): Promise<void> {
    const payload = encodeSetRequest(key, value);

    try {
      await this.raftClient.submitCommand(payload);
      // Result is Unit, no decoding needed
    } catch (err) {
      const reason = this.mapErrorReason(err);
      throw new OperationError('set', reason, `Set operation failed (reason=${reason})`, err);
    }
  }

  /**
   * Get a value by key (read-only query)
   */
  async get(key: string): Promise<string | null> {
    const payload = encodeGetQuery(key);

    try {
      const resultBuffer = await this.raftClient.submitQuery(payload);
      return decodeGetResult(resultBuffer);
    } catch (err) {
      const reason = this.mapErrorReason(err);
      throw new OperationError('get', reason, `Get operation failed (reason=${reason})`, err);
    }
  }

  /**
   * Register a watch for a key (write operation)
   */
  async watch(key: string): Promise<void> {
    const payload = encodeWatchRequest(key);

    try {
      await this.raftClient.submitCommand(payload);
      // Watch is registered, notifications will arrive via notifications()
    } catch (err) {
      const reason = this.mapErrorReason(err);
      throw new OperationError('watch', reason, `Watch operation failed (reason=${reason})`, err);
    }
  }

  /**
   * Iterate over watch notifications.
   * Yields WatchNotification objects decoded from server requests.
   * Iterator completes when client disconnects.
   */
  async *notifications(): AsyncIterableIterator<WatchNotification> {
    for await (const serverRequest of this.raftClient.serverRequests) {
      try {
        yield decodeNotification(serverRequest);
      } catch (err) {
        // Log decode errors but don't break iteration
        console.error('Warning: Failed to decode watch notification:', err);
      }
    }
  }
}
