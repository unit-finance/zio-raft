// Event name constants for type-safe event handling
// Provides compile-time and runtime safety for event names

/**
 * Event name constants for RaftClient events
 * 
 * Usage:
 * ```typescript
 * client.on(ClientEvents.CONNECTED, (evt) => {
 *   console.log(evt.sessionId);
 * });
 * ```
 */
export const ClientEvents = {
  /** Emitted when client successfully connects and establishes a session */
  CONNECTED: 'connected',
  
  /** Emitted when client disconnects from the cluster */
  DISCONNECTED: 'disconnected',
  
  /** Emitted when client is attempting to reconnect */
  RECONNECTING: 'reconnecting',
  
  /** Emitted when the session expires on the server */
  SESSION_EXPIRED: 'sessionExpired',
  
  /** Emitted when server sends a request to the client (internal - routed to queue) */
  SERVER_REQUEST_RECEIVED: 'serverRequestReceived',
  
  /** Emitted when an error occurs */
  ERROR: 'error',
} as const;

/**
 * Type representing all valid client event names
 */
export type ClientEventName = typeof ClientEvents[keyof typeof ClientEvents];

/**
 * Type guard to check if a string is a valid client event name
 */
export function isClientEventName(name: string): name is ClientEventName {
  return Object.values(ClientEvents).includes(name as ClientEventName);
}
