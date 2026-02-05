// Handler for Disconnected state

import { Nonce } from '../types';
import { CreateSession } from '../protocol/messages';
import { PendingRequests } from './pendingRequests';
import { PendingQueries } from './pendingQueries';
import {
  DisconnectedState,
  ConnectingNewSessionState,
  StreamEvent,
  ClientAction,
  ConnectAction,
  StateTransitionResult,
} from './types';

/**
 * Handler for Disconnected state
 * Uses class with methods for state transition logic
 */
export class DisconnectedStateHandler {
  /**
   * Handle an event in the Disconnected state
   */
  async handle(state: DisconnectedState, event: StreamEvent): Promise<StateTransitionResult> {
    switch (event.type) {
      case 'Action':
        return this.handleAction(state, event.action);

      case 'ServerMsg':
      case 'KeepAliveTick':
      case 'TimeoutCheck':
        // Ignore these events when disconnected
        return { newState: state };
    }
  }

  /**
   * Handle user actions in Disconnected state
   */
  private async handleAction(state: DisconnectedState, action: ClientAction): Promise<StateTransitionResult> {
    switch (action.type) {
      case 'Connect':
        return this.handleConnect(state, action);

      case 'SubmitCommand':
        // Reject command - not connected
        action.reject(new Error('Not connected to cluster'));
        return { newState: state };

      case 'SubmitQuery':
        // Reject query - not connected
        action.reject(new Error('Not connected to cluster'));
        return { newState: state };

      case 'Disconnect':
        // Already disconnected, no-op
        return { newState: state };
    }
  }

  /**
   * Handle Connect action: transition to ConnectingNewSession
   */
  private async handleConnect(state: DisconnectedState, action: ConnectAction): Promise<StateTransitionResult> {
    const { config } = state;

    // Get first cluster member to try
    const members = Array.from(config.clusterMembers.entries());
    if (members.length === 0) {
      throw new Error('No cluster members configured');
    }

    const firstMember = members[0];
    if (!firstMember) {
      throw new Error('No cluster members configured');
    }

    const [firstMemberId] = firstMember;
    const nonce = Nonce.generate();
    const createdAt = new Date();

    // Create new state
    const newState: ConnectingNewSessionState = {
      state: 'ConnectingNewSession',
      config,
      capabilities: new Map(config.capabilities),
      nonce,
      currentMemberId: firstMemberId,
      createdAt,
      pendingRequests: new PendingRequests(),
      pendingQueries: new PendingQueries(),

      // Store connect callbacks to resolve/reject the connect() promise
      connectResolve: action.resolve,
      connectReject: action.reject,
    };

    // Create CreateSession message to send
    const createSessionMsg: CreateSession = {
      type: 'CreateSession',
      capabilities: config.capabilities,
      nonce,
    };

    return {
      newState,
      messagesToSend: [createSessionMsg],
    };
  }
}
