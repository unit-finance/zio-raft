/**
 * Mock implementations for testing
 */

import { KVClient, KVClientConfig } from '../../src/kvClient.js';
import { WatchNotification } from '../../src/types.js';

/**
 * Mock KVClient for testing
 */
export class MockKVClient extends KVClient {
  private mockConnected = false;
  private readonly mockNotifications: WatchNotification[] = [];
  private mockGetResult: string | null = null;
  private shouldThrowOnConnect = false;
  private shouldThrowOnSet = false;
  private shouldThrowOnGet = false;
  private shouldThrowOnWatch = false;
  private connectError: Error | null = null;
  private setError: Error | null = null;
  private getError: Error | null = null;
  private watchError: Error | null = null;

  // Track method calls
  public connectCalled = false;
  public disconnectCalled = false;
  public setCalled = false;
  public getCalled = false;
  public watchCalled = false;
  public lastSetKey: string | null = null;
  public lastSetValue: string | null = null;
  public lastGetKey: string | null = null;
  public lastWatchKey: string | null = null;

  constructor(config: KVClientConfig) {
    super(config);
  }

  // Configuration methods for tests
  setConnectError(error: Error): void {
    this.shouldThrowOnConnect = true;
    this.connectError = error;
  }

  setSetError(error: Error): void {
    this.shouldThrowOnSet = true;
    this.setError = error;
  }

  setGetError(error: Error): void {
    this.shouldThrowOnGet = true;
    this.getError = error;
  }

  setWatchError(error: Error): void {
    this.shouldThrowOnWatch = true;
    this.watchError = error;
  }

  setGetResult(result: string | null): void {
    this.mockGetResult = result;
  }

  addNotification(notification: WatchNotification): void {
    this.mockNotifications.push(notification);
  }

  // Override methods for testing
  async connect(): Promise<void> {
    this.connectCalled = true;
    if (this.shouldThrowOnConnect && this.connectError) {
      throw this.connectError;
    }
    this.mockConnected = true;
  }

  async disconnect(): Promise<void> {
    this.disconnectCalled = true;
    this.mockConnected = false;
  }

  async set(key: string, value: string): Promise<void> {
    this.setCalled = true;
    this.lastSetKey = key;
    this.lastSetValue = value;

    if (this.shouldThrowOnSet && this.setError) {
      throw this.setError;
    }
  }

  async get(key: string): Promise<string | null> {
    this.getCalled = true;
    this.lastGetKey = key;

    if (this.shouldThrowOnGet && this.getError) {
      throw this.getError;
    }

    return this.mockGetResult;
  }

  async watch(key: string): Promise<void> {
    this.watchCalled = true;
    this.lastWatchKey = key;

    if (this.shouldThrowOnWatch && this.watchError) {
      throw this.watchError;
    }
  }

  async *notifications(): AsyncIterableIterator<WatchNotification> {
    for (const notification of this.mockNotifications) {
      yield notification;
    }
  }
}

/**
 * Create a mock KVClient with default configuration
 */
export function createMockClient(config?: Partial<KVClientConfig>): MockKVClient {
  const defaultConfig: KVClientConfig = {
    endpoints: new Map([['node-1', 'tcp://127.0.0.1:7001']]),
    connectionTimeout: 5000,
    requestTimeout: 5000,
    ...config,
  };

  return new MockKVClient(defaultConfig);
}
