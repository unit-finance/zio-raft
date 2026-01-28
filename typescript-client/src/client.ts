// Main RaftClient class - public API for applications
// Idiomatic TypeScript: EventEmitter-based, Promise API, classes

import { EventEmitter } from 'events';
import { ClientConfig, createConfig, ClientConfigInput } from './config';
import { ClientTransport } from './transport/transport';
import {
  ClientState,
  StateManager,
  StateTransitionResult,
  StreamEvent,
  ClientAction,
  ClientEventData,
  ConnectedState,
  ConnectingExistingSessionState,
} from './state/clientState';
import { AsyncQueue } from './utils/asyncQueue';
import { mergeStreams } from './utils/streamMerger';
import { ValidationError } from './errors';
import { ServerRequest } from './protocol/messages';
import { ClientEvents } from './events/eventNames';
import { MemberId } from './types';

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
// Event Listener Types (for type-safe EventEmitter overloads)
// ============================================================================

type EventListener =
  | { event: typeof ClientEvents.CONNECTED; listener: (evt: ConnectedEvent) => void }
  | { event: typeof ClientEvents.DISCONNECTED; listener: (evt: DisconnectedEvent) => void }
  | { event: typeof ClientEvents.RECONNECTING; listener: (evt: ReconnectingEvent) => void }
  | { event: typeof ClientEvents.SESSION_EXPIRED; listener: (evt: SessionExpiredEvent) => void }
  | { event: typeof ClientEvents.ERROR; listener: (err: Error) => void };

type EventListenerFunction = EventListener['listener'];

