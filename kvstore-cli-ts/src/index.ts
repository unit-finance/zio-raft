#!/usr/bin/env node

/**
 * KVStore CLI - Main entry point
 */

import { Command } from 'commander';
import { createSetCommand } from './commands/set.js';
import { createGetCommand } from './commands/get.js';
import { createWatchCommand } from './commands/watch.js';

/**
 * Main CLI program
 */
function main() {
  const program = new Command();

  program.name('kvstore').version('0.1.0').description('KVStore CLI - interact with a distributed key-value store');

  // Register commands
  program.addCommand(createSetCommand());
  program.addCommand(createGetCommand());
  program.addCommand(createWatchCommand());

  // Parse arguments
  program.parse(process.argv);

  // Show help if no command provided
  if (process.argv.slice(2).length === 0) {
    program.outputHelp();
  }
}

main();
