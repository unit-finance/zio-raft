/**
 * Watch command implementation
 */

import { Command } from 'commander';
import { KVClient } from '../kvClient.js';
import { validateKey, parseEndpoints } from '../validation.js';
import { formatError, formatNotification, getExitCode } from '../formatting.js';

/**
 * Create the watch command
 */
export function createWatchCommand(): Command {
  const cmd = new Command('watch');

  cmd
    .description('Watch a key for updates')
    .argument('<key>', 'Key to watch')
    .option('-e, --endpoints <endpoints>', 'Cluster endpoints', 'node-1=tcp://127.0.0.1:7001')
    .action(async (key: string, options: { endpoints: string }) => {
      let client: KVClient | null = null;
      let isShuttingDown = false;

      // Signal handler for graceful shutdown
      const shutdownHandler = async (): Promise<void> => {
        if (isShuttingDown) {
          return;
        }

        isShuttingDown = true;
        console.error('\nShutting down...');

        if (client) {
          try {
            await client.disconnect();
          } catch (cleanupError) {
            // Log cleanup errors for debugging
            console.error('Warning: Failed to disconnect client during shutdown:', cleanupError);
          }
        }

        process.exit(130); // Standard SIGINT exit code
      };

      // Register signal handlers
      process.on('SIGINT', shutdownHandler);
      process.on('SIGTERM', shutdownHandler);

      try {
        // 1. Validate key
        validateKey(key);

        // 2. Parse endpoints
        const endpointConfig = parseEndpoints(options.endpoints);

        // 3. Create KVClient with keepalive
        client = new KVClient({
          endpoints: endpointConfig.endpoints,
          connectionTimeout: 5000,
          requestTimeout: 5000,
        });

        // 4. Connect to cluster
        await client.connect();

        // 5. Register watch
        await client.watch(key);

        // 6. Display watching message
        console.log(`watching ${key} - press Ctrl+C to stop`);
        console.log(); // Empty line

        // 7. Iterate over notifications
        for await (const notification of client.notifications()) {
          // 8. Filter by key (only display matching key)
          if (notification.key === key) {
            // 9. Format and display notification
            console.log(formatNotification(notification));
          }
        }

        // 10. Normal exit (stream ended)
        await client.disconnect();
        process.exit(0);
      } catch (error) {
        // Error handling
        console.error(formatError(error));

        // Ensure cleanup
        if (client) {
          try {
            await client.disconnect();
          } catch (cleanupError) {
            // Log cleanup errors for debugging
            console.error('Warning: Failed to disconnect client during cleanup:', cleanupError);
          }
        }

        process.exit(getExitCode(error));
      }
    });

  return cmd;
}
