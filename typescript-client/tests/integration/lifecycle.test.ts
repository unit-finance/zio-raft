// Full lifecycle integration test
// Tests complete client lifecycle with mock server

import { describe, it, expect } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import { MemberId, SessionId } from '../../src/types';
import { sessionCreatedFor, clientResponseFor, queryResponseFor } from '../helpers/messageFactories';

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
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

/**
 * Create a test client with MockTransport
 * Each test creates its own isolated client to avoid shared state issues
 */
function createTestClient(options?: { autoRespondToCreateSession?: boolean }): {
  client: RaftClient;
  transport: MockTransport;
} {
  const transport = new MockTransport();
  transport.autoRespondToCreateSession = options?.autoRespondToCreateSession ?? true;

  const client = new RaftClient(
    {
      clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
      capabilities: new Map([['version', '1.0.0']]),
      connectionTimeout: 5000,
      requestTimeout: 10000,
      keepAliveInterval: 50,
    },
    transport
  );

  return { client, transport };
}

describe('Client Lifecycle Integration', () => {
  describe('TC-INT-001: Full Connect → Command → Disconnect Cycle', () => {
    it('should complete full lifecycle', async () => {
      // Create client with MANUAL session creation for fine-grained control
      const { client, transport } = createTestClient({ autoRespondToCreateSession: false });

      try {
        // 1. Start connect (non-blocking)
        const connectPromise = client.connect();

        // Wait for CreateSession to be sent
        await waitForCondition(
          () => transport.getSentMessagesOfType('CreateSession').length > 0,
          1000,
          'CreateSession not sent'
        );

        const createSessionMessages = transport.getSentMessagesOfType('CreateSession');
        expect(createSessionMessages).toHaveLength(1);

        // Manually inject SessionCreated response
        const createSessionMsg = createSessionMessages[0];
        if (!createSessionMsg) throw new Error('Expected CreateSession message');
        transport.injectMessage(sessionCreatedFor(createSessionMsg.nonce, SessionId.fromString('test-session-123')));

        // Wait for connect to complete
        await connectPromise;

        // 2. Submit Command
        const commandPromise = client.submitCommand(Buffer.from('test-command-payload'));

        // Wait for ClientRequest to be sent
        await waitForCondition(
          () => transport.getSentMessagesOfType('ClientRequest').length > 0,
          1000,
          'ClientRequest not sent within timeout'
        );

        const clientRequests = transport.getSentMessagesOfType('ClientRequest');
        expect(clientRequests).toHaveLength(1);
        expect(clientRequests[0].payload.toString()).toBe('test-command-payload');

        // Respond with ClientResponse
        transport.injectMessage(clientResponseFor(clientRequests[0].requestId, Buffer.from('command-result')));

        // Wait for command to complete
        const result = await commandPromise;
        expect(result.toString()).toBe('command-result');

        // 3. Disconnect
        await client.disconnect();

        // Verify CloseSession was sent
        const closeSessionMessages = transport.getSentMessagesOfType('CloseSession');
        expect(closeSessionMessages).toHaveLength(1);
      } finally {
        // Cleanup
        try {
          await client.disconnect();
        } catch {
          // Ignore errors - may already be disconnected
        }
      }
    });
  });

  describe('TC-INT-002: Multiple Commands Concurrently', () => {
    it('should handle concurrent commands', async () => {
      // Create client with AUTO session creation (default behavior)
      const { client, transport } = createTestClient();

      try {
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
          () => transport.getSentMessagesOfType('ClientRequest').length === 5,
          2000,
          'Not all ClientRequests sent'
        );

        const requests = transport.getSentMessagesOfType('ClientRequest');
        expect(requests).toHaveLength(5);

        // Respond to all in reverse order (test out-of-order handling)
        for (let i = requests.length - 1; i >= 0; i--) {
          transport.injectMessage(clientResponseFor(requests[i].requestId, Buffer.from(`result-for-request-${i + 1}`)));
        }

        // Wait for all to complete
        const results = await Promise.all(commandPromises);

        // Results should match request order (not response order)
        expect(results.map((r) => r.toString())).toEqual([
          'result-for-request-1',
          'result-for-request-2',
          'result-for-request-3',
          'result-for-request-4',
          'result-for-request-5',
        ]);
      } finally {
        await client.disconnect();
      }
    });
  });

  describe('TC-INT-003: Query submission', () => {
    it('should submit query and receive response', async () => {
      // Create client with AUTO session creation (default behavior)
      const { client, transport } = createTestClient();

      try {
        // Connect first
        await client.connect();

        // Submit a query (read-only operation)
        const queryPromise = client.submitQuery(Buffer.from('query-payload'));

        // Wait for Query message to be sent
        await waitForCondition(
          () => transport.getSentMessagesOfType('Query').length > 0,
          1000,
          'Query not sent within timeout'
        );

        const queries = transport.getSentMessagesOfType('Query');
        expect(queries).toHaveLength(1);
        expect(queries[0].payload.toString()).toBe('query-payload');

        // Respond with QueryResponse
        transport.injectMessage(queryResponseFor(queries[0].correlationId, Buffer.from('query-result')));

        // Wait for query to complete
        const result = await queryPromise;
        expect(result.toString()).toBe('query-result');
      } finally {
        await client.disconnect();
      }
    });
  });
});
