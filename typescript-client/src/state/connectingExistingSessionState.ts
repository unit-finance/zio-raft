// Handler for ConnectingExistingSession state

import { MemberId, Nonce } from '../types';
import { ContinueSession } from '../protocol/messages';
import {
  DisconnectedState,
  ConnectingExistingSessionState,
  ConnectedState,
  StreamEvent,
  ClientAction,
  StateTransitionResult,
  SessionContinued,
  SessionRejected,
  ServerMessage,
  buildResendMessages,
} from './types';

/**
 * Handler for ConnectingExistingSession state
 * Handles session resumption after reconnection
 */
export class ConnectingExistingSessionStateHandler {
  /**
   * Handle an event in the ConnectingExistingSession state
   */
  async handle(state: ConnectingExistingSessionState, event: StreamEvent): Promise<StateTransitionResult> {
    switch (event.type) {
      case 'Action':
        return this.handleAction(state, event.action);

      case 'ServerMsg':
        return this.handleServerMessage(state, event.message);

      case 'KeepAliveTick':
        // Don't send keep-alive until connected
        return { newState: state };

      case 'TimeoutCheck':
        return this.handleTimeoutCheck(state);
    }
  }

  /**
   * Handle user actions in ConnectingExistingSession state
   */
  private async handleAction(
    state: ConnectingExistingSessionState,
    action: ClientAction
  ): Promise<StateTransitionResult> {
    switch (action.type) {
      case 'Connect':
        // Already connecting, no-op
        return { newState: state };

      case 'SubmitCommand':
        // Queue command - will be sent after connection
        action.reject(new Error('Session reconnection in progress'));
        return { newState: state };

      case 'SubmitQuery':
        // Queue query - will be sent after connection
        action.reject(new Error('Session reconnection in progress'));
        return { newState: state };

      case 'Disconnect':
        return this.handleDisconnect(state);
    }
  }

  /**
   * Handle Disconnect: fail all pending, return to Disconnected
   */
  private async handleDisconnect(state: ConnectingExistingSessionState): Promise<StateTransitionResult> {
    const error = new Error('Client disconnected');
    state.pendingRequests.failAll(error);
    state.pendingQueries.failAll(error);

    const newState: DisconnectedState = {
      state: 'Disconnected',
      config: state.config,
    };

    return { newState };
  }

  /**
   * Handle server messages in ConnectingExistingSession state
   */
  private async handleServerMessage(
    state: ConnectingExistingSessionState,
    message: ServerMessage
  ): Promise<StateTransitionResult> {
    switch (message.type) {
      case 'SessionContinued':
        return this.handleSessionContinued(state, message);

      case 'SessionRejected':
        return this.handleSessionRejected(state, message);

      default:
        // Ignore other message types during connection
        return { newState: state };
    }
  }

  /**
   * Handle SessionContinued: transition to Connected if nonce matches
   */
  private async handleSessionContinued(
    state: ConnectingExistingSessionState,
    message: SessionContinued
  ): Promise<StateTransitionResult> {
    // Verify nonce matches
    if (Nonce.unwrap(message.nonce) !== Nonce.unwrap(state.nonce)) {
      // Nonce mismatch - ignore (old/duplicate message)
      return { newState: state };
    }

    // Transition to Connected state
    const newState: ConnectedState = {
      state: 'Connected',
      config: state.config,
      sessionId: state.sessionId,
      capabilities: state.capabilities,
      createdAt: state.createdAt,
      serverRequestTracker: state.serverRequestTracker,
      requestIdCounter: state.requestIdCounter,
      pendingRequests: state.pendingRequests,
      pendingQueries: state.pendingQueries,
      currentMemberId: state.currentMemberId,
    };

    // Resend all pending requests and queries
    const now = new Date();
    const { requests: updatedRequests, toResend: requestsToResend } = state.pendingRequests.resendAll(now);
    const { queries: updatedQueries, toResend: queriesToResend } = state.pendingQueries.resendAll(now);

    // Update state with new immutable pending requests and queries
    const updatedState: ConnectedState = {
      ...newState,
      pendingRequests: updatedRequests,
      pendingQueries: updatedQueries,
    };

    // Build messages for pending requests and queries
    const messagesToSend = buildResendMessages(requestsToResend, queriesToResend, updatedRequests, now);

    return {
      newState: updatedState,
      messagesToSend,
    };
  }

