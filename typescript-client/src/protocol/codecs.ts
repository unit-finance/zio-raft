// Binary protocol codecs for ZIO Raft client-server communication
// These must match the Scala scodec implementation exactly

import {
  SessionId,
  RequestId,
  MemberId,
  Nonce,
  CorrelationId,
} from '../types';
import {
  ClientMessage,
  ServerMessage,
  RejectionReason,
  SessionCloseReason,
  CloseReason,
  RequestErrorReason,
} from './messages';
import {
  PROTOCOL_SIGNATURE,
  PROTOCOL_VERSION,
  ClientMessageType,
  ServerMessageType,
  RejectionReasonCode,
  SessionCloseReasonCode,
  CloseReasonCode,
  RequestErrorReasonCode,
} from './constants';

// ============================================================================
// Encoding Primitives
// ============================================================================

/**
 * Encode a string with uint16 length prefix
 */
export function encodeString(str: string): Buffer {
  const utf8 = Buffer.from(str, 'utf8');
  const length = Buffer.allocUnsafe(2);
  length.writeUInt16BE(utf8.length, 0);
  return Buffer.concat([length, utf8]);
}

/**
 * Encode a payload (Buffer) with int32 length prefix
 */
export function encodePayload(payload: Buffer): Buffer {
  const length = Buffer.allocUnsafe(4);
  length.writeInt32BE(payload.length, 0);
  return Buffer.concat([length, payload]);
}

/**
 * Encode a Map<string, string> with uint16 count prefix
 */
export function encodeMap(map: Map<string, string>): Buffer {
  const count = Buffer.allocUnsafe(2);
  count.writeUInt16BE(map.size, 0);
  
  const entries: Buffer[] = [count];
  for (const [key, value] of map) {
    entries.push(encodeString(key));
    entries.push(encodeString(value));
  }
  
  return Buffer.concat(entries);
}

/**
 * Encode a Date as int64 epoch milliseconds
 */
export function encodeTimestamp(date: Date): Buffer {
  const millis = BigInt(date.getTime());
  const buffer = Buffer.allocUnsafe(8);
  buffer.writeBigInt64BE(millis, 0);
  return buffer;
}

/**
 * Encode a Nonce as 8 bytes (bigint)
 */
export function encodeNonce(nonce: Nonce): Buffer {
  const buffer = Buffer.allocUnsafe(8);
  buffer.writeBigInt64BE(Nonce.unwrap(nonce), 0);
  return buffer;
}

/**
 * Encode a RequestId as 8 bytes (bigint)
 */
export function encodeRequestId(id: RequestId): Buffer {
  const buffer = Buffer.allocUnsafe(8);
  buffer.writeBigInt64BE(RequestId.unwrap(id), 0);
  return buffer;
}

/**
 * Encode a SessionId as uint16 length + UTF-8 bytes
 */
export function encodeSessionId(id: SessionId): Buffer {
  return encodeString(SessionId.unwrap(id));
}

/**
 * Encode a MemberId as uint16 length + UTF-8 bytes
 */
export function encodeMemberId(id: MemberId): Buffer {
  return encodeString(MemberId.unwrap(id));
}

/**
 * Encode a CorrelationId as uint16 length + UTF-8 bytes
 */
export function encodeCorrelationId(id: CorrelationId): Buffer {
  return encodeString(CorrelationId.unwrap(id));
}

// ============================================================================
// Decoding Primitives
// ============================================================================

export interface DecodeResult<T> {
  value: T;
  newOffset: number;
}

/**
 * Decode a string with uint16 length prefix
 */
export function decodeString(buffer: Buffer, offset: number): DecodeResult<string> {
  const length = buffer.readUInt16BE(offset);
  const value = buffer.toString('utf8', offset + 2, offset + 2 + length);
  return { value, newOffset: offset + 2 + length };
}

/**
 * Decode a payload (Buffer) with int32 length prefix
 */
export function decodePayload(buffer: Buffer, offset: number): DecodeResult<Buffer> {
  const length = buffer.readInt32BE(offset);
  const value = buffer.slice(offset + 4, offset + 4 + length);
  return { value, newOffset: offset + 4 + length };
}

/**
 * Decode a Map<string, string> with uint16 count prefix
 */
export function decodeMap(buffer: Buffer, offset: number): DecodeResult<Map<string, string>> {
  const count = buffer.readUInt16BE(offset);
  let currentOffset = offset + 2;
  const map = new Map<string, string>();
  
  for (let i = 0; i < count; i++) {
    const keyResult = decodeString(buffer, currentOffset);
    currentOffset = keyResult.newOffset;
    
    const valueResult = decodeString(buffer, currentOffset);
    currentOffset = valueResult.newOffset;
    
    map.set(keyResult.value, valueResult.value);
  }
  
  return { value: map, newOffset: currentOffset };
}

