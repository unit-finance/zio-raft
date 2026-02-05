// TODO: This file is too large and should be split into separate files per state:
// - disconnectedState.ts
// - connectingNewSessionState.ts
// - connectingExistingSessionState.ts
// - connectedState.ts
// - stateManager.ts (orchestrator)

// Client state machine types and state handlers
// Implements idiomatic TypeScript state machine pattern with discriminated unions

import { SessionId, MemberId, Nonce, RequestId, CorrelationId } from '../types';
import { ClientConfig } from '../config';
import { PendingRequests, PendingRequestData } from './pendingRequests';
import { PendingQueries, PendingQueryData } from './pendingQueries';
import { ServerRequestTracker } from './serverRequestTracker';
import {
  CreateSession,
  ContinueSession,
  CloseSession,
  ServerRequestAck,
  KeepAlive,
  ClientMessage,
  ClientRequest,
  Query,
} from '../protocol/messages';

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * Build ClientRequest and Query messages from pending items for resend.
 * Used after reconnection (resendAll) and on timeout (resendExpired).
 */
function buildResendMessages(
  requestsToResend: ReadonlyArray<{ requestId: RequestId; payload: Buffer }>,
  queriesToResend: ReadonlyArray<{ correlationId: CorrelationId; payload: Buffer }>,
  pendingRequests: PendingRequests,
  now: Date
): ClientMessage[] {
  const messages: ClientMessage[] = [];

  // Build ClientRequest messages for pending commands
  for (const { requestId, payload } of requestsToResend) {
    const lowestPendingRequestId = pendingRequests.lowestPendingRequestIdOr(requestId);
    const clientRequest: ClientRequest = {
      type: 'ClientRequest',
      requestId,
      lowestPendingRequestId,
      payload,
      createdAt: now,
    };
    messages.push(clientRequest);
  }

  // Build Query messages for pending queries
  for (const { correlationId, payload } of queriesToResend) {
    const query: Query = {
      type: 'Query',
      correlationId,
      payload,
      createdAt: now,
    };
    messages.push(query);
  }

  return messages;
}

// ============================================================================
// Client State (Discriminated Union)
// ============================================================================

/**
 * Client state machine states
 * Uses discriminated union for type-safe state transitions
 */
export type ClientState =
  | DisconnectedState
  | ConnectingNewSessionState
  | ConnectingExistingSessionState
  | ConnectedState;

/**
 * Disconnected: Initial state, not connected to cluster
 */
export interface DisconnectedState {
  readonly state: 'Disconnected';
  readonly config: ClientConfig;
}

/**
 * ConnectingNewSession: Creating a new session with the cluster
 */
export interface ConnectingNewSessionState {
  readonly state: 'ConnectingNewSession';
  readonly config: ClientConfig;
  readonly capabilities: Map<string, string>;
  readonly nonce: Nonce;
  readonly currentMemberId: MemberId;
  readonly createdAt: Date;
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
  // Connect action callbacks (to resolve/reject the connect() promise)
  readonly connectResolve: () => void;
  readonly connectReject: (err: Error) => void;
}

/**
 * ConnectingExistingSession: Resuming an existing session after reconnection
 */
export interface ConnectingExistingSessionState {
  readonly state: 'ConnectingExistingSession';
  readonly config: ClientConfig;
  readonly sessionId: SessionId;
  readonly capabilities: Map<string, string>;
  readonly nonce: Nonce;
  readonly currentMemberId: MemberId;
  readonly createdAt: Date;
  readonly serverRequestTracker: ServerRequestTracker;
  readonly requestIdCounter: RequestId;
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
}

/**
 * Connected: Active session with the cluster
 */
export interface ConnectedState {
  readonly state: 'Connected';
  readonly config: ClientConfig;
  readonly sessionId: SessionId;
  readonly capabilities: Map<string, string>;
  readonly createdAt: Date;
  readonly serverRequestTracker: ServerRequestTracker;
  readonly requestIdCounter: RequestId;
  readonly pendingRequests: PendingRequests;
  readonly pendingQueries: PendingQueries;
  readonly currentMemberId: MemberId;
}

// ============================================================================
// Stream Events (Internal State Machine Events)
// ============================================================================

/**
 * Unified event stream events (internal to state machine)
 * Combines action commands, server messages, timers into single stream
 */
export type StreamEvent = ActionEvent | ServerMessageEvent | KeepAliveTickEvent | TimeoutCheckEvent;

/**
 * User action event (from API calls)
 */
export interface ActionEvent {
  readonly type: 'Action';
  readonly action: ClientAction;
}

/**
 * Server message event (from ZMQ transport)
 */
export interface ServerMessageEvent {
  readonly type: 'ServerMsg';
  readonly message: ServerMessage;
}

/**
 * Keep-alive timer tick event
 */
