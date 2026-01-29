// State machine unit tests
// Tests state transitions with mock transport

import { describe, it, expect, beforeEach } from 'vitest';
import { DisconnectedState, StateManager, StreamEvent } from '../../../src/state/clientState';
import { ClientConfig } from '../../../src/config';
import { MemberId } from '../../../src/types';

describe('State Machine', () => {
  let stateManager: StateManager;
  let mockConfig: ClientConfig;

  beforeEach(() => {
    stateManager = new StateManager();
    mockConfig = {
      clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
      capabilities: new Map([['version', '1.0.0']]),
      connectionTimeout: 5000,
      keepAliveInterval: 30000,
      requestTimeout: 10000,
    };
  });

  describe('TC-STATE-001: Disconnected handles Connect', () => {
    it('should transition from Disconnected to ConnectingNewSession on Connect action', async () => {
      const state: DisconnectedState = {
        state: 'Disconnected',
        config: mockConfig,
      };

      const connectAction: StreamEvent = {
        type: 'Action',
        action: {
          type: 'Connect',
          resolve: () => {},
          reject: () => {},
        },
      };

      const result = await stateManager.handleEvent(state, connectAction);

      expect(result.newState.state).toBe('ConnectingNewSession');
      expect(result.messagesToSend).toBeDefined();
      expect(result.messagesToSend?.length).toBeGreaterThan(0);
      expect(result.messagesToSend?.[0]?.type).toBe('CreateSession');
    });
  });

  describe('TC-STATE-002: Disconnected rejects SubmitCommand', () => {
    it('should reject SubmitCommand when disconnected', async () => {
      const state: DisconnectedState = {
        state: 'Disconnected',
        config: mockConfig,
      };

      let rejected = false;
      const submitAction: StreamEvent = {
        type: 'Action',
        action: {
          type: 'SubmitCommand',
          payload: Buffer.from([1, 2, 3]),
          resolve: () => {},
          reject: () => {
            rejected = true;
          },
        },
      };

      await stateManager.handleEvent(state, submitAction);

    expect(rejected).toBe(true);
  });
});
});
