// Main RaftClient class - public API for applications
// Idiomatic TypeScript: EventEmitter-based, Promise API, classes

import { EventEmitter } from 'events';
import { ClientConfig, createConfig, ClientConfigInput } from './config';
import { ClientTransport } from './transport/transport';
import { ZmqTransport } from './transport/zmqTransport';
import { ClientState, StateManager, StateTransitionResult } from './state/clientState';
import { AsyncQueue } from './utils/asyncQueue';
import { mergeStreams } from './utils/streamMerger';
import { ValidationError } from './errors';
import { ServerRequest } from './protocol/messages';
import { ClientEvents } from './events/eventNames';

// ============================================================================
// Event Types
// ============================================================================

export interface ConnectedEvent {
  readonly sessionId: string;
  readonly endpoint: string;
  readonly timestamp: Date;
}

export interface DisconnectedEvent {
  readonly reason: 'network' | 'server-closed' | 'client-shutdown';
  readonly timestamp: Date;
}

export interface ReconnectingEvent {
  readonly attempt: number;
  readonly endpoint: string;
  readonly timestamp: Date;
}

export interface SessionExpiredEvent {
  readonly sessionId: string;
  readonly timestamp: Date;
}

// ============================================================================
// Stream Events (internal)
// ============================================================================

type StreamEvent = ServerMessageEvent | KeepAliveTickEvent | TimeoutCheckEvent | ActionEvent;

interface ServerMessageEvent {
  readonly type: 'ServerMsg';
  readonly message: import('./protocol/messages').ServerMessage;
}

interface KeepAliveTickEvent {
  readonly type: 'KeepAliveTick';
}

interface TimeoutCheckEvent {
  readonly type: 'TimeoutCheck';
}

interface ActionEvent {
  readonly type: 'Action';
  readonly action: ClientAction;
}

// ============================================================================
// Client Actions (internal)
// ============================================================================

type ClientAction = ConnectAction | SubmitCommandAction | SubmitQueryAction | DisconnectAction;

interface ConnectAction {
  readonly type: 'Connect';
  readonly resolve: () => void;
  readonly reject: (err: Error) => void;
}

interface SubmitCommandAction {
  readonly type: 'SubmitCommand';
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (err: Error) => void;
}

interface SubmitQueryAction {
  readonly type: 'SubmitQuery';
  readonly payload: Buffer;
  readonly resolve: (result: Buffer) => void;
  readonly reject: (err: Error) => void;
}

interface DisconnectAction {
  readonly type: 'Disconnect';
  readonly resolve: () => void;
  readonly reject: (err: Error) => void;
}

// ============================================================================
// RaftClient - Main Public API
// ============================================================================

/**
 * ZIO Raft TypeScript client
 * 
 * Provides Promise-based API with automatic reconnection and session management.
 * 
 * Usage:
 * ```typescript
 * const client = new RaftClient({
 *   clusterMembers: new Map([['node1', 'tcp://localhost:5555']]),
 *   capabilities: new Map([['version', '1.0.0']]),
 * });
 * 
 * await client.connect();
 * const result = await client.submitCommand(payload);
 * await client.disconnect();
 * ```
 */
export class RaftClient extends EventEmitter {
  private readonly config: ClientConfig;
  private readonly transport: ClientTransport;
  private readonly stateManager: StateManager;
  private readonly actionQueue: AsyncQueue<ClientAction>;
  private readonly serverRequestQueue: AsyncQueue<ServerRequest>;
  
  private currentState: ClientState;
  private eventLoopPromise: Promise<void> | null = null;
  private isShuttingDown = false;

  /**
   * Constructor - validates config, initializes state machine
   * Does NOT initiate connection (lazy initialization)
   * 
   * @param configInput - Client configuration
   * @param transport - Optional transport for testing (defaults to ZmqTransport)
   */
  constructor(configInput: ClientConfigInput, transport?: ClientTransport) {
    super();
    
    // Validate and apply defaults
    this.config = createConfig(configInput);
    
    // Use provided transport or default to production ZMQ transport
    this.transport = transport ?? new ZmqTransport();
    
    // Initialize state machine
    this.stateManager = new StateManager();
    this.currentState = {
      state: 'Disconnected',
      config: this.config,
    };
    
    // Create queues
    this.actionQueue = new AsyncQueue<ClientAction>();
    this.serverRequestQueue = new AsyncQueue<ServerRequest>();
  }

