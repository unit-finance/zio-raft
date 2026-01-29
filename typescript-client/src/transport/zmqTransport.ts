// ZeroMQ transport implementation for client-server communication

import { Client as ZmqClient } from 'zeromq/draft';
import { ClientTransport } from './transport';
import { ClientMessage, ServerMessage } from '../protocol/messages';
import { encodeClientMessage, decodeServerMessage } from '../protocol/codecs';

/**
 * ZeroMQ CLIENT socket transport implementation (draft socket)
 * Uses CLIENT socket to communicate with SERVER socket (not ROUTER)
 */
export class ZmqTransport implements ClientTransport {
  private readonly socket: ZmqClient;
  private currentAddress: string | null = null;

  constructor() {
    // Create CLIENT socket (draft) to communicate with SERVER socket
    // Configuration matching maitred's approach
    this.socket = new ZmqClient({
      linger: 0,
      heartbeatInterval: 100,
      heartbeatTimeToLive: 1000,
      heartbeatTimeout: 1000,
    });
  }

  /**
   * Connect to a ZMQ address
   * Simple approach matching maitred - just connect and let ZMQ handle queueing
   */
  async connect(address: string): Promise<void> {
    if (this.currentAddress !== null) {
      throw new Error('Already connected. Call disconnect() first.');
    }

    try {
      this.socket.connect(address);
      this.currentAddress = address;
    } catch (error) {
      this.currentAddress = null;
      throw new Error(`Failed to connect to ${address}: ${String(error)}`);
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
      throw new Error(`Failed to disconnect: ${String(error)}`);
    }
  }

  /**
   * Send a client message
   */
  async sendMessage(message: ClientMessage): Promise<void> {
    if (this.currentAddress === null) {
      throw new Error('Not connected. Call connect() first.');
    }

    try {
      const encoded = encodeClientMessage(message);

      // ZMQ will queue this until connection is ready
      await this.socket.send(encoded);
    } catch (error) {
      throw new Error(`Failed to send message: ${String(error)}`);
    }
  }

  /**
   * Receive server messages as an async iterable
   */
  get incomingMessages(): AsyncIterable<ServerMessage> {
    return {
      [Symbol.asyncIterator]: (): AsyncIterator<ServerMessage> => {
        return this.createMessageStream();
      },
    };
  }

  private async *createMessageStream(): AsyncIterator<ServerMessage> {
    try {
      for await (const [buffer] of this.socket) {
        if (buffer === undefined || buffer === null) {
          continue;
        }

        try {
          const message = decodeServerMessage(buffer as Buffer);
          yield message;
        } catch (error) {
          // Log decode error but continue receiving
          // In production, this would emit an error event
        }
      }
    } catch (error) {
      throw new Error(`Failed to receive messages: ${String(error)}`);
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
