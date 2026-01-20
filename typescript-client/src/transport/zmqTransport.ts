// ZeroMQ transport implementation for client-server communication
// TODO (eran): Heavy debug logging - this file has 20+ debugLog() calls which adds overhead
// in production even if debug is disabled (unless debugLog short-circuits early). Consider
// adding log levels or making debug logging conditional on a build flag.
// SCALA COMPARISON: SAME - Scala also uses debug logging (ZIO.logDebug) but ZIO's logging
// can be filtered at runtime via log level configuration.

import { Client as ZmqClient } from 'zeromq/draft';
import { ClientTransport } from './transport';
import { ClientMessage, ServerMessage } from '../protocol/messages';
import { encodeClientMessage, decodeServerMessage } from '../protocol/codecs';
import { debugLog } from '../utils/debug';

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
      debugLog('Socket event: connect', event);
    });
    this.socket.events.on('connect:delay', (event: any) => {
      debugLog('Socket event: connect:delay', event);
    });
    this.socket.events.on('connect:retry', (event: any) => {
      debugLog('Socket event: connect:retry', event);
    });
    this.socket.events.on('disconnect', (event: any) => {
      debugLog('Socket event: disconnect', event);
    });
    this.socket.events.on('close', () => {
      debugLog('Socket event: close');
    });
    this.socket.events.on('end', () => {
      debugLog('Socket event: end');
    });
    
    debugLog('ZmqTransport constructor - CLIENT socket created');
    debugLog('Socket type: CLIENT (draft)');
    debugLog('Socket immediate:', this.socket.immediate);
    debugLog('Socket heartbeat interval:', this.socket.heartbeatInterval);
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
      debugLog('ZmqTransport.connect() - connecting to:', address);
      this.socket.connect(address);
      this.currentAddress = address;
      debugLog('ZmqTransport.connect() completed');
    } catch (error) {
      debugLog('ZmqTransport.connect() failed:', error);
      this.currentAddress = null;
      throw new Error(`Failed to connect to ${address}: ${error}`);
    }
  }

  /**
   * Disconnect from the current address
   */
  async disconnect(): Promise<void> {
    if (this.currentAddress === null) {
      debugLog('ZmqTransport.disconnect() - already disconnected, skipping');
      return; // Already disconnected
    }

    try {
      debugLog('ZmqTransport.disconnect() - disconnecting from:', this.currentAddress);
      debugLog('This will trigger server to receive auto-generated ConnectionClosed message');
      this.socket.disconnect(this.currentAddress);
      this.currentAddress = null;
      debugLog('ZmqTransport.disconnect() completed');
    } catch (error) {
      debugLog('ZmqTransport.disconnect() failed:', error);
      throw new Error(`Failed to disconnect: ${error}`);
    }
  }

  /**
   * Send a client message
   */
  async sendMessage(message: ClientMessage): Promise<void> {
    debugLog('ZmqTransport.sendMessage() called');
    debugLog('currentAddress:', this.currentAddress);
    
    if (this.currentAddress === null) {
      debugLog('ZmqTransport.sendMessage() - NOT CONNECTED, throwing error');
      throw new Error('Not connected. Call connect() first.');
    }

    try {
      debugLog('Encoding client message...');
      const encoded = encodeClientMessage(message);
      debugLog('Message encoded, size:', encoded.length, 'bytes');
      debugLog('Socket writable:', this.socket.writable);
      debugLog('Socket readable:', this.socket.readable);
      debugLog('Socket closed:', this.socket.closed);
      debugLog('Sending to socket...');
      
      // ZMQ will queue this until connection is ready
      await this.socket.send(encoded);
      debugLog('Socket.send() completed - message sent/queued');
    } catch (error) {
      debugLog('ZmqTransport.sendMessage() failed:', error);
      throw new Error(`Failed to send message: ${error}`);
    }
  }

  /**
   * Receive server messages as an async iterable
   */
  get incomingMessages(): AsyncIterable<ServerMessage> {
    debugLog('ZmqTransport.incomingMessages getter called');
    const self = this;
    return {
      [Symbol.asyncIterator]() {
        debugLog('ZmqTransport.incomingMessages Symbol.asyncIterator called');
        return self.createMessageStream();
      }
    };
  }

  private async *createMessageStream(): AsyncIterator<ServerMessage> {
    debugLog('ZmqTransport.createMessageStream() starting to read from socket');

    try {
      let iterationCount = 0;
      for await (const [buffer] of this.socket) {
        iterationCount++;
        debugLog('Socket iteration', iterationCount, '- received data, buffer:', buffer ? buffer.length + ' bytes' : 'null');
        
        if (!buffer) {
          debugLog('Buffer is null/undefined, skipping');
          continue;
        }
        
        try {
          debugLog('Decoding server message...');
          const message = decodeServerMessage(buffer as Buffer);
          debugLog('Message decoded successfully, yielding to client');
          yield message;
        } catch (error) {
          // Log decode error but continue receiving
          // In production, this would emit an error event
          debugLog('Failed to decode server message:', error);
        }
      }
      debugLog('Socket iteration loop ended');
    } catch (error) {
      debugLog('Socket iteration error:', error);
      throw new Error(`Failed to receive messages: ${error}`);
    }
  }

  /**
   * Close the socket completely (for cleanup)
   */
  async close(): Promise<void> {
    debugLog('ZmqTransport.close() called');
    if (this.currentAddress !== null) {
      await this.disconnect();
    }
    debugLog('Closing ZMQ socket');
    this.socket.close();
    debugLog('ZmqTransport.close() completed');
  }
}