// Union of all possible event argument types
type EventArgs = ConnectedEvent | DisconnectedEvent | ReconnectingEvent | SessionExpiredEvent | Error;

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
 * import { RaftClient, ZmqTransport } from '@zio-raft/typescript-client';
 *
 * const client = new RaftClient(
 *   {
 *     clusterMembers: new Map([['node1', 'tcp://localhost:5555']]),
 *     capabilities: new Map([['version', '1.0.0']]),
 *   },
 *   new ZmqTransport()
 * );
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
  private reconnectAttempt = 0; // Tracks reconnection attempts for RECONNECTING event

  private serverRequestHandler: ((request: ServerRequest) => void) | null = null;
  private serverRequestLoopPromise: Promise<void> | null = null;
  private stopServerRequestLoop = false;

  /**
   * Constructor - validates config, initializes state machine
   * Does NOT initiate connection (lazy initialization)
   *
   * @param configInput - Client configuration
   * @param transport - Transport implementation (e.g., ZmqTransport for production, MockTransport for testing)
   */
  constructor(configInput: ClientConfigInput, transport: ClientTransport) {
    super();

    // Validate and apply defaults
    this.config = createConfig(configInput);

    // Use provided transport (dependency injection)
    this.transport = transport;

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

    // Enqueue disconnect action and wait for event loop to complete
    return new Promise<void>((resolve, _) => {
      // Store callbacks so we can call them after cleanup
      this.disconnectResolve = resolve;

      this.actionQueue.offer({
        type: 'Disconnect',
        resolve: () => {}, // No-op, actual resolve called after cleanup
        reject: () => {}, // No-op, actual reject called after cleanup
      });
    });
  }

  /**
   * Register handler for server-initiated requests
   * Handler is invoked for each ServerRequest received
   *
   * @throws Error if handler already registered
   */
  onServerRequest(handler: (request: ServerRequest) => void): void {
    if (this.serverRequestHandler !== null) {
      throw new Error('Server request handler already registered. Call removeServerRequestHandler() first.');
    }

    this.serverRequestHandler = handler;
    this.stopServerRequestLoop = false;
    this.startServerRequestLoop();
  }

  /**
   * Remove the registered server request handler
   * Stops the background consumer loop
   */
  removeServerRequestHandler(): void {
    this.serverRequestHandler = null;
    this.stopServerRequestLoop = true;
  }

  /**
   * Start background loop to consume server requests
   * Only one loop runs at a time
   */
  private startServerRequestLoop(): void {
    this.serverRequestLoopPromise = (async () => {
      for await (const request of this.serverRequestQueue) {
        if (this.stopServerRequestLoop || this.serverRequestHandler === null) {
          break;
        }
        try {
          this.serverRequestHandler(request);
        } catch (err) {
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

      const result: StateTransitionResult = await this.stateManager.handleEvent(this.currentState, event);

      const newState = result.newState;

      // Handle transport connection changes based on state transitions
      await this.handleTransportConnection(oldState, newState);

      // Update state
      this.currentState = result.newState;

      // Send messages via transport
      if (result.messagesToSend && result.messagesToSend.length > 0) {
        for (let i = 0; i < result.messagesToSend.length; i++) {
          const message = result.messagesToSend[i]!;
          await this.transport.sendMessage(message);
        }
      } else {
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

    // Check if we're transitioning TO a connecting state FROM a different state
    const isEnteringConnectingState =
      (newState.state === 'ConnectingNewSession' || newState.state === 'ConnectingExistingSession') &&
      oldState.state !== newState.state;

    if (!isEnteringConnectingState) {
      return;
    }

    const address = newState.config.clusterMembers.get(newState.currentMemberId);
    if (!address) {
      throw new Error(`No address found for member ${MemberId.unwrap(newState.currentMemberId)}`);
    }

    // Check if we're switching to a different member (need to reconnect)
    if (
      oldState.state === 'ConnectingNewSession' ||
      oldState.state === 'ConnectingExistingSession' ||
      oldState.state === 'Connected'
    ) {
      const oldMemberId = oldState.currentMemberId;
      if (MemberId.unwrap(oldMemberId) !== MemberId.unwrap(newState.currentMemberId)) {
        // Switching to different member - disconnect first
        await this.transport.disconnect();
      }
    }

    // Connect to the target member
    await this.transport.connect(address);
  }

  /**
   * Emit typed client event
   * Translates low-level state machine events (ClientEventData) to public API events
   */
  private emitClientEvent(evt: ClientEventData): void {
    switch (evt.type) {
      case 'stateChange':
        this.handleStateChangeEvent(evt.oldState, evt.newState);
        break;

      case 'sessionExpired': {
        // Get sessionId from current state if available
        const sessionId = this.getSessionIdFromCurrentState();
        this.emit(ClientEvents.SESSION_EXPIRED, {
          sessionId,
          timestamp: new Date(),
        } as SessionExpiredEvent);
        break;
      }

      case 'serverRequestReceived':
        // Route ServerRequest to queue for onServerRequest() handlers
        this.serverRequestQueue.offer(evt.request);
        break;

      case 'connectionAttempt':
        // Track endpoint for reconnection events (used in handleStateChangeEvent)
        break;

      case 'connectionSuccess':
      case 'connectionFailure':
      case 'requestTimeout':
        // Internal events - logged for debugging
        break;
    }
  }

  /**
   * Handle state change events and emit appropriate public events
   *
   */
  private handleStateChangeEvent(oldState: ClientState['state'], newState: ClientState['state']): void {

    // Emit CONNECTED when transitioning to Connected state
    if (newState === 'Connected' && oldState !== 'Connected') {
      this.reconnectAttempt = 0; // Reset reconnect counter on successful connection
      const state = this.currentState as ConnectedState;
      const endpoint = state.config.clusterMembers.get(state.currentMemberId) ?? '';
      this.emit(ClientEvents.CONNECTED, {
        sessionId: state.sessionId,
        endpoint,
        timestamp: new Date(),
      } as ConnectedEvent);
    }

    // Emit DISCONNECTED when transitioning from Connected to Disconnected
    if (newState === 'Disconnected' && oldState === 'Connected') {
      const reason = this.isShuttingDown ? 'client-shutdown' : 'network';
      this.emit(ClientEvents.DISCONNECTED, {
        reason,
        timestamp: new Date(),
      } as DisconnectedEvent);
    }

    // Emit RECONNECTING when transitioning from Connected to ConnectingExistingSession
    if (newState === 'ConnectingExistingSession' && oldState === 'Connected') {
      this.reconnectAttempt++;
      const state = this.currentState as ConnectingExistingSessionState;
      const endpoint = state.config.clusterMembers.get(state.currentMemberId) ?? '';
      this.emit(ClientEvents.RECONNECTING, {
        attempt: this.reconnectAttempt,
        endpoint,
        timestamp: new Date(),
      } as ReconnectingEvent);
    }
  }

  /**
   * Get session ID from current state if available
   */
  private getSessionIdFromCurrentState(): string {
    if (this.currentState.state === 'Connected') {
      return this.currentState.sessionId;
    }
    if (this.currentState.state === 'ConnectingExistingSession') {
      return this.currentState.sessionId;
    }
    return '';
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
    for await (const message of this.transport.incomingMessages) {
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
    // Stop server request loop
    this.stopServerRequestLoop = true;

    // Close queues first to unblock any pending iterations
    this.actionQueue.close();
    this.serverRequestQueue.close();

    // Wait for server request loop to complete
    if (this.serverRequestLoopPromise) {
      await this.serverRequestLoopPromise;
      this.serverRequestLoopPromise = null;
    }

    // Disconnect transport
    await this.transport.disconnect();
  }

  // ==========================================================================
  // TypeScript Event Emitter Type Overrides
  // ==========================================================================

  on(event: typeof ClientEvents.CONNECTED, listener: (evt: ConnectedEvent) => void): this;
  on(event: typeof ClientEvents.DISCONNECTED, listener: (evt: DisconnectedEvent) => void): this;
  on(event: typeof ClientEvents.RECONNECTING, listener: (evt: ReconnectingEvent) => void): this;
  on(event: typeof ClientEvents.SESSION_EXPIRED, listener: (evt: SessionExpiredEvent) => void): this;
  on(event: typeof ClientEvents.ERROR, listener: (err: Error) => void): this;
  on(event: string | symbol, listener: EventListenerFunction): this {
    return super.on(event, listener);
  }

  emit(event: typeof ClientEvents.CONNECTED, evt: ConnectedEvent): boolean;
  emit(event: typeof ClientEvents.DISCONNECTED, evt: DisconnectedEvent): boolean;
  emit(event: typeof ClientEvents.RECONNECTING, evt: ReconnectingEvent): boolean;
  emit(event: typeof ClientEvents.SESSION_EXPIRED, evt: SessionExpiredEvent): boolean;
  emit(event: typeof ClientEvents.ERROR, err: Error): boolean;
  emit(event: string | symbol, ...args: EventArgs[]): boolean {
    return super.emit(event, ...args);
  }
}
