// Event types for client observability
// Uses discriminated unions for type-safe event handling

import { MemberId, RequestId, CorrelationId } from '../types';
import type { ServerRequest } from '../protocol/messages';
import type { ClientState } from '../state/clientState';

/**
 * All events emitted by the RaftClient
 * Discriminated union for type-safe event handling
 */
export type ClientEvent =
  | StateChangeEvent
  | ConnectionAttemptEvent
  | ConnectionSuccessEvent
  | ConnectionFailureEvent
  | MessageReceivedEvent
  | MessageSentEvent
  | RequestTimeoutEvent
  | QueryTimeoutEvent
  | SessionExpiredEvent
  | ServerRequestReceivedEvent;

/**
 * State change event
 * Emitted when client transitions between states
 */
export interface StateChangeEvent {
  readonly type: 'stateChange';
  readonly oldState: ClientState['state'];
  readonly newState: ClientState['state'];
  readonly timestamp: Date;
}

/**
 * Connection attempt event
 * Emitted when client attempts to connect to a cluster member
 */
export interface ConnectionAttemptEvent {
  readonly type: 'connectionAttempt';
  readonly memberId: MemberId;
  readonly address: string;
  readonly timestamp: Date;
}

/**
 * Connection success event
 * Emitted when client successfully connects to a cluster member
 */
export interface ConnectionSuccessEvent {
  readonly type: 'connectionSuccess';
  readonly memberId: MemberId;
  readonly timestamp: Date;
}

/**
 * Connection failure event
 * Emitted when client fails to connect to a cluster member
 */
export interface ConnectionFailureEvent {
  readonly type: 'connectionFailure';
  readonly memberId: MemberId;
  readonly error: Error;
  readonly timestamp: Date;
}

/**
 * Message received event
 * Emitted when client receives a message from the server
 */
export interface MessageReceivedEvent {
  readonly type: 'messageReceived';
  readonly message: import('../protocol/messages').ServerMessage;
  readonly timestamp: Date;
}

/**
 * Message sent event
 * Emitted when client sends a message to the server
 */
export interface MessageSentEvent {
  readonly type: 'messageSent';
  readonly message: import('../protocol/messages').ClientMessage;
  readonly timestamp: Date;
}

/**
 * Request timeout event
 * Emitted when a command request times out and is being retried
 */
export interface RequestTimeoutEvent {
  readonly type: 'requestTimeout';
  readonly requestId: RequestId;
  readonly timestamp: Date;
}

/**
 * Query timeout event
 * Emitted when a query times out and is being retried
 */
export interface QueryTimeoutEvent {
  readonly type: 'queryTimeout';
  readonly correlationId: CorrelationId;
  readonly timestamp: Date;
}

/**
 * Session expired event
 * Emitted when the session expires (terminal event)
 */
export interface SessionExpiredEvent {
  readonly type: 'sessionExpired';
  readonly timestamp: Date;
}

/**
 * Server request received event
 * Emitted when the server sends a request to the client
 */
export interface ServerRequestReceivedEvent {
  readonly type: 'serverRequestReceived';
  readonly request: ServerRequest;
  readonly timestamp: Date;
}

/**
 * Helper to create timestamped events
 */
export const EventFactory = {
  stateChange: (oldState: ClientState['state'], newState: ClientState['state']): StateChangeEvent => ({
    type: 'stateChange',
    oldState,
    newState,
    timestamp: new Date(),
  }),

  connectionAttempt: (memberId: MemberId, address: string): ConnectionAttemptEvent => ({
    type: 'connectionAttempt',
    memberId,
    address,
    timestamp: new Date(),
  }),

  connectionSuccess: (memberId: MemberId): ConnectionSuccessEvent => ({
    type: 'connectionSuccess',
    memberId,
    timestamp: new Date(),
  }),

  connectionFailure: (memberId: MemberId, error: Error): ConnectionFailureEvent => ({
    type: 'connectionFailure',
    memberId,
    error,
    timestamp: new Date(),
  }),

  messageReceived: (message: import('../protocol/messages').ServerMessage): MessageReceivedEvent => ({
    type: 'messageReceived',
    message,
    timestamp: new Date(),
  }),

  messageSent: (message: import('../protocol/messages').ClientMessage): MessageSentEvent => ({
    type: 'messageSent',
    message,
    timestamp: new Date(),
  }),

  requestTimeout: (requestId: RequestId): RequestTimeoutEvent => ({
    type: 'requestTimeout',
    requestId,
    timestamp: new Date(),
  }),

  queryTimeout: (correlationId: CorrelationId): QueryTimeoutEvent => ({
    type: 'queryTimeout',
    correlationId,
    timestamp: new Date(),
  }),

  sessionExpired: (): SessionExpiredEvent => ({
    type: 'sessionExpired',
    timestamp: new Date(),
  }),

  serverRequestReceived: (request: ServerRequest): ServerRequestReceivedEvent => ({
    type: 'serverRequestReceived',
    request,
    timestamp: new Date(),
  }),
};

