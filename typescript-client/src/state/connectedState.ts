// Handler for Connected state

import { MemberId, Nonce, RequestId, CorrelationId } from '../types';
import { CloseSession, KeepAlive, ClientRequest, Query, ServerRequestAck, ContinueSession } from '../protocol/messages';
import { PendingRequestData } from './pendingRequests';
import { PendingQueryData } from './pendingQueries';
import {
  DisconnectedState,
  ConnectingExistingSessionState,
  ConnectedState,
  StreamEvent,
  ClientAction,
  StateTransitionResult,
  ServerMessage,
  ClientResponse,
  QueryResponse,
  ServerRequest,
  RequestError,
  SessionClosed,
  SessionCloseReason,
  buildResendMessages,
} from './types';

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
