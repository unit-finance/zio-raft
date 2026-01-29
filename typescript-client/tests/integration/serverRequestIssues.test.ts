/* eslint-disable @typescript-eslint/no-explicit-any */
/* eslint-disable @typescript-eslint/no-unsafe-assignment */
/* eslint-disable @typescript-eslint/no-unsafe-member-access */
/* eslint-disable @typescript-eslint/no-unsafe-call */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import { RequestId } from '../../src/types';
import { serverRequestWith } from '../helpers/messageFactories';
import type { ServerRequest } from '../../src/protocol/messages';

describe('Server Request Iterator', () => {
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
  });

  afterEach(async () => {
    // Properly cleanup MockTransport resources
    if (mockTransport) {
      mockTransport.closeQueues();
    }
  });

  // ==========================================================================
  // Iterator Break and Restart
  // ==========================================================================

  describe('Iterator Break and Restart', () => {
    it('can break out of iterator and start a new one', async () => {
      // Simulate kvstore-cli watch command usage

      // First iteration (user runs watch command)
      const iteration1: ServerRequest[] = [];
      const iterator1Promise = (async () => {
        for await (const request of client.serverRequests) {
          iteration1.push(request);
          if (iteration1.length >= 5) break; // User presses Ctrl+C after 5
        }
      })();

      // Inject 10 requests during first watch
      const processInternalEvent = (client as any).processInternalEvent.bind(client);
      for (let i = 1; i <= 10; i++) {
        processInternalEvent({
          type: 'serverRequestReceived',
          request: serverRequestWith(RequestId.fromBigInt(BigInt(i)), Buffer.from(`notification-${i}`)),
        });
      }

      // Wait for first iterator to complete (breaks after 5)
      await Promise.race([
        iterator1Promise,
        new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 1000)),
      ]);

      // First iteration should have received only 5 (broke early)
      expect(iteration1.length).toBe(5);

      // User runs watch command again
      const iteration2: ServerRequest[] = [];
      const iterator2Promise = (async () => {
        for await (const request of client.serverRequests) {
          iteration2.push(request);
          if (iteration2.length >= 5) break;
        }
      })();

      // Wait for second iterator to consume the remaining 5
      await Promise.race([
        iterator2Promise,
        new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 1000)),
      ]);

      // Second iteration should receive the remaining 5 requests
      expect(iteration2.length).toBe(5);

      // Verify requests were in order
      const allReceivedIds = [...iteration1, ...iteration2].map((r) => r.requestId);
      expect(allReceivedIds).toEqual([
        RequestId.fromBigInt(1n),
        RequestId.fromBigInt(2n),
        RequestId.fromBigInt(3n),
        RequestId.fromBigInt(4n),
        RequestId.fromBigInt(5n),
        RequestId.fromBigInt(6n),
        RequestId.fromBigInt(7n),
        RequestId.fromBigInt(8n),
        RequestId.fromBigInt(9n),
        RequestId.fromBigInt(10n),
      ]);
    }, 15000);
  });
});
