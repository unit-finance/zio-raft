// Unit tests for ConnectingExistingSessionStateHandler

import { describe, it, expect } from 'vitest';
import { ConnectingExistingSessionStateHandler } from '../../../src/state/connectingExistingSessionState';
import { ConnectingExistingSessionState, StreamEvent } from '../../../src/state/types';
import { PendingRequests, PendingRequestData } from '../../../src/state/pendingRequests';
import { PendingQueries, PendingQueryData } from '../../../src/state/pendingQueries';
import { ServerRequestTracker } from '../../../src/state/serverRequestTracker';
import { ClientConfig } from '../../../src/config';
import { SessionId, RequestId, MemberId, Nonce, CorrelationId } from '../../../src/types';

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

function makeConnectingExistingState(overrides?: {
  nonce?: Nonce;
  currentMemberId?: MemberId;
  config?: ClientConfig;
  createdAt?: Date;
  pendingRequests?: PendingRequests;
  pendingQueries?: PendingQueries;
}): ConnectingExistingSessionState {
  return {
    state: 'ConnectingExistingSession',
    config: overrides?.config ?? makeConfig(),
    sessionId: SessionId.fromString('existing-session'),
    capabilities: new Map([['version', '1.0.0']]),
    nonce: overrides?.nonce ?? Nonce.fromBigInt(42n),
    currentMemberId: overrides?.currentMemberId ?? MemberId.fromString('node1'),
    createdAt: overrides?.createdAt ?? new Date(),
    serverRequestTracker: new ServerRequestTracker(),
    requestIdCounter: RequestId.fromBigInt(3n),
    pendingRequests: overrides?.pendingRequests ?? new PendingRequests(),
    pendingQueries: overrides?.pendingQueries ?? new PendingQueries(),
  };
}