  /**
   * Handle SessionRejected: handle based on rejection reason
   */
  private async handleSessionRejected(
    state: ConnectingExistingSessionState,
    message: SessionRejected
  ): Promise<StateTransitionResult> {
    // Verify nonce matches
    if (Nonce.unwrap(message.nonce) !== Nonce.unwrap(state.nonce)) {
      // Nonce mismatch - ignore (old/duplicate message)
      return { newState: state };
    }

    switch (message.reason) {
      case 'SessionExpired':
        return this.handleSessionExpired(state);

      case 'NotLeader':
        return this.handleNotLeader(state, message.leaderId);

      case 'InvalidCapabilities':
        return this.handleInvalidCapabilities(state);

      case 'Other':
        // Try next member
        return this.tryNextMember(state);
    }
  }

  /**
   * Handle SessionExpired: fail all pending, return to Disconnected (terminal)
   */
  private async handleSessionExpired(state: ConnectingExistingSessionState): Promise<StateTransitionResult> {
    const error = new Error('Session expired');
    state.pendingRequests.failAll(error);
    state.pendingQueries.failAll(error);

    const newState: DisconnectedState = {
      state: 'Disconnected',
      config: state.config,
    };

    return { newState };
  }

  /**
   * Handle NotLeader rejection: try to connect to leader if known
   */
  private async handleNotLeader(
    state: ConnectingExistingSessionState,
    leaderId?: MemberId
  ): Promise<StateTransitionResult> {
    if (leaderId) {
      const leaderAddress = state.config.clusterMembers.get(leaderId);
      if (leaderAddress) {
        // Try connecting to the leader
        const nonce = Nonce.generate();
        const newState: ConnectingExistingSessionState = {
          ...state,
          nonce,
          currentMemberId: leaderId,
          createdAt: new Date(),
        };

        const continueSessionMsg: ContinueSession = {
          type: 'ContinueSession',
          sessionId: state.sessionId,
          nonce,
        };

        return {
          newState,
          messagesToSend: [continueSessionMsg],
        };
      }
    }

    // Leader unknown or not in our cluster config - try next member
    return this.tryNextMember(state);
  }

  /**
   * Handle InvalidCapabilities rejection: fail immediately
   */
  private async handleInvalidCapabilities(state: ConnectingExistingSessionState): Promise<StateTransitionResult> {
    const error = new Error('Invalid capabilities');
    state.pendingRequests.failAll(error);
    state.pendingQueries.failAll(error);

    const newState: DisconnectedState = {
      state: 'Disconnected',
      config: state.config,
    };

    return { newState };
  }

  /**
   * Try connecting to the next member in the cluster
   */
  private async tryNextMember(state: ConnectingExistingSessionState): Promise<StateTransitionResult> {
    const members = Array.from(state.config.clusterMembers.entries());
    const currentIndex = members.findIndex(([id]) => MemberId.unwrap(id) === MemberId.unwrap(state.currentMemberId));

    if (currentIndex === -1 || currentIndex === members.length - 1) {
      // No more members to try - fail
      const error = new Error('Failed to reconnect to any cluster member');
      state.pendingRequests.failAll(error);
      state.pendingQueries.failAll(error);

      const newState: DisconnectedState = {
        state: 'Disconnected',
        config: state.config,
      };

      return { newState };
    }

    // Try next member
    const nextMember = members[currentIndex + 1];
    if (!nextMember) {
      // Should not happen due to check above
      throw new Error('Unexpected: next member not found');
    }

    const [nextMemberId] = nextMember;
    const nonce = Nonce.generate();

    const newState: ConnectingExistingSessionState = {
      ...state,
      nonce,
      currentMemberId: nextMemberId,
      createdAt: new Date(),
    };

    const continueSessionMsg: ContinueSession = {
      type: 'ContinueSession',
      sessionId: state.sessionId,
      nonce,
    };

    return {
      newState,
      messagesToSend: [continueSessionMsg],
    };
  }

  /**
   * Handle timeout: check if connection attempt has timed out
   */
  private async handleTimeoutCheck(state: ConnectingExistingSessionState): Promise<StateTransitionResult> {
    const elapsed = Date.now() - state.createdAt.getTime();

    if (elapsed >= state.config.connectionTimeout) {
      // Connection timeout - try next member
      return this.tryNextMember(state);
    }

    return { newState: state };
  }
}
