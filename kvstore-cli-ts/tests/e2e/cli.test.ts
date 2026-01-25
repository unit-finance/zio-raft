/**
 * KVStore CLI End-to-End Tests
 * ============================
 *
 * These tests run the actual CLI against a real running KVStore server.
 * They spawn the CLI as a child process and verify its output and behavior.
 *
 * Prerequisites:
 *   - KVStore server running on tcp://127.0.0.1:7002
 *   - CLI built: npm run build
 *
 * Run with:
 *   npm test -- tests/e2e/cli.test.ts
 *
 * Adding New Tests:
 *   1. Use the helper functions: runCli(), runCliExpectSuccess(), runCliExpectError()
 *   2. For watch tests, use startWatch() and stopWatch()
 *   3. Each test should be independent - use unique keys to avoid conflicts
 *   4. Follow the naming pattern: describe('Category', () => { it('should...') })
 *
 * Example:
 *   it('should do something', async () => {
 *     const result = await runCli('set', 'mykey', 'myvalue');
 *     expect(result.stdout).toContain('OK');
 *     expect(result.exitCode).toBe(0);
 *   });
 */

/* eslint-disable @typescript-eslint/no-unsafe-call */
/* eslint-disable @typescript-eslint/no-unsafe-member-access */
/* eslint-disable @typescript-eslint/no-unsafe-assignment */
/* eslint-disable @typescript-eslint/no-unsafe-argument */

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { spawn, ChildProcess } from 'child_process';
import { join } from 'path';
import { existsSync } from 'fs';

// =============================================================================
// Configuration
// =============================================================================

/** Path to the CLI entry point (relative to project root) */
const CLI_PATH = join(process.cwd(), 'dist/index.js');

/** Default command timeout in milliseconds */
const CMD_TIMEOUT = 5000;

/** Time to wait for watch to start up */
const WATCH_STARTUP_DELAY = 2000;

/** Time to wait for notifications to propagate */
const NOTIFICATION_DELAY = 1000;

// =============================================================================
// Types
// =============================================================================

interface CliResult {
  stdout: string;
  stderr: string;
  exitCode: number;
}

interface WatchHandle {
  process: ChildProcess;
  output: string[];
  stop: () => Promise<string[]>;
}

// =============================================================================
// Helper Functions
// =============================================================================

/**
 * Run CLI command and return result
 *
 * @param args - CLI arguments (e.g., 'set', 'key', 'value')
 * @returns Promise with stdout, stderr, and exit code
 *
 * @example
 * const result = await runCli('get', 'mykey');
 * console.log(result.stdout); // "mykey = myvalue"
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
      // Filter out ZMQ warnings but keep error messages
      stdout = filterWarnings(stdout);
      // Don't filter stderr as heavily - it contains real errors
      stderr = stderr
        .split('\n')
        .filter((line) => !line.includes('ZeroMQ draft features'))
        .join('\n');
      resolve({ stdout: stdout.trim(), stderr: stderr.trim(), exitCode: code ?? 1 });
    });

    child.on('error', (err) => {
      resolve({ stdout: '', stderr: err.message, exitCode: 1 });
    });
  });
}

/**
 * Run CLI command expecting success (exit code 0)
 * Throws if command fails.
 */
async function runCliExpectSuccess(...args: string[]): Promise<string> {
  const result = await runCli(...args);
  if (result.exitCode !== 0) {
    throw new Error(`CLI failed with exit code ${result.exitCode}: ${result.stderr || result.stdout}`);
  }
  return result.stdout;
}

/**
 * Run CLI command expecting failure (exit code != 0)
 * Throws if command succeeds.
 */
async function _runCliExpectError(...args: string[]): Promise<CliResult> {
  const result = await runCli(...args);
  if (result.exitCode === 0) {
    throw new Error(`Expected CLI to fail, but it succeeded: ${result.stdout}`);
  }
  return result;
}

/**
 * Start a watch process in background
 *
 * @param key - Key to watch
 * @returns Handle with stop() function and output array
 *
 * @example
 * const watch = await startWatch('mykey');
 * await runCli('set', 'mykey', 'newvalue');
 * await sleep(1000);
 * const notifications = await watch.stop();
 * expect(notifications).toContain('newvalue');
 */
