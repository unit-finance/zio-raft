/**
 * Unit tests for protocol codecs
 */

import { describe, it, expect } from 'vitest';
import {
  encodeUtf8_32,
  decodeUtf8_32,
  encodeSetRequest,
  encodeGetQuery,
  encodeWatchRequest,
  decodeGetResult,
  decodeNotification,
} from '../../src/codecs.js';
import { ProtocolError } from '../../src/errors.js';

describe('UTF-8 Codec', () => {
  it('should encode and decode UTF-8 strings correctly', () => {
    const testStrings = ['test', 'hello world', 'unicode-键', 'emoji-🔑'];
    
    for (const str of testStrings) {
      const encoded = encodeUtf8_32(str);
      const { value, bytesRead } = decodeUtf8_32(encoded, 0);
      
      expect(value).toBe(str);
      expect(bytesRead).toBe(encoded.length);
    }
  });

  it('should encode length as big-endian 4-byte prefix', () => {
    const encoded = encodeUtf8_32('test');
    
    // First 4 bytes should be length (4) in big-endian
    expect(encoded.readUInt32BE(0)).toBe(4);
    // Next 4 bytes should be 'test'
    expect(encoded.subarray(4).toString('utf8')).toBe('test');
  });
});

describe('Encoding', () => {
  // TC-020: Set request encodes correctly
  it('should encode Set request with correct format', () => {
    const buffer = encodeSetRequest('test', 'data');
    
    // Verify discriminator
    expect(buffer[0]).toBe(0x53); // ASCII 'S'
    
    // Verify key length and content
    expect(buffer.readUInt32BE(1)).toBe(4); // length of "test"
    expect(buffer.subarray(5, 9).toString('utf8')).toBe('test');
    
    // Verify value length and content
    expect(buffer.readUInt32BE(9)).toBe(4); // length of "data"
    expect(buffer.subarray(13, 17).toString('utf8')).toBe('data');
  });

  // TC-021: Get query encodes correctly
  it('should encode Get query with correct format', () => {
    const buffer = encodeGetQuery('test');
    
    // Verify discriminator
    expect(buffer[0]).toBe(0x47); // ASCII 'G'
    
    // Verify key length and content
    expect(buffer.readUInt32BE(1)).toBe(4); // length of "test"
    expect(buffer.subarray(5, 9).toString('utf8')).toBe('test');
  });

  // TC-022: Watch request encodes correctly
  it('should encode Watch request with correct format', () => {
    const buffer = encodeWatchRequest('test');
    
    // Verify discriminator
    expect(buffer[0]).toBe(0x57); // ASCII 'W'
    
    // Verify key length and content
    expect(buffer.readUInt32BE(1)).toBe(4); // length of "test"
    expect(buffer.subarray(5, 9).toString('utf8')).toBe('test');
  });

  // TC-023: Unicode key encodes with correct byte length
  it('should encode unicode key with correct byte length not character count', () => {
    const unicodeKey = 'key🔑'; // 'key' (3 bytes) + emoji (4 bytes) = 7 bytes
    const buffer = encodeSetRequest(unicodeKey, 'value');
    
    // Verify key length is based on bytes, not characters
    const keyLength = buffer.readUInt32BE(1);
    expect(keyLength).toBe(Buffer.from(unicodeKey, 'utf8').length);
    expect(keyLength).not.toBe(unicodeKey.length); // Should be 7, not 4
  });

  // TC-024: Large value encodes correctly
  it('should encode large values correctly', () => {
    const largeValue = 'a'.repeat(500 * 1024); // 500KB
    const buffer = encodeSetRequest('key', largeValue);
    
    // Verify structure is intact
    expect(buffer[0]).toBe(0x53); // Discriminator
    
    // Decode and verify
    const keyLength = buffer.readUInt32BE(1);
    const valueLength = buffer.readUInt32BE(1 + 4 + keyLength);
    
    expect(keyLength).toBe(3);
    expect(valueLength).toBe(500 * 1024);
  });
});

