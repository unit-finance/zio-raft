// Protocol constants and enums for ZIO Raft client-server communication

/**
 * Protocol signature: "zraft" in bytes
 */
export const PROTOCOL_SIGNATURE = Buffer.from([0x7a, 0x72, 0x61, 0x66, 0x74]);

/**
 * Protocol version (1 byte)
 */
export const PROTOCOL_VERSION: number = 1;

/**
 * Client message type discriminators (1 byte)
 */
export enum ClientMessageType {
  CreateSession = 1,
  ContinueSession = 2,
  KeepAlive = 3,
  ClientRequest = 4,
  ServerRequestAck = 5,
  CloseSession = 6,
  ConnectionClosed = 7,
  Query = 8,
}

/**
 * Server message type discriminators (1 byte)
 */
export enum ServerMessageType {
  SessionCreated = 1,
  SessionContinued = 2,
  SessionRejected = 3,
  SessionClosed = 4,
  KeepAliveResponse = 5,
  ClientResponse = 6,
  ServerRequest = 7,
  RequestError = 8,
  QueryResponse = 9,
}

/**
 * Rejection reason codes
 */
export enum RejectionReasonCode {
  NotLeader = 1,
  SessionExpired = 2,
  InvalidCapabilities = 3,
  Other = 4,
}

/**
 * Session close reason codes
 */
export enum SessionCloseReasonCode {
  Shutdown = 1,
  NotLeaderAnymore = 2,
  SessionError = 3,
  ConnectionClosed = 4,
  SessionExpired = 5,
}

/**
 * Close reason codes (client-initiated)
 */
export enum CloseReasonCode {
  ClientShutdown = 1,
}

/**
 * Request error reason codes
 */
export enum RequestErrorReasonCode {
  ResponseEvicted = 1,
}
