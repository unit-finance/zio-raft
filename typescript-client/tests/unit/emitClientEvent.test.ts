/**
 * Unit test for emit ClientEvent - specifically tests that serverRequestReceived
 * is properly routed to the serverRequestQueue
 */

import { describe, it, expect } from 'vitest';
import { RaftClient } from '../../src/client';
import type { ServerRequest } from '../../src/protocol/messages';

describe('emitClientEvent - serverRequestReceived routing', () => {
  it('should route serverRequestReceived event to serverRequestQueue', async () => {
    // Create client
    const client = new RaftClient({
      clusterMembers: new Map([['node1', 'tcp://localhost:5555']]),
      capabilities: new Map([['test', '1.0']]),
    });

    // Register handler
    const receivedRequests: ServerRequest[] = [];
    client.onServerRequest((request) => {
      receivedRequests.push(request);
    });

    // Access private method emitClientEvent via type assertion
    const emitClientEvent = (client as any).emitClientEvent.bind(client);

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

    // Call emitClientEvent
    emitClientEvent(event);

    // Wait a bit for async queue processing
    await new Promise((resolve) => setTimeout(resolve, 100));

    // Verify handler was called
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
    client.onServerRequest((request) => {
      receivedRequests.push(request);
    });

    const emitClientEvent = (client as any).emitClientEvent.bind(client);

    // Emit multiple events
    for (let i = 1; i <= 5; i++) {
      emitClientEvent({
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
    await new Promise((resolve) => setTimeout(resolve, 100));

    // Verify all received
    expect(receivedRequests).toHaveLength(5);
    expect(receivedRequests.map((r) => r.requestId)).toEqual(['req-1', 'req-2', 'req-3', 'req-4', 'req-5']);
  });
});