export interface KeepAliveTickEvent {
  readonly type: 'KeepAliveTick';
}

/**
 * Timeout check event (for request retry)
 */
export interface TimeoutCheckEvent {
  readonly type: 'TimeoutCheck';
}

// ============================================================================
// Client Actions (Internal API Commands)
// ============================================================================

/**
 * Internal actions from user API calls
 */
export type ClientAction = ConnectAction | DisconnectAction | SubmitCommandAction | SubmitQueryAction;

/**
 * Connect action: initiate connection to cluster
 */
export interface ConnectAction {
  readonly type: 'Connect';
  readonly resolve: () => void;
  readonly reject: (error: Error) => void;
}

/**
 * Disconnect action: close session and disconnect
 */
export interface DisconnectAction {
  readonly type: 'Disconnect';
  readonly resolve: () => void;
  readonly reject: (error: Error) => void;
}

/**
 * Submit command action: send write operation
 */
export interface SubmitCommandAction {
  readonly type: 'SubmitCommand';
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
}

/**
 * Submit query action: send read-only operation
 */
export interface SubmitQueryAction {
  readonly type: 'SubmitQuery';
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (error: Error) => void;
}

// ============================================================================
// Import Server Message Types (for type checking)
// ============================================================================

// Re-export server message types from protocol module for convenience
import type {
  ServerMessage,
  SessionCreated,
  SessionContinued,
  SessionRejected,
  SessionClosed,
  SessionCloseReason,
  KeepAliveResponse,
  ClientResponse,
  QueryResponse,
  ServerRequest,
  RequestError,
} from '../protocol/messages';

export type {
  ServerMessage,
  SessionCreated,
  SessionContinued,
  SessionRejected,
  SessionClosed,
  SessionCloseReason,
  KeepAliveResponse,
  ClientResponse,
  QueryResponse,
  ServerRequest,
  RequestError,
};

// ============================================================================
// State Transition Result
// ============================================================================

/**
 * Result of a state transition
 * Includes new state and optional side effects (messages to send, server requests to process)
 */
export interface StateTransitionResult {
  readonly newState: ClientState;
  readonly messagesToSend?: Array<ClientMessage>;
  readonly serverRequests?: Array<ServerRequest>;
}

// ============================================================================
// State Handlers
// ============================================================================

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
   * TODO: This logic repeats in ConnectingNewSessionStateHandler - consider extracting to shared helper
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
   * TODO: This logic repeats in ConnectingNewSessionStateHandler - consider extracting to shared helper
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

/**
 * Handler for Connected state
 * Handles all operational messages in the active session
 */
export class ConnectedStateHandler {
  /**
   * Handle an event in the Connected state
   */
  async handle(state: ConnectedState, event: StreamEvent): Promise<StateTransitionResult> {
    switch (event.type) {
      case 'Action':
        return this.handleAction(state, event.action);

      case 'ServerMsg':
        return this.handleServerMessage(state, event.message);

      case 'KeepAliveTick':
        return this.handleKeepAliveTick(state);

      case 'TimeoutCheck':
        return this.handleTimeoutCheck(state);
    }
  }

  /**
   * Handle user actions in Connected state
   */
  private async handleAction(state: ConnectedState, action: ClientAction): Promise<StateTransitionResult> {
    switch (action.type) {
      case 'Connect':
        // Already connected, no-op
        return { newState: state };

      case 'SubmitCommand': {
        // Use current request ID and compute next one
        const requestId = state.requestIdCounter;
        const nextId = RequestId.next(requestId);
        const lowestPendingRequestId = state.pendingRequests.lowestPendingRequestIdOr(requestId);
        const now = new Date();

        // Create ClientRequest protocol message
        const clientRequest: ClientRequest = {
          type: 'ClientRequest',
          requestId,
          lowestPendingRequestId,
          payload: action.payload,
          createdAt: now,
        };

        // Track pending request with callbacks (immutable - returns new instance)
        const pendingData: PendingRequestData = {
          payload: action.payload,
          resolve: action.resolve,
          reject: action.reject,
          createdAt: now,
          lastSentAt: now,
        };

        const updatedPendingRequests = state.pendingRequests.add(requestId, pendingData);

        // Return updated state with new requestIdCounter and pendingRequests
        return {
          newState: {
            ...state,
            requestIdCounter: nextId,
            pendingRequests: updatedPendingRequests,
          },
          messagesToSend: [clientRequest],
        };
      }

      case 'SubmitQuery': {
        // Generate correlation ID for query
        const correlationId = CorrelationId.generate();
        const now = new Date();

        // Create Query protocol message
        const query: Query = {
          type: 'Query',
          correlationId,
          payload: action.payload,
          createdAt: now,
        };

        // Track pending query with callbacks (immutable - returns new instance)
        const pendingData: PendingQueryData = {
          payload: action.payload,
          resolve: action.resolve,
          reject: action.reject,
          createdAt: now,
          lastSentAt: now,
        };

        const updatedPendingQueries = state.pendingQueries.add(correlationId, pendingData);

        // Return updated state with new pendingQueries
        return {
          newState: {
            ...state,
            pendingQueries: updatedPendingQueries,
          },
          messagesToSend: [query],
        };
      }

      case 'Disconnect':
        return this.handleDisconnect(state);
    }
  }

