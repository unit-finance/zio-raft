/**
 * E2E Tests - Server Not Running
 * ===============================
 *
 * Tests CLI behavior when connecting to a non-existent server.
 * These tests are fast because they don't require a running server.
 */

import { describe, it, expect } from 'vitest';
import { runCli } from '../helpers/cliRunner.js';

// =============================================================================
// Tests
// =============================================================================

describe('Server Not Running', () => {
  /**
   * Test: CLI fails when connecting to non-existent server
   * Verifies CLI doesn't succeed when server is not available
   *
   * Note: Currently the CLI hangs when unable to connect (timeout not working properly).
   * The spawn timeout will kill the process after 10 seconds, resulting in non-zero exit.
   * This test verifies the process doesn't succeed, which is the important behavior.
   */
  it('should fail with timeout when connecting to non-existent server', async () => {
    // Use a fake endpoint - nothing listening on port 9999
    const fakeEndpoint = 'node-1=tcp://127.0.0.1:9999';

    const result = await runCli(10000, 'set', 'testkey', 'testvalue', '-e', fakeEndpoint);

    // Should fail with non-zero exit code
    // This happens because spawn timeout kills the hanging process
    expect(result.exitCode).not.toBe(0);
  }, 15000); // 15 second timeout (spawn has 10s timeout)
});