describe('ConnectingExistingSessionStateHandler', () => {
  const handler = new ConnectingExistingSessionStateHandler();

  it('SessionContinued with matching nonce should transition to Connected and resend pending', async () => {
    const stateNonce = Nonce.fromBigInt(42n);
    const reqId = RequestId.fromBigInt(1n);
    const corrId = CorrelationId.fromString('q1');

    const pendingReqData: PendingRequestData = {
      payload: Buffer.from('pending-cmd'),
      resolve: () => {},
      reject: () => {},
      createdAt: new Date(),
      lastSentAt: new Date(Date.now() - 5000),
    };
    const pendingQueryData: PendingQueryData = {
      payload: Buffer.from('pending-query'),
      resolve: () => {},
      reject: () => {},
      createdAt: new Date(),
      lastSentAt: new Date(Date.now() - 5000),
    };

    const state = makeConnectingExistingState({
      nonce: stateNonce,
      pendingRequests: new PendingRequests().add(reqId, pendingReqData),
      pendingQueries: new PendingQueries().add(corrId, pendingQueryData),
    });

    const event: StreamEvent = {
      type: 'ServerMsg',
      message: { type: 'SessionContinued', nonce: stateNonce },
    };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('Connected');
    // Should resend both the pending command and query
    expect(result.messagesToSend).toBeDefined();
    expect(result.messagesToSend!.length).toBe(2);

    const msgTypes = result.messagesToSend!.map((m) => m.type);
    expect(msgTypes).toContain('ClientRequest');
    expect(msgTypes).toContain('Query');
  });

  it('SessionContinued with nonce mismatch should be ignored', async () => {
    const state = makeConnectingExistingState({ nonce: Nonce.fromBigInt(42n) });

    const event: StreamEvent = {
      type: 'ServerMsg',
      message: { type: 'SessionContinued', nonce: Nonce.fromBigInt(999n) },
    };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('ConnectingExistingSession');
    expect(result.messagesToSend).toBeUndefined();
  });

  it('SessionRejected:SessionExpired should fail all pending and go Disconnected', async () => {
    const rejections: string[] = [];
    const stateNonce = Nonce.fromBigInt(42n);

    const pendingReqData: PendingRequestData = {
      payload: Buffer.from('cmd'),
      resolve: () => {},
      reject: (e) => rejections.push(e.message),
      createdAt: new Date(),
      lastSentAt: new Date(),
    };

    const state = makeConnectingExistingState({
      nonce: stateNonce,
      pendingRequests: new PendingRequests().add(RequestId.fromBigInt(1n), pendingReqData),
    });

    const event: StreamEvent = {
      type: 'ServerMsg',
      message: {
        type: 'SessionRejected',
        reason: 'SessionExpired',
        nonce: stateNonce,
      },
    };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('Disconnected');
    expect(rejections).toHaveLength(1);
    expect(rejections[0]).toContain('Session expired');
  });

  it('SessionRejected:NotLeader with known leader should retry with that leader', async () => {
    const stateNonce = Nonce.fromBigInt(42n);
    const state = makeConnectingExistingState({
      nonce: stateNonce,
      currentMemberId: MemberId.fromString('node1'),
    });

    const event: StreamEvent = {
      type: 'ServerMsg',
      message: {
        type: 'SessionRejected',
        reason: 'NotLeader',
        nonce: stateNonce,
        leaderId: MemberId.fromString('node2'),
      },
    };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('ConnectingExistingSession');
    if (result.newState.state === 'ConnectingExistingSession') {
      expect(MemberId.unwrap(result.newState.currentMemberId)).toBe('node2');
    }
    expect(result.messagesToSend).toHaveLength(1);
    expect(result.messagesToSend![0].type).toBe('ContinueSession');
  });

  it('tryNextMember at last member should fail all pending and go Disconnected', async () => {
    const rejections: string[] = [];
    const stateNonce = Nonce.fromBigInt(42n);

    const pendingReqData: PendingRequestData = {
      payload: Buffer.from('cmd'),
      resolve: () => {},
      reject: (e) => rejections.push(e.message),
      createdAt: new Date(),
      lastSentAt: new Date(),
    };

    const state = makeConnectingExistingState({
      nonce: stateNonce,
      currentMemberId: MemberId.fromString('node2'), // last member
      pendingRequests: new PendingRequests().add(RequestId.fromBigInt(1n), pendingReqData),
    });

    // Use 'Other' reason to trigger tryNextMember directly
    const event: StreamEvent = {
      type: 'ServerMsg',
      message: {
        type: 'SessionRejected',
        reason: 'Other',
        nonce: stateNonce,
      },
    };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('Disconnected');
    expect(rejections).toHaveLength(1);
    expect(rejections[0]).toContain('Failed to reconnect');
  });

  it('Disconnect should fail all pending and go Disconnected', async () => {
    const rejections: string[] = [];

    const pendingReqData: PendingRequestData = {
      payload: Buffer.from('cmd'),
      resolve: () => {},
      reject: (e) => rejections.push(e.message),
      createdAt: new Date(),
      lastSentAt: new Date(),
    };

    const state = makeConnectingExistingState({
      pendingRequests: new PendingRequests().add(RequestId.fromBigInt(1n), pendingReqData),
    });

    const event: StreamEvent = {
      type: 'Action',
      action: {
        type: 'Disconnect',
        resolve: () => {},
        reject: () => {},
      },
    };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('Disconnected');
    expect(rejections).toHaveLength(1);
    expect(rejections[0]).toContain('disconnected');
  });

  it('TimeoutCheck when timed out should try next member', async () => {
    const state = makeConnectingExistingState({
      currentMemberId: MemberId.fromString('node1'),
      createdAt: new Date(Date.now() - 10000), // 10 seconds ago
    });

    const event: StreamEvent = { type: 'TimeoutCheck' };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('ConnectingExistingSession');
    if (result.newState.state === 'ConnectingExistingSession') {
      expect(MemberId.unwrap(result.newState.currentMemberId)).toBe('node2');
    }
    expect(result.messagesToSend).toHaveLength(1);
    expect(result.messagesToSend![0].type).toBe('ContinueSession');
  });
});
