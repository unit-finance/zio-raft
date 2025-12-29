// Type-safe event emitter for RaftClient
// Uses Node.js EventEmitter with TypeScript type safety

import { EventEmitter } from 'events';
import type { ClientEvent } from './eventTypes';

/**
 * Event map for type-safe event handling
 * Maps event types to their corresponding event data
 */
export interface RaftClientEventMap {
  stateChange: ClientEvent & { type: 'stateChange' };
  connectionAttempt: ClientEvent & { type: 'connectionAttempt' };
  connectionSuccess: ClientEvent & { type: 'connectionSuccess' };
  connectionFailure: ClientEvent & { type: 'connectionFailure' };
  messageReceived: ClientEvent & { type: 'messageReceived' };
  messageSent: ClientEvent & { type: 'messageSent' };
  requestTimeout: ClientEvent & { type: 'requestTimeout' };
  queryTimeout: ClientEvent & { type: 'queryTimeout' };
  sessionExpired: ClientEvent & { type: 'sessionExpired' };
  serverRequestReceived: ClientEvent & { type: 'serverRequestReceived' };
}

/**
 * Type-safe event emitter for RaftClient
 * Extends Node.js EventEmitter with typed event methods
 */
export class RaftClientEventEmitter extends EventEmitter {
  /**
   * Emit a typed event
   */
  emit<K extends keyof RaftClientEventMap>(
    event: K,
    data: RaftClientEventMap[K]
  ): boolean {
    return super.emit(event, data);
  }

  /**
   * Add a typed event listener
   */
  on<K extends keyof RaftClientEventMap>(
    event: K,
    listener: (data: RaftClientEventMap[K]) => void
  ): this {
    return super.on(event, listener);
  }

  /**
   * Add a typed event listener (one-time)
   */
  once<K extends keyof RaftClientEventMap>(
    event: K,
    listener: (data: RaftClientEventMap[K]) => void
  ): this {
    return super.once(event, listener);
  }

  /**
   * Remove a typed event listener
   */
  off<K extends keyof RaftClientEventMap>(
    event: K,
    listener: (data: RaftClientEventMap[K]) => void
  ): this {
    return super.off(event, listener);
  }

  /**
   * Remove all listeners for an event
   */
  removeAllListeners<K extends keyof RaftClientEventMap>(event?: K): this {
    return super.removeAllListeners(event);
  }
}

/**
 * Helper to emit a ClientEvent using the appropriate event name
 */
export function emitClientEvent(emitter: RaftClientEventEmitter, event: ClientEvent): void {
  switch (event.type) {
    case 'stateChange':
      emitter.emit('stateChange', event);
      break;
    case 'connectionAttempt':
      emitter.emit('connectionAttempt', event);
      break;
    case 'connectionSuccess':
      emitter.emit('connectionSuccess', event);
      break;
    case 'connectionFailure':
      emitter.emit('connectionFailure', event);
      break;
    case 'messageReceived':
      emitter.emit('messageReceived', event);
      break;
    case 'messageSent':
      emitter.emit('messageSent', event);
      break;
    case 'requestTimeout':
      emitter.emit('requestTimeout', event);
      break;
    case 'queryTimeout':
      emitter.emit('queryTimeout', event);
      break;
    case 'sessionExpired':
      emitter.emit('sessionExpired', event);
      break;
    case 'serverRequestReceived':
      emitter.emit('serverRequestReceived', event);
      break;
  }
}

