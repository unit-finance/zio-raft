/**
 * Integration test for server-initiated requests
 *
 * This test verifies that RaftClient properly receives and delivers
 * ServerRequest messages from the server to user handlers.
 *
 * Expected to FAIL initially due to missing queue population in emitClientEvent()
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import { RequestId, MemberId } from '../../src/types';
import type { ServerRequest } from '../../src/protocol/messages';

describe('Server-Initiated Requests Integration', () => {
  let client: RaftClient;
  let mockTransport: MockTransport;

  beforeEach(() => {
    // Create mock transport
    mockTransport = new MockTransport();

    // Create RaftClient with injected transport
    client = new RaftClient(
      {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['test', '1.0']]),
      },
      mockTransport
    );
  });

  afterEach(async () => {
    try {
      client.removeServerRequestHandler();
    } catch {
      // Ignore if no handler registered
    }
    await client.disconnect();
  });

  it('should receive and deliver ServerRequest to handler', async () => {
    await client.connect();

    const receivedRequests: ServerRequest[] = [];
    client.onServerRequest((request) => {
      receivedRequests.push(request);
    });

    const testRequest: ServerRequest = {
      type: 'ServerRequest',
      requestId: RequestId.fromBigInt(1n),
      payload: Buffer.from('test-work-item'),
      createdAt: new Date(),
    };

    mockTransport.injectMessage(testRequest);

    await waitForCondition(
      () => receivedRequests.length > 0,
      1000,
      'ServerRequest handler was not called'
    );

    expect(receivedRequests).toHaveLength(1);
    expect(receivedRequests[0].requestId).toBe(RequestId.fromBigInt(1n));
    expect(receivedRequests[0].payload.toString()).toBe('test-work-item');
  }, 15000);

  it('should handle multiple ServerRequest messages', async () => {
    await client.connect();

    const receivedRequests: ServerRequest[] = [];
    client.onServerRequest((request) => {
      receivedRequests.push(request);
    });

    // Inject multiple requests
    mockTransport.injectMessage({
      type: 'ServerRequest',
      requestId: RequestId.fromBigInt(1n),
      payload: Buffer.from('work-1'),
      createdAt: new Date(),
    });

    mockTransport.injectMessage({
      type: 'ServerRequest',
      requestId: RequestId.fromBigInt(2n),
      payload: Buffer.from('work-2'),
      createdAt: new Date(),
    });

    mockTransport.injectMessage({
      type: 'ServerRequest',
      requestId: RequestId.fromBigInt(3n),
      payload: Buffer.from('work-3'),
      createdAt: new Date(),
    });

    await waitForCondition(
      () => receivedRequests.length >= 3,
      2000,
      'ServerRequest handlers were not called'
    );

    expect(receivedRequests).toHaveLength(3);
    expect(receivedRequests.map((r) => r.requestId)).toEqual([
      RequestId.fromBigInt(1n),
      RequestId.fromBigInt(2n),
      RequestId.fromBigInt(3n),
    ]);
  }, 15000);

  it('should prevent multiple handler registration', async () => {
    await client.connect();

    client.onServerRequest(() => {});

    expect(() => {
      client.onServerRequest(() => {});
    }).toThrow(/already registered/i);
  }, 15000);
});

// Helper function to wait for a condition with timeout
async function waitForCondition(condition: () => boolean, timeoutMs: number, errorMessage: string): Promise<void> {
  const startTime = Date.now();

  while (!condition()) {
    if (Date.now() - startTime > timeoutMs) {
      throw new Error(errorMessage);
    }
    // Wait 10ms before checking again
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
