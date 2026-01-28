/**
 * Get command implementation
 */

import { Command } from 'commander';
import { KVClient } from '../kvClient.js';
import { validateKey, parseEndpoints } from '../validation.js';
import { formatError, getExitCode } from '../formatting.js';

/**
 * Create the get command
 */
export function createGetCommand(): Command {
  const cmd = new Command('get');

  cmd
    .description('Get a value by key')
    .argument('<key>', 'Key to get')
    .option('-e, --endpoints <endpoints>', 'Cluster endpoints', 'node-1=tcp://127.0.0.1:7001')
    .action(async (key: string, options: { endpoints: string }) => {
      let client: KVClient | null = null;

      try {
        // 1. Validate key
        validateKey(key);

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

        // 5. Execute get query
        const value = await client.get(key);

        // 6. Display result
        if (value !== null) {
          console.log(`${key} = ${value}`);
        } else {
          console.log(`${key} = <none>`);
        }

        // 7. Disconnect and exit
        await client?.disconnect();
        process.exit(0);
      } catch (error) {
        // 8. Error handling
        console.error(formatError(error));

        // Ensure cleanup
        await client?.disconnect();

        process.exit(getExitCode(error));
      }
    });

  return cmd;
}