describe('Decoding', () => {
  // TC-025: Get result with value decodes correctly
  it('should decode Get result with value (Some case)', () => {
    // Create buffer: [0x01][length:4][value bytes]
    const value = 'myvalue';
    const valueBytes = Buffer.from(value, 'utf8');
    const lengthBuffer = Buffer.allocUnsafe(4);
    lengthBuffer.writeUInt32BE(valueBytes.length, 0);
    
    const buffer = Buffer.concat([
      Buffer.from([0x01]), // hasValue = 1 (Some)
      lengthBuffer,
      valueBytes
    ]);
    
    const result = decodeGetResult(buffer);
    expect(result).toBe(value);
  });

  // TC-026: Get result without value decodes to null
  it('should decode Get result without value (None case)', () => {
    const buffer = Buffer.from([0x00]); // hasValue = 0 (None)
    
    const result = decodeGetResult(buffer);
    expect(result).toBeNull();
  });

  // TC-027: Notification decodes correctly
  it('should decode Notification with correct fields', () => {
    // Create buffer: [0x4E][length:4][key][length:4][value]
    const key = 'mykey';
    const value = 'myvalue';
    
    const keyBytes = Buffer.from(key, 'utf8');
    const valueBytes = Buffer.from(value, 'utf8');
    
    const keyLengthBuffer = Buffer.allocUnsafe(4);
    keyLengthBuffer.writeUInt32BE(keyBytes.length, 0);
    
    const valueLengthBuffer = Buffer.allocUnsafe(4);
    valueLengthBuffer.writeUInt32BE(valueBytes.length, 0);
    
    const buffer = Buffer.concat([
      Buffer.from([0x4E]), // Discriminator 'N'
      keyLengthBuffer,
      keyBytes,
      valueLengthBuffer,
      valueBytes
    ]);
    
    const notification = decodeNotification(buffer);
    
    expect(notification.key).toBe(key);
    expect(notification.value).toBe(value);
    expect(notification.timestamp).toBeInstanceOf(Date);
    expect(notification.sequenceNumber).toBe(0n);
  });

  // TC-028: Invalid discriminator throws error
  it('should throw error for invalid discriminator', () => {
    const buffer = Buffer.from([0xFF, 0x00, 0x00, 0x00, 0x04, 0x74, 0x65, 0x73, 0x74]);
    
    expect(() => decodeNotification(buffer)).toThrow(ProtocolError);
    
    try {
      decodeNotification(buffer);
    } catch (error) {
      expect(error).toBeInstanceOf(ProtocolError);
      expect((error as ProtocolError).message).toContain('Invalid discriminator');
      expect((error as ProtocolError).message).toContain('0xff');
    }
  });

  it('should throw error for invalid hasValue byte in Get result', () => {
    const buffer = Buffer.from([0x02]); // Invalid hasValue (not 0 or 1)
    
    expect(() => decodeGetResult(buffer)).toThrow(ProtocolError);
    
    try {
      decodeGetResult(buffer);
    } catch (error) {
      expect(error).toBeInstanceOf(ProtocolError);
      expect((error as ProtocolError).message).toContain('Invalid hasValue byte');
    }
  });
});

describe('Round-Trip', () => {
  // TC-029: Set request round-trips correctly
  it('should round-trip Set request correctly', () => {
    const key = 'test';
    const value = 'data';
    
    const encoded = encodeSetRequest(key, value);
    
    // Verify we can decode the key and value back
    expect(encoded[0]).toBe(0x53);
    
    const { value: decodedKey } = decodeUtf8_32(encoded, 1);
    expect(decodedKey).toBe(key);
    
    const keyBytesRead = 4 + Buffer.from(key, 'utf8').length;
    const { value: decodedValue } = decodeUtf8_32(encoded, 1 + keyBytesRead);
    expect(decodedValue).toBe(value);
  });

  // TC-030: Unicode round-trips correctly
  it('should preserve unicode through round-trip', () => {
    const unicodeKey = '键';
    const unicodeValue = '值🎉';
    
    const encoded = encodeSetRequest(unicodeKey, unicodeValue);
    
    // Decode and verify
    const { value: decodedKey, bytesRead: keyBytesRead } = decodeUtf8_32(encoded, 1);
    expect(decodedKey).toBe(unicodeKey);
    
    const { value: decodedValue } = decodeUtf8_32(encoded, 1 + keyBytesRead);
    expect(decodedValue).toBe(unicodeValue);
  });

  it('should handle Get query and result round-trip', () => {
    const key = 'testkey';
    const value = 'testvalue';
    
    // Encode Get query
    const queryBuffer = encodeGetQuery(key);
    expect(queryBuffer[0]).toBe(0x47);
    
    // Simulate server response (Some value)
    const valueBytes = Buffer.from(value, 'utf8');
    const lengthBuffer = Buffer.allocUnsafe(4);
    lengthBuffer.writeUInt32BE(valueBytes.length, 0);
    
    const resultBuffer = Buffer.concat([
      Buffer.from([0x01]),
      lengthBuffer,
      valueBytes
    ]);
    
    const decodedValue = decodeGetResult(resultBuffer);
    expect(decodedValue).toBe(value);
  });

  it('should handle Watch notification round-trip', () => {
    const key = 'watchkey';
    
    // Encode Watch request
    const watchBuffer = encodeWatchRequest(key);
    expect(watchBuffer[0]).toBe(0x57);
    
    // Simulate server notification
    const notificationKey = 'watchkey';
    const notificationValue = 'newvalue';
    
    const keyBytes = Buffer.from(notificationKey, 'utf8');
    const valueBytes = Buffer.from(notificationValue, 'utf8');
    
    const keyLengthBuffer = Buffer.allocUnsafe(4);
    keyLengthBuffer.writeUInt32BE(keyBytes.length, 0);
    
    const valueLengthBuffer = Buffer.allocUnsafe(4);
    valueLengthBuffer.writeUInt32BE(valueBytes.length, 0);
    
    const notificationBuffer = Buffer.concat([
      Buffer.from([0x4E]),
      keyLengthBuffer,
      keyBytes,
      valueLengthBuffer,
      valueBytes
    ]);
    
    const notification = decodeNotification(notificationBuffer);
    expect(notification.key).toBe(notificationKey);
    expect(notification.value).toBe(notificationValue);
  });
});
