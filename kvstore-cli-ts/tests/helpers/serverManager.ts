/**
 * ServerManager - Manage KVStore server lifecycle for E2E tests
 * ==============================================================
 *
 * Provides automated server lifecycle management for E2E tests.
 * Handles server startup, port allocation, and cleanup.
 */

import { spawn, ChildProcess } from 'child_process';
import { join } from 'path';
import { findAvailablePort, waitForPort } from './portUtils.js';

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
  private memberId: string;
  private serverPort: number | null = null;
  private memberPort: number | null = null;
  private process: ChildProcess | null = null;

  /**
   * Create a new ServerManager
   *
   * @param memberId - Optional member ID (default: test-node-{timestamp})
   */
  constructor(memberId?: string) {
    this.memberId = memberId ?? `test-node-${Date.now()}`;
  }

  /**
   * Start the server and wait for it to be ready
   *
   * @throws Error if server fails to start
   */
  async start(): Promise<void> {
    console.log(`[ServerManager] Starting server with member ID: ${this.memberId}`);

    // Allocate ports
    this.serverPort = await findAvailablePort();
    this.memberPort = await findAvailablePort(this.serverPort + 1);

    console.log(`[ServerManager] Allocated ports - server: ${this.serverPort}, member: ${this.memberPort}`);

    // Path to run-kvstore.sh (relative to project root)
    const scriptPath = join(process.cwd(), '..', 'run-kvstore.sh');

    // Build arguments
    const args = [
      '--serverPort',
      this.serverPort.toString(),
      '--memberId',
      this.memberId,
      '--members',
      `${this.memberId}=tcp://127.0.0.1:${this.memberPort}`,
      '--rebuild',
    ];

    console.log(`[ServerManager] Spawning: ${scriptPath} ${args.join(' ')}`);

    // Spawn the server process
    this.process = spawn(scriptPath, args, {
      detached: false,
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    // Log server output for debugging
    this.process.stdout?.on('data', (data) => {
      const lines = data.toString().split('\n').filter((l: string) => l.trim());
      lines.forEach((line: string) => {
        console.log(`[SERVER] ${line}`);
      });
    });

    this.process.stderr?.on('data', (data) => {
      const lines = data.toString().split('\n').filter((l: string) => l.trim());
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
    if (!this.serverPort) {
      throw new Error('Server not started - call start() first');
    }
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