  /**
   * Explicitly connect to cluster and establish session
   * Returns when session is created
   */
  async connect(): Promise<void> {
    if (this.currentState.state !== 'Disconnected') {
      // Already connected or connecting
      return;
    }
    
    // Start event loop if not running
    if (this.eventLoopPromise === null) {
      this.eventLoopPromise = this.runEventLoop();
    }
    
    // Enqueue connect action and wait for completion
    return new Promise<void>((resolve, reject) => {
      this.actionQueue.offer({
        type: 'Connect',
        resolve,
        reject,
      });
    });
  }

  /**
   * Submit a write command
   * Returns when response received or timeout
   */
  async submitCommand(payload: Buffer): Promise<Buffer> {
    // Validate payload
    if (!Buffer.isBuffer(payload)) {
      throw new ValidationError('Command payload must be Buffer');
    }
    
    if (payload.length === 0) {
      throw new ValidationError('Command payload cannot be empty');
    }
    
    // Enqueue action and return promise
    return new Promise<Buffer>((resolve, reject) => {
      this.actionQueue.offer({
        type: 'SubmitCommand',
        payload,
        resolve,
        reject,
      });
    });
  }

  /**
   * Submit a read-only query
   * Returns when response received or timeout
   */
  async submitQuery(payload: Buffer): Promise<Buffer> {
    // Validate payload
    if (!Buffer.isBuffer(payload)) {
      throw new ValidationError('Query payload must be Buffer');
    }
    
    if (payload.length === 0) {
      throw new ValidationError('Query payload cannot be empty');
    }
    
    // Enqueue action and return promise
    return new Promise<Buffer>((resolve, reject) => {
      this.actionQueue.offer({
        type: 'SubmitQuery',
        payload,
        resolve,
        reject,
      });
    });
  }

  /**
   * Gracefully disconnect from cluster
   * Sends CloseSession message and cleans up resources
   */
  async disconnect(): Promise<void> {
    if (this.isShuttingDown) {
      return;
    }
    
    this.isShuttingDown = true;
    
    // Enqueue disconnect action
    return new Promise<void>((resolve, reject) => {
      this.actionQueue.offer({
        type: 'Disconnect',
        resolve,
        reject,
      });
    });
  }

  /**
   * Register handler for server-initiated requests
   * Handler is invoked for each ServerRequest received
   */
  onServerRequest(handler: (request: ServerRequest) => void): void {
    // Consume from server request queue in background
    (async () => {
      for await (const request of this.serverRequestQueue) {
        try {
          handler(request);
        } catch (err) {
          // Swallow errors in user handler to prevent crash
          this.emit(ClientEvents.ERROR, err instanceof Error ? err : new Error(String(err)));
        }
      }
    })().catch((err) => {
      this.emit(ClientEvents.ERROR, err instanceof Error ? err : new Error(String(err)));
    });
  }

  // ==========================================================================
  // Event Loop (Private)
  // ==========================================================================

  /**
   * Main event processing loop
   * Merges action queue, transport messages, and timers into unified stream
   */
  private async runEventLoop(): Promise<void> {
    try {
      // Create unified event stream
      const actionStream = this.createActionStream();
      const serverMessageStream = this.createServerMessageStream();
      const keepAliveStream = this.createKeepAliveStream();
      const timeoutCheckStream = this.createTimeoutCheckStream();
      
      const unifiedStream = mergeStreams<StreamEvent>(
        actionStream,
        serverMessageStream,
        keepAliveStream,
        timeoutCheckStream
      );
      
      // Process events
      for await (const event of unifiedStream) {
        await this.processEvent(event);
        
        // Exit loop if shutting down and disconnected
        if (this.isShuttingDown && this.currentState.state === 'Disconnected') {
          break;
        }
      }
    } catch (err) {
      this.emit(ClientEvents.ERROR, err instanceof Error ? err : new Error(String(err)));
    } finally {
      await this.cleanup();
    }
  }

