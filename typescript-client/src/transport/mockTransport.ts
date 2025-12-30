// Mock transport implementation for testing

import { ClientTransport } from './transport';
import { ClientMessage, ServerMessage } from '../protocol/messages';

/**
 * Mock transport for testing without real ZMQ
 */
export class MockTransport implements ClientTransport {
  private connected: boolean = false;
  private address: string | null = null;
  
  // In-memory queues
  public sentMessages: ClientMessage[] = [];
  private receivedMessages: ServerMessage[] = [];
  private messageIteratorCallbacks: Array<(msg: ServerMessage) => void> = [];

  /**
   * Connect to an address (simulated)
   */
  async connect(address: string): Promise<void> {
    if (this.connected) {
      throw new Error('Already connected');
    }
    this.address = address;
    this.connected = true;
  }

  /**
   * Disconnect (simulated)
   */
  async disconnect(): Promise<void> {
    this.connected = false;
    this.address = null;
  }

  /**
   * Send a client message (store in sentMessages queue)
   */
  async sendMessage(message: ClientMessage): Promise<void> {
    if (!this.connected) {
      throw new Error('Not connected');
    }
    this.sentMessages.push(message);
  }

  /**
   * Incoming messages as an async iterable
   */
  get incomingMessages(): AsyncIterable<ServerMessage> {
    const self = this;
    return {
      [Symbol.asyncIterator]() {
        return self.createMessageStream();
      }
    };
  }

  private async *createMessageStream(): AsyncIterator<ServerMessage> {
    if (!this.connected) {
      throw new Error('Not connected');
    }

    // Yield any messages already in the queue
    while (this.receivedMessages.length > 0) {
      const msg = this.receivedMessages.shift();
      if (msg !== undefined) {
        yield msg;
      }
    }

    // Wait for new messages
    while (this.connected) {
      const msg = await this.waitForMessage();
      if (msg !== null) {
        yield msg;
      } else {
        break; // Connection closed
      }
    }
  }

  /**
   * Inject a server message for testing
   * This simulates receiving a message from the server
   */
  injectServerMessage(message: ServerMessage): void {
    this.receivedMessages.push(message);
    
    // Notify any waiting iterator
    const callback = this.messageIteratorCallbacks.shift();
    if (callback !== undefined) {
      callback(message);
    }
  }

  /**
   * Wait for the next message (used internally by createMessageStream())
   */
  private waitForMessage(): Promise<ServerMessage | null> {
    return new Promise((resolve) => {
      // Check if message is already available
      if (this.receivedMessages.length > 0) {
        const msg = this.receivedMessages.shift();
        resolve(msg ?? null);
        return;
      }

      // Wait for message injection
      this.messageIteratorCallbacks.push((msg) => {
        resolve(msg);
      });

      // Also handle disconnection
      const checkInterval = setInterval(() => {
        if (!this.connected) {
          clearInterval(checkInterval);
          resolve(null);
        }
      }, 100);
    });
  }

  /**
   * Get all sent messages and clear the queue
   */
  getSentMessages(): ClientMessage[] {
    const messages = [...this.sentMessages];
    this.sentMessages = [];
    return messages;
  }

  /**
   * Get the last sent message
   */
  getLastSentMessage(): ClientMessage | undefined {
    return this.sentMessages[this.sentMessages.length - 1];
  }

  /**
   * Clear all queues (for test cleanup)
   */
  reset(): void {
    this.sentMessages = [];
    this.receivedMessages = [];
    this.messageIteratorCallbacks = [];
  }

  /**
   * Check if connected
   */
  isConnected(): boolean {
    return this.connected;
  }

  /**
   * Get current address
   */
  getAddress(): string | null {
    return this.address;
  }
}

