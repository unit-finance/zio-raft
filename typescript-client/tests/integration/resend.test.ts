// Integration tests for resend logic after reconnection and timeout
//
// These tests verify that pending commands and queries are properly resent:
// 1. After reconnection (SessionClosed → ContinueSession → SessionContinued)
// 2. After request timeout (TimeoutCheck event)

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import { MemberId } from '../../src/types';
import {
  sessionCreatedFor,
  sessionClosedDueTo,
  sessionContinuedFor,
  sessionRejectedWith,
  clientResponseFor,
  queryResponseFor,
} from '../helpers/messageFactories';

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

describe('Resend Logic After Reconnection', () => {
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
    if (client !== null && client !== undefined) {
      try {
        await client.disconnect();
      } catch {
        // Ignore errors during cleanup
      }
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
      mockTransport.injectMessage(sessionCreatedFor(createSessionMsgs[0].nonce, 'test-session-001'));

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
      mockTransport.injectMessage(sessionClosedDueTo('NotLeaderAnymore', MemberId.fromString('node2')));

      // 5. Wait for ContinueSession to be sent (client tries to reconnect)
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ContinueSession').length > 0,
        1000,
        'ContinueSession not sent after SessionClosed'
      );

      const continueSessionMsgs = mockTransport.getSentMessagesOfType('ContinueSession');
      expect(continueSessionMsgs).toHaveLength(1);

      // 6. Respond with SessionContinued (reconnection successful)
      mockTransport.injectMessage(sessionContinuedFor(continueSessionMsgs[0].nonce));

      // 7. Wait for the pending command to be resent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ClientRequest').length > 0,
        1000,
        'ClientRequest not resent after reconnection'
      );

      const resentRequests = mockTransport.getSentMessagesOfType('ClientRequest');
      expect(resentRequests).toHaveLength(1);
      expect(resentRequests[0].requestId).toBe(originalRequestId);
      expect(resentRequests[0].payload.toString()).toBe('pending-command');

      // 8. Now respond to complete the command
      mockTransport.injectMessage(clientResponseFor(resentRequests[0].requestId, Buffer.from('command-result')));

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
      mockTransport.injectMessage(sessionCreatedFor(createSessionMsgs[0].nonce, 'test-session-002'));

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
      mockTransport.injectMessage(sessionClosedDueTo('NotLeaderAnymore', MemberId.fromString('node2')));

      // 5. Wait for ContinueSession
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ContinueSession').length > 0,
        1000,
        'ContinueSession not sent after SessionClosed'
      );

      const continueSessionMsgs = mockTransport.getSentMessagesOfType('ContinueSession');

      // 6. Respond with SessionContinued
      mockTransport.injectMessage(sessionContinuedFor(continueSessionMsgs[0].nonce));

      // 7. Wait for the pending query to be resent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('Query').length > 0,
        1000,
        'Query not resent after reconnection'
      );

      const resentQueries = mockTransport.getSentMessagesOfType('Query');
      expect(resentQueries).toHaveLength(1);
      expect(resentQueries[0].correlationId).toBe(originalCorrelationId);
      expect(resentQueries[0].payload.toString()).toBe('pending-query');

      // 8. Respond to complete the query
      mockTransport.injectMessage(queryResponseFor(resentQueries[0].correlationId, Buffer.from('query-result')));

      // 9. Verify query completed successfully
      const result = await queryPromise;
      expect(result.toString()).toBe('query-result');
    });
  });

  describe('TC-RESEND-003: Command resend on timeout', () => {
    it('should resend command when request timeout elapses without response', async () => {
      // This test uses its own client with shorter timeout, not the shared one
      // Mark the shared client as null so afterEach doesn't try to disconnect it
      (client as unknown) = null;

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
        shortTimeoutTransport.injectMessage(sessionCreatedFor(createSessionMsgs[0].nonce, 'test-session-003'));

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
          'ClientRequest not resent after timeout'
        );

        const allRequests = shortTimeoutTransport.getSentMessagesOfType('ClientRequest');
        expect(allRequests.length).toBeGreaterThanOrEqual(2);

        // Verify the resent request has the same requestId
        expect(allRequests[1].requestId).toBe(originalRequestId);
        expect(allRequests[1].payload.toString()).toBe('timeout-command');

        // 4. Now respond to complete
        shortTimeoutTransport.injectMessage(clientResponseFor(originalRequestId, Buffer.from('timeout-result')));

        const result = await commandPromise;
        expect(result.toString()).toBe('timeout-result');
      } finally {
        // Clean up the test-specific client
        try {
          await shortTimeoutClient.disconnect();
        } catch {
          // Ignore disconnect errors during cleanup
        }
      }
    });
  });

  describe('TC-RESEND-004: Multiple pending commands resend after reconnection', () => {
    it('should resend all pending commands after reconnection', async () => {
      // 1. Connect
      const connectPromise = client.connect();

      await waitForCondition(() => mockTransport.getSentMessagesOfType('CreateSession').length > 0, 1000);

      const createSessionMsgs = mockTransport.getSentMessagesOfType('CreateSession');
      mockTransport.injectMessage(sessionCreatedFor(createSessionMsgs[0].nonce, 'test-session-004'));

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

      mockTransport.injectMessage(sessionClosedDueTo('NotLeaderAnymore', MemberId.fromString('node2')));

      await waitForCondition(() => mockTransport.getSentMessagesOfType('ContinueSession').length > 0, 1000);

      const continueSessionMsgs = mockTransport.getSentMessagesOfType('ContinueSession');
      mockTransport.injectMessage(sessionContinuedFor(continueSessionMsgs[0].nonce));

      // 4. Wait for all 3 commands to be resent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ClientRequest').length === 3,
        1000,
        'Not all ClientRequests resent after reconnection'
      );

      const resentRequests = mockTransport.getSentMessagesOfType('ClientRequest');
      expect(resentRequests).toHaveLength(3);

      // Verify all original request IDs are present
      const resentRequestIds = resentRequests.map((r) => r.requestId);
      expect(resentRequestIds.sort()).toEqual(originalRequestIds.sort());

      // 5. Respond to all and verify completion
      for (const request of resentRequests) {
        mockTransport.injectMessage(
          clientResponseFor(request.requestId, Buffer.from(`result-${request.payload.toString()}`))
        );
      }

      const [result1, result2, result3] = await Promise.all([command1Promise, command2Promise, command3Promise]);

      expect(result1.toString()).toBe('result-command-1');
      expect(result2.toString()).toBe('result-command-2');
      expect(result3.toString()).toBe('result-command-3');
    });
  });

  describe('TC-RESEND-005: SessionExpired during reconnect', () => {
    it('should fail pending commands when session expires during reconnection', async () => {
      // 1. Connect to cluster
      const connectPromise = client.connect();

      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('CreateSession').length > 0,
        1000,
        'CreateSession not sent'
      );

      const createSessionMsgs = mockTransport.getSentMessagesOfType('CreateSession');
      mockTransport.injectMessage(sessionCreatedFor(createSessionMsgs[0].nonce, 'test-session-005'));

      await connectPromise;

      // 2. Submit a command (don't respond - keep it pending)
      const commandPromise = client.submitCommand(Buffer.from('pending-command'));

      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ClientRequest').length > 0,
        1000,
        'ClientRequest not sent'
      );

      // 3. Simulate leader change
      mockTransport.clearSentMessages();
      mockTransport.injectMessage(sessionClosedDueTo('NotLeaderAnymore', MemberId.fromString('node2')));

      // 4. Wait for ContinueSession to be sent
      await waitForCondition(
        () => mockTransport.getSentMessagesOfType('ContinueSession').length > 0,
        1000,
        'ContinueSession not sent after SessionClosed'
      );

      const continueSessionMsgs = mockTransport.getSentMessagesOfType('ContinueSession');
      expect(continueSessionMsgs).toHaveLength(1);

      // 5. Respond with SessionRejected(SessionExpired) - session has expired on server
      mockTransport.injectMessage(sessionRejectedWith(continueSessionMsgs[0].nonce, 'SessionExpired'));

      // 6. Verify command promise rejects with session expired error
      await expect(commandPromise).rejects.toThrow('Session expired');

      // 7. Verify client transitioned to Disconnected state
      // We can check this by trying to submit a new command which should reject immediately
      const newCommandPromise = client.submitCommand(Buffer.from('new-command'));
      await expect(newCommandPromise).rejects.toThrow();
    });
  });

  describe('TC-RESEND-006: Multiple sequential failovers with success', () => {
    it('should successfully reconnect after multiple leader redirects', async () => {
      // Setup: Create client with 3 nodes in cluster
      (client as unknown) = null; // Don't disconnect in afterEach

      const multiNodeTransport = new MockTransport();
      multiNodeTransport.autoRespondToCreateSession = false;

      const multiNodeClient = new RaftClient(
        {
          clusterMembers: new Map([
            [MemberId.fromString('node1'), 'tcp://localhost:5555'],
            [MemberId.fromString('node2'), 'tcp://localhost:5556'],
            [MemberId.fromString('node3'), 'tcp://localhost:5557'],
          ]),
          capabilities: new Map([['version', '1.0.0']]),
          connectionTimeout: 5000,
          requestTimeout: 200,
          keepAliveInterval: 50,
        },
        multiNodeTransport
      );

      try {
        // 1. Connect to node1
        const connectPromise = multiNodeClient.connect();

        await waitForCondition(() => multiNodeTransport.getSentMessagesOfType('CreateSession').length > 0, 1000);

        const createSessionMsgs = multiNodeTransport.getSentMessagesOfType('CreateSession');
        multiNodeTransport.injectMessage(sessionCreatedFor(createSessionMsgs[0].nonce, 'test-session-006'));

        await connectPromise;

        // 2. Submit a command
        const commandPromise = multiNodeClient.submitCommand(Buffer.from('multi-failover-command'));

        await waitForCondition(() => multiNodeTransport.getSentMessagesOfType('ClientRequest').length > 0, 1000);

        const originalRequest = multiNodeTransport.getSentMessagesOfType('ClientRequest')[0];
        const originalRequestId = originalRequest.requestId;

        // 3. First failover: node1 → node2
        multiNodeTransport.clearSentMessages();
        multiNodeTransport.injectMessage(sessionClosedDueTo('NotLeaderAnymore', MemberId.fromString('node2')));

        await waitForCondition(() => multiNodeTransport.getSentMessagesOfType('ContinueSession').length > 0, 1000);

        const continueSession1 = multiNodeTransport.getSentMessagesOfType('ContinueSession')[0];

        // 4. Second failover: node2 rejects, redirect to node3
        multiNodeTransport.clearSentMessages();
        multiNodeTransport.injectMessage(
          sessionRejectedWith(continueSession1.nonce, 'NotLeader', MemberId.fromString('node3'))
        );

        await waitForCondition(() => multiNodeTransport.getSentMessagesOfType('ContinueSession').length > 0, 1000);

        const continueSession2 = multiNodeTransport.getSentMessagesOfType('ContinueSession')[0];

        // 5. Third attempt succeeds: node3 accepts
        multiNodeTransport.injectMessage(sessionContinuedFor(continueSession2.nonce));

        // 6. Wait for command to be resent
        await waitForCondition(() => multiNodeTransport.getSentMessagesOfType('ClientRequest').length > 0, 1000);

        const resentRequest = multiNodeTransport.getSentMessagesOfType('ClientRequest')[0];
        expect(resentRequest.requestId).toBe(originalRequestId);

        // 7. Respond and verify success
        multiNodeTransport.injectMessage(clientResponseFor(originalRequestId, Buffer.from('multi-failover-result')));

        const result = await commandPromise;
        expect(result.toString()).toBe('multi-failover-result');
      } finally {
        try {
          await multiNodeClient.disconnect();
        } catch {
          // Ignore
        }
      }
    });
  });

  describe('TC-RESEND-007: Sequential failover with member exhaustion', () => {
    it('should fail when all cluster members are exhausted during reconnection', async () => {
      // Use the shared 2-node client from beforeEach
      // 1. Connect to node1
      const connectPromise = client.connect();

      await waitForCondition(() => mockTransport.getSentMessagesOfType('CreateSession').length > 0, 1000);

      const createSessionMsgs = mockTransport.getSentMessagesOfType('CreateSession');
      mockTransport.injectMessage(sessionCreatedFor(createSessionMsgs[0].nonce, 'test-session-007'));

      await connectPromise;

      // 2. Submit a command
      const commandPromise = client.submitCommand(Buffer.from('exhaustion-command'));

      await waitForCondition(() => mockTransport.getSentMessagesOfType('ClientRequest').length > 0, 1000);

      // 3. First failover: node1 → node2
      mockTransport.clearSentMessages();
      mockTransport.injectMessage(sessionClosedDueTo('NotLeaderAnymore', MemberId.fromString('node2')));

      await waitForCondition(() => mockTransport.getSentMessagesOfType('ContinueSession').length > 0, 1000);

      const continueSession1 = mockTransport.getSentMessagesOfType('ContinueSession')[0];

      // 4. Second attempt fails: node2 rejects with NotLeader but no valid leaderId
      mockTransport.clearSentMessages();
      // No leaderId - cluster has no known leader, or points to node not in our config
      mockTransport.injectMessage(sessionRejectedWith(continueSession1.nonce, 'NotLeader'));

      // 5. Client should exhaust retry attempts and fail
      await expect(commandPromise).rejects.toThrow('Failed to reconnect to any cluster member');

      // 6. Verify client is disconnected
      const newCommandPromise = client.submitCommand(Buffer.from('should-fail'));
      await expect(newCommandPromise).rejects.toThrow();
    });
  });
});
