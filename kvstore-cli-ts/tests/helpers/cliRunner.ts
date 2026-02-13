/**
 * CLI Runner Utility
 * ==================
 *
 * Shared utility for running CLI commands in E2E tests.
 */

import { spawn } from 'child_process';
import { join } from 'path';

/** Path to the CLI entry point */
const CLI_PATH = join(process.cwd(), 'dist/index.js');

/**
 * Result from running a CLI command
 */
export interface CliResult {
  stdout: string;
  stderr: string;
  exitCode: number;
}

/**
 * Run CLI command and return result
 *
 * @param args - CLI arguments
 * @param timeout - Command timeout in milliseconds (default: 5000)
 * @returns Promise with stdout, stderr, and exit code
 *
 * @example
 * const result = await runCli('get', 'mykey');
 * console.log(result.stdout); // "mykey = myvalue"
 */
export async function runCli(...args: string[]): Promise<CliResult>;
export async function runCli(timeout: number, ...args: string[]): Promise<CliResult>;
export async function runCli(...argsWithTimeout: Array<string | number>): Promise<CliResult> {
  let timeout = 5000;
  let args: string[];

  // Handle overload: first arg might be timeout
  if (typeof argsWithTimeout[0] === 'number') {
    timeout = argsWithTimeout[0];
    args = argsWithTimeout.slice(1) as string[];
  } else {
    args = argsWithTimeout as string[];
  }

  return new Promise((resolve) => {
    const child = spawn('node', [CLI_PATH, ...args], {
      timeout,
    });

    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (data: Buffer) => {
      stdout += data.toString();
    });

    child.stderr.on('data', (data: Buffer) => {
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
