/**
 * Set command implementation
 */

import { Command } from 'commander';
import { KVClient } from '../kvClient.js';
import { validateKey, validateValue, parseEndpoints } from '../validation.js';
import { formatError, getExitCode } from '../formatting.js';

/**
 * Create the set command
 */
export function createSetCommand(): Command {
  const cmd = new Command('set');

  cmd
    .description('Set a key-value pair')
    .argument('<key>', 'Key to set')
    .argument('<value>', 'Value to set')
    .option('-e, --endpoints <endpoints>', 'Cluster endpoints', 'node-1=tcp://127.0.0.1:7001')
    .action(async (key: string, value: string, options: { endpoints: string }) => {
      let client: KVClient | null = null;

      try {
        // 1. Validate inputs (fast-fail)
        validateKey(key);
        validateValue(value);

        // 2. Parse endpoints
        const endpointConfig = parseEndpoints(options.endpoints);

        // 3. Create KVClient with timeouts
        client = new KVClient({
          endpoints: endpointConfig.endpoints,
          connectionTimeout: 5000,
          requestTimeout: 5000,
        });

        // 4. Connect to cluster
        await client.connect();

        // 5. Execute set command
        await client.set(key, value);

        // 6. Display success
        console.log('OK');

        // 7. Disconnect and exit
        await client.disconnect();
        process.exit(0);
      } catch (error) {
        // 8. Error handling
        console.error(formatError(error));

        // Ensure cleanup
        if (client) {
          try {
            await client.disconnect();
          } catch {
            // Ignore cleanup errors
          }
        }

        process.exit(getExitCode(error));
      }
    });

  return cmd;
}
