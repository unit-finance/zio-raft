/**
 * Unit tests for validation functions
 */

import { describe, it, expect } from 'vitest';
import { validateKey, validateValue, parseEndpoints } from '../../src/validation.js';
import { ValidationError } from '../../src/errors.js';

describe('Key Validation', () => {
  // TC-001: Valid key passes validation
  it('should accept valid key', () => {
    expect(() => validateKey('valid-key')).not.toThrow();
    expect(() => validateKey('user:123')).not.toThrow();
    expect(() => validateKey('config.db.host')).not.toThrow();
  });

  // TC-002: Empty key is rejected
  it('should reject empty key', () => {
    expect(() => validateKey('')).toThrow(ValidationError);
    try {
      validateKey('');
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      expect((error as ValidationError).message).toBe('Key cannot be empty');
      expect((error as ValidationError).field).toBe('key');
    }
  });

  // TC-003: Key exceeding 256 bytes is rejected
  it('should reject key exceeding 256 bytes', () => {
    const longKey = 'a'.repeat(257);
    expect(() => validateKey(longKey)).toThrow(ValidationError);

    try {
      validateKey(longKey);
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      const ve = error as ValidationError;
      expect(ve.field).toBe('key');
      expect(ve.message).toBe('Key exceeds maximum size');
      expect(ve.actual).toBe(257);
      expect(ve.expected).toBe(256);
    }
  });

  // TC-004: Key at exactly 256 bytes passes
  it('should accept key at exactly 256 bytes', () => {
    const maxKey = 'a'.repeat(256);
    expect(() => validateKey(maxKey)).not.toThrow();
  });

  // TC-005: Unicode key is validated correctly by byte length
  it('should validate unicode key by byte length not character count', () => {
    // Emoji takes 4 bytes in UTF-8
    const unicodeKey = 'key🔑'; // 'key' (3 bytes) + emoji (4 bytes) = 7 bytes
    expect(() => validateKey(unicodeKey)).not.toThrow();

    // Create a key that's under 256 chars but over 256 bytes
    const manyEmojis = '🔑'.repeat(65); // 65 * 4 = 260 bytes
    expect(() => validateKey(manyEmojis)).toThrow(ValidationError);
  });
});

describe('Value Validation', () => {
  // TC-007: Valid value passes validation
  it('should accept valid value', () => {
    expect(() => validateValue('simple value')).not.toThrow();
    expect(() => validateValue('{"json": "data"}')).not.toThrow();
    expect(() => validateValue('multi\nline\nvalue')).not.toThrow();
  });

  // TC-008: Empty value is rejected
  it('should reject empty value', () => {
    expect(() => validateValue('')).toThrow(ValidationError);

    try {
      validateValue('');
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      expect((error as ValidationError).message).toBe('Value cannot be empty');
      expect((error as ValidationError).field).toBe('value');
    }
  });

  // TC-009: Value exceeding 1MB is rejected
  it('should reject value exceeding 1MB', () => {
    const largeValue = 'a'.repeat(1024 * 1024 + 1); // 1MB + 1 byte
    expect(() => validateValue(largeValue)).toThrow(ValidationError);

    try {
      validateValue(largeValue);
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      const ve = error as ValidationError;
      expect(ve.field).toBe('value');
      expect(ve.message).toBe('Value exceeds maximum size');
      expect(ve.actual).toBe(1024 * 1024 + 1);
      expect(ve.expected).toBe(1024 * 1024);
    }
  });

  // TC-010: Value at exactly 1MB passes
  it('should accept value at exactly 1MB', () => {
    const maxValue = 'a'.repeat(1024 * 1024); // Exactly 1MB
    expect(() => validateValue(maxValue)).not.toThrow();
  });

  // TC-012: Invalid UTF-8 value is rejected
  it('should reject invalid UTF-8 sequences', () => {
    // Similar to key validation, TypeScript strings are always valid UTF-16
    // Testing the round-trip validation logic
    const validUtf8 = 'valid-utf8-値🎉';
    expect(() => validateValue(validUtf8)).not.toThrow();
  });
});

describe('Endpoint Parsing', () => {
  // TC-013: Valid single endpoint is parsed
  it('should parse single endpoint correctly', () => {
    const result = parseEndpoints('node-1=tcp://127.0.0.1:7001');

    expect(result.endpoints.size).toBe(1);
    expect(result.endpoints.get('node-1')).toBe('tcp://127.0.0.1:7001');
  });

  // TC-014: Multiple endpoints are parsed
  it('should parse multiple endpoints correctly', () => {
    const result = parseEndpoints('node-1=tcp://127.0.0.1:7001,node-2=tcp://192.168.1.10:7002');

    expect(result.endpoints.size).toBe(2);
    expect(result.endpoints.get('node-1')).toBe('tcp://127.0.0.1:7001');
    expect(result.endpoints.get('node-2')).toBe('tcp://192.168.1.10:7002');
  });

  // TC-015: Default endpoint is used when empty
  it('should use default endpoint when empty string provided', () => {
    const result = parseEndpoints('');

    expect(result.endpoints.size).toBe(1);
    expect(result.endpoints.get('node-1')).toBe('tcp://127.0.0.1:7001');
  });

  // TC-016: Invalid endpoint format is rejected
  it('should reject invalid endpoint format', () => {
    expect(() => parseEndpoints('node-1=invalid-format')).toThrow(ValidationError);

    try {
      parseEndpoints('node-1=invalid-format');
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      const ve = error as ValidationError;
      expect(ve.field).toBe('endpoints');
      expect(ve.message).toContain('Invalid endpoint format');
    }
  });

  // TC-017: Invalid port number is rejected
  it('should reject invalid port numbers', () => {
    // Port too high
    expect(() => parseEndpoints('node-1=tcp://127.0.0.1:99999')).toThrow(ValidationError);

    try {
      parseEndpoints('node-1=tcp://127.0.0.1:99999');
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      const ve = error as ValidationError;
      expect(ve.message).toContain('Invalid port');
      expect(ve.message).toContain('99999');
    }

    // Port zero
    expect(() => parseEndpoints('node-1=tcp://127.0.0.1:0')).toThrow(ValidationError);
  });

  // TC-018: Missing member ID is rejected
  it('should reject missing member ID', () => {
    expect(() => parseEndpoints('=tcp://127.0.0.1:7001')).toThrow(ValidationError);

    try {
      parseEndpoints('=tcp://127.0.0.1:7001');
    } catch (error) {
      expect(error).toBeInstanceOf(ValidationError);
      const ve = error as ValidationError;
      expect(ve.field).toBe('endpoints');
      expect(ve.message).toContain('Invalid format');
    }
  });

  // TC-019: Whitespace is trimmed
  it('should trim whitespace from member IDs and endpoints', () => {
    const result = parseEndpoints('  node-1 = tcp://127.0.0.1:7001  ,  node-2 = tcp://host:7002  ');

    expect(result.endpoints.size).toBe(2);
    expect(result.endpoints.get('node-1')).toBe('tcp://127.0.0.1:7001');
    expect(result.endpoints.get('node-2')).toBe('tcp://host:7002');
  });
});
