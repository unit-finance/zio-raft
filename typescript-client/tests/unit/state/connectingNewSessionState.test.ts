// Unit tests for ConnectingNewSessionStateHandler — paths not covered by integration tests

import { describe, it, expect } from 'vitest';
import { ConnectingNewSessionStateHandler } from '../../../src/state/connectingNewSessionState';
import { ConnectingNewSessionState, StreamEvent } from '../../../src/state/types';
import { PendingRequests } from '../../../src/state/pendingRequests';
import { PendingQueries } from '../../../src/state/pendingQueries';
import { ClientConfig } from '../../../src/config';
import { MemberId, Nonce } from '../../../src/types';

function makeConfig(members?: Map<MemberId, string>): ClientConfig {
  return {
    clusterMembers:
      members ??
      new Map([
        [MemberId.fromString('node1'), 'tcp://localhost:5555'],
        [MemberId.fromString('node2'), 'tcp://localhost:5556'],
      ]),
    capabilities: new Map([['version', '1.0.0']]),
    connectionTimeout: 5000,
    keepAliveInterval: 30000,
    requestTimeout: 10000,
  };
}

function makeConnectingNewState(overrides?: {
  nonce?: Nonce;
  currentMemberId?: MemberId;
  config?: ClientConfig;
  createdAt?: Date;
  connectResolve?: () => void;
  connectReject?: (err: Error) => void;
}): ConnectingNewSessionState {
  return {
    state: 'ConnectingNewSession',
    config: overrides?.config ?? makeConfig(),
    capabilities: new Map([['version', '1.0.0']]),
    nonce: overrides?.nonce ?? Nonce.fromBigInt(42n),
    currentMemberId: overrides?.currentMemberId ?? MemberId.fromString('node1'),
    createdAt: overrides?.createdAt ?? new Date(),
    pendingRequests: new PendingRequests(),
    pendingQueries: new PendingQueries(),
    connectResolve: overrides?.connectResolve ?? (() => {}),
    connectReject: overrides?.connectReject ?? (() => {}),
  };
}

describe('ConnectingNewSessionStateHandler', () => {
  const handler = new ConnectingNewSessionStateHandler();

  it('should ignore SessionCreated with nonce mismatch', async () => {
    const stateNonce = Nonce.fromBigInt(42n);
    const state = makeConnectingNewState({ nonce: stateNonce });

    const event: StreamEvent = {
      type: 'ServerMsg',
      message: {
        type: 'SessionCreated',
        sessionId: 'session-1' as never, // branded type — cast for test
        nonce: Nonce.fromBigInt(999n), // different nonce
      },
    };

    const result = await handler.handle(state, event);

    // Should remain in same state
    expect(result.newState.state).toBe('ConnectingNewSession');
    expect(result.messagesToSend).toBeUndefined();
  });

  it('SessionRejected:NotLeader with known leader should retry with that leader', async () => {
    const stateNonce = Nonce.fromBigInt(42n);
    const state = makeConnectingNewState({
      nonce: stateNonce,
      currentMemberId: MemberId.fromString('node1'),
    });

    const event: StreamEvent = {
      type: 'ServerMsg',
      message: {
        type: 'SessionRejected',
        reason: 'NotLeader',
        nonce: stateNonce,
        leaderId: MemberId.fromString('node2'), // known leader in config
      },
    };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('ConnectingNewSession');
    if (result.newState.state === 'ConnectingNewSession') {
      expect(MemberId.unwrap(result.newState.currentMemberId)).toBe('node2');
    }
    expect(result.messagesToSend).toHaveLength(1);
    expect(result.messagesToSend![0].type).toBe('CreateSession');
  });

  it('SessionRejected:NotLeader without leader at last member should reject connect', async () => {
    let rejectedError: Error | null = null;
    const stateNonce = Nonce.fromBigInt(42n);
    const state = makeConnectingNewState({
      nonce: stateNonce,
      currentMemberId: MemberId.fromString('node2'), // last member
      connectReject: (err) => {
        rejectedError = err;
      },
    });

    const event: StreamEvent = {
      type: 'ServerMsg',
      message: {
        type: 'SessionRejected',
        reason: 'NotLeader',
        nonce: stateNonce,
        // no leaderId
      },
    };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('Disconnected');
    expect(rejectedError).not.toBeNull();
    expect(rejectedError!.message).toContain('Failed to connect');
  });

  it('SessionRejected:InvalidCapabilities should reject connect and go Disconnected', async () => {
    let rejectedError: Error | null = null;
    const stateNonce = Nonce.fromBigInt(42n);
    const state = makeConnectingNewState({
      nonce: stateNonce,
      connectReject: (err) => {
        rejectedError = err;
      },
    });

    const event: StreamEvent = {
      type: 'ServerMsg',
      message: {
        type: 'SessionRejected',
        reason: 'InvalidCapabilities',
        nonce: stateNonce,
      },
    };

    const result = await handler.handle(state, event);

    expect(result.newState.state).toBe('Disconnected');
    expect(rejectedError).not.toBeNull();
    expect(rejectedError!.message).toContain('Invalid capabilities');
  });

  it('TimeoutCheck when timed out should try next member', async () => {
    const stateNonce = Nonce.fromBigInt(42n);
    const state = makeConnectingNewState({
      nonce: stateNonce,
      currentMemberId: MemberId.fromString('node1'),
      createdAt: new Date(Date.now() - 10000), // 10 seconds ago
      config: makeConfig(), // connectionTimeout = 5000
    });

    const event: StreamEvent = { type: 'TimeoutCheck' };

    const result = await handler.handle(state, event);

    // Should move to next member (node2)
    expect(result.newState.state).toBe('ConnectingNewSession');
    if (result.newState.state === 'ConnectingNewSession') {
      expect(MemberId.unwrap(result.newState.currentMemberId)).toBe('node2');
    }
    expect(result.messagesToSend).toHaveLength(1);
    expect(result.messagesToSend![0].type).toBe('CreateSession');
  });

  it('Disconnect during connect should reject the connect promise', async () => {
    let rejectedError: Error | null = null;
    const state = makeConnectingNewState({
      connectReject: (err) => {
        rejectedError = err;
      },
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
    expect(rejectedError).not.toBeNull();
    expect(rejectedError!.message).toContain('disconnected');
  });
});