  /**
   * Handle Disconnect: close session cleanly
   */
  private async handleDisconnect(state: ConnectedState): Promise<StateTransitionResult> {
    const closeSessionMsg: CloseSession = {
      type: 'CloseSession',
      reason: 'ClientShutdown',
    };

    // Fail all pending requests/queries
    const error = new Error('Client disconnected');
    state.pendingRequests.failAll(error);
    state.pendingQueries.failAll(error);

    const newState: DisconnectedState = {
      state: 'Disconnected',
      config: state.config,
    };

    return {
      newState,
      messagesToSend: [closeSessionMsg],
    };
  }

  /**
   * Handle server messages in Connected state
   */
  private async handleServerMessage(state: ConnectedState, message: ServerMessage): Promise<StateTransitionResult> {
    switch (message.type) {
      case 'ClientResponse':
        return this.handleClientResponse(state, message);

      case 'QueryResponse':
        return this.handleQueryResponse(state, message);

      case 'ServerRequest':
        return this.handleServerRequest(state, message);

      case 'RequestError':
        return this.handleRequestError(state, message);

      case 'SessionClosed':
        return this.handleSessionClosed(state, message);

      case 'KeepAliveResponse':
        // TODO: Implement proper KeepAliveResponse handling:
        // - Validate timestamp is not too far in the past (detect stale responses)
        // - Track RTT for connection health monitoring
        // - Consider clock drift detection for distributed systems
        // For now, we just acknowledge the response without validation
        return { newState: state };

      default:
        // Ignore other message types (e.g., SessionCreated, SessionRejected)
        return { newState: state };
    }
  }

  /**
   * Handle ClientResponse: complete pending request
   */
  private async handleClientResponse(state: ConnectedState, message: ClientResponse): Promise<StateTransitionResult> {
    // Complete the pending request (immutable - returns new instance)
    const updatedPendingRequests = state.pendingRequests.complete(message.requestId, message.result);
    return {
      newState: {
        ...state,
        pendingRequests: updatedPendingRequests,
      },
    };
  }

  /**
   * Handle QueryResponse: complete pending query
   */
  private async handleQueryResponse(state: ConnectedState, message: QueryResponse): Promise<StateTransitionResult> {
    // Complete the pending query (immutable - returns new instance)
    const updatedPendingQueries = state.pendingQueries.complete(message.correlationId, message.result);
    return {
      newState: {
        ...state,
        pendingQueries: updatedPendingQueries,
      },
    };
  }

  /**
   * Handle ServerRequest: process or re-ack based on tracker
   */
  private async handleServerRequest(state: ConnectedState, message: ServerRequest): Promise<StateTransitionResult> {
    const result = state.serverRequestTracker.shouldProcess(message.requestId);

    switch (result.type) {
      case 'Process': {
        // New request - acknowledge and emit event
        const newTracker = state.serverRequestTracker.withLastAcknowledged(message.requestId);
        const newState: ConnectedState = {
          ...state,
          serverRequestTracker: newTracker,
        };

        const ackMsg: ServerRequestAck = {
          type: 'ServerRequestAck',
          requestId: message.requestId,
        };

        return {
          newState,
          messagesToSend: [ackMsg],
          serverRequests: [message],
        };
      }

      case 'OldRequest': {
        // Duplicate request - re-acknowledge without processing
        const ackMsg: ServerRequestAck = {
          type: 'ServerRequestAck',
          requestId: message.requestId,
        };

        return {
          newState: state,
          messagesToSend: [ackMsg],
        };
      }

      case 'OutOfOrder':
        // Gap detected - drop the request
        return { newState: state };
    }
  }

  /**
   * Handle RequestError: fail the pending request
   */
  private async handleRequestError(state: ConnectedState, message: RequestError): Promise<StateTransitionResult> {
    const error = new Error(`Request error: ${message.reason}`);
    // Fail the pending request (immutable - returns new instance)
    const updatedPendingRequests = state.pendingRequests.fail(message.requestId, error);
    return {
      newState: {
        ...state,
        pendingRequests: updatedPendingRequests,
      },
    };
  }

