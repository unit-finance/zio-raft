// Public API exports for @zio-raft/typescript-client
// Main entry point for the library

// Main client class
export { RaftClient } from './client.js';

// Configuration
export type { ClientConfig, ClientConfigInput } from './config.js';
export {
  createConfig,
  validateConfig,
  DEFAULT_CONNECTION_TIMEOUT,
  DEFAULT_KEEP_ALIVE_INTERVAL,
  DEFAULT_REQUEST_TIMEOUT,
} from './config.js';

// Error types
export {
  RaftClientError,
  ValidationError,
  TimeoutError,
  ConnectionError,
  SessionExpiredError,
  ProtocolError,
} from './errors.js';

// Core types (for advanced usage)
// Note: These are exported as both types and values (namespace pattern for branded types)
export { SessionId, RequestId, MemberId, Nonce, CorrelationId } from './types.js';

// Protocol message types (for advanced usage / testing)
export type {
  ClientMessage,
  ServerMessage,
  ServerRequest,
  RejectionReason,
  SessionCloseReason,
} from './protocol/messages.js';

// Transport interface (for testing / custom implementations)
export type { ClientTransport } from './transport/transport.js';

// Transport implementations
export { ZmqTransport } from './transport/zmqTransport.js';

// Testing utilities
export { MockTransport } from './testing/MockTransport.js';
