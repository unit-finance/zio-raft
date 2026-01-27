/**
 * KVClient - Protocol-specific wrapper around RaftClient
 * Provides typed API for KVStore operations
 */

import { RaftClient, ClientEvents, ZmqTransport } from '@zio-raft/typescript-client';
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
  private isConnected = false;

  constructor(config: KVClientConfig) {
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
        connectionTimeout: config.connectionTimeout !== undefined ? config.connectionTimeout : 5000,
        requestTimeout: config.requestTimeout !== undefined ? config.requestTimeout : 5000,
        keepAliveInterval: 3000,
      },
      new ZmqTransport()
    );
  }

  /**
   * Connect to the KVStore cluster
   */
  async connect(): Promise<void> {
    try {
      await this.raftClient.connect();
      this.isConnected = true;
    } catch (err) {
      throw new OperationError('connect', 'timeout', 'Could not connect to cluster (timeout after 5s)', err);
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
      throw new OperationError('set', 'timeout', 'Operation timed out after 5s', err);
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
      throw new OperationError('get', 'timeout', 'Operation timed out after 5s', err);
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
      throw new OperationError('watch', 'timeout', 'Operation timed out after 5s', err);
    }
  }

  /**
   * Iterate over watch notifications
   * Yields WatchNotification objects decoded from server requests
   *
   * Handlers are automatically cleaned up when iteration completes,
   * even if the consumer breaks early or throws an error.
   */
  async *notifications(): AsyncIterableIterator<WatchNotification> {
    // Create a promise-based queue for server requests
    const queue: WatchNotification[] = [];
    // Use explicit union type for proper done/value typing
    type WatchIteratorResult = { value: WatchNotification; done: false } | { value: undefined; done: true };
    let resolveNext: ((value: WatchIteratorResult) => void) | null = null;
    let done = false;

    // Store handler reference for cleanup
    const disconnectHandler: () => void = () => {
      done = true;
      if (resolveNext) {
        resolveNext({ value: undefined, done: true });
        resolveNext = null;
      }
    };

    // Register handler for server requests
    this.raftClient.onServerRequest((serverRequest) => {
      try {
        const notification = decodeNotification(serverRequest.payload);

        if (resolveNext) {
          resolveNext({ value: notification, done: false });
          resolveNext = null;
        } else {
          queue.push(notification);
        }
      } catch (err) {
        // Log decode errors - malformed notifications should be visible for debugging
        console.error('Warning: Failed to decode watch notification:', err);
        console.error('  Payload length:', serverRequest.payload.length);
        console.error('  First 20 bytes:', serverRequest.payload.subarray(0, 20).toString('hex'));
        // Don't throw - one bad notification shouldn't break the watch stream
      }
    });

    // Register disconnection handler
    this.raftClient.on(ClientEvents.DISCONNECTED, disconnectHandler);

    try {
      // Yield notifications
      while (!done) {
        if (queue.length > 0) {
          const notification = queue.shift()!;
          yield notification;
        } else {
          // Wait for next notification
          const result = await new Promise<WatchIteratorResult>((resolve) => {
            resolveNext = resolve;
          });

          if (result.done) {
            break;
          }

          yield result.value;
        }
      }
    } finally {
      // Always cleanup handlers, even on early break/error
      // This prevents memory leaks when iterator is abandoned
      this.raftClient.removeServerRequestHandler();
      this.raftClient.removeListener(ClientEvents.DISCONNECTED, disconnectHandler);
    }
  }
}