async function startWatch(key: string): Promise<WatchHandle> {
  const output: string[] = [];
  let stopped = false;

  const childProcess = spawn('node', [CLI_PATH, 'watch', key], {
    detached: false, // Don't let it outlive parent
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  childProcess.stdout.on('data', (data) => {
    const lines = data
      .toString()
      .split('\n')
      .filter((l: string) => l.trim());
    output.push(...lines.filter((l: string) => !l.includes('Warning:')));
  });

  childProcess.stderr.on('data', (_data) => {
    // Ignore stderr (usually just warnings)
  });

  // Track when process exits naturally
  childProcess.on('exit', () => {
    stopped = true;
  });

  // Wait for watch to start
  await sleep(WATCH_STARTUP_DELAY);

  const stop = async (): Promise<string[]> => {
    if (stopped || childProcess.pid === undefined) {
      return output;
    }

    // Try multiple signals to ensure process dies
    const signals: NodeJS.Signals[] = ['SIGINT', 'SIGTERM', 'SIGKILL'];

    for (const signal of signals) {
      if (stopped) break;

      try {
        childProcess.kill(signal);
        await sleep(200);

        // Check if process is still running
        try {
          process.kill(childProcess.pid, 0); // Signal 0 = check if alive
        } catch {
          // Process is dead
          stopped = true;
          break;
        }
      } catch {
        // Kill failed, try next signal
      }
    }

    // If still not stopped after all signals, log warning
    if (!stopped) {
      console.warn(`Warning: Could not kill watch process ${childProcess.pid}`);
    }

    return output;
  };

  return { process: childProcess, output, stop };
}

/**
 * Sleep for specified milliseconds
 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Filter out ZMQ warnings from output
 */
function filterWarnings(text: string): string {
  return text
    .split('\n')
    .filter((line) => !line.includes('Warning:') && !line.includes('trace-warnings'))
    .join('\n');
}

/**
 * Generate unique key for test isolation
 */
function uniqueKey(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

// =============================================================================
// Test Setup
// =============================================================================

// Track active watch processes for cleanup
const activeWatches: WatchHandle[] = [];

beforeAll(async () => {
  // Verify CLI is built
  if (!existsSync(CLI_PATH)) {
    throw new Error(`CLI not built. Run "npm run build" first. Expected: ${CLI_PATH}`);
  }

  // Verify server connection
  const result = await runCli('get', '__e2e_connection_test__');
  if (result.exitCode !== 0 && !result.stdout.includes('<none>')) {
    throw new Error(`Cannot connect to server. Is it running? Error: ${result.stderr || result.stdout}`);
  }
});

afterEach(async () => {
  // Stop any active watches
  for (const watch of activeWatches) {
    try {
      await watch.stop();
    } catch {
      // Ignore cleanup errors
    }
  }
  activeWatches.length = 0;
});

afterAll(async () => {
  // Final cleanup - give any remaining processes time to die
  await sleep(500);

  // Force kill any remaining watches (safety net)
  for (const watch of activeWatches) {
    try {
      if (watch.process.pid !== undefined) {
        watch.process.kill('SIGKILL');
      }
    } catch {
      // Ignore
    }
  }
});

// =============================================================================
// Tests: Basic Operations
// =============================================================================

describe('Basic Operations', () => {
  /**
   * Test: Basic set operation
   * Verifies CLI can connect and write a key-value pair
   */
  it('should set a key-value pair', async () => {
    const key = uniqueKey('test_set');
    const result = await runCli('set', key, 'testvalue');

    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain('OK');
  });

  /**
   * Test: Get existing key
   * Verifies CLI can retrieve a previously set value
   */
  it('should get an existing key', async () => {
    const key = uniqueKey('test_get');
    await runCliExpectSuccess('set', key, 'myvalue');

    const result = await runCli('get', key);

    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain(`${key} = myvalue`);
  });

  /**
   * Test: Get non-existent key
   * Verifies CLI handles missing keys gracefully
   */
  it('should return <none> for non-existent key', async () => {
    const key = uniqueKey('nonexistent');
    const result = await runCli('get', key);

    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain('<none>');
  });

  /**
   * Test: Set and get round-trip
   * Verifies written value can be read back correctly
   */
  it('should round-trip set and get', async () => {
    const key = uniqueKey('roundtrip');
    const value = `value_${Date.now()}`;

    await runCliExpectSuccess('set', key, value);
    const output = await runCliExpectSuccess('get', key);

    expect(output).toContain(`${key} = ${value}`);
  });

  /**
   * Test: Update existing key
   * Verifies overwriting a key works correctly
   */
  it('should update an existing key', async () => {
    const key = uniqueKey('update');

    await runCliExpectSuccess('set', key, 'original');
    await runCliExpectSuccess('set', key, 'updated');

    const output = await runCliExpectSuccess('get', key);
    expect(output).toContain('updated');
    expect(output).not.toContain('original');
  });
});

// =============================================================================
// Tests: Validation
// =============================================================================

describe('Validation', () => {
  /**
   * Test: Invalid endpoint format
   * Verifies CLI rejects malformed endpoint strings
   */
  it('should reject invalid endpoint format', async () => {
    const result = await runCli('get', 'testkey', '-e', 'invalid-address');

    expect(result.exitCode).toBe(1);
    // Error may be in stdout or stderr depending on how commander handles it
    const output = result.stdout + result.stderr;
    expect(output).toContain('Invalid format');
  });

  /**
   * Test: Empty value validation
   * Verifies CLI rejects empty values
   */
  it('should reject empty value', async () => {
    const result = await runCli('set', 'testkey', '');

    expect(result.exitCode).toBe(1);
    // Error may be in stdout or stderr
    const output = result.stdout + result.stderr;
    expect(output).toContain('cannot be empty');
  });
});

// =============================================================================
// Tests: Special Characters
// =============================================================================

describe('Special Characters', () => {
  /**
   * Test: Spaces in value
   * Verifies spaces are preserved in values
   */
  it('should handle spaces in value', async () => {
    const key = uniqueKey('spaces');
    const value = 'hello world with spaces';

    await runCliExpectSuccess('set', key, value);
    const output = await runCliExpectSuccess('get', key);

    expect(output).toContain(value);
  });

  /**
   * Test: Special characters
   * Verifies special characters are preserved
   */
  it('should handle special characters', async () => {
    const key = uniqueKey('special');
    const value = '!@#$%^&*()';

    await runCliExpectSuccess('set', key, value);
    const output = await runCliExpectSuccess('get', key);

    expect(output).toContain(value);
  });

  /**
   * Test: Unicode characters
   * Verifies Unicode (emoji, CJK) is preserved
   */
  it('should handle unicode characters', async () => {
    const key = uniqueKey('unicode');
    const value = 'Hello 世界 🌍';

    await runCliExpectSuccess('set', key, value);
    const output = await runCliExpectSuccess('get', key);

    expect(output).toContain(value);
  });
});

// =============================================================================
// Tests: Sequential Operations
// =============================================================================

describe('Sequential Operations', () => {
  /**
   * Test: Multiple sequential operations
   * Verifies multiple sequential set/get operations work reliably
   */
  it('should handle 10 sequential set/get operations', async () => {
    const prefix = uniqueKey('seq');

    // Set 10 keys
    for (let i = 1; i <= 10; i++) {
      await runCliExpectSuccess('set', `${prefix}_${i}`, `value_${i}`);
    }

    // Verify all 10 keys
    for (let i = 1; i <= 10; i++) {
      const output = await runCliExpectSuccess('get', `${prefix}_${i}`);
      expect(output).toContain(`value_${i}`);
    }
  });
});

// =============================================================================
// Tests: Watch Functionality
// =============================================================================

describe('Watch Functionality', () => {
  /**
   * Test: Watch receives notifications
   * Verifies watch receives updates when key changes
   */
  it('should receive notifications when key is updated', async () => {
    const key = uniqueKey('watch_single');

    const watch = await startWatch(key);
    activeWatches.push(watch);

    // Update key twice
    await runCliExpectSuccess('set', key, 'value1');
    await sleep(NOTIFICATION_DELAY);
    await runCliExpectSuccess('set', key, 'value2');
    await sleep(NOTIFICATION_DELAY);

    const output = await watch.stop();
    const notifications = output.filter((l) => l.startsWith('['));

    expect(notifications.length).toBeGreaterThanOrEqual(2);
    expect(output.some((l) => l.includes('value1'))).toBe(true);
    expect(output.some((l) => l.includes('value2'))).toBe(true);
  });

  /**
   * Test: Watch receives notification for new key
   * Verifies watch works when key doesn't exist initially
   */
  it('should receive notification when key is created', async () => {
    const key = uniqueKey('watch_new');

    const watch = await startWatch(key);
    activeWatches.push(watch);

    // Create key (didn't exist before)
    await runCliExpectSuccess('set', key, 'initial_value');
    await sleep(NOTIFICATION_DELAY);

    const output = await watch.stop();

    expect(output.some((l) => l.includes('initial_value'))).toBe(true);
  });

  /**
   * Test: Multiple watches are independent
   * Verifies each watch only receives its own key's notifications
   */
  it('should isolate notifications between watches', { timeout: 15000 }, async () => {
    const key1 = uniqueKey('watch_multi_1');
    const key2 = uniqueKey('watch_multi_2');

    const watch1 = await startWatch(key1);
    const watch2 = await startWatch(key2);
    activeWatches.push(watch1, watch2);

    // Update each key
    await runCliExpectSuccess('set', key1, 'value_for_key1');
    await sleep(NOTIFICATION_DELAY);
    await runCliExpectSuccess('set', key2, 'value_for_key2');
    await sleep(NOTIFICATION_DELAY);

    const output1 = await watch1.stop();
    const output2 = await watch2.stop();

    // watch1 should have key1's value, not key2's
    expect(output1.some((l) => l.includes('value_for_key1'))).toBe(true);
    expect(output1.some((l) => l.includes('value_for_key2'))).toBe(false);

    // watch2 should have key2's value, not key1's
    expect(output2.some((l) => l.includes('value_for_key2'))).toBe(true);
    expect(output2.some((l) => l.includes('value_for_key1'))).toBe(false);
  });

  /**
   * Test: Watch handles rapid updates
   * Verifies watch doesn't drop notifications during rapid updates
   */
  it('should handle rapid updates without dropping notifications', { timeout: 15000 }, async () => {
    const key = uniqueKey('watch_rapid');

    const watch = await startWatch(key);
    activeWatches.push(watch);

    // Perform 5 rapid updates
    for (let i = 1; i <= 5; i++) {
      await runCliExpectSuccess('set', key, `rapid_${i}`);
      await sleep(300); // Small delay between updates
    }
    await sleep(NOTIFICATION_DELAY);

    const output = await watch.stop();
    const notifications = output.filter((l) => l.startsWith('['));

    // Should have received all 5 notifications
    expect(notifications.length).toBeGreaterThanOrEqual(5);
  });
});
