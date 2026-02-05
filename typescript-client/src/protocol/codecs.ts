// Binary protocol codecs for ZIO Raft client-server communication
// These must match the Scala scodec implementation exactly

import { SessionId, RequestId, MemberId, Nonce, CorrelationId } from '../types';
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
 * Encode a string with uint8 length prefix (for SessionId)
 * Matches Scala's variableSizeBytes(uint8, utf8)
 */
export function encodeString8(str: string): Buffer {
  const utf8 = Buffer.from(str, 'utf8');
  if (utf8.length > 255) {
    throw new Error(`String too long for uint8 length prefix: ${utf8.length} bytes`);
  }
  const length = Buffer.allocUnsafe(1);
  length.writeUInt8(utf8.length, 0);
  return Buffer.concat([length, utf8]);
}

/**
 * Encode a string with uint16 length prefix (for MemberId, CorrelationId)
 * Matches Scala's variableSizeBytes(uint16, utf8)
 */
export function encodeString(str: string): Buffer {
  const utf8 = Buffer.from(str, 'utf8');
  if (utf8.length > 65535) {
    throw new Error(`String too long for uint16 length prefix: ${utf8.length} bytes`);
  }
  const length = Buffer.allocUnsafe(2);
  length.writeUInt16BE(utf8.length, 0);
  return Buffer.concat([length, utf8]);
}

/**
 * Encode a payload (Buffer) with int32 length prefix
 */
