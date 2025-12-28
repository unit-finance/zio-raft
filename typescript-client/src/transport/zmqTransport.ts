// ZeroMQ transport implementation for client-server communication

import * as zmq from 'zeromq';
import { ClientTransport } from './transport';
import { ClientMessage, ServerMessage } from '../protocol/messages';
import { encodeClientMessage, decodeServerMessage } from '../protocol/codecs';

/**
 * ZeroMQ DEALER socket transport implementation
 */
export class ZmqTransport implements ClientTransport {
  private socket: zmq.Dealer;
  private currentAddress: string | null = null;

  constructor() {
    // Create DEALER socket with configuration matching Scala ClientTransport
    this.socket = new zmq.Dealer({
      linger: 0, // Immediate close, don't wait for pending messages
      sendHighWaterMark: 200000, // High water mark for send buffer
      receiveHighWaterMark: 200000, // High water mark for receive buffer
      // Note: zeromq.js doesn't expose heartbeat options directly
      // These would need to be configured at the ZMQ library level if needed
    });
  }

  /**
   * Connect to a ZMQ address
   */
  async connect(address: string): Promise<void> {
    if (this.currentAddress !== null) {
      throw new Error('Already connected. Call disconnect() first.');
    }

    try {
      this.socket.connect(address);
      this.currentAddress = address;
    } catch (error) {
      throw new Error(`Failed to connect to ${address}: ${error}`);
    }
  }

  /**
   * Disconnect from the current address
   */
  async disconnect(): Promise<void> {
    if (this.currentAddress === null) {
      return; // Already disconnected
    }

    try {
      this.socket.disconnect(this.currentAddress);
      this.currentAddress = null;
    } catch (error) {
      throw new Error(`Failed to disconnect: ${error}`);
    }
  }

  /**
   * Send a client message
   */
  async send(message: ClientMessage): Promise<void> {
    if (this.currentAddress === null) {
      throw new Error('Not connected. Call connect() first.');
    }

    try {
      const encoded = encodeClientMessage(message);
      await this.socket.send(encoded);
    } catch (error) {
      throw new Error(`Failed to send message: ${error}`);
    }
  }

  /**
   * Receive server messages as an async iterator
   */
  async *receive(): AsyncIterator<ServerMessage> {
    if (this.currentAddress === null) {
      throw new Error('Not connected. Call connect() first.');
    }

    try {
      for await (const [buffer] of this.socket) {
        try {
          const message = decodeServerMessage(buffer);
          yield message;
        } catch (error) {
          // Log decode error but continue receiving
          // In production, this would emit an error event
          console.error('Failed to decode server message:', error);
        }
      }
    } catch (error) {
      throw new Error(`Failed to receive messages: ${error}`);
    }
  }

  /**
   * Close the socket completely (for cleanup)
   */
  async close(): Promise<void> {
    if (this.currentAddress !== null) {
      await this.disconnect();
    }
    this.socket.close();
  }
}

