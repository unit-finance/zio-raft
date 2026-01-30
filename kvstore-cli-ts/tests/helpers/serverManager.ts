/**
 * ServerManager - Manage KVStore server lifecycle for E2E tests
 * ==============================================================
 *
 * Provides automated server lifecycle management for E2E tests.
 * Handles server startup, port allocation, and cleanup.
 *
 * NOTE: The KVStore server currently has a bug where command-line arguments
 * are not properly parsed. As a workaround, this manager uses the server's
 * default configuration (port 7001 for client, port 7002 for member, memberId "node-1").
 */

/* eslint-disable @typescript-eslint/no-unsafe-call */
/* eslint-disable @typescript-eslint/no-unsafe-member-access */
/* eslint-disable @typescript-eslint/no-unsafe-assignment */
/* eslint-disable @typescript-eslint/strict-boolean-expressions */

import { spawn, ChildProcess } from 'child_process';
import { join } from 'path';
import { waitForPort } from './portUtils.js';

/**
 * ServerManager - Manages a KVStore server instance for testing
 *
 * @example
 * const server = new ServerManager();
 * await server.start();
 * const endpoint = server.getEndpoint();
 * // ... run tests ...
 * await server.cleanup();
 */
export class ServerManager {
  private process: ChildProcess | null = null;
  // Use default server configuration (workaround for command-line arg parsing bug)
  private readonly serverPort = 7001;
  private readonly memberPort = 7002;
  private readonly memberId = 'node-1';

  /**
   * Start the server and wait for it to be ready
   *
   * @throws Error if server fails to start
   */
  async start(): Promise<void> {
    console.log(`[ServerManager] Starting server with default configuration`);
    console.log(
      `[ServerManager] Server port: ${this.serverPort}, Member port: ${this.memberPort}, Member ID: ${this.memberId}`
    );

    // Path to run-kvstore.sh (in repository root, one level up from kvstore-cli-ts)
    const repoRoot = join(process.cwd(), '..');
    const scriptPath = join(repoRoot, 'run-kvstore.sh');

    // Use --rebuild to ensure JAR is up to date
    const args = ['--rebuild'];

    console.log(`[ServerManager] Spawning: ${scriptPath} ${args.join(' ')}`);

    // Spawn the server process from the repository root directory
    this.process = spawn(scriptPath, args, {
      cwd: repoRoot, // Run from repository root
      detached: false,
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    // Log server output for debugging
    this.process.stdout?.on('data', (data) => {
      const lines = data
        .toString()
        .split('\n')
        .filter((l: string) => l.trim());
      lines.forEach((line: string) => {
        console.log(`[SERVER] ${line}`);
      });
    });

    this.process.stderr?.on('data', (data) => {
      const lines = data
        .toString()
        .split('\n')
        .filter((l: string) => l.trim());
      lines.forEach((line: string) => {
        console.log(`[SERVER] ${line}`);
      });
    });

    // Handle unexpected process exit
    this.process.on('exit', (code, signal) => {
      console.log(`[ServerManager] Server process exited with code ${code}, signal ${signal}`);
    });

    // Wait for server port to be ready
    console.log(`[ServerManager] Waiting for server port ${this.serverPort} to be ready...`);
    await waitForPort(this.serverPort);
    console.log(`[ServerManager] Server port ${this.serverPort} is ready`);

    // Add buffer for full initialization
    console.log('[ServerManager] Waiting 2 seconds for full initialization...');
    await new Promise((resolve) => setTimeout(resolve, 2000));

    console.log('[ServerManager] Server is ready');
  }

  /**
   * Force kill the server process
   */
  async kill(): Promise<void> {
    if (this.process && this.process.pid) {
      console.log(`[ServerManager] Killing server process ${this.process.pid}`);
      this.process.kill('SIGKILL');
      await new Promise((resolve) => setTimeout(resolve, 500));
      console.log('[ServerManager] Server process killed');
    }
  }

  /**
   * Get the endpoint string for CLI -e flag
   *
   * @returns Endpoint string in format: memberId=tcp://host:port
   */
  getEndpoint(): string {
    return `${this.memberId}=tcp://127.0.0.1:${this.serverPort}`;
  }

  /**
   * Cleanup server resources
   */
  async cleanup(): Promise<void> {
    console.log('[ServerManager] Cleaning up...');
    await this.kill();
  }
}
