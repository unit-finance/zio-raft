/**
 * Integration test for server-initiated requests
 * 
 * This test verifies that RaftClient properly receives and delivers
 * ServerRequest messages from the server to user handlers.
 * 
 * Expected to FAIL initially due to missing queue population in emitClientEvent()
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { RaftClient } from '../../src/client';
import type { ServerRequest } from '../../src/protocol/messages';
import { AsyncQueue } from '../../src/utils/asyncQueue';

// Mock transport that allows us to inject messages
class MockTransport {
  public incomingMessages: AsyncQueue<any>;
  private connected = false;
  
  constructor() {
    this.incomingMessages = new AsyncQueue();
  }
  
  async connect(): Promise<void> {
    this.connected = true;
  }
  
  async disconnect(): Promise<void> {
    this.connected = false;
    this.incomingMessages.close();
  }
  
  async sendMessage(message: any): Promise<void> {
    // Respond to CreateSession with SessionCreated
    if (message.type === 'CreateSession') {
      // Simulate server response
      setTimeout(() => {
        this.incomingMessages.offer({
          type: 'SessionCreated',
          sessionId: 'test-session-123',
          nonce: message.nonce,
        });
      }, 10);
    }
  }
  
  // Test helper: inject a ServerRequest message
  injectServerRequest(request: ServerRequest): void {
    if (!this.connected) {
      throw new Error('Cannot inject message - transport not connected');
    }
    this.incomingMessages.offer(request);
  }
}

describe('Server-Initiated Requests Integration', () => {
  let client: RaftClient;
  let mockTransport: MockTransport;
  
  beforeEach(() => {
    // Create mock transport
    mockTransport = new MockTransport();
    
    // Create RaftClient
    client = new RaftClient({
      clusterMembers: new Map([['node1', 'tcp://localhost:5555']]),
      capabilities: new Map([['test', '1.0']]),
    });
    
    // Replace transport with mock (hacky but necessary for testing)
    (client as any).transport = mockTransport;
  });
  
  afterEach(async () => {
    await client.disconnect();
  });
  
  it('should receive and deliver ServerRequest to handler', async () => {
    // This test will FAIL until emitClientEvent() is fixed
    
    // 1. Connect client
    console.log('[TEST] About to connect client');
    await client.connect();
    console.log('[TEST] Client connected successfully');
    
    // 2. Register handler to capture server requests
    const receivedRequests: ServerRequest[] = [];
    client.onServerRequest((request) => {
      receivedRequests.push(request);
    });
    
    // 3. Inject a ServerRequest message from "server"
    const testRequest: ServerRequest = {
      type: 'ServerRequest',
      requestId: 'req-001',
      payload: Buffer.from('test-work-item'),
      createdAt: new Date(),
    };
    
    console.log('[TEST] About to inject ServerRequest');
    mockTransport.injectServerRequest(testRequest);
    console.log('[TEST] Inject call completed');
    
    // 4. Wait for handler to be called
    // This will timeout because queue is never populated!
    await waitForCondition(
      () => receivedRequests.length > 0,
      1000,  // 1 second timeout
      'EXPECTED FAILURE: ServerRequest handler was never called - this proves the bug exists'
    );
    
    // 5. Verify handler received the request
    expect(receivedRequests).toHaveLength(1);
    expect(receivedRequests[0].requestId).toBe('req-001');
    expect(receivedRequests[0].payload.toString()).toBe('test-work-item');
  }, 15000); // Increase timeout since we know it will fail
  
  it('should handle multiple ServerRequest messages', async () => {
    await client.connect();
    
    const receivedRequests: ServerRequest[] = [];
    client.onServerRequest((request) => {
      receivedRequests.push(request);
    });
    
    // Inject multiple requests
    mockTransport.injectServerRequest({
      type: 'ServerRequest',
      requestId: 'req-001',
      payload: Buffer.from('work-1'),
      createdAt: new Date(),
    });
    
    mockTransport.injectServerRequest({
      type: 'ServerRequest',
      requestId: 'req-002',
      payload: Buffer.from('work-2'),
      createdAt: new Date(),
    });
    
    mockTransport.injectServerRequest({
      type: 'ServerRequest',
      requestId: 'req-003',
      payload: Buffer.from('work-3'),
      createdAt: new Date(),
    });
    
    // Wait for all three
    await waitForCondition(
      () => receivedRequests.length >= 3,
      2000,
      'EXPECTED FAILURE: ServerRequest handlers never called - proves bug'
    );
    
    expect(receivedRequests).toHaveLength(3);
    expect(receivedRequests.map(r => r.requestId)).toEqual([
      'req-001',
      'req-002',
      'req-003',
    ]);
  }, 15000);
  
  it('should demonstrate race condition with multiple handlers', async () => {
    // This test demonstrates Issue 2: multiple handlers
    await client.connect();
    
    const handler1Requests: ServerRequest[] = [];
    const handler2Requests: ServerRequest[] = [];
    
    // Register TWO handlers (should not be allowed!)
    client.onServerRequest((request) => {
      handler1Requests.push(request);
    });
    
    client.onServerRequest((request) => {
      handler2Requests.push(request);
    });
    
    // Inject multiple requests
    for (let i = 1; i <= 10; i++) {
      mockTransport.injectServerRequest({
        type: 'ServerRequest',
        requestId: `req-${i.toString().padStart(3, '0')}`,
        payload: Buffer.from(`work-${i}`),
        createdAt: new Date(),
      });
    }
    
    // Wait for some to be received
    await waitForCondition(
      () => handler1Requests.length + handler2Requests.length >= 10,
      2000,
      'EXPECTED FAILURE: No handlers called - proves Issue 1 (queue never populated)'
    );
    
    // With current buggy implementation, requests are randomly distributed
    console.log('Handler 1 received:', handler1Requests.length);
    console.log('Handler 2 received:', handler2Requests.length);
    
    // Ideal behavior: Should throw error on second registration
    // OR: All requests go to handler2 (last registered)
    // Buggy behavior: Random distribution (race condition)
    
    // For now, just assert we got all 10 total
    expect(handler1Requests.length + handler2Requests.length).toBe(10);
  }, 15000);
});

// Helper function to wait for a condition with timeout
async function waitForCondition(
  condition: () => boolean,
  timeoutMs: number,
  errorMessage: string
): Promise<void> {
  const startTime = Date.now();
  
  while (!condition()) {
    if (Date.now() - startTime > timeoutMs) {
      throw new Error(errorMessage);
    }
    // Wait 10ms before checking again
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}
