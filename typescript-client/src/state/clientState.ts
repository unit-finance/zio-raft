// Client state machine types and state handlers
// Implements idiomatic TypeScript state machine pattern with discriminated unions

import { SessionId, MemberId, Nonce, RequestId } from '../types';
import { PendingRequests } from './pendingRequests';
import { PendingQueries } from './pendingQueries';
import { ServerRequestTracker } from './serverRequestTracker';

// ============================================================================
// Client Configuration
// ============================================================================

/**
 * Client configuration
 */
export interface ClientConfig {
  readonly clusterMembers: Map<MemberId, string>; // memberId -> ZMQ address
  readonly capabilities: Map<string, string>; // user-provided capabilities
  readonly connectionTimeout: number; // milliseconds
  readonly keepAliveInterval: number; // milliseconds
  readonly requestTimeout: number; // milliseconds
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
export type StreamEvent =
  | ActionEvent
  | ServerMessageEvent
  | KeepAliveTickEvent
  | TimeoutCheckEvent;

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
export type ClientAction =
  | ConnectAction
  | DisconnectAction
  | SubmitCommandAction
  | SubmitQueryAction;

/**
 * Connect action: initiate connection to cluster
 */
export interface ConnectAction {
  readonly type: 'Connect';
}

/**
 * Disconnect action: close session and disconnect
 */
export interface DisconnectAction {
  readonly type: 'Disconnect';
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
 * Includes new state and optional side effects (messages to send, events to emit)
 */
export interface StateTransitionResult {
  readonly newState: ClientState;
  readonly messagesToSend?: Array<import('../protocol/messages').ClientMessage>;
  readonly eventsToEmit?: Array<ClientEventData>;
}

/**
 * Client event data for observability
 */
export type ClientEventData =
  | { type: 'stateChange'; oldState: ClientState['state']; newState: ClientState['state'] }
  | { type: 'connectionAttempt'; memberId: MemberId; address: string }
  | { type: 'connectionSuccess'; memberId: MemberId }
  | { type: 'connectionFailure'; memberId: MemberId; error: Error }
  | { type: 'sessionExpired' }
  | { type: 'requestTimeout'; requestId: RequestId }
  | { type: 'serverRequestReceived'; request: ServerRequest };

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
  private async handleAction(
    state: DisconnectedState,
    action: ClientAction
  ): Promise<StateTransitionResult> {
    switch (action.type) {
      case 'Connect':
        return this.handleConnect(state);
      
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
  private async handleConnect(state: DisconnectedState): Promise<StateTransitionResult> {
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
    
    const [firstMemberId, firstAddress] = firstMember;
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
    };
    
    // Create CreateSession message to send
    const createSessionMsg: import('../protocol/messages').CreateSession = {
      type: 'CreateSession',
      capabilities: config.capabilities,
      nonce,
    };
    
    return {
      newState,
      messagesToSend: [createSessionMsg],
      eventsToEmit: [
        { type: 'stateChange', oldState: 'Disconnected', newState: 'ConnectingNewSession' },
        { type: 'connectionAttempt', memberId: firstMemberId, address: firstAddress },
      ],
    };
  }
}