  /**
   * Handle SessionClosed: transition to Disconnected or reconnect
   */
  private async handleSessionClosed(state: ConnectedState, message: SessionClosed): Promise<StateTransitionResult> {
    switch (message.reason) {
      case 'SessionExpired':
        return this.handleSessionExpired(state);

      case 'NotLeaderAnymore':
        return this.handleNotLeaderAnymore(state, message.leaderId);

      case 'Shutdown':
      case 'SessionError':
      case 'ConnectionClosed':
        return this.handleTerminalClose(state, message.reason);
    }
  }

  /**
   * Handle SessionExpired: terminal failure
   */
  private async handleSessionExpired(state: ConnectedState): Promise<StateTransitionResult> {
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
   * Handle NotLeaderAnymore: reconnect to new leader
   */
  private async handleNotLeaderAnymore(state: ConnectedState, leaderId?: MemberId): Promise<StateTransitionResult> {
    // Determine which member to connect to
    let targetMemberId: MemberId;

    if (leaderId) {
      const targetAddress = state.config.clusterMembers.get(leaderId);
      if (targetAddress) {
        targetMemberId = leaderId;
      } else {
        // Leader not in our config - try first member
        const members = Array.from(state.config.clusterMembers.entries());
        const firstMember = members[0];
        if (!firstMember) {
          throw new Error('No cluster members configured');
        }
        [targetMemberId] = firstMember;
      }
    } else {
      // No leader hint - try first member
      const members = Array.from(state.config.clusterMembers.entries());
      const firstMember = members[0];
      if (!firstMember) {
        throw new Error('No cluster members configured');
      }
      [targetMemberId] = firstMember;
    }

    // Transition to ConnectingExistingSession
    const nonce = Nonce.generate();
    const newState: ConnectingExistingSessionState = {
      state: 'ConnectingExistingSession',
      config: state.config,
      sessionId: state.sessionId,
      capabilities: state.capabilities,
      nonce,
      currentMemberId: targetMemberId,
      createdAt: new Date(),
      serverRequestTracker: state.serverRequestTracker,
      requestIdCounter: state.requestIdCounter,
      pendingRequests: state.pendingRequests,
      pendingQueries: state.pendingQueries,
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
   * Handle terminal session close reasons
   */
  private async handleTerminalClose(state: ConnectedState, reason: SessionCloseReason): Promise<StateTransitionResult> {
    const error = new Error(`Session closed: ${reason}`);
    state.pendingRequests.failAll(error);
    state.pendingQueries.failAll(error);

    const newState: DisconnectedState = {
      state: 'Disconnected',
      config: state.config,
    };

    return { newState };
  }

  /**
   * Handle KeepAliveTick: send keep-alive message
   */
  private async handleKeepAliveTick(state: ConnectedState): Promise<StateTransitionResult> {
    const keepAliveMsg: KeepAlive = {
      type: 'KeepAlive',
      timestamp: new Date(),
    };

    return {
      newState: state,
      messagesToSend: [keepAliveMsg],
    };
  }

  /**
   * Handle TimeoutCheck: resend expired requests and queries
   */
  private async handleTimeoutCheck(state: ConnectedState): Promise<StateTransitionResult> {
    const now = new Date();
    const { requests: updatedRequests, toResend: expiredRequests } = state.pendingRequests.resendExpired(
      now,
      state.config.requestTimeout
    );
    const { queries: updatedQueries, toResend: expiredQueries } = state.pendingQueries.resendExpired(
      now,
      state.config.requestTimeout
    );

    // Build messages for expired requests and queries
    const messagesToSend = buildResendMessages(expiredRequests, expiredQueries, updatedRequests, now);

    return {
      newState: {
        ...state,
        pendingRequests: updatedRequests,
        pendingQueries: updatedQueries,
      },
      messagesToSend,
    };
  }
}

// ============================================================================
// State Manager (Orchestrator)
// ============================================================================

/**
 * State manager - orchestrates state handlers
 * Dispatches events to appropriate handler based on current state
 */
export class StateManager {
  private readonly disconnectedHandler = new DisconnectedStateHandler();
  private readonly connectingNewSessionHandler = new ConnectingNewSessionStateHandler();
  private readonly connectingExistingSessionHandler = new ConnectingExistingSessionStateHandler();
  private readonly connectedHandler = new ConnectedStateHandler();

  /**
   * Handle an event in the current state
   * Routes to appropriate state handler
   */
  async handleEvent(state: ClientState, event: StreamEvent): Promise<StateTransitionResult> {
    switch (state.state) {
      case 'Disconnected':
        return this.disconnectedHandler.handle(state, event);

      case 'ConnectingNewSession':
        return this.connectingNewSessionHandler.handle(state, event);

      case 'ConnectingExistingSession':
        return this.connectingExistingSessionHandler.handle(state, event);

      case 'Connected':
        return this.connectedHandler.handle(state, event);

      default:
        // Exhaustive check
        const _exhaustive: never = state;
        throw new Error(`Unknown state: ${(_exhaustive as ClientState).state}`);
    }
  }
}
