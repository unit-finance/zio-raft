/**
 * E2E Tests - Server Not Running
 * ===============================
 *
 * Tests CLI behavior when connecting to a non-existent server.
 * These tests are fast because they don't require a running server.
 */

import { describe, it, expect } from 'vitest';
import { spawn } from 'child_process';
import { join } from 'path';

// =============================================================================
// Configuration
// =============================================================================

/** Path to the CLI entry point */
const CLI_PATH = join(process.cwd(), 'dist/index.js');

/** Command timeout in milliseconds */
const CMD_TIMEOUT = 10000;

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
// Tests
// =============================================================================

describe('Server Not Running', () => {
  /**
   * Test: CLI fails gracefully when server is not running
   * Verifies timeout behavior when connecting to non-existent server
   */
  it(
    'should fail with timeout when connecting to non-existent server',
    async () => {
      // Use a fake endpoint - nothing listening on port 9999
      const fakeEndpoint = 'node-1=tcp://127.0.0.1:9999';

      const result = await runCli('set', 'testkey', 'testvalue', '-e', fakeEndpoint);

      // Should fail with non-zero exit code
      expect(result.exitCode).not.toBe(0);

      // Output should indicate connection issue
      const output = (result.stdout + result.stderr).toLowerCase();
      const hasConnectionError =
        output.includes('connect') || output.includes('timeout') || output.includes('unreachable');

      expect(hasConnectionError).toBe(true);
    },
    15000
  ); // 15 second timeout
});
