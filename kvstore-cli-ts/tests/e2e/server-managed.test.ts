/**
 * E2E Tests - Server Managed
 * ===========================
 *
 * Tests using ServerManager for automated server lifecycle management.
 * These tests take longer (~1-2 minutes) due to server startup time.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { ServerManager } from '../helpers/serverManager.js';
import { runCli } from '../helpers/cliRunner.js';

// =============================================================================
// Test Suite
// =============================================================================

describe('Server Managed Tests', () => {
  let server: ServerManager;
  let endpoint: string;

  beforeAll(async () => {
    console.log('Starting managed server...');
    server = new ServerManager();
    await server.start();
    endpoint = server.getEndpoint();
    console.log(`Server started with endpoint: ${endpoint}`);
  }, 120000); // 2 minute timeout for server startup

  afterAll(async () => {
    console.log('Cleaning up server...');
    await server.cleanup();
  });

  /**
   * Test: Connect to managed server and set a value
   * Verifies basic connectivity and set operation
   */
  it('should connect to managed server and set a value', async () => {
    const timestamp = Date.now();
    const key = `test_${timestamp}`;

    const result = await runCli('set', key, 'testvalue', '-e', endpoint);

    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain('OK');
  });

  /**
   * Test: Get a value from managed server
   * Verifies set and get operations work correctly
   */
  it('should get a value from managed server', async () => {
    const timestamp = Date.now();
    const key = `test_get_${timestamp}`;
    const value = `value_${timestamp}`;

    // Set a value
    const setResult = await runCli('set', key, value, '-e', endpoint);
    expect(setResult.exitCode).toBe(0);

    // Get the value back
    const getResult = await runCli('get', key, '-e', endpoint);
    expect(getResult.exitCode).toBe(0);
    expect(getResult.stdout).toContain(value);
  });
});
