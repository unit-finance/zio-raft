/**
 * Binary protocol encoding/decoding for KVStore
 * Matches Scala scodec format exactly
 */

import { ProtocolError } from './errors.js';
import { WatchNotification } from './types.js';

/**
 * Encodes a string as UTF-8 with 4-byte big-endian length prefix
 * Format: [length:4 bytes BE][UTF-8 bytes]
 */
export function encodeUtf8_32(str: string): Buffer {
  const utf8Bytes = Buffer.from(str, 'utf8');
  const length = utf8Bytes.length;

  const lengthBuffer = Buffer.allocUnsafe(4);
  lengthBuffer.writeUInt32BE(length, 0);

  return Buffer.concat([lengthBuffer, utf8Bytes]);
}

/**
 * Decodes a UTF-8 string with 4-byte big-endian length prefix
 * @returns Decoded string and total bytes read
 */
export function decodeUtf8_32(buffer: Buffer, offset: number): { value: string; bytesRead: number } {
  if (buffer.length < offset + 4) {
    throw new ProtocolError('Buffer too short to read length prefix');
  }

  const length = buffer.readUInt32BE(offset);
  const dataStart = offset + 4;

  if (buffer.length < dataStart + length) {
    throw new ProtocolError(`Buffer too short to read UTF-8 data (expected ${length} bytes)`);
  }

  const utf8Bytes = buffer.subarray(dataStart, dataStart + length);
  const value = utf8Bytes.toString('utf8');

  return {
    value,
    bytesRead: 4 + length,
  };
}

/**
 * Encodes a Set request
 * Format: [0x53 'S'][length:4][key bytes][length:4][value bytes]
 */
export function encodeSetRequest(key: string, value: string): Buffer {
  const discriminator = Buffer.from([0x53]); // ASCII 'S'
  const encodedKey = encodeUtf8_32(key);
  const encodedValue = encodeUtf8_32(value);

  return Buffer.concat([discriminator, encodedKey, encodedValue]);
}

/**
 * Encodes a Get query
 * Format: [0x47 'G'][length:4][key bytes]
 */
export function encodeGetQuery(key: string): Buffer {
  const discriminator = Buffer.from([0x47]); // ASCII 'G'
  const encodedKey = encodeUtf8_32(key);

  return Buffer.concat([discriminator, encodedKey]);
}

/**
 * Encodes a Watch request
 * Format: [0x57 'W'][length:4][key bytes]
 */
export function encodeWatchRequest(key: string): Buffer {
  const discriminator = Buffer.from([0x57]); // ASCII 'W'
  const encodedKey = encodeUtf8_32(key);

  return Buffer.concat([discriminator, encodedKey]);
}

/**
 * Decodes a Get query result (Option[String])
 * Format: [hasValue:1][length:4][value bytes] (if hasValue=1) or [0x00] (if None)
 * @returns Value if present, null if absent
 */
export function decodeGetResult(buffer: Buffer): string | null {
  if (buffer.length < 1) {
    throw new ProtocolError('Buffer too short to read hasValue byte');
  }

  const hasValue = buffer[0];

  if (hasValue === 0x00) {
    return null; // None - scodec bool encodes false as 0x00
  }

  if (hasValue === 0xff) {
    const { value } = decodeUtf8_32(buffer, 1);
    return value; // Some - scodec bool encodes true as 0xFF
  }

  throw new ProtocolError(`Invalid hasValue byte: ${hasValue} (expected 0x00 or 0xFF)`);
}

/**
 * Decodes a Notification message
 * Format: [0x4E 'N'][length:4][key bytes][length:4][value bytes]
 * @throws ProtocolError if discriminator is invalid
 */
export function decodeNotification(buffer: Buffer): WatchNotification {
  if (buffer.length < 1) {
    throw new ProtocolError('Buffer too short to read discriminator');
  }

  const discriminator = buffer[0];

  if (discriminator !== 0x4e) {
    // ASCII 'N'
    throw new ProtocolError(
      `Invalid discriminator for Notification: 0x${discriminator.toString(16)} (expected 0x4E 'N')`
    );
  }

  let offset = 1;

  // Decode key
  const { value: key, bytesRead: keyBytesRead } = decodeUtf8_32(buffer, offset);
  offset += keyBytesRead;

  // Decode value
  const { value, bytesRead: valueBytesRead } = decodeUtf8_32(buffer, offset);
  offset += valueBytesRead;

  return {
    timestamp: new Date(),
    sequenceNumber: 0n, // TODO: Extract from metadata if available
    key,
    value,
  };
}
