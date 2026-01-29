/**
 * Unit test for processInternalEvent - specifically tests that serverRequestReceived
 * is properly routed to the serverRequestQueue
 */

/* eslint-disable @typescript-eslint/no-explicit-any */
/* eslint-disable @typescript-eslint/no-unsafe-assignment */
/* eslint-disable @typescript-eslint/no-unsafe-member-access */
/* eslint-disable @typescript-eslint/no-unsafe-call */

import { describe, it, expect } from 'vitest';
import { RaftClient } from '../../src/client';
import type { ServerRequest } from '../../src/protocol/messages';

describe('processInternalEvent - serverRequestReceived routing', () => {
  it('should route serverRequestReceived event to serverRequests iterator', async () => {
    // Create client
    const client = new RaftClient({
      clusterMembers: new Map([['node1', 'tcp://localhost:5555']]),
      capabilities: new Map([['test', '1.0']]),
    });

    const receivedRequests: ServerRequest[] = [];

    // Start consuming iterator in background
    const iteratorPromise = (async () => {
      for await (const request of client.serverRequests) {
        receivedRequests.push(request);
        if (receivedRequests.length >= 1) break;
      }
    })();

    // Access private method processInternalEvent via type assertion
    const processInternalEvent = (client as any).processInternalEvent.bind(client);

    // Create a serverRequestReceived event (what state machine emits)
    const testRequest: ServerRequest = {
      type: 'ServerRequest',
      requestId: 'test-req-001',
      payload: Buffer.from('test-payload'),
      createdAt: new Date(),
    };

    const event = {
      type: 'serverRequestReceived',
      request: testRequest,
    };

    // Call processInternalEvent
    processInternalEvent(event);

    // Wait for async processing
    await Promise.race([
      iteratorPromise,
      new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 1000)),
    ]);

    // Verify request was received
    expect(receivedRequests).toHaveLength(1);
    expect(receivedRequests[0].requestId).toBe('test-req-001');
    expect(receivedRequests[0].payload.toString()).toBe('test-payload');
  });

  it('should handle multiple serverRequestReceived events', async () => {
    const client = new RaftClient({
      clusterMembers: new Map([['node1', 'tcp://localhost:5555']]),
      capabilities: new Map([['test', '1.0']]),
    });

    const receivedRequests: ServerRequest[] = [];

    // Start consuming iterator in background
    const iteratorPromise = (async () => {
      for await (const request of client.serverRequests) {
        receivedRequests.push(request);
        if (receivedRequests.length >= 5) break;
      }
    })();

    const processInternalEvent = (client as any).processInternalEvent.bind(client);

    // Emit multiple events
    for (let i = 1; i <= 5; i++) {
      processInternalEvent({
        type: 'serverRequestReceived',
        request: {
          type: 'ServerRequest',
          requestId: `req-${i}`,
          payload: Buffer.from(`payload-${i}`),
          createdAt: new Date(),
        },
      });
    }

    // Wait for async processing
    await Promise.race([
      iteratorPromise,
      new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 1000)),
    ]);

    // Verify all received
    expect(receivedRequests).toHaveLength(5);
    expect(receivedRequests.map((r) => r.requestId)).toEqual(['req-1', 'req-2', 'req-3', 'req-4', 'req-5']);
  });
});