export function encodePayload(payload: Buffer): Buffer {
  if (payload.length > 2147483647) {
    throw new Error(`Payload too large for int32 length prefix: ${payload.length} bytes`);
  }
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
 * Encode a SessionId as uint8 length + UTF-8 bytes
 * Matches Scala's variableSizeBytes(uint8, utf8)
 */
export function encodeSessionId(id: SessionId): Buffer {
  return encodeString8(SessionId.unwrap(id));
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
 * Validate buffer has enough bytes for reading.
 * Throws an error if buffer underflow would occur.
 * @param buffer - The buffer to validate
 * @param offset - Current read offset
 * @param bytesNeeded - Number of bytes needed for the read operation
 * @param context - Description of what is being decoded (for error messages)
 */
function validateBounds(buffer: Buffer, offset: number, bytesNeeded: number, context: string): void {
  if (offset < 0) {
    throw new Error(`Invalid offset ${offset} when decoding ${context}`);
  }
  if (offset + bytesNeeded > buffer.length) {
    throw new Error(
      `Buffer underflow when decoding ${context}: need ${bytesNeeded} bytes at offset ${offset}, but buffer has ${buffer.length} bytes`
    );
  }
}

/**
 * Decode optional field with bit-level boolean (scodec's optional(bool, codec))
 *
 * Scala's scodec encodes optional(bool, codec) as:
 * - 1 bit for presence (0 = None, 1 = Some)
 * - If present, remaining bits contain the value
 *
 * Example: optional(bool, variableSizeBytes(uint16, utf8)) for "node-1"
 * - Bit 0: 1 (present)
 * - Bits 1-16: 0x0006 (length = 6)
 * - Next 6 bytes: "node-1"
 *
 * Hex: 80 03 37 37 b2 32 96 98 80
 * - 0x80 = 10000000 (bit 0 = 1 for Some, bits 1-7 = 0000000)
 * - 0x03 = 00000011 (bits 8-15, combined with bits 1-7 gives 0x0003... wait, that's wrong)
 *
 * Actually: When bit-packed, after the 1 bit for Some, the next 16 bits are the uint16:
 * - Byte 0: 10000000 (bit 0 = Some)
 * - Byte 0 (bits 1-7) + Byte 1 (bits 0-7) + Byte 2 (bit 0) = 16 bits for length
 *
 * Let me recalculate:
 * Hex: 80 03 37 37 b2 32 96 98 80
 * Binary:
 * 10000000 00000011 00110111 00110111 10110010 00110010 10010110 10011000 10000000
 * ^--1 bit for Some
 *  ^^^^^^^----7 bits (part of uint16 length)
 *         ^^^^^^^^----8 bits (rest of uint16 length)
 *                ^----1 bit (last bit of uint16)
 *
 * This is too complex. Let me use a simpler approach: read byte-by-byte and reconstruct.
 */
/**
 * Decode optional MemberId with bit-level boolean (scodec's optional(bool, memberIdCodec))
 *
 * Scala's scodec encodes optional(bool, variableSizeBytes(uint16, utf8)) as:
 * - 1 bit for presence (0 = None, 1 = Some)
 * - If present: uint16 length (16 bits) + UTF-8 string bytes
 *
 * Example: MemberId("node-1")
 * Hex: 80 03 37 37 b2 32 96 98 80
 * Binary structure:
 *   Byte 0: [1][0000000]  ← presence=1, first 7 bits of uint16
 *   Byte 1: [00000011]    ← bits 7-14 of uint16
 *   Byte 2: [0][0110111]  ← bit 15 of uint16, first 7 bits of 'n'
 *   ... (6 string bytes, each straddling 2 buffer bytes)
 *
 * Implementation Strategy:
 * Uses Uint8Array view for direct byte access (faster than Buffer.readUInt8):
 * - Alternative approach: Shift entire buffer left by 1 bit, then reuse decodeMemberId()
 * - Problem: Requires allocating new buffer and shifting ALL remaining bytes
 * - This approach: Direct Uint8Array indexing for the bytes we need
 * - Trade-off: Duplicates uint16+UTF-8 decoding logic, but avoids allocation overhead
 */
function decodeOptionalMemberId(buffer: Buffer, offset: number): DecodeResult<MemberId | undefined> {
  // Validate we have at least 1 byte for presence flag
  validateBounds(buffer, offset, 1, 'optional MemberId presence');

  // TODO: We need to add some tests for this function.

  // Use Uint8Array view for faster direct byte access
  const bytes = new Uint8Array(buffer.buffer, buffer.byteOffset + offset, buffer.length - offset);
  const firstByte = bytes[0] as number; // we validatedBounds above we know we have at least 1 byte

  // Check MSB (bit 0) for presence
  const hasValue = (firstByte & 0x80) !== 0;

  if (!hasValue) {
    // None case: bit is 0, value is absent
    return {
      value: undefined,
      newOffset: offset + 1,
    };
  }

  // Validate we have at least 3 bytes for presence + uint16 length
  validateBounds(buffer, offset, 3, 'optional MemberId length');

  // Some case: read uint16 starting at bit 1
  // Extract uint16 from bits 1-16 (spans bytes 0-2)
  const byte0 = firstByte & 0x7f; // bits 1-7 of uint16
  const byte1 = bytes[1]!; // bits 8-15 of uint16 (validated by validateBounds above)
  const byte2 = bytes[2]!; // bit 16 of uint16 (MSB) (validated by validateBounds above)

  // Reconstruct uint16 from bit-shifted pieces
  const length = (byte0 << 9) | (byte1 << 1) | (byte2 >> 7);

  // Calculate total bytes needed and validate
  const totalBits = 1 + 16 + length * 8;
  const bytesConsumed = Math.ceil(totalBits / 8);
  validateBounds(buffer, offset, bytesConsumed, `optional MemberId string (length=${length})`);

  // Read string bytes starting at bit 17
  // Each character byte straddles two buffer bytes due to 1-bit shift
  const stringBytes = Buffer.allocUnsafe(length);
  let bitOffset = 17;

  for (let i = 0; i < length; i++) {
    const byteIndex = Math.floor(bitOffset / 8);
    const bitInByte = bitOffset % 8;

    // Bounds validated by validateBounds above for bytesConsumed
    const currentByte = bytes[byteIndex]!;
    const nextByte = bytes[byteIndex + 1]!;

    // Extract 8 bits starting at bitOffset
    stringBytes[i] = (currentByte << bitInByte) | (nextByte >> (8 - bitInByte));
    bitOffset += 8;
  }

  const memberId = MemberId.fromString(stringBytes.toString('utf8'));

  return {
    value: memberId,
    newOffset: offset + bytesConsumed,
  };
}

/**
 * Decode a string with uint8 length prefix (for SessionId)
 * Matches Scala's variableSizeBytes(uint8, utf8)
 */
export function decodeString8(buffer: Buffer, offset: number): DecodeResult<string> {
  validateBounds(buffer, offset, 1, 'string8 length');
  const length = buffer.readUInt8(offset);
  validateBounds(buffer, offset + 1, length, `string8 content (length=${length})`);
  const value = buffer.toString('utf8', offset + 1, offset + 1 + length);
  return { value, newOffset: offset + 1 + length };
}

/**
 * Decode a string with uint16 length prefix (for MemberId, CorrelationId)
 * Matches Scala's variableSizeBytes(uint16, utf8)
 */
export function decodeString(buffer: Buffer, offset: number): DecodeResult<string> {
  validateBounds(buffer, offset, 2, 'string length');
  const length = buffer.readUInt16BE(offset);
  validateBounds(buffer, offset + 2, length, `string content (length=${length})`);
  const value = buffer.toString('utf8', offset + 2, offset + 2 + length);
  return { value, newOffset: offset + 2 + length };
}

/**
 * Decode a payload (Buffer) with int32 length prefix
 */
export function decodePayload(buffer: Buffer, offset: number): DecodeResult<Buffer> {
  validateBounds(buffer, offset, 4, 'payload length');
  const length = buffer.readInt32BE(offset);
  if (length < 0) {
    throw new Error(`Invalid negative payload length: ${length}`);
  }
  validateBounds(buffer, offset + 4, length, `payload content (length=${length})`);
  const value = buffer.slice(offset + 4, offset + 4 + length);
  return { value, newOffset: offset + 4 + length };
}

/**
 * Decode a Map<string, string> with uint16 count prefix
 */
export function decodeMap(buffer: Buffer, offset: number): DecodeResult<Map<string, string>> {
  validateBounds(buffer, offset, 2, 'map count');
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
  validateBounds(buffer, offset, 8, 'timestamp');
  const millis = buffer.readBigInt64BE(offset);
  const value = new Date(Number(millis));
  return { value, newOffset: offset + 8 };
}

/**
 * Decode a Nonce from 8 bytes (bigint)
 */
export function decodeNonce(buffer: Buffer, offset: number): DecodeResult<Nonce> {
  validateBounds(buffer, offset, 8, 'nonce');
  const bigintValue = buffer.readBigInt64BE(offset);
  const value = Nonce.fromBigInt(bigintValue);
  return { value, newOffset: offset + 8 };
}

/**
 * Decode a RequestId from 8 bytes (bigint)
 */
export function decodeRequestId(buffer: Buffer, offset: number): DecodeResult<RequestId> {
  validateBounds(buffer, offset, 8, 'requestId');
  const bigintValue = buffer.readBigInt64BE(offset);
  const value = RequestId.fromBigInt(bigintValue);
  return { value, newOffset: offset + 8 };
}

/**
 * Decode a SessionId from uint8 length + UTF-8 bytes
 * Matches Scala's variableSizeBytes(uint8, utf8)
 */
export function decodeSessionId(buffer: Buffer, offset: number): DecodeResult<SessionId> {
  const result = decodeString8(buffer, offset);
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
  // Validate buffer has enough bytes for signature + version
  validateBounds(buffer, offset, 6, 'protocol header');

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

// Encode rejection reason (used internally, exported for testing)
export function encodeRejectionReason(reason: RejectionReason): Buffer {
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
  validateBounds(buffer, offset, 1, 'rejection reason');
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

// Encode session close reason (used internally, exported for testing)
export function encodeSessionCloseReason(reason: SessionCloseReason): Buffer {
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
  validateBounds(buffer, offset, 1, 'session close reason');
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

// Encode close reason (used internally, exported for testing)
export function encodeCloseReason(_reason: CloseReason): Buffer {
  const buffer = Buffer.allocUnsafe(1);
  buffer.writeUInt8(CloseReasonCode.ClientShutdown, 0);
  return buffer;
}

// Encode request error reason (used internally, exported for testing)
export function encodeRequestErrorReason(_reason: RequestErrorReason): Buffer {
  const buffer = Buffer.allocUnsafe(1);
  buffer.writeUInt8(RequestErrorReasonCode.ResponseEvicted, 0);
  return buffer;
}

function decodeRequestErrorReason(buffer: Buffer, offset: number): DecodeResult<RequestErrorReason> {
  validateBounds(buffer, offset, 1, 'request error reason');
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
  validateBounds(buffer, offset, 1, 'message type');
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

      // Decode optional leaderId with bit-level boolean (scodec's optional(bool, memberIdCodec))
      const leaderIdResult = decodeOptionalMemberId(buffer, offset);

      return {
        type: 'SessionRejected',
        reason: reasonResult.value,
        nonce: nonceResult.value,
        leaderId: leaderIdResult.value,
      };
    }

    case ServerMessageType.SessionClosed: {
      const reasonResult = decodeSessionCloseReason(buffer, offset);
      offset = reasonResult.newOffset;

      // Decode optional leaderId with bit-level boolean (scodec's optional(bool, memberIdCodec))
      const leaderIdResult = decodeOptionalMemberId(buffer, offset);

      return {
        type: 'SessionClosed',
        reason: reasonResult.value,
        leaderId: leaderIdResult.value,
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
