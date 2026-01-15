/**
 * Mock transport for testing RaftClient without network dependencies
 * 
 * Provides precise control over message flow and timing for reliable testing.
 * 
 * Usage:
 * ```typescript
 * const mockTransport = new MockTransport();
 * const client = new RaftClient(config, mockTransport);
 * 
 * // Test can now inject server messages
 * mockTransport.injectMessage({
 *   type: 'ServerRequest',
 *   requestId: 'req-001',
 *   payload: Buffer.from('work'),
 *   createdAt: new Date(),
 * });
 * 
 * // And assert on messages client sent
 * const sentMessages = mockTransport.getSentMessagesOfType('ClientRequest');
 * ```
 */

import { AsyncQueue } from '../utils/asyncQueue';
import type { ClientTransport } from '../transport/transport';
import type { ClientMessage, ServerMessage } from '../protocol/messages';

export class MockTransport implements ClientTransport {
  /**
   * Incoming messages queue - messages flow to RaftClient from here
   * Exposed publicly so tests can observe queue state if needed
   */
  public readonly incomingMessages: AsyncQueue<ServerMessage>;
  
  /**
   * Track all messages sent by RaftClient for test assertions
   */
  public readonly sentMessages: ClientMessage[] = [];
  
  /**
   * Current connection state
   * MockTransport starts connected (no real network layer to establish)
   * Tests can use connect()/disconnect() for state tracking if needed
   */
  private _connected = true;
  
  /**
   * Auto-respond to CreateSession with SessionCreated
   * Set to false to manually control session creation in tests
   */
  public autoRespondToCreateSession = true;
  
  /**
   * Delay for auto-responses (milliseconds)
   */
  public autoResponseDelay = 10;
  
  constructor() {
    this.incomingMessages = new AsyncQueue();
  }
  
  /**
   * Connect (mock - just sets flag)
   */
  async connect(address: string): Promise<void> {
    this._connected = true;
  }
  
  /**
   * Disconnect and close incoming message queue
   */
  async disconnect(): Promise<void> {
    this._connected = false;
    this.incomingMessages.close();
  }
  
  /**
   * Send message (mock - records and optionally auto-responds)
   * Note: MockTransport doesn't enforce connection state for testing convenience
   */
  async sendMessage(message: ClientMessage): Promise<void> {
    // Record for assertions
    this.sentMessages.push(message);
    
    // Auto-respond to CreateSession for convenience
    if (this.autoRespondToCreateSession && message.type === 'CreateSession') {
      setTimeout(() => {
        this.incomingMessages.offer({
          type: 'SessionCreated',
          sessionId: `mock-session-${Date.now()}`,
          nonce: message.nonce,
        });
      }, this.autoResponseDelay);
    }
  }
  
  // ==========================================================================
  // Test Helper Methods
  // ==========================================================================
  
  /**
   * Inject a server message into the incoming queue
   * This simulates the server sending a message to the client
   * Note: MockTransport doesn't enforce connection state for testing convenience
   */
  injectMessage(message: ServerMessage): void {
    this.incomingMessages.offer(message);
  }
  
  /**
   * Get the last message sent by the client
   */
  getLastSentMessage(): ClientMessage | undefined {
    return this.sentMessages[this.sentMessages.length - 1];
  }
  
  /**
   * Get all sent messages of a specific type
   */
  getSentMessagesOfType<T extends ClientMessage['type']>(
    type: T
  ): Extract<ClientMessage, { type: T }>[] {
    return this.sentMessages.filter(m => m.type === type) as any;
  }
  
  /**
   * Clear all sent messages (useful for multi-phase tests)
   */
  clearSentMessages(): void {
    this.sentMessages.length = 0;
  }
  
  /**
   * Check if transport is connected
   */
  isConnected(): boolean {
    return this._connected;
  }
  
  /**
   * Wait for a specific message type to be sent
   * Useful for async assertions
   */
  async waitForMessageType(
    type: ClientMessage['type'],
    timeoutMs = 1000
  ): Promise<Extract<ClientMessage, { type: typeof type }>> {
    const startTime = Date.now();
    
    while (Date.now() - startTime < timeoutMs) {
      const message = this.sentMessages.find(m => m.type === type);
      if (message) {
        return message as any;
      }
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    
    throw new Error(`MockTransport: No message of type '${type}' sent within ${timeoutMs}ms`);
  }
}
