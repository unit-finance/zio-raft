/**
 * Tests for remaining server request issues (Issues 2 & 3)
 *
 * These tests are EXPECTED TO FAIL demonstrating:
 * - Issue 2: Multiple handler race condition
 * - Issue 3: No handler cleanup (memory leak)
 *
 * Uses MockTransport for clean, reliable testing
 */

/* eslint-disable @typescript-eslint/no-explicit-any */
/* eslint-disable @typescript-eslint/no-unsafe-assignment */
/* eslint-disable @typescript-eslint/no-unsafe-member-access */
/* eslint-disable @typescript-eslint/no-unsafe-call */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import type { ServerRequest } from '../../src/protocol/messages';

describe('Server Request Issues (Expected Failures)', () => {
  let client: RaftClient;
  let mockTransport: MockTransport;

  beforeEach(() => {
    mockTransport = new MockTransport();
    client = new RaftClient(
      {
        clusterMembers: new Map([['node1', 'tcp://localhost:5555']]),
        capabilities: new Map([['test', '1.0']]),
      },
      mockTransport
    );

    // Don't connect - we're testing handler registration issues
    // which don't require a full connection
  });

  afterEach(() => {
    // Don't disconnect - would hang due to issues we're testing
  });

  // ==========================================================================
  // Issue 2: Multiple Handler Race Condition
  // ==========================================================================

  describe('Issue 2: Multiple Handler Race Condition', () => {
    it('FAILS: multiple handlers cause race condition', async () => {
      // Register TWO handlers (current API allows this)
      const handler1Requests: ServerRequest[] = [];
      const handler2Requests: ServerRequest[] = [];

      client.onServerRequest((request) => {
        handler1Requests.push(request);
      });

      client.onServerRequest((request) => {
        handler2Requests.push(request);
      });

      // Inject 10 server requests directly via emitClientEvent
      // (simulates what state machine does)
      const emitClientEvent = (client as any).emitClientEvent.bind(client);

      for (let i = 1; i <= 10; i++) {
        emitClientEvent({
          type: 'serverRequestReceived',
          request: {
            type: 'ServerRequest',
            requestId: `req-${i.toString().padStart(3, '0')}`,
            payload: Buffer.from(`work-${i}`),
            createdAt: new Date(),
          },
        });
      }

      // Wait for messages to be processed
      await new Promise((resolve) => setTimeout(resolve, 200));

      // EXPECTED BEHAVIOR: Should throw error on second registration
      // OR: All requests go to handler2 (last registered)
      // ACTUAL BEHAVIOR: Requests randomly distributed (race condition)

      console.log('Handler 1 received:', handler1Requests.length);
      console.log('Handler 2 received:', handler2Requests.length);

      // This assertion will fail unpredictably
      // Sometimes handler1 gets all, sometimes handler2, usually split
      expect(handler1Requests.length + handler2Requests.length).toBe(10);

      // FAILING ASSERTION: Requests should not be split
      // Either all to handler1 (0 + 10) or all to handler2 (10 + 0)
      expect(
        (handler1Requests.length === 10 && handler2Requests.length === 0) ||
          (handler1Requests.length === 0 && handler2Requests.length === 10)
      ).toBe(true); // ← This will FAIL showing the race condition
    }, 15000);

    it('FAILS: calling onServerRequest twice should throw error', () => {
      // First registration should succeed
      client.onServerRequest(() => {});

      // Second registration should throw
      // EXPECTED: Throws error like "Handler already registered"
      // ACTUAL: Silently creates second consumer loop (race condition)
      expect(() => {
        client.onServerRequest(() => {});
      }).toThrow(/already registered|multiple handlers not allowed/i);
      // ↑ This will FAIL - no error is thrown
    });
  });

  // ==========================================================================
  // Issue 3: No Handler Cleanup (Memory Leak)
  // ==========================================================================

  describe('Issue 3: No Handler Cleanup', () => {
    it('FAILS: handlers are never cleaned up (memory leak)', async () => {
      // Simulate calling notifications() multiple times
      // (e.g., user creates multiple iterators)

      const handlerIds: number[] = [];

      // Register 5 handlers
      for (let i = 1; i <= 5; i++) {
        const handlerId = i;
        client.onServerRequest((_request) => {
          handlerIds.push(handlerId);
        });
      }

      // Inject one request via emitClientEvent
      const emitClientEvent = (client as any).emitClientEvent.bind(client);
      emitClientEvent({
        type: 'serverRequestReceived',
        request: {
          type: 'ServerRequest',
          requestId: 'test-req',
          payload: Buffer.from('work'),
          createdAt: new Date(),
        },
      });

      // Wait for processing
      await new Promise((resolve) => setTimeout(resolve, 100));

      // EXPECTED: Only handler 5 (last registered) receives it
      // OR: Error was thrown on handlers 2-5
      // ACTUAL: Random handler receives it (race condition)

      console.log('Handlers that received request:', handlerIds);

      // FAILING ASSERTION: Only one handler should receive message
      expect(handlerIds).toHaveLength(1);
      // ↑ This will FAIL - multiple handlers received it
    }, 15000);

    it('FAILS: no removeServerRequestHandler API exists', () => {
      // Register a handler
      client.onServerRequest(() => {});

      // Try to remove it
      // EXPECTED: API like client.removeServerRequestHandler() exists
      // ACTUAL: No such API

      expect(typeof (client as any).removeServerRequestHandler).toBe('function');
      // ↑ This will FAIL - API doesn't exist
    });

    it('DEMONSTRATES: background loops accumulate', async () => {
      // This test demonstrates the memory leak
      // Each call to onServerRequest() spawns a background loop
      // These loops are never stopped

      // Simulate repeated calls (e.g., user creates/abandons iterators)
      for (let i = 0; i < 100; i++) {
        client.onServerRequest(() => {});
      }

      // All 100 loops are now running in background, consuming from same queue
      // This is a resource leak - loops should be cleaned up

      // We can't easily assert on this, but it's observable in:
      // 1. Memory profiling (100 async loops)
      // 2. CPU usage (100 loops polling queue)
      // 3. Race conditions (random distribution)

      // For now, just document the issue
      console.log('Created 100 background consumer loops - none can be stopped!');

      // This test passes but demonstrates the problem
      expect(true).toBe(true);
    }, 15000);
  });

  // ==========================================================================
  // Issue 2 + 3 Combined: Real-World Scenario
  // ==========================================================================

  describe('Real-World Scenario: kvstore-cli watch notifications', () => {
    it('FAILS: demonstrates issue in actual usage', async () => {
      // Simulate kvstore-cli watch command usage
      // User calls client.notifications() which internally calls onServerRequest()

      // First iteration (user runs watch command)
      const iteration1: ServerRequest[] = [];
      client.onServerRequest((req) => iteration1.push(req));

      // User gets bored, cancels (Ctrl+C)
      // Handler is NOT cleaned up (Issue 3)

      // User runs watch command again
      const iteration2: ServerRequest[] = [];
      client.onServerRequest((req) => iteration2.push(req));

      // Now TWO handlers are registered
      // Requests will be randomly distributed (Issue 2)

      // Inject requests via emitClientEvent
      const emitClientEvent = (client as any).emitClientEvent.bind(client);

      for (let i = 1; i <= 10; i++) {
        emitClientEvent({
          type: 'serverRequestReceived',
          request: {
            type: 'ServerRequest',
            requestId: `req-${i}`,
            payload: Buffer.from(`notification-${i}`),
            createdAt: new Date(),
          },
        });
      }

      await new Promise((resolve) => setTimeout(resolve, 200));

      console.log('First iteration received:', iteration1.length);
      console.log('Second iteration received:', iteration2.length);

      // EXPECTED: iteration2 gets all 10 (iteration1 should be cleaned up)
      // ACTUAL: Random split between iteration1 and iteration2

      // FAILING ASSERTION: Only second iteration should receive
      expect(iteration1.length).toBe(0);
      expect(iteration2.length).toBe(10);
      // ↑ Both will FAIL showing the combined issue
    }, 15000);
  });
});
