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
  private socket: ZmqClient;
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
    
    // Add event listeners to debug connection issues
    this.socket.events.on('connect', (event: any) => {
      console.log('[DEBUG] Socket event: connect', event);
    });
    this.socket.events.on('connect:delay', (event: any) => {
      console.log('[DEBUG] Socket event: connect:delay', event);
    });
    this.socket.events.on('connect:retry', (event: any) => {
      console.log('[DEBUG] Socket event: connect:retry', event);
    });
    this.socket.events.on('disconnect', (event: any) => {
      console.log('[DEBUG] Socket event: disconnect', event);
    });
    this.socket.events.on('close', () => {
      console.log('[DEBUG] Socket event: close');
    });
    this.socket.events.on('end', () => {
      console.log('[DEBUG] Socket event: end');
    });
    
    console.log('[DEBUG] ZmqTransport constructor - CLIENT socket created');
    console.log('[DEBUG] Socket type: CLIENT (draft)');
    console.log('[DEBUG] Socket immediate:', this.socket.immediate);
    console.log('[DEBUG] Socket heartbeat interval:', this.socket.heartbeatInterval);
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
      console.log('[DEBUG] ZmqTransport.connect() - connecting to:', address);
      this.socket.connect(address);
      this.currentAddress = address;
      console.log('[DEBUG] ZmqTransport.connect() completed');
    } catch (error) {
      console.log('[DEBUG] ZmqTransport.connect() failed:', error);
      this.currentAddress = null;
      throw new Error(`Failed to connect to ${address}: ${error}`);
    }
  }

  /**
   * Disconnect from the current address
   */
  async disconnect(): Promise<void> {
    if (this.currentAddress === null) {
      console.log('[DEBUG] ZmqTransport.disconnect() - already disconnected, skipping');
      return; // Already disconnected
    }

    try {
      console.log('[DEBUG] ZmqTransport.disconnect() - disconnecting from:', this.currentAddress);
      console.log('[DEBUG] This will trigger server to receive auto-generated ConnectionClosed message');
      this.socket.disconnect(this.currentAddress);
      this.currentAddress = null;
      console.log('[DEBUG] ZmqTransport.disconnect() completed');
    } catch (error) {
      console.log('[DEBUG] ZmqTransport.disconnect() failed:', error);
      throw new Error(`Failed to disconnect: ${error}`);
    }
  }

  /**
   * Send a client message
   */
  async sendMessage(message: ClientMessage): Promise<void> {
    console.log('[DEBUG] ZmqTransport.sendMessage() called');
    console.log('[DEBUG] currentAddress:', this.currentAddress);
    
    if (this.currentAddress === null) {
      console.log('[DEBUG] ZmqTransport.sendMessage() - NOT CONNECTED, throwing error');
      throw new Error('Not connected. Call connect() first.');
    }

    try {
      console.log('[DEBUG] Encoding client message...');
      const encoded = encodeClientMessage(message);
      console.log('[DEBUG] Message encoded, size:', encoded.length, 'bytes');
      console.log('[DEBUG] Socket writable:', this.socket.writable);
      console.log('[DEBUG] Socket readable:', this.socket.readable);
      console.log('[DEBUG] Socket closed:', this.socket.closed);
      console.log('[DEBUG] Sending to socket...');
      
      // ZMQ will queue this until connection is ready
      await this.socket.send(encoded);
      console.log('[DEBUG] Socket.send() completed - message sent/queued');
    } catch (error) {
      console.log('[DEBUG] ZmqTransport.sendMessage() failed:', error);
      throw new Error(`Failed to send message: ${error}`);
    }
  }

  /**
   * Receive server messages as an async iterable
   */
  get incomingMessages(): AsyncIterable<ServerMessage> {
    console.log('[DEBUG] ZmqTransport.incomingMessages getter called');
    const self = this;
    return {
      [Symbol.asyncIterator]() {
        console.log('[DEBUG] ZmqTransport.incomingMessages Symbol.asyncIterator called');
        return self.createMessageStream();
      }
    };
  }

  private async *createMessageStream(): AsyncIterator<ServerMessage> {
    console.log('[DEBUG] ZmqTransport.createMessageStream() starting to read from socket');

    try {
      let iterationCount = 0;
      for await (const [buffer] of this.socket) {
        iterationCount++;
        console.log('[DEBUG] Socket iteration', iterationCount, '- received data, buffer:', buffer ? buffer.length + ' bytes' : 'null');
        
        if (!buffer) {
          console.log('[DEBUG] Buffer is null/undefined, skipping');
          continue;
        }
        
        try {
          console.log('[DEBUG] Decoding server message...');
          const message = decodeServerMessage(buffer as Buffer);
          console.log('[DEBUG] Message decoded successfully, yielding to client');
          yield message;
        } catch (error) {
          // Log decode error but continue receiving
          // In production, this would emit an error event
          console.error('[DEBUG] Failed to decode server message:', error);
        }
      }
      console.log('[DEBUG] Socket iteration loop ended');
    } catch (error) {
      console.log('[DEBUG] Socket iteration error:', error);
      throw new Error(`Failed to receive messages: ${error}`);
    }
  }

  /**
   * Close the socket completely (for cleanup)
   */
  async close(): Promise<void> {
    console.log('[DEBUG] ZmqTransport.close() called');
    if (this.currentAddress !== null) {
      await this.disconnect();
    }
    console.log('[DEBUG] Closing ZMQ socket');
    this.socket.close();
    console.log('[DEBUG] ZmqTransport.close() completed');
  }
}