  /**
   * Process a single event through state machine
   */
  private async processEvent(event: StreamEvent): Promise<void> {
    try {
      const result: StateTransitionResult = await this.stateManager.handleEvent(
        this.currentState,
        event
      );
      
      // Update state
      this.currentState = result.newState;
      
      // Send messages via transport
      if (result.messagesToSend) {
        for (const message of result.messagesToSend) {
          await this.transport.sendMessage(message);
        }
      }
      
      // Emit client events
      if (result.eventsToEmit) {
        for (const evt of result.eventsToEmit) {
          this.emitClientEvent(evt);
        }
      }
    } catch (err) {
      this.emit(ClientEvents.ERROR, err instanceof Error ? err : new Error(String(err)));
    }
  }

  /**
   * Emit typed client event
   */
  private emitClientEvent(evt: any): void {
    switch (evt.type) {
      case 'connected':
        this.emit(ClientEvents.CONNECTED, {
          sessionId: evt.sessionId,
          endpoint: evt.endpoint,
          timestamp: new Date(),
        } as ConnectedEvent);
        break;
      
      case 'disconnected':
        this.emit(ClientEvents.DISCONNECTED, {
          reason: evt.reason,
          timestamp: new Date(),
        } as DisconnectedEvent);
        break;
      
      case 'reconnecting':
        this.emit(ClientEvents.RECONNECTING, {
          attempt: evt.attempt,
          endpoint: evt.endpoint,
          timestamp: new Date(),
        } as ReconnectingEvent);
        break;
      
      case 'sessionExpired':
        this.emit(ClientEvents.SESSION_EXPIRED, {
          sessionId: evt.sessionId,
          timestamp: new Date(),
        } as SessionExpiredEvent);
        break;
      
      case 'serverRequestReceived':
        // Route ServerRequest to queue for onServerRequest() handlers
        // This is the critical missing piece that connects state machine events to user handlers
        this.serverRequestQueue.offer(evt.request);
        break;
    }
  }

  // ==========================================================================
  // Stream Creators (Private)
  // ==========================================================================

  private async *createActionStream(): AsyncIterable<StreamEvent> {
    for await (const action of this.actionQueue) {
      yield { type: 'Action', action };
    }
  }

  private async *createServerMessageStream(): AsyncIterable<StreamEvent> {
    for await (const message of this.transport.incomingMessages) {
      yield { type: 'ServerMsg', message };
    }
  }

  private async *createKeepAliveStream(): AsyncIterable<StreamEvent> {
    // This would be a timer-based stream in production
    // Placeholder implementation
    while (!this.isShuttingDown) {
      await new Promise((resolve) => setTimeout(resolve, this.config.keepAliveInterval));
      yield { type: 'KeepAliveTick' };
    }
  }

  private async *createTimeoutCheckStream(): AsyncIterable<StreamEvent> {
    // Check for timeouts every 100ms
    while (!this.isShuttingDown) {
      await new Promise((resolve) => setTimeout(resolve, 100));
      yield { type: 'TimeoutCheck' };
    }
  }

  // ==========================================================================
  // Lifecycle Management (Private)
  // ==========================================================================

  private async cleanup(): Promise<void> {    
    // Disconnect transport
    await this.transport.disconnect();
    
    // Clear queues
    this.actionQueue.close();
    this.serverRequestQueue.close();
  }

  // ==========================================================================
  // TypeScript Event Emitter Type Overrides
  // ==========================================================================

  on(event: typeof ClientEvents.CONNECTED, listener: (evt: ConnectedEvent) => void): this;
  on(event: typeof ClientEvents.DISCONNECTED, listener: (evt: DisconnectedEvent) => void): this;
  on(event: typeof ClientEvents.RECONNECTING, listener: (evt: ReconnectingEvent) => void): this;
  on(event: typeof ClientEvents.SESSION_EXPIRED, listener: (evt: SessionExpiredEvent) => void): this;
  on(event: typeof ClientEvents.ERROR, listener: (err: Error) => void): this;
  on(event: string, listener: (...args: any[]) => void): this {
    return super.on(event, listener);
  }

  emit(event: typeof ClientEvents.CONNECTED, evt: ConnectedEvent): boolean;
  emit(event: typeof ClientEvents.DISCONNECTED, evt: DisconnectedEvent): boolean;
  emit(event: typeof ClientEvents.RECONNECTING, evt: ReconnectingEvent): boolean;
  emit(event: typeof ClientEvents.SESSION_EXPIRED, evt: SessionExpiredEvent): boolean;
  emit(event: typeof ClientEvents.ERROR, err: Error): boolean;
  emit(event: string, ...args: any[]): boolean {
    return super.emit(event, ...args);
  }
}