/**
 * Decode a Date from int64 epoch milliseconds
 */
export function decodeTimestamp(buffer: Buffer, offset: number): DecodeResult<Date> {
  const millis = buffer.readBigInt64BE(offset);
  const value = new Date(Number(millis));
  return { value, newOffset: offset + 8 };
}

/**
 * Decode a Nonce from 8 bytes (bigint)
 */
export function decodeNonce(buffer: Buffer, offset: number): DecodeResult<Nonce> {
  const bigintValue = buffer.readBigInt64BE(offset);
  const value = Nonce.fromBigInt(bigintValue);
  return { value, newOffset: offset + 8 };
}

/**
 * Decode a RequestId from 8 bytes (bigint)
 */
export function decodeRequestId(buffer: Buffer, offset: number): DecodeResult<RequestId> {
  const bigintValue = buffer.readBigInt64BE(offset);
  const value = RequestId.fromBigInt(bigintValue);
  return { value, newOffset: offset + 8 };
}

/**
 * Decode a SessionId from uint16 length + UTF-8 bytes
 */
export function decodeSessionId(buffer: Buffer, offset: number): DecodeResult<SessionId> {
  const result = decodeString(buffer, offset);
  return { value: SessionId.fromString(result.value), newOffset: result.newOffset };
}

/**
 * Decode a MemberId from uint16 length + UTF-8 bytes
 */
export function decodeMemberId(buffer: Buffer, offset: number): DecodeResult<MemberId> {
  const result = decodeString(buffer, offset);
  return { value: MemberId.fromString(result.value), newOffset: result.newOffset };
}

/**
 * Decode a CorrelationId from uint16 length + UTF-8 bytes
 */
export function decodeCorrelationId(buffer: Buffer, offset: number): DecodeResult<CorrelationId> {
  const result = decodeString(buffer, offset);
  return { value: CorrelationId.fromString(result.value), newOffset: result.newOffset };
}

// ============================================================================
// Protocol Header Codec
// ============================================================================

/**
 * Encode protocol header (signature + version)
 */
export function encodeProtocolHeader(): Buffer {
  const version = Buffer.allocUnsafe(1);
  version.writeUInt8(PROTOCOL_VERSION, 0);
  return Buffer.concat([PROTOCOL_SIGNATURE, version]);
}

/**
 * Decode and verify protocol header
 * @throws Error if signature doesn't match or version != 1
 */
export function decodeProtocolHeader(buffer: Buffer, offset: number): number {
  // Verify signature (5 bytes)
  for (let i = 0; i < PROTOCOL_SIGNATURE.length; i++) {
    if (buffer[offset + i] !== PROTOCOL_SIGNATURE[i]) {
      throw new Error(
        `Invalid protocol signature at offset ${offset}: expected ${PROTOCOL_SIGNATURE[i]}, got ${buffer[offset + i]}`
      );
    }
  }
  
  // Verify version (1 byte)
  const version = buffer.readUInt8(offset + 5);
  if (version !== PROTOCOL_VERSION) {
    throw new Error(`Unsupported protocol version: ${version}, expected ${PROTOCOL_VERSION}`);
  }
  
  return offset + 6; // 5 bytes signature + 1 byte version
}

// ============================================================================
// Reason Codecs
// ============================================================================

function encodeRejectionReason(reason: RejectionReason): Buffer {
  const buffer = Buffer.allocUnsafe(1);
  switch (reason) {
    case 'NotLeader':
      buffer.writeUInt8(RejectionReasonCode.NotLeader, 0);
      break;
    case 'SessionExpired':
      buffer.writeUInt8(RejectionReasonCode.SessionExpired, 0);
      break;
    case 'InvalidCapabilities':
      buffer.writeUInt8(RejectionReasonCode.InvalidCapabilities, 0);
      break;
    case 'Other':
      buffer.writeUInt8(RejectionReasonCode.Other, 0);
      break;
  }
  return buffer;
}

function decodeRejectionReason(buffer: Buffer, offset: number): DecodeResult<RejectionReason> {
  const code = buffer.readUInt8(offset);
  let value: RejectionReason;
  switch (code) {
    case RejectionReasonCode.NotLeader:
      value = 'NotLeader';
      break;
    case RejectionReasonCode.SessionExpired:
      value = 'SessionExpired';
      break;
    case RejectionReasonCode.InvalidCapabilities:
      value = 'InvalidCapabilities';
      break;
    case RejectionReasonCode.Other:
      value = 'Other';
      break;
    default:
      throw new Error(`Unknown rejection reason code: ${code}`);
  }
  return { value, newOffset: offset + 1 };
}

