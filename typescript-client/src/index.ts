// Public API exports for @zio-raft/typescript-client
// Main entry point for the library

// Main client class
export { RaftClient } from './client';

// Configuration
export type { ClientConfig, ClientConfigInput } from './config';
export {
  createConfig,
  validateConfig,
  DEFAULT_CONNECTION_TIMEOUT,
  DEFAULT_KEEP_ALIVE_INTERVAL,
  DEFAULT_REQUEST_TIMEOUT,
} from './config';

// Error types
export {
  RaftClientError,
  ValidationError,
  TimeoutError,
  ConnectionError,
  SessionExpiredError,
  ProtocolError,
} from './errors';

// Core types (for advanced usage)
export type { SessionId, RequestId, MemberId, Nonce, CorrelationId } from './types';

// Protocol message types (for advanced usage / testing)
export type {
  ClientMessage,
  ServerMessage,
  ServerRequest,
  RejectionReason,
  SessionCloseReason,
} from './protocol/messages';

// Transport interface (for testing / custom implementations)
export type { ClientTransport } from './transport/transport';

// Transport implementations
export { ZmqTransport } from './transport/zmqTransport';

// Testing utilities
export { MockTransport } from './testing/MockTransport';

// Event types
export type { ConnectionEvent } from './events/eventTypes';
