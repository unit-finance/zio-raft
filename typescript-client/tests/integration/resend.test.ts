// Integration tests for resend logic after reconnection and timeout
// Tests GAP-001: Incomplete Resend Logic After Reconnection
//
// These tests verify that pending commands and queries are properly resent:
// 1. After reconnection (SessionClosed → ContinueSession → SessionContinued)
// 2. After request timeout (TimeoutCheck event)

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import { MemberId } from '../../src/types';

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

describe('GAP-001: Resend Logic After Reconnection', () => {
  let client: RaftClient;
  let mockTransport: MockTransport;

  beforeEach(() => {
    mockTransport = new MockTransport();
    mockTransport.autoRespondToCreateSession = false;

    client = new RaftClient(
      {
        clusterMembers: new Map([
          [MemberId.fromString('node1'), 'tcp://localhost:5555'],
          [MemberId.fromString('node2'), 'tcp://localhost:5556'],
        ]),
        capabilities: new Map([['version', '1.0.0']]),
        connectionTimeout: 5000,
        requestTimeout: 200, // Short timeout for faster tests
        keepAliveInterval: 50,
      },
      mockTransport
    );
  });

  afterEach(async () => {
    if (client) {
      try {
        await client.disconnect();
      } catch {
        // Ignore errors during cleanup
      }
    }
    if (mockTransport) {
      mockTransport.closeQueues();
    }
  });

  describe('TC-RESEND-001: Command resend after NotLeaderAnymore', () => {
    it('should resend pending command after leader change and reconnection', async () => {
      // 1. Connect to cluster
      const connectPromise = client.connect();

      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('CreateSession').length > 0,
        1000,
        'CreateSession not sent'
      );

      const createSessionMsgs = mockTransport.getSentMessagesOfType('CreateSession');
      mockTransport.injectMessage({
        type: 'SessionCreated',
        sessionId: 'test-session-001',
        nonce: createSessionMsgs[0].nonce,
      });

      await connectPromise;

      // 2. Submit a command (don't respond - keep it pending)
      const commandPromise = client.submitCommand(Buffer.from('pending-command'));

      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ClientRequest').length > 0,
        1000,
        'ClientRequest not sent'
      );

      const originalRequests = mockTransport.getSentMessagesOfType('ClientRequest');
      expect(originalRequests).toHaveLength(1);
      expect(originalRequests[0].payload.toString()).toBe('pending-command');
      const originalRequestId = originalRequests[0].requestId;

      // 3. Clear sent messages to track new ones
      mockTransport.clearSentMessages();

      // 4. Simulate leader change - server sends SessionClosed with NotLeaderAnymore
      mockTransport.injectMessage({
        type: 'SessionClosed',
        reason: 'NotLeaderAnymore',
        leaderId: MemberId.fromString('node2'),
      });

      // 5. Wait for ContinueSession to be sent (client tries to reconnect)
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ContinueSession').length > 0,
        1000,
        'ContinueSession not sent after SessionClosed'
      );

      const continueSessionMsgs = mockTransport.getSentMessagesOfType('ContinueSession');
      expect(continueSessionMsgs).toHaveLength(1);

      // 6. Respond with SessionContinued (reconnection successful)
      mockTransport.injectMessage({
        type: 'SessionContinued',
        nonce: continueSessionMsgs[0].nonce,
      });

      // 7. Wait for the pending command to be RESENT
      // THIS IS THE BUG: Currently the command is NOT resent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ClientRequest').length > 0,
        1000,
        'ClientRequest not resent after reconnection - GAP-001 BUG!'
      );

      const resentRequests = mockTransport.getSentMessagesOfType('ClientRequest');
      expect(resentRequests).toHaveLength(1);
      expect(resentRequests[0].requestId).toBe(originalRequestId);
      expect(resentRequests[0].payload.toString()).toBe('pending-command');

      // 8. Now respond to complete the command
      mockTransport.injectMessage({
        type: 'ClientResponse',
        requestId: resentRequests[0].requestId,
        result: Buffer.from('command-result'),
      });

      // 9. Verify command completed successfully
      const result = await commandPromise;
      expect(result.toString()).toBe('command-result');
    });
  });

  describe('TC-RESEND-002: Query resend after NotLeaderAnymore', () => {
    it('should resend pending query after leader change and reconnection', async () => {
      // 1. Connect to cluster
      const connectPromise = client.connect();

      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('CreateSession').length > 0,
        1000,
        'CreateSession not sent'
      );

      const createSessionMsgs = mockTransport.getSentMessagesOfType('CreateSession');
      mockTransport.injectMessage({
        type: 'SessionCreated',
        sessionId: 'test-session-002',
        nonce: createSessionMsgs[0].nonce,
      });

      await connectPromise;

      // 2. Submit a query (don't respond - keep it pending)
      const queryPromise = client.submitQuery(Buffer.from('pending-query'));

      await waitForCondition(() => mockTransport.getSentMessagesOfType('Query').length > 0, 1000, 'Query not sent');

      const originalQueries = mockTransport.getSentMessagesOfType('Query');
      expect(originalQueries).toHaveLength(1);
      expect(originalQueries[0].payload.toString()).toBe('pending-query');
      const originalCorrelationId = originalQueries[0].correlationId;

      // 3. Clear sent messages to track new ones
      mockTransport.clearSentMessages();

      // 4. Simulate leader change
      mockTransport.injectMessage({
        type: 'SessionClosed',
        reason: 'NotLeaderAnymore',
        leaderId: MemberId.fromString('node2'),
      });

      // 5. Wait for ContinueSession
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ContinueSession').length > 0,
        1000,
        'ContinueSession not sent after SessionClosed'
      );

      const continueSessionMsgs = mockTransport.getSentMessagesOfType('ContinueSession');

      // 6. Respond with SessionContinued
      mockTransport.injectMessage({
        type: 'SessionContinued',
        nonce: continueSessionMsgs[0].nonce,
      });

      // 7. Wait for the pending query to be RESENT
      // THIS IS THE BUG: Currently the query is NOT resent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('Query').length > 0,
        1000,
        'Query not resent after reconnection - GAP-001 BUG!'
      );

      const resentQueries = mockTransport.getSentMessagesOfType('Query');
      expect(resentQueries).toHaveLength(1);
      expect(resentQueries[0].correlationId).toBe(originalCorrelationId);
      expect(resentQueries[0].payload.toString()).toBe('pending-query');

      // 8. Respond to complete the query
      mockTransport.injectMessage({
        type: 'QueryResponse',
        correlationId: resentQueries[0].correlationId,
        result: Buffer.from('query-result'),
      });

      // 9. Verify query completed successfully
      const result = await queryPromise;
      expect(result.toString()).toBe('query-result');
    });
  });

  describe('TC-RESEND-003: Command resend on timeout', () => {
    it('should resend command when request timeout elapses without response', async () => {
      // This test uses its own client with shorter timeout, not the shared one
      // Mark the shared client as null so afterEach doesn't try to disconnect it
      (client as any) = null;

      // Use a very short request timeout for this test
      const shortTimeoutTransport = new MockTransport();
      shortTimeoutTransport.autoRespondToCreateSession = false;

      const shortTimeoutClient = new RaftClient(
        {
          clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
          capabilities: new Map([['version', '1.0.0']]),
          connectionTimeout: 5000,
          requestTimeout: 150, // 150ms timeout
          keepAliveInterval: 50,
        },
        shortTimeoutTransport
      );

      try {
        // 1. Connect
        const connectPromise = shortTimeoutClient.connect();

        await waitForCondition(() => shortTimeoutTransport.getSentMessagesOfType('CreateSession').length > 0, 1000);

        const createSessionMsgs = shortTimeoutTransport.getSentMessagesOfType('CreateSession');
        shortTimeoutTransport.injectMessage({
          type: 'SessionCreated',
          sessionId: 'test-session-003',
          nonce: createSessionMsgs[0].nonce,
        });

        await connectPromise;

        // 2. Submit command (don't respond)
        const commandPromise = shortTimeoutClient.submitCommand(Buffer.from('timeout-command'));

        await waitForCondition(() => shortTimeoutTransport.getSentMessagesOfType('ClientRequest').length > 0, 1000);

        const originalRequests = shortTimeoutTransport.getSentMessagesOfType('ClientRequest');
        expect(originalRequests).toHaveLength(1);
        const originalRequestId = originalRequests[0].requestId;

        // 3. Wait for timeout to elapse and command to be resent
        // TimeoutCheck runs every 100ms, requestTimeout is 150ms
        // So after ~200-250ms we should see a resend
        await waitForCondition(
          () => shortTimeoutTransport.getSentMessagesOfType('ClientRequest').length >= 2,
          500,
          'ClientRequest not resent after timeout - GAP-001 BUG!'
        );

        const allRequests = shortTimeoutTransport.getSentMessagesOfType('ClientRequest');
        expect(allRequests.length).toBeGreaterThanOrEqual(2);

        // Verify the resent request has the same requestId
        expect(allRequests[1].requestId).toBe(originalRequestId);
        expect(allRequests[1].payload.toString()).toBe('timeout-command');

        // 4. Now respond to complete
        shortTimeoutTransport.injectMessage({
          type: 'ClientResponse',
          requestId: originalRequestId,
          result: Buffer.from('timeout-result'),
        });

        const result = await commandPromise;
        expect(result.toString()).toBe('timeout-result');
      } finally {
        // Clean up the test-specific client
        try {
          await shortTimeoutClient.disconnect();
        } catch {
          // Ignore disconnect errors during cleanup
        }
        shortTimeoutTransport.closeQueues();
      }
    });
  });

  describe('TC-RESEND-004: Multiple pending commands resend after reconnection', () => {
    it('should resend all pending commands after reconnection', async () => {
      // 1. Connect
      const connectPromise = client.connect();

      await waitForCondition(() => mockTransport.getSentMessagesOfType('CreateSession').length > 0, 1000);

      const createSessionMsgs = mockTransport.getSentMessagesOfType('CreateSession');
      mockTransport.injectMessage({
        type: 'SessionCreated',
        sessionId: 'test-session-004',
        nonce: createSessionMsgs[0].nonce,
      });

      await connectPromise;

      // 2. Submit 3 commands concurrently (don't respond to any)
      const command1Promise = client.submitCommand(Buffer.from('command-1'));
      const command2Promise = client.submitCommand(Buffer.from('command-2'));
      const command3Promise = client.submitCommand(Buffer.from('command-3'));

      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ClientRequest').length === 3,
        1000,
        'Not all ClientRequests sent'
      );

      const originalRequests = mockTransport.getSentMessagesOfType('ClientRequest');
      expect(originalRequests).toHaveLength(3);

      const originalRequestIds = originalRequests.map((r) => r.requestId);

      // 3. Clear and trigger reconnection
      mockTransport.clearSentMessages();

      mockTransport.injectMessage({
        type: 'SessionClosed',
        reason: 'NotLeaderAnymore',
        leaderId: MemberId.fromString('node2'),
      });

      await waitForCondition(() => mockTransport.getSentMessagesOfType('ContinueSession').length > 0, 1000);

      const continueSessionMsgs = mockTransport.getSentMessagesOfType('ContinueSession');
      mockTransport.injectMessage({
        type: 'SessionContinued',
        nonce: continueSessionMsgs[0].nonce,
      });

      // 4. Wait for ALL 3 commands to be resent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ClientRequest').length === 3,
        1000,
        'Not all ClientRequests resent after reconnection - GAP-001 BUG!'
      );

      const resentRequests = mockTransport.getSentMessagesOfType('ClientRequest');
      expect(resentRequests).toHaveLength(3);

      // Verify all original request IDs are present
      const resentRequestIds = resentRequests.map((r) => r.requestId);
      expect(resentRequestIds.sort()).toEqual(originalRequestIds.sort());

      // 5. Respond to all and verify completion
      for (const request of resentRequests) {
        mockTransport.injectMessage({
          type: 'ClientResponse',
          requestId: request.requestId,
          result: Buffer.from(`result-${request.payload.toString()}`),
        });
      }

      const [result1, result2, result3] = await Promise.all([command1Promise, command2Promise, command3Promise]);

      expect(result1.toString()).toBe('result-command-1');
      expect(result2.toString()).toBe('result-command-2');
      expect(result3.toString()).toBe('result-command-3');
    });
  });
});
