// Transport layer interfaces for client-server communication

import { ClientMessage, ServerMessage } from '../protocol/messages';

/**
 * Client transport interface for sending and receiving messages
 */
export interface ClientTransport {
  /**
   * Connect to a remote address
   * @param address - ZMQ address (e.g., "tcp://localhost:5555")
   */
  connect(address: string): Promise<void>;

  /**
   * Disconnect from the current address
   */
  disconnect(): Promise<void>;

  /**
   * Send a client message
   * @param message - The message to send
   */
  send(message: ClientMessage): Promise<void>;

  /**
   * Receive server messages as an async iterator
   * @returns AsyncIterator yielding decoded server messages
   */
  receive(): AsyncIterator<ServerMessage>;
}

