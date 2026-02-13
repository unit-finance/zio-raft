/**
 * Port Management Utilities
 * ==========================
 *
 * Utilities for waiting for ports to become ready.
 * Used by ServerManager to wait for test servers.
 */

import { Socket } from 'net';

/**
 * Wait for a port to accept connections
 *
 * @param port - Port number to wait for
 * @param timeout - Timeout in milliseconds (default: 60000 = 60 seconds)
 * @returns Promise that resolves when port is ready
 * @throws Error if timeout is reached before port is ready
 */
export async function waitForPort(port: number, timeout: number = 60000): Promise<void> {
  const startTime = Date.now();
  const retryInterval = 500;

  while (Date.now() - startTime < timeout) {
    const isReady = await new Promise<boolean>((resolve) => {
      const socket = new Socket();

      socket.setTimeout(1000);

      socket.once('connect', () => {
        socket.destroy();
        resolve(true);
      });

      socket.once('timeout', () => {
        socket.destroy();
        resolve(false);
      });

      socket.once('error', () => {
        socket.destroy();
        resolve(false);
      });

      socket.connect(port, '127.0.0.1');
    });

    if (isReady) {
      return;
    }

    await new Promise((resolve) => setTimeout(resolve, retryInterval));
  }

  throw new Error(`Timeout waiting for port ${port} to be ready after ${timeout}ms`);
}
