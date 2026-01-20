// Full lifecycle integration test
// Tests complete client lifecycle with mock server

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import { MemberId, RequestId } from '../../src/types';

// Helper to wait for a condition with timeout
async function waitForCondition(
  condition: () => boolean,
  timeoutMs: number = 1000,
  errorMessage: string = 'Condition not met within timeout'
): Promise<void> {
  const startTime = Date.now();
  while (!condition()) {
    if (Date.now() - startTime > timeoutMs) {
      throw new Error(errorMessage);
    }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

describe('Client Lifecycle Integration', () => {
  let client: RaftClient;
  let mockTransport: MockTransport;

  beforeEach(() => {
    // Create mock transport WITHOUT auto-session-creation (we'll manually respond)
    mockTransport = new MockTransport();
    mockTransport.autoRespondToCreateSession = false;
    
    // Create client with test configuration and injected transport
    client = new RaftClient({
      clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
      capabilities: new Map([['version', '1.0.0']]),
      connectionTimeout: 5000,
      requestTimeout: 10000,
      keepAliveInterval: 50, // Short interval for fast test cleanup
    }, mockTransport);
  });

  afterEach(async () => {
    // Cleanup
    if (client) {
      try {
        await client.disconnect();
      } catch (err) {
        // Ignore errors during cleanup
      }
    }
  });

  describe('TC-INT-001: Full Connect → Command → Disconnect Cycle', () => {
    it('should complete full lifecycle', async () => {
      // 1. Start connect (non-blocking)
      const connectPromise = client.connect();
      
      // Wait for CreateSession to be sent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('CreateSession').length > 0,
        1000,
        'CreateSession not sent'
      );
      
      const createSessionMessages = mockTransport.getSentMessagesOfType('CreateSession');
      expect(createSessionMessages).toHaveLength(1);
      
      // Manually inject SessionCreated response
      mockTransport.injectMessage({
        type: 'SessionCreated',
        sessionId: 'test-session-123',
        nonce: createSessionMessages[0].nonce,
      });
      
      // Wait for connect to complete
      await connectPromise;
      
      // 2. Submit Command
      const commandPromise = client.submitCommand(Buffer.from('test-command-payload'));
      
      // Wait for ClientRequest to be sent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ClientRequest').length > 0,
        1000,
        'ClientRequest not sent within timeout'
      );
      
      const clientRequests = mockTransport.getSentMessagesOfType('ClientRequest');
      expect(clientRequests).toHaveLength(1);
      expect(clientRequests[0].payload.toString()).toBe('test-command-payload');
      
      // Respond with ClientResponse
      mockTransport.injectMessage({
        type: 'ClientResponse',
        requestId: clientRequests[0].requestId,
        result: Buffer.from('command-result'),
      });
      
      // Wait for command to complete
      const result = await commandPromise;
      expect(result.toString()).toBe('command-result');
      
      // 3. Disconnect
      await client.disconnect();
      
      // Verify CloseSession was sent
      const closeSessionMessages = mockTransport.getSentMessagesOfType('CloseSession');
      expect(closeSessionMessages).toHaveLength(1);
    });
  });

  describe('TC-INT-002: Multiple Commands Sequentially', () => {
    it('should handle sequential commands', async () => {
      // Connect first
      await client.connect();
      
      // Submit 3 commands sequentially
      const commands = ['command-1', 'command-2', 'command-3'];
      const results: string[] = [];
      
      for (const cmd of commands) {
        const cmdPromise = client.submitCommand(Buffer.from(cmd));
        
        // Wait for request to be sent
        await waitForCondition(
          () => mockTransport.getSentMessagesOfType('ClientRequest').length === results.length + 1,
          1000
        );
        
        const requests = mockTransport.getSentMessagesOfType('ClientRequest');
        const latestRequest = requests[requests.length - 1];
        
        // Respond immediately
        mockTransport.injectMessage({
          type: 'ClientResponse',
          requestId: latestRequest.requestId,
          result: Buffer.from(`result-${cmd}`),
        });
        
        const result = await cmdPromise;
        results.push(result.toString());
      }
      
      // Verify all commands executed in order
      expect(results).toEqual(['result-command-1', 'result-command-2', 'result-command-3']);
      
      await client.disconnect();
    });
  });

  describe('TC-INT-003: Multiple Commands Concurrently', () => {
    it('should handle concurrent commands', async () => {
      // Connect first
      await client.connect();
      
      // Submit 5 commands concurrently
      const commandPromises = [
        client.submitCommand(Buffer.from('concurrent-cmd-1')),
        client.submitCommand(Buffer.from('concurrent-cmd-2')),
        client.submitCommand(Buffer.from('concurrent-cmd-3')),
        client.submitCommand(Buffer.from('concurrent-cmd-4')),
        client.submitCommand(Buffer.from('concurrent-cmd-5')),
      ];
      
      // Wait for all requests to be sent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ClientRequest').length === 5,
        2000,
        'Not all ClientRequests sent'
      );
      
      const requests = mockTransport.getSentMessagesOfType('ClientRequest');
      expect(requests).toHaveLength(5);
      
      // Respond to all in reverse order (test out-of-order handling)
      for (let i = requests.length - 1; i >= 0; i--) {
        mockTransport.injectMessage({
          type: 'ClientResponse',
          requestId: requests[i].requestId,
          result: Buffer.from(`result-for-request-${i + 1}`),
        });
      }
      
      // Wait for all to complete
      const results = await Promise.all(commandPromises);
      
      // Results should match request order (not response order)
      expect(results.map(r => r.toString())).toEqual([
        'result-for-request-1',
        'result-for-request-2',
        'result-for-request-3',
        'result-for-request-4',
        'result-for-request-5',
      ]);
      
      await client.disconnect();
    });
  });

  describe('TC-INT-004: Query submission', () => {
    it('should submit query and receive response', async () => {
      // Connect first
      await client.connect();
      
      // Submit a query (read-only operation)
      const queryPromise = client.submitQuery(Buffer.from('query-payload'));
      
      // Wait for Query message to be sent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('Query').length > 0,
        1000,
        'Query not sent within timeout'
      );
      
      const queries = mockTransport.getSentMessagesOfType('Query');
      expect(queries).toHaveLength(1);
      expect(queries[0].payload.toString()).toBe('query-payload');
      
      // Respond with QueryResponse
      mockTransport.injectMessage({
        type: 'QueryResponse',
        correlationId: queries[0].correlationId,
        result: Buffer.from('query-result'),
      });
      
      // Wait for query to complete
      const result = await queryPromise;
      expect(result.toString()).toBe('query-result');
      
      await client.disconnect();
    });
  });

  describe('TC-INT-005: Keep-alive maintenance', () => {
    it('should send keep-alive messages', async () => {
      // Create client with short keep-alive interval for testing
      const testTransport = new MockTransport();
      const testClient = new RaftClient({
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['version', '1.0.0']]),
        connectionTimeout: 5000,
        requestTimeout: 10000,
        keepAliveInterval: 100, // 100ms for fast testing
      }, testTransport);
      
      try {
        // Connect
        await testClient.connect();
        
        const initialKeepAlives = testTransport.getSentMessagesOfType('KeepAlive').length;
        
        // Wait for keep-alive interval to elapse (100ms + buffer)
        await new Promise(resolve => setTimeout(resolve, 250));
        
        // Verify at least one KeepAlive was sent
        const keepAlives = testTransport.getSentMessagesOfType('KeepAlive');
        expect(keepAlives.length).toBeGreaterThan(initialKeepAlives);
        
        // Verify KeepAlive message structure
        expect(keepAlives[0].type).toBe('KeepAlive');
        expect(keepAlives[0].timestamp).toBeInstanceOf(Date);
      } finally {
        await testClient.disconnect();
      }
    });
  });
});

