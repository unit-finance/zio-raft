/**
 * Debug logging utility
 *
 * Centralized debug logging that can be easily enabled/disabled.
 * Set DEBUG_ENABLED to true to enable debug logs during development.
 */

const DEBUG_ENABLED = false;

/**
 * Log a debug message if debug mode is enabled
 */
export function debugLog(message: string, ...args: any[]): void {
  if (DEBUG_ENABLED) {
    console.log(`[DEBUG] ${message}`, ...args);
  }
}

/**
 * Log a debug message with no prefix (for raw output)
 */
export function debugLogRaw(...args: any[]): void {
  if (DEBUG_ENABLED) {
    console.log(...args);
  }
}