function encodeSessionCloseReason(reason: SessionCloseReason): Buffer {
  const buffer = Buffer.allocUnsafe(1);
  switch (reason) {
    case 'Shutdown':
      buffer.writeUInt8(SessionCloseReasonCode.Shutdown, 0);
      break;
    case 'NotLeaderAnymore':
      buffer.writeUInt8(SessionCloseReasonCode.NotLeaderAnymore, 0);
      break;
    case 'SessionError':
      buffer.writeUInt8(SessionCloseReasonCode.SessionError, 0);
      break;
    case 'ConnectionClosed':
      buffer.writeUInt8(SessionCloseReasonCode.ConnectionClosed, 0);
      break;
    case 'SessionExpired':
      buffer.writeUInt8(SessionCloseReasonCode.SessionExpired, 0);
      break;
  }
  return buffer;
}

function decodeSessionCloseReason(buffer: Buffer, offset: number): DecodeResult<SessionCloseReason> {
  const code = buffer.readUInt8(offset);
  let value: SessionCloseReason;
  switch (code) {
    case SessionCloseReasonCode.Shutdown:
      value = 'Shutdown';
      break;
    case SessionCloseReasonCode.NotLeaderAnymore:
      value = 'NotLeaderAnymore';
      break;
    case SessionCloseReasonCode.SessionError:
      value = 'SessionError';
      break;
    case SessionCloseReasonCode.ConnectionClosed:
      value = 'ConnectionClosed';
      break;
    case SessionCloseReasonCode.SessionExpired:
      value = 'SessionExpired';
      break;
    default:
      throw new Error(`Unknown session close reason code: ${code}`);
  }
  return { value, newOffset: offset + 1 };
}

function encodeCloseReason(reason: CloseReason): Buffer {
  const buffer = Buffer.allocUnsafe(1);
  buffer.writeUInt8(CloseReasonCode.ClientShutdown, 0);
  return buffer;
}

function encodeRequestErrorReason(reason: RequestErrorReason): Buffer {
  const buffer = Buffer.allocUnsafe(1);
  buffer.writeUInt8(RequestErrorReasonCode.ResponseEvicted, 0);
  return buffer;
}

function decodeRequestErrorReason(buffer: Buffer, offset: number): DecodeResult<RequestErrorReason> {
  const code = buffer.readUInt8(offset);
  if (code !== RequestErrorReasonCode.ResponseEvicted) {
    throw new Error(`Unknown request error reason code: ${code}`);
  }
  return { value: 'ResponseEvicted', newOffset: offset + 1 };
}

// ============================================================================
// Client Message Encoding
// ============================================================================

/**
 * Encode a client message to binary format
 */
export function encodeClientMessage(message: ClientMessage): Buffer {
  const header = encodeProtocolHeader();
  const parts: Buffer[] = [header];
  
  const typeBuffer = Buffer.allocUnsafe(1);
  
  switch (message.type) {
    case 'CreateSession':
      typeBuffer.writeUInt8(ClientMessageType.CreateSession, 0);
      parts.push(typeBuffer);
      parts.push(encodeMap(message.capabilities));
      parts.push(encodeNonce(message.nonce));
      break;
      
    case 'ContinueSession':
      typeBuffer.writeUInt8(ClientMessageType.ContinueSession, 0);
      parts.push(typeBuffer);
      parts.push(encodeSessionId(message.sessionId));
      parts.push(encodeNonce(message.nonce));
      break;
      
    case 'KeepAlive':
      typeBuffer.writeUInt8(ClientMessageType.KeepAlive, 0);
      parts.push(typeBuffer);
      parts.push(encodeTimestamp(message.timestamp));
      break;
      
    case 'ClientRequest':
      typeBuffer.writeUInt8(ClientMessageType.ClientRequest, 0);
      parts.push(typeBuffer);
      parts.push(encodeRequestId(message.requestId));
      parts.push(encodeRequestId(message.lowestPendingRequestId));
      parts.push(encodePayload(message.payload));
      parts.push(encodeTimestamp(message.createdAt));
      break;
      
    case 'Query':
      typeBuffer.writeUInt8(ClientMessageType.Query, 0);
      parts.push(typeBuffer);
      parts.push(encodeCorrelationId(message.correlationId));
      parts.push(encodePayload(message.payload));
      parts.push(encodeTimestamp(message.createdAt));
      break;
      
    case 'ServerRequestAck':
      typeBuffer.writeUInt8(ClientMessageType.ServerRequestAck, 0);
      parts.push(typeBuffer);
      parts.push(encodeRequestId(message.requestId));
      break;
      
    case 'CloseSession':
      typeBuffer.writeUInt8(ClientMessageType.CloseSession, 0);
      parts.push(typeBuffer);
      parts.push(encodeCloseReason(message.reason));
      break;
      
    case 'ConnectionClosed':
      typeBuffer.writeUInt8(ClientMessageType.ConnectionClosed, 0);
      parts.push(typeBuffer);
      break;
  }
  
  return Buffer.concat(parts);
}

