// Unit tests for ConnectedStateHandler — paths not covered by integration tests

import { describe, it, expect } from 'vitest';
import { ConnectedStateHandler } from '../../../src/state/connectedState';
import { ConnectedState, StreamEvent } from '../../../src/state/types';
import { PendingRequests, PendingRequestData } from '../../../src/state/pendingRequests';
import { PendingQueries, PendingQueryData } from '../../../src/state/pendingQueries';
import { ServerRequestTracker } from '../../../src/state/serverRequestTracker';
import { ClientConfig } from '../../../src/config';
import { SessionId, RequestId, MemberId, CorrelationId } from '../../../src/types';

function makeConfig(members?: Map<MemberId, string>): ClientConfig {
  return {
    clusterMembers: members ?? new Map([
      [MemberId.fromString('node1'), 'tcp://localhost:5555'],
      [MemberId.fromString('node2'), 'tcp://localhost:5556'],
    ]),
    capabilities: new Map([['version', '1.0.0']]),
    connectionTimeout: 5000,
    keepAliveInterval: 30000,
    requestTimeout: 10000,
  };
}

function makeConnectedState(overrides?: Partial<ConnectedState>): ConnectedState {
  return {
    state: 'Connected',
    config: makeConfig(),
    sessionId: SessionId.fromString('test-session'),
    capabilities: new Map([['version', '1.0.0']]),
    createdAt: new Date(),
    serverRequestTracker: new ServerRequestTracker(),
    requestIdCounter: RequestId.zero,
    pendingRequests: new PendingRequests(),
    pendingQueries: new PendingQueries(),
    currentMemberId: MemberId.fromString('node1'),
    ...overrides,
  };
}

describe('ConnectedStateHandler', () => {
  const handler = new ConnectedStateHandler();

  describe('ServerRequest deduplication', () => {
    it('should re-ack duplicate (OldRequest) without emitting', async () => {
      // Tracker already processed request 1
      const state = makeConnectedState({
        serverRequestTracker: new ServerRequestTracker(RequestId.fromBigInt(1n)),
      });

      const event: StreamEvent = {
        type: 'ServerMsg',
        message: {
          type: 'ServerRequest',
          requestId: RequestId.fromBigInt(1n), // duplicate — already acked
          payload: Buffer.from('dup'),
          createdAt: new Date(),
        },
      };

      const result = await handler.handle(state, event);

      // Should re-ack
      expect(result.messagesToSend).toHaveLength(1);
      expect(result.messagesToSend![0].type).toBe('ServerRequestAck');
      // Should NOT emit server request for processing
      expect(result.serverRequests).toBeUndefined();
    });

    it('should drop out-of-order ServerRequest silently', async () => {
      // Fresh tracker (last = 0n), request 3 arrives (gap: expected 1)
      const state = makeConnectedState();

      const event: StreamEvent = {
        type: 'ServerMsg',
        message: {
          type: 'ServerRequest',
          requestId: RequestId.fromBigInt(3n), // gap — expected 1n
          payload: Buffer.from('ooo'),
          createdAt: new Date(),
        },
      };

      const result = await handler.handle(state, event);

      // Should not ack or emit
      expect(result.messagesToSend).toBeUndefined();
      expect(result.serverRequests).toBeUndefined();
      expect(result.newState.state).toBe('Connected');
    });
  });

  describe('RequestError handling', () => {
    it('should fail the matching pending request', async () => {
      let rejectedWith: Error | null = null;
      const reqId = RequestId.fromBigInt(5n);
      const pendingData: PendingRequestData = {
        payload: Buffer.from('cmd'),
        resolve: () => {},
        reject: (e) => { rejectedWith = e; },
        createdAt: new Date(),
        lastSentAt: new Date(),
      };

      const state = makeConnectedState({
        pendingRequests: new PendingRequests().add(reqId, pendingData),
      });

      const event: StreamEvent = {
        type: 'ServerMsg',
        message: {
          type: 'RequestError',
          requestId: reqId,
          reason: 'ResponseEvicted',
        },
      };

      const result = await handler.handle(state, event);

      expect(rejectedWith).not.toBeNull();
      expect(rejectedWith!.message).toContain('ResponseEvicted');
      expect(result.newState.state).toBe('Connected');
    });
  });

  describe('SessionClosed handling', () => {
    it('SessionExpired should fail all pending and transition to Disconnected', async () => {
      const rejections: string[] = [];
      const reqId = RequestId.fromBigInt(1n);
      const corrId = CorrelationId.fromString('q1');

      const pendingReqData: PendingRequestData = {
        payload: Buffer.from('cmd'),
        resolve: () => {},
        reject: (e) => rejections.push(e.message),
        createdAt: new Date(),
        lastSentAt: new Date(),
      };
      const pendingQueryData: PendingQueryData = {
        payload: Buffer.from('query'),
        resolve: () => {},
        reject: (e) => rejections.push(e.message),
        createdAt: new Date(),
        lastSentAt: new Date(),
      };

      const state = makeConnectedState({
        pendingRequests: new PendingRequests().add(reqId, pendingReqData),
        pendingQueries: new PendingQueries().add(corrId, pendingQueryData),
      });

      const event: StreamEvent = {
        type: 'ServerMsg',
        message: { type: 'SessionClosed', reason: 'SessionExpired' },
      };

      const result = await handler.handle(state, event);

      expect(result.newState.state).toBe('Disconnected');
      expect(rejections).toHaveLength(2);
      expect(rejections[0]).toContain('Session expired');
    });

    it('NotLeaderAnymore without leader hint should reconnect using first config member', async () => {
      const state = makeConnectedState({
        currentMemberId: MemberId.fromString('node2'), // currently on node2
      });

      const event: StreamEvent = {
        type: 'ServerMsg',
        message: { type: 'SessionClosed', reason: 'NotLeaderAnymore' }, // no leaderId
      };

      const result = await handler.handle(state, event);

      expect(result.newState.state).toBe('ConnectingExistingSession');
      if (result.newState.state === 'ConnectingExistingSession') {
        // Should fall back to first member in config (node1)
        expect(MemberId.unwrap(result.newState.currentMemberId)).toBe('node1');
      }
      expect(result.messagesToSend).toHaveLength(1);
      expect(result.messagesToSend![0].type).toBe('ContinueSession');
    });

    it('Shutdown should fail all pending and transition to Disconnected', async () => {
      const rejections: string[] = [];
      const state = makeConnectedState({
        pendingRequests: new PendingRequests().add(
          RequestId.fromBigInt(1n),
          {
            payload: Buffer.from('cmd'),
            resolve: () => {},
            reject: (e) => rejections.push(e.message),
            createdAt: new Date(),
            lastSentAt: new Date(),
          }
        ),
      });

      const event: StreamEvent = {
        type: 'ServerMsg',
        message: { type: 'SessionClosed', reason: 'Shutdown' },
      };

      const result = await handler.handle(state, event);

      expect(result.newState.state).toBe('Disconnected');
      expect(rejections).toHaveLength(1);
      expect(rejections[0]).toContain('Shutdown');
    });
  });
});
