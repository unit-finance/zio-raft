/**
 * Port Management Utilities
 * ==========================
 *
 * Utilities for finding available ports and waiting for ports to become ready.
 * Used by ServerManager to allocate ports for test servers.
 */

import { createServer, Socket } from 'net';

/**
 * Check if a port is free (not in use)
 *
 * @param port - Port number to check
 * @returns Promise that resolves to true if port is free, false otherwise
 */
async function isPortFree(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const server = createServer();

    server.once('error', () => {
      resolve(false);
    });

    server.once('listening', () => {
      server.close();
      resolve(true);
    });

    server.listen(port);
  });
}

/**
 * Find an available port in the given range
 *
 * @param start - Start of port range (default: 8000)
 * @param end - End of port range (default: 9000)
 * @returns Promise that resolves to an available port number
 * @throws Error if no available port is found in the range
 */
export async function findAvailablePort(start: number = 8000, end: number = 9000): Promise<number> {
  for (let port = start; port <= end; port++) {
    if (await isPortFree(port)) {
      return port;
    }
  }
  throw new Error(`No available port found in range ${start}-${end}`);
}

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
