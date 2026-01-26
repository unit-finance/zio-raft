/**
 * Tests for server request handler lifecycle management
 *
 * These tests verify:
 * - Issue 2 FIXED: Prevents multiple handler race condition
 * - Issue 3 FIXED: Handler cleanup via removeServerRequestHandler()
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

describe('Server Request Handler Lifecycle', () => {
  let client: RaftClient;
  let mockTransport: MockTransport | undefined;

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

  afterEach(async () => {
    // Properly cleanup MockTransport resources
    // Note: client.disconnect() won't work as these tests simulate broken states
    // But we need to cleanup the MockTransport queue to avoid resource leaks
    if (mockTransport) {
      mockTransport.closeQueues();
    }
  });

  // ==========================================================================
  // Issue 2 FIXED: Single Handler Enforcement
  // ==========================================================================

  describe('Issue 2 FIXED: Single Handler Enforcement', () => {
    it('prevents multiple handlers via exception', () => {
      // First registration should succeed
      client.onServerRequest(() => {});

      // Second registration should throw
      expect(() => {
        client.onServerRequest(() => {});
      }).toThrow(/already registered/i);
    });

    it('single handler receives all requests', async () => {
      const handlerRequests: ServerRequest[] = [];

      client.onServerRequest((request) => {
        handlerRequests.push(request);
      });

      // Inject 10 server requests directly via emitClientEvent
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

      // All requests should go to the single handler
      expect(handlerRequests.length).toBe(10);
    }, 15000);
  });

  // ==========================================================================
  // Issue 3 FIXED: Handler Cleanup API
  // ==========================================================================

  describe('Issue 3 FIXED: Handler Cleanup API', () => {
    it('removeServerRequestHandler API exists', () => {
      // Register a handler
      client.onServerRequest(() => {});

      // API should exist
      expect(typeof client.removeServerRequestHandler).toBe('function');

      // Should be able to remove handler
      expect(() => client.removeServerRequestHandler()).not.toThrow();
    });

    it('can register new handler after removal', () => {
      // Register first handler
      client.onServerRequest(() => {});

      // Remove it
      client.removeServerRequestHandler();

      // Should be able to register new handler
      expect(() => {
        client.onServerRequest(() => {});
      }).not.toThrow();
    });

    it('removed handler stops receiving requests', async () => {
      const handler1Requests: ServerRequest[] = [];
      const handler2Requests: ServerRequest[] = [];

      // Register first handler
      client.onServerRequest((request) => {
        handler1Requests.push(request);
      });

      // Inject 5 requests
      const emitClientEvent = (client as any).emitClientEvent.bind(client);
      for (let i = 1; i <= 5; i++) {
        emitClientEvent({
          type: 'serverRequestReceived',
          request: {
            type: 'ServerRequest',
            requestId: `req-${i}`,
            payload: Buffer.from(`work-${i}`),
            createdAt: new Date(),
          },
        });
      }

      await new Promise((resolve) => setTimeout(resolve, 100));

      // Handler 1 should receive all 5
      expect(handler1Requests.length).toBe(5);

      // Remove handler 1
      client.removeServerRequestHandler();

      // Register handler 2
      client.onServerRequest((request) => {
        handler2Requests.push(request);
      });

      // Inject 5 more requests
      for (let i = 6; i <= 10; i++) {
        emitClientEvent({
          type: 'serverRequestReceived',
          request: {
            type: 'ServerRequest',
            requestId: `req-${i}`,
            payload: Buffer.from(`work-${i}`),
            createdAt: new Date(),
          },
        });
      }

      await new Promise((resolve) => setTimeout(resolve, 100));

      // Handler 1 should still have only 5 (stopped receiving)
      expect(handler1Requests.length).toBe(5);

      // Handler 2 should receive the new 5
      expect(handler2Requests.length).toBe(5);
    }, 15000);
  });

  // ==========================================================================
  // Real-World Scenario: kvstore-cli watch notifications
  // ==========================================================================

  describe('Real-World Scenario: kvstore-cli watch notifications', () => {
    it('properly handles user restarting watch command', async () => {
      // Simulate kvstore-cli watch command usage

      // First iteration (user runs watch command)
      const iteration1: ServerRequest[] = [];
      client.onServerRequest((req) => iteration1.push(req));

      // Inject 5 requests during first watch
      const emitClientEvent = (client as any).emitClientEvent.bind(client);
      for (let i = 1; i <= 5; i++) {
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

      await new Promise((resolve) => setTimeout(resolve, 100));

      // First handler should receive all 5
      expect(iteration1.length).toBe(5);

      // User cancels (Ctrl+C) - properly cleanup handler
      client.removeServerRequestHandler();

      // User runs watch command again
      const iteration2: ServerRequest[] = [];
      client.onServerRequest((req) => iteration2.push(req));

      // Inject 5 more requests during second watch
      for (let i = 6; i <= 10; i++) {
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

      await new Promise((resolve) => setTimeout(resolve, 100));

      // First handler should still have only 5 (stopped after removal)
      expect(iteration1.length).toBe(5);

      // Second handler should receive the new 5
      expect(iteration2.length).toBe(5);
    }, 15000);
  });
});
