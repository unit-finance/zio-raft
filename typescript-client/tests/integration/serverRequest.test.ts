/**
 * Integration test for server-initiated requests
 *
 * This test verifies that RaftClient properly receives and delivers
 * ServerRequest messages from the server to user handlers.
 */

import { describe, it, expect } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import { RequestId, MemberId } from '../../src/types';
import { ServerRequest } from '../../src/protocol/messages';
import { serverRequestWith } from '../helpers/messageFactories';

describe('Server-Initiated Requests Integration', () => {
  it('should receive ServerRequest via async iterable', async () => {
    const mockTransport = new MockTransport();
    const client = new RaftClient(
      {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['test', '1.0']]),
      },
      mockTransport
    );

    await client.connect();

    const receivedRequests: ServerRequest[] = [];

    // Start consuming iterator in background
    const iteratorPromise = (async () => {
      for await (const request of client.serverRequests) {
        receivedRequests.push(request);
        if (receivedRequests.length >= 1) break;
      }
    })();

    const testRequest = serverRequestWith(RequestId.fromBigInt(1n), Buffer.from('test-work-item'));

    mockTransport.injectMessage(testRequest);

    await Promise.race([
      iteratorPromise,
      new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 1000)),
    ]);

    expect(receivedRequests).toHaveLength(1);
    expect(receivedRequests[0].requestId).toEqual(RequestId.fromBigInt(1n));
    expect(receivedRequests[0].payload.toString()).toBe('test-work-item');

    await client.disconnect();
  }, 15000);

  it('should handle multiple ServerRequest messages', async () => {
    const mockTransport = new MockTransport();
    const client = new RaftClient(
      {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['test', '1.0']]),
      },
      mockTransport
    );

    await client.connect();

    const receivedRequests: ServerRequest[] = [];

    // Start consuming iterator in background
    const iteratorPromise = (async () => {
      for await (const request of client.serverRequests) {
        receivedRequests.push(request);
        if (receivedRequests.length >= 3) break;
      }
    })();

    // Inject multiple requests
    mockTransport.injectMessage(serverRequestWith(RequestId.fromBigInt(1n), Buffer.from('work-1')));
    mockTransport.injectMessage(serverRequestWith(RequestId.fromBigInt(2n), Buffer.from('work-2')));
    mockTransport.injectMessage(serverRequestWith(RequestId.fromBigInt(3n), Buffer.from('work-3')));

    await Promise.race([
      iteratorPromise,
      new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 2000)),
    ]);

    expect(receivedRequests).toHaveLength(3);
    expect(receivedRequests.map((r) => r.requestId)).toEqual([
      RequestId.fromBigInt(1n),
      RequestId.fromBigInt(2n),
      RequestId.fromBigInt(3n),
    ]);
    expect(receivedRequests.map((r) => r.payload.toString())).toEqual(['work-1', 'work-2', 'work-3']);

    await client.disconnect();
  }, 15000);
});
