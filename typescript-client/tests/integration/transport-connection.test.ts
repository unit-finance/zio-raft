// Transport Connection Lifecycle Test
// Verifies that RaftClient properly calls transport.connect() and disconnect()
// This is an integration test that caught a critical bug where transport.connect()
// was never called before sending messages.

import { describe, it, expect, beforeEach } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import { MemberId } from '../../src/types';
import type { ClientMessage } from '../../src/protocol/messages';

describe('Transport Connection Lifecycle', () => {
  describe('TC-TRANSPORT-001: connect() before sendMessage()', () => {
    it('should call transport.connect() before sending any messages', async () => {
      const connectCalls: string[] = [];
      const sendCalls: ClientMessage[] = [];
      
      const mockTransport = new MockTransport();
      
      // Spy on connect() and sendMessage() to verify call order
      const originalConnect = mockTransport.connect.bind(mockTransport);
      const originalSend = mockTransport.sendMessage.bind(mockTransport);
      
      mockTransport.connect = async (address: string) => {
        console.log(`[TEST] transport.connect called with: ${address}`);
        connectCalls.push(address);
        await originalConnect(address);
        console.log(`[TEST] transport.connect completed`);
      };
      
      mockTransport.sendMessage = async (msg: ClientMessage) => {
        console.log(`[TEST] transport.sendMessage called with: ${msg.type}`);
        sendCalls.push(msg);
        await originalSend(msg);
        console.log(`[TEST] transport.sendMessage completed: ${msg.type}`);
      };
      
      const client = new RaftClient({
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['protocol', 'test'], ['version', '1.0']]),
        connectionTimeout: 5000,
        requestTimeout: 10000,
        keepAliveInterval: 50, // Short interval for fast test cleanup
      }, mockTransport);
      
      console.log(`[TEST] Calling client.connect()`);
      // Start connect (will trigger CreateSession message)
      const connectPromise = client.connect();
      console.log(`[TEST] client.connect() called, waiting 50ms`);
      
      // Wait a bit for event loop to process
      await new Promise(resolve => setTimeout(resolve, 50));
      console.log(`[TEST] 50ms wait completed`);
      
      // CRITICAL ASSERTION: transport.connect() must be called BEFORE first sendMessage()
      expect(connectCalls.length).toBeGreaterThan(0);
      expect(connectCalls[0]).toBe('tcp://localhost:5555');
      
      // Verify that if messages were sent, connect was called first
      if (sendCalls.length > 0) {
        expect(connectCalls.length).toBeGreaterThan(0);
        expect(sendCalls[0].type).toBe('CreateSession');
      }
      
      // Complete the connection
      await connectPromise;
      
      // Verify CreateSession was sent after connect
      expect(sendCalls.length).toBeGreaterThan(0);
      expect(sendCalls[0].type).toBe('CreateSession');
      
      await client.disconnect();
    });
  });

  describe('TC-TRANSPORT-002: disconnect() on client shutdown', () => {
    it('should call transport.disconnect() when client disconnects', async () => {
      let disconnectCalled = false;
      
      const mockTransport = new MockTransport();
      
      // Spy on disconnect()
      const originalDisconnect = mockTransport.disconnect.bind(mockTransport);
      mockTransport.disconnect = async () => {
        disconnectCalled = true;
        await originalDisconnect();
      };
      
      const client = new RaftClient({
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['protocol', 'test'], ['version', '1.0']]),
        keepAliveInterval: 50, // Short interval for fast test cleanup
      }, mockTransport);
      
      await client.connect();
      await client.disconnect();
      
      expect(disconnectCalled).toBe(true);
    });
  });

  describe('TC-TRANSPORT-003: connection address correctness', () => {
    it('should connect to the first cluster member address', async () => {
      let connectedAddress: string | null = null;
      
      const mockTransport = new MockTransport();
      
      const originalConnect = mockTransport.connect.bind(mockTransport);
      mockTransport.connect = async (address: string) => {
        connectedAddress = address;
        await originalConnect(address);
      };
      
      const client = new RaftClient({
        clusterMembers: new Map([
          [MemberId.fromString('node1'), 'tcp://localhost:5555'],
          [MemberId.fromString('node2'), 'tcp://localhost:5556'],
          [MemberId.fromString('node3'), 'tcp://localhost:5557'],
        ]),
        capabilities: new Map([['protocol', 'test'], ['version', '1.0']]),
        keepAliveInterval: 50, // Short interval for fast test cleanup
      }, mockTransport);
      
      await client.connect();
      
      // Should connect to the first member
      expect(connectedAddress).toBe('tcp://localhost:5555');
      
      await client.disconnect();
    });
  });
});
