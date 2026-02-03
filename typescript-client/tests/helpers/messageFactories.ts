/**
 * Test helpers for constructing protocol messages
 *
 * These factories reduce duplication in tests by providing convenient methods
 * for creating protocol messages. When the protocol changes, update these
 * factories instead of every test.
 *
 * Design principles:
 * - Type-safe (use actual protocol types)
 * - Minimal required parameters (reasonable defaults)
 * - Clear naming (reads naturally in tests)
 * - No magic (explicit about what's being created)
 */

import {
  SessionCreated,
  SessionClosed,
  SessionContinued,
  SessionRejected,
  ClientResponse,
  QueryResponse,
  ServerRequest,
  SessionCloseReason,
} from '../../src/protocol/messages';
import { SessionId, RequestId, CorrelationId, Nonce, MemberId } from '../../src/types';

// =============================================================================
// Session Management Messages
// =============================================================================

/**
 * Create SessionCreated message
 *
 * @param nonce - Nonce from CreateSession message
 * @param sessionId - Optional session ID (generates random if not provided)
 *
 * @example
 * const createSession = transport.getSentMessagesOfType('CreateSession')[0];
 * transport.injectMessage(sessionCreatedFor(createSession.nonce));
 */
export function sessionCreatedFor(nonce: Nonce, sessionId?: SessionId): SessionCreated {
  return {
    type: 'SessionCreated',
    sessionId: sessionId ?? SessionId.fromString(`test-session-${Date.now()}`),
    nonce,
  };
}

/**
 * Create SessionClosed message (leader changed)
 *
 * @param reason - Why session closed
 * @param leaderId - Optional new leader ID
 *
 * @example
 * transport.injectMessage(sessionClosedDueTo('NotLeaderAnymore', MemberId.fromString('node2')));
 */
export function sessionClosedDueTo(reason: SessionCloseReason, leaderId?: MemberId): SessionClosed {
  return {
    type: 'SessionClosed',
    reason,
    ...(leaderId !== undefined && { leaderId }),
  };
}

/**
 * Create SessionContinued message (reconnection successful)
 *
 * @param nonce - Nonce from ContinueSession message
 *
 * @example
 * const continueSession = transport.getSentMessagesOfType('ContinueSession')[0];
 * transport.injectMessage(sessionContinuedFor(continueSession.nonce));
 */
export function sessionContinuedFor(nonce: Nonce): SessionContinued {
  return {
    type: 'SessionContinued',
    nonce,
  };
}

/**
 * Create SessionRejected message (reconnection failed)
 *
 * @param nonce - Nonce from ContinueSession message
 * @param reason - Why session was rejected
 * @param leaderId - Optional leader ID for NotLeader reason
 *
 * @example
 * transport.injectMessage(sessionRejectedWith(nonce, 'SessionExpired'));
 * transport.injectMessage(sessionRejectedWith(nonce, 'NotLeader', MemberId.fromString('node3')));
 */
export function sessionRejectedWith(
  nonce: Nonce,
  reason: 'NotLeader' | 'SessionExpired' | 'InvalidSession' | 'Unknown',
  leaderId?: MemberId
): SessionRejected {
  return {
    type: 'SessionRejected',
    nonce,
    reason,
    ...(leaderId !== undefined && { leaderId }),
  };
}

// =============================================================================
// Request/Response Messages
// =============================================================================

/**
 * Create ClientResponse message (command result)
 *
 * @param requestId - Request ID from ClientRequest
 * @param result - Response payload
 *
 * @example
 * const request = transport.getSentMessagesOfType('ClientRequest')[0];
 * transport.injectMessage(clientResponseFor(request.requestId, Buffer.from('success')));
 */
export function clientResponseFor(requestId: RequestId, result: Buffer): ClientResponse {
  return {
    type: 'ClientResponse',
    requestId,
    result,
  };
}

/**
 * Create QueryResponse message (query result)
 *
 * @param correlationId - Correlation ID from Query
 * @param result - Response payload
 *
 * @example
 * const query = transport.getSentMessagesOfType('Query')[0];
 * transport.injectMessage(queryResponseFor(query.correlationId, Buffer.from('data')));
 */
export function queryResponseFor(correlationId: CorrelationId, result: Buffer): QueryResponse {
  return {
    type: 'QueryResponse',
    correlationId,
    result,
  };
}

/**
 * Create ServerRequest message (server-initiated work)
 *
 * @param requestId - Request ID (use RequestId.fromBigInt() for test values)
 * @param payload - Work item payload
 * @param createdAt - Optional timestamp (defaults to now)
 *
 * @example
 * transport.injectMessage(serverRequestWith(RequestId.fromBigInt(1n), Buffer.from('work')));
 */
export function serverRequestWith(requestId: RequestId, payload: Buffer, createdAt?: Date): ServerRequest {
  return {
    type: 'ServerRequest',
    requestId,
    payload,
    createdAt: createdAt ?? new Date(),
  };
}

