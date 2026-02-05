// Handler for ConnectingNewSession state

import { MemberId, Nonce, RequestId } from '../types';
import { CreateSession } from '../protocol/messages';
import { ServerRequestTracker } from './serverRequestTracker';
import {
  DisconnectedState,
  ConnectingNewSessionState,
  ConnectedState,
  StreamEvent,
  ClientAction,
  SubmitCommandAction,
  SubmitQueryAction,
  StateTransitionResult,
  SessionCreated,
  SessionRejected,
  ServerMessage,
} from './types';

/**
 * Handler for ConnectingNewSession state
 * Handles session creation, leader redirection, and connection failures
 */
export class ConnectingNewSessionStateHandler {
  /**
   * Handle an event in the ConnectingNewSession state
   */
  async handle(state: ConnectingNewSessionState, event: StreamEvent): Promise<StateTransitionResult> {
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
   * Handle user actions in ConnectingNewSession state
   */
  private async handleAction(state: ConnectingNewSessionState, action: ClientAction): Promise<StateTransitionResult> {
    switch (action.type) {
      case 'Connect':
        // Already connecting, no-op
        return { newState: state };

      case 'SubmitCommand':
        return this.handleSubmitCommand(state, action);

      case 'SubmitQuery':
        return this.handleSubmitQuery(state, action);

      case 'Disconnect':
        return this.handleDisconnect(state);
    }
  }

  /**
   * Handle SubmitCommand: queue the command as pending
   */
  private async handleSubmitCommand(
    state: ConnectingNewSessionState,
    action: SubmitCommandAction
  ): Promise<StateTransitionResult> {
    // Reject command - session not yet established
    // (Could queue for later, but rejecting is simpler for now)
    action.reject(new Error('Session not yet established'));
    return { newState: state };
  }

  /**
   * Handle SubmitQuery: queue the query as pending
   */
  private async handleSubmitQuery(
    state: ConnectingNewSessionState,
    action: SubmitQueryAction
  ): Promise<StateTransitionResult> {
    // Queue query as pending - will be sent after connection
    action.reject(new Error('Session not yet established'));
    return { newState: state };
  }

  /**
   * Handle Disconnect: reject connect promise, return to Disconnected
   */
  private async handleDisconnect(state: ConnectingNewSessionState): Promise<StateTransitionResult> {
    const error = new Error('Client disconnected');

    // Reject the connect() promise
    state.connectReject(error);

    const newState: DisconnectedState = {
      state: 'Disconnected',
      config: state.config,
    };

    return { newState };
  }

  /**
   * Handle server messages in ConnectingNewSession state
   */
  private async handleServerMessage(
    state: ConnectingNewSessionState,
    message: ServerMessage
  ): Promise<StateTransitionResult> {
    switch (message.type) {
      case 'SessionCreated':
        return this.handleSessionCreated(state, message);

      case 'SessionRejected':
        return this.handleSessionRejected(state, message);

      default:
        // Ignore other message types during connection
        return { newState: state };
    }
  }

  /**
   * Handle SessionCreated: transition to Connected if nonce matches
   */
  private async handleSessionCreated(
    state: ConnectingNewSessionState,
    message: SessionCreated
  ): Promise<StateTransitionResult> {
    // Verify nonce matches
    if (Nonce.unwrap(message.nonce) !== Nonce.unwrap(state.nonce)) {
      // Nonce mismatch - ignore (old/duplicate message)
      return { newState: state };
    }

    // Resolve the connect() promise
    state.connectResolve();

    // Transition to Connected state
    const newState: ConnectedState = {
      state: 'Connected',
      config: state.config,
      sessionId: message.sessionId,
      capabilities: state.capabilities,
      createdAt: state.createdAt,
      serverRequestTracker: new ServerRequestTracker(),
      requestIdCounter: RequestId.zero,
      pendingRequests: state.pendingRequests,
      pendingQueries: state.pendingQueries,
      currentMemberId: state.currentMemberId,
    };

    return { newState };
  }

  /**
   * Handle SessionRejected: try next member or fail
   */
  private async handleSessionRejected(
    state: ConnectingNewSessionState,
    message: SessionRejected
  ): Promise<StateTransitionResult> {
    // Verify nonce matches
    if (Nonce.unwrap(message.nonce) !== Nonce.unwrap(state.nonce)) {
      // Nonce mismatch - ignore (old/duplicate message)
      return { newState: state };
    }

    switch (message.reason) {
      case 'NotLeader':
        return this.handleNotLeader(state, message.leaderId);

      case 'InvalidCapabilities':
        return this.handleInvalidCapabilities(state);

      case 'SessionExpired':
      case 'Other':
        // Try next member
        return this.tryNextMember(state);
    }
  }

  /**
   * Handle NotLeader rejection: try to connect to leader if known
   */
  private async handleNotLeader(state: ConnectingNewSessionState, leaderId?: MemberId): Promise<StateTransitionResult> {
    if (leaderId) {
      const leaderAddress = state.config.clusterMembers.get(leaderId);
      if (leaderAddress) {
        // Try connecting to the leader
        const nonce = Nonce.generate();
        const newState: ConnectingNewSessionState = {
          ...state,
          nonce,
          currentMemberId: leaderId,
          createdAt: new Date(),
        };

        const createSessionMsg: CreateSession = {
          type: 'CreateSession',
          capabilities: state.capabilities,
          nonce,
        };

        return {
          newState,
          messagesToSend: [createSessionMsg],
        };
      }
    }

    // Leader unknown or not in our cluster config - try next member
    return this.tryNextMember(state);
  }

  /**
   * Handle InvalidCapabilities rejection: fail immediately
   */
  private async handleInvalidCapabilities(state: ConnectingNewSessionState): Promise<StateTransitionResult> {
    // Reject the connect() promise
    state.connectReject(new Error('Invalid capabilities'));

    const newState: DisconnectedState = {
      state: 'Disconnected',
      config: state.config,
    };

    return { newState };
  }

  /**
   * Try connecting to the next member in the cluster
   */
  private async tryNextMember(state: ConnectingNewSessionState): Promise<StateTransitionResult> {
    const members = Array.from(state.config.clusterMembers.entries());
    const currentIndex = members.findIndex(([id]) => MemberId.unwrap(id) === MemberId.unwrap(state.currentMemberId));

    if (currentIndex === -1 || currentIndex === members.length - 1) {
      // No more members to try - fail
      state.connectReject(new Error('Failed to connect to any cluster member'));

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

    const newState: ConnectingNewSessionState = {
      ...state,
      nonce,
      currentMemberId: nextMemberId,
      createdAt: new Date(),
    };

    const createSessionMsg: CreateSession = {
      type: 'CreateSession',
      capabilities: state.capabilities,
      nonce,
    };

    return {
      newState,
      messagesToSend: [createSessionMsg],
    };
  }

  /**
   * Handle timeout: check if connection attempt has timed out
   */
  private async handleTimeoutCheck(state: ConnectingNewSessionState): Promise<StateTransitionResult> {
    const elapsed = Date.now() - state.createdAt.getTime();

    if (elapsed >= state.config.connectionTimeout) {
      // Connection timeout - try next member
      return this.tryNextMember(state);
    }

    return { newState: state };
  }
}
