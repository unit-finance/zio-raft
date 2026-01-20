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
import { ServerRequest, ServerMessage } from './protocol/messages';
import { ClientEvents } from './events/eventNames';
import { MemberId } from './types';
import { debugLog } from './utils/debug';

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
  readonly message: ServerMessage;
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
  private disconnectResolve: (() => void) | null = null;

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
    debugLog('RaftClient.connect() called, currentState:', this.currentState.state);
    
    if (this.currentState.state !== 'Disconnected') {
      // Already connected or connecting
      debugLog('Already connected/connecting, returning early');
      return;
    }

    // Start event loop if not running
    if (this.eventLoopPromise === null) {
      debugLog('Starting event loop...');
      this.eventLoopPromise = this.runEventLoop();
    }
    
    // Enqueue connect action and wait for completion
    debugLog('Enqueuing Connect action to action queue');
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
    
    // Enqueue disconnect action and wait for event loop to complete
    return new Promise<void>((resolve, _) => {
      // Store callbacks so we can call them after cleanup
      this.disconnectResolve = resolve;
      
      this.actionQueue.offer({
        type: 'Disconnect',
        resolve: () => {}, // No-op, actual resolve called after cleanup
        reject: () => {},  // No-op, actual reject called after cleanup
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
    debugLog('runEventLoop() starting...');
    try {
      // Create unified event stream
      debugLog('Creating action stream...');
      const actionStream = this.createActionStream();
      
      debugLog('Creating server message stream...');
      const serverMessageStream = this.createServerMessageStream();
      
      debugLog('Creating keep-alive stream...');
      const keepAliveStream = this.createKeepAliveStream();
      
      debugLog('Creating timeout check stream...');
      const timeoutCheckStream = this.createTimeoutCheckStream();
      
      debugLog('Merging streams...');
      const unifiedStream = mergeStreams<StreamEvent>(
        actionStream,
        serverMessageStream,
        keepAliveStream,
        timeoutCheckStream
      );
      
      debugLog('Starting event loop iteration...');
      // Process events
      for await (const event of unifiedStream) {
        debugLog('Received event:', event.type);
        await this.processEvent(event);
        
        // Exit loop if shutting down and disconnected
        if (this.isShuttingDown && this.currentState.state === 'Disconnected') {
          break;
        }
      }
    } catch (err) {
      debugLog('runEventLoop error:', err);
      this.emit(ClientEvents.ERROR, err instanceof Error ? err : new Error(String(err)));
    } finally {
      debugLog('runEventLoop cleanup...');
      await this.cleanup();
      
      // Resolve disconnect promise if waiting
      if (this.disconnectResolve) {
        this.disconnectResolve();
        this.disconnectResolve = null;
      }
    }
  }

  /**
   * Process a single event through state machine
   */
  private async processEvent(event: StreamEvent): Promise<void> {
    try {
      const oldState = this.currentState;
      debugLog('processEvent - oldState:', oldState.state, 'event:', event.type);
      
      const result: StateTransitionResult = await this.stateManager.handleEvent(
        this.currentState,
        event
      );
      
      const newState = result.newState;
      debugLog('processEvent - newState:', newState.state);
      
      // Handle transport connection changes based on state transitions
      debugLog('Calling handleTransportConnection...');
      await this.handleTransportConnection(oldState, newState);
      debugLog('handleTransportConnection completed');
      
      // Update state
      this.currentState = result.newState;
      
      // Send messages via transport
      if (result.messagesToSend && result.messagesToSend.length > 0) {
        debugLog('processEvent - sending', result.messagesToSend.length, 'messages');
        for (let i = 0; i < result.messagesToSend.length; i++) {
          const message = result.messagesToSend[i]!;
          debugLog('Sending message', i+1, 'of', result.messagesToSend.length);
          await this.transport.sendMessage(message);
          debugLog('Message', i+1, 'sent successfully');
        }
      } else {
        debugLog('processEvent - no messages to send');
      }
      
      // Emit client events
      if (result.eventsToEmit) {
        for (const evt of result.eventsToEmit) {
          this.emitClientEvent(evt);
        }
      }
      
      // If we're shutting down and now disconnected, close action queue to exit event loop
      if (this.isShuttingDown && this.currentState.state === 'Disconnected') {
        this.actionQueue.close();
      }
      
    } catch (err) {
      this.emit(ClientEvents.ERROR, err instanceof Error ? err : new Error(String(err)));
    }
  }

  /**
   * Handle transport connection/disconnection based on state transitions
   */
  private async handleTransportConnection(oldState: ClientState, newState: ClientState): Promise<void> {
    debugLog('handleTransportConnection - oldState:', oldState.state, 'newState:', newState.state);
    
    // Check if we're transitioning TO a connecting state FROM a different state
    const isEnteringConnectingState = 
      (newState.state === 'ConnectingNewSession' || newState.state === 'ConnectingExistingSession') &&
      oldState.state !== newState.state;
    
    if (!isEnteringConnectingState) {
      debugLog('Not entering connecting state, no connection change needed');
      return;
    }
    
    debugLog('Entering connecting state, need to establish connection');
    const address = newState.config.clusterMembers.get(newState.currentMemberId);
    if (!address) {
      throw new Error(`No address found for member ${MemberId.unwrap(newState.currentMemberId)}`);
    }
    debugLog('Target address:', address);
    
    // Check if we're switching to a different member (need to reconnect)
    if (oldState.state === 'ConnectingNewSession' || 
        oldState.state === 'ConnectingExistingSession' || 
        oldState.state === 'Connected') {
      const oldMemberId = oldState.currentMemberId;
      if (MemberId.unwrap(oldMemberId) !== MemberId.unwrap(newState.currentMemberId)) {
        // Switching to different member - disconnect first
        debugLog('Switching members, disconnecting first...');
        await this.transport.disconnect();
      }
    }
    
    // Connect to the target member
    debugLog('Calling transport.connect()...');
    await this.transport.connect(address);
    debugLog('transport.connect() completed successfully');
    

    // TODO (eran): think about this comment... does it make sense? 
    
    // Note: We don't disconnect here when transitioning to Disconnected
    // because the event loop is still running and needs the transport.
    // Disconnect happens in cleanup() after the event loop exits.
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
      
      // After yielding an action, check if we should exit
      // This ensures all queued actions are processed before exiting
      if (this.isShuttingDown && this.currentState.state === 'Disconnected') {
        break;
      }
    }
  }

  private async *createServerMessageStream(): AsyncIterable<StreamEvent> {
    debugLog('createServerMessageStream - About to access transport.incomingMessages');
    for await (const message of this.transport.incomingMessages) {
      debugLog('createServerMessageStream - Received message from transport');
      yield { type: 'ServerMsg', message };
      
      // Check if we should exit after processing a server message
      if (this.isShuttingDown && this.currentState.state === 'Disconnected') {
        break;
      }
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

