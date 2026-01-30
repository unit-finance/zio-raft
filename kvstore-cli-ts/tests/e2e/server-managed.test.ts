/**
 * E2E Tests - Server Managed
 * ===========================
 *
 * Tests using ServerManager for automated server lifecycle management.
 * These tests take longer (~1-2 minutes) due to server startup time.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { spawn } from 'child_process';
import { join } from 'path';
import { ServerManager } from '../helpers/serverManager.js';

// =============================================================================
// Configuration
// =============================================================================

/** Path to the CLI entry point */
const CLI_PATH = join(process.cwd(), 'dist/index.js');

/** Command timeout in milliseconds */
const CMD_TIMEOUT = 5000;

// =============================================================================
// Types
// =============================================================================

interface CliResult {
  stdout: string;
  stderr: string;
  exitCode: number;
}

// =============================================================================
// Helper Functions
// =============================================================================

/**
 * Run CLI command and return result
 *
 * @param args - CLI arguments
 * @returns Promise with stdout, stderr, and exit code
 */
async function runCli(...args: string[]): Promise<CliResult> {
  return new Promise((resolve) => {
    const child = spawn('node', [CLI_PATH, ...args], {
      timeout: CMD_TIMEOUT,
    });

    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (data) => {
      stdout += data.toString();
    });

    child.stderr.on('data', (data) => {
      stderr += data.toString();
    });

    child.on('close', (code) => {
      resolve({
        stdout: stdout.trim(),
        stderr: stderr.trim(),
        exitCode: code ?? 1,
      });
    });

    child.on('error', (err) => {
      resolve({
        stdout: '',
        stderr: err.message,
        exitCode: 1,
      });
    });
  });
}

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
