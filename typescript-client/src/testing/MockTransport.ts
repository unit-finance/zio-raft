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
import { SessionId } from '../types';

/**
 * MockTransport enables deterministic testing of client protocol logic,
 * edge cases, and failover scenarios that are difficult to reproduce reliably in E2E tests.
 *
 * Design rationale:
 * - E2E tests validate end-to-end flow with real servers (kvstore-cli-ts)
 * - MockTransport tests validate client protocol correctness in isolation
 * - Trade-off: Tests construct protocol messages (maintained via helpers in tests/helpers/messageFactories.ts)
 * - TypeScript enforces correctness at compile time when protocol changes
 *
 * Use MockTransport for:
 * - Protocol edge cases (leader failover, session expiration, timeouts)
 * - Request resend logic after reconnection
 * - Multi-node failover scenarios
 * - Internal API contracts (transport lifecycle, handler registration)
 *
 * Use E2E tests for:
 * - Full stack integration validation
 * - User-facing workflows (CLI operations, watch functionality)
 * - Real network behavior
 */
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
   * Track pending auto-response timeout for cleanup
   */
  private autoResponseTimeout: ReturnType<typeof setTimeout> | null = null;

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
  async connect(_address: string): Promise<void> {
    this._connected = true;
  }

  /**
   * Disconnect from current address (like ZmqTransport.disconnect)
   * Queue stays open - survives across disconnect/connect cycles
   * This matches ZMQ socket behavior where disconnect() just removes the connection
   */
  async disconnect(): Promise<void> {
    if (!this._connected) {
      return; // Already disconnected
    }

    this._connected = false;

    // Clean up pending timeout
    if (this.autoResponseTimeout !== null) {
      clearTimeout(this.autoResponseTimeout);
      this.autoResponseTimeout = null;
    }

    // DON'T close queue here - it survives across reconnects like ZMQ socket
  }

  /**
   * Send message (mock - records and optionally auto-responds)
   */
  async sendMessage(message: ClientMessage): Promise<void> {
    if (!this._connected) {
      throw new Error('MockTransport: Cannot send message while disconnected');
    }

    // Record for assertions
    this.sentMessages.push(message);

    // Auto-respond to CreateSession for convenience
    if (this.autoRespondToCreateSession && message.type === 'CreateSession') {
      this.autoResponseTimeout = setTimeout(() => {
        this.autoResponseTimeout = null;
        this.incomingMessages.offer({
          type: 'SessionCreated',
          sessionId: SessionId.fromString(`mock-session-${Date.now()}`),
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
   * Enforces connection state - messages can't arrive while disconnected (like real transport)
   */
  injectMessage(message: ServerMessage): void {
    if (!this._connected) {
      throw new Error('MockTransport: Cannot inject message while disconnected');
    }
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
  getSentMessagesOfType<T extends ClientMessage['type']>(type: T): Extract<ClientMessage, { type: T }>[] {
    type MatchingMessage = Extract<ClientMessage, { type: T }>;
    return this.sentMessages.filter((m): m is MatchingMessage => m.type === type);
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
}
