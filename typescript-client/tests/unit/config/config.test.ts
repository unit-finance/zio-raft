// Configuration validation unit tests
// Tests ClientConfig validation and defaults

import { describe, it, expect } from 'vitest';
import { createConfig, validateConfig, ClientConfigInput } from '../../../src/config';
import { MemberId } from '../../../src/types';

describe('Configuration', () => {
  describe('TC-CONFIG-001: Valid configuration accepted', () => {
    it('should accept valid configuration', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['version', '1.0.0']]),
      };

      const config = createConfig(input);

      expect(config.clusterMembers.size).toBe(1);
      expect(config.capabilities.size).toBe(1);
      expect(config.connectionTimeout).toBe(5000); // Default
      expect(config.keepAliveInterval).toBe(30000); // Default
      expect(config.requestTimeout).toBe(10000); // Default
    });

    it('should accept custom timeouts', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['version', '1.0.0']]),
        connectionTimeout: 10000,
        keepAliveInterval: 15000,
        requestTimeout: 20000,
      };

      const config = createConfig(input);

      expect(config.connectionTimeout).toBe(10000);
      expect(config.keepAliveInterval).toBe(15000);
      expect(config.requestTimeout).toBe(20000);
    });
  });

  describe('TC-CONFIG-002: Empty endpoints rejected', () => {
    it('should reject empty cluster members', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map(),
        capabilities: new Map([['version', '1.0.0']]),
      };

      expect(() => createConfig(input)).toThrow('clusterMembers cannot be empty');
    });
  });

  describe('TC-CONFIG-003: Empty capabilities rejected', () => {
    it('should reject empty capabilities', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map(),
      };

      expect(() => createConfig(input)).toThrow('capabilities cannot be empty');
    });
  });

  describe('TC-CONFIG-004: Negative timeout rejected', () => {
    it('should reject negative connection timeout', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['version', '1.0.0']]),
        connectionTimeout: -1000,
      };

      expect(() => createConfig(input)).toThrow('connectionTimeout must be positive');
    });

    it('should reject zero connection timeout', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['version', '1.0.0']]),
        connectionTimeout: 0,
      };

      expect(() => createConfig(input)).toThrow('connectionTimeout must be positive');
    });

    it('should reject negative keep-alive interval', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['version', '1.0.0']]),
        keepAliveInterval: -5000,
      };

      expect(() => createConfig(input)).toThrow('keepAliveInterval must be positive');
    });

    it('should reject negative request timeout', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['version', '1.0.0']]),
        requestTimeout: -3000,
      };

      expect(() => createConfig(input)).toThrow('requestTimeout must be positive');
    });
  });

  describe('Invalid addresses', () => {
    it('should reject empty address', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), '']]),
        capabilities: new Map([['version', '1.0.0']]),
      };

      expect(() => createConfig(input)).toThrow('has empty address');
    });

    it('should reject invalid address format', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'localhost:5555']]),
        capabilities: new Map([['version', '1.0.0']]),
      };

      expect(() => createConfig(input)).toThrow('has invalid address');
    });

    it('should accept tcp:// addresses', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
        capabilities: new Map([['version', '1.0.0']]),
      };

      expect(() => createConfig(input)).not.toThrow();
    });

    it('should accept ipc:// addresses', () => {
      const input: ClientConfigInput = {
        clusterMembers: new Map([[MemberId.fromString('node1'), 'ipc:///tmp/raft.sock']]),
        capabilities: new Map([['version', '1.0.0']]),
      };

      expect(() => createConfig(input)).not.toThrow();
    });
  });
});