// ============================================================================
// Server Message Decoding
// ============================================================================

/**
 * Decode a server message from binary format
 */
export function decodeServerMessage(buffer: Buffer): ServerMessage {
  let offset = 0;
  
  // Decode and verify protocol header
  offset = decodeProtocolHeader(buffer, offset);
  
  // Read message type discriminator
  const messageType = buffer.readUInt8(offset);
  offset += 1;
  
  switch (messageType) {
    case ServerMessageType.SessionCreated: {
      const sessionIdResult = decodeSessionId(buffer, offset);
      offset = sessionIdResult.newOffset;
      const nonceResult = decodeNonce(buffer, offset);
      return {
        type: 'SessionCreated',
        sessionId: sessionIdResult.value,
        nonce: nonceResult.value,
      };
    }
    
    case ServerMessageType.SessionContinued: {
      const nonceResult = decodeNonce(buffer, offset);
      return {
        type: 'SessionContinued',
        nonce: nonceResult.value,
      };
    }
    
    case ServerMessageType.SessionRejected: {
      const reasonResult = decodeRejectionReason(buffer, offset);
      offset = reasonResult.newOffset;
      const nonceResult = decodeNonce(buffer, offset);
      offset = nonceResult.newOffset;
      
      // Check if leaderId is present (optional field)
      let leaderId: MemberId | undefined;
      if (offset < buffer.length) {
        const hasLeader = buffer.readUInt8(offset);
        offset += 1;
        if (hasLeader === 1) {
          const leaderIdResult = decodeMemberId(buffer, offset);
          leaderId = leaderIdResult.value;
        }
      }
      
      return {
        type: 'SessionRejected',
        reason: reasonResult.value,
        nonce: nonceResult.value,
        leaderId,
      };
    }
    
    case ServerMessageType.SessionClosed: {
      const reasonResult = decodeSessionCloseReason(buffer, offset);
      offset = reasonResult.newOffset;
      
      // Check if leaderId is present (optional field)
      let leaderId: MemberId | undefined;
      if (offset < buffer.length) {
        const hasLeader = buffer.readUInt8(offset);
        offset += 1;
        if (hasLeader === 1) {
          const leaderIdResult = decodeMemberId(buffer, offset);
          leaderId = leaderIdResult.value;
        }
      }
      
      return {
        type: 'SessionClosed',
        reason: reasonResult.value,
        leaderId,
      };
    }
    
    case ServerMessageType.KeepAliveResponse: {
      const timestampResult = decodeTimestamp(buffer, offset);
      return {
        type: 'KeepAliveResponse',
        timestamp: timestampResult.value,
      };
    }
    
    case ServerMessageType.ClientResponse: {
      const requestIdResult = decodeRequestId(buffer, offset);
      offset = requestIdResult.newOffset;
      const resultBuffer = decodePayload(buffer, offset);
      return {
        type: 'ClientResponse',
        requestId: requestIdResult.value,
        result: resultBuffer.value,
      };
    }
    
    case ServerMessageType.QueryResponse: {
      const correlationIdResult = decodeCorrelationId(buffer, offset);
      offset = correlationIdResult.newOffset;
      const resultBuffer = decodePayload(buffer, offset);
      return {
        type: 'QueryResponse',
        correlationId: correlationIdResult.value,
        result: resultBuffer.value,
      };
    }
    
    case ServerMessageType.ServerRequest: {
      const requestIdResult = decodeRequestId(buffer, offset);
      offset = requestIdResult.newOffset;
      const payloadResult = decodePayload(buffer, offset);
      offset = payloadResult.newOffset;
      const createdAtResult = decodeTimestamp(buffer, offset);
      return {
        type: 'ServerRequest',
        requestId: requestIdResult.value,
        payload: payloadResult.value,
        createdAt: createdAtResult.value,
      };
    }
    
    case ServerMessageType.RequestError: {
      const requestIdResult = decodeRequestId(buffer, offset);
      offset = requestIdResult.newOffset;
      const reasonResult = decodeRequestErrorReason(buffer, offset);
      return {
        type: 'RequestError',
        requestId: requestIdResult.value,
        reason: reasonResult.value,
      };
    }
    
    default:
      throw new Error(`Unknown server message type: ${messageType}`);
  }
}

