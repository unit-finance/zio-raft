// State Manager - orchestrates state handlers

import { DisconnectedStateHandler } from './disconnectedState';
import { ConnectingNewSessionStateHandler } from './connectingNewSessionState';
import { ConnectingExistingSessionStateHandler } from './connectingExistingSessionState';
import { ConnectedStateHandler } from './connectedState';
import { ClientState, StreamEvent, StateTransitionResult } from './types';

/**
 * State manager - orchestrates state handlers
 * Dispatches events to appropriate handler based on current state
 */
export class StateManager {
  private readonly disconnectedHandler = new DisconnectedStateHandler();
  private readonly connectingNewSessionHandler = new ConnectingNewSessionStateHandler();
  private readonly connectingExistingSessionHandler = new ConnectingExistingSessionStateHandler();
  private readonly connectedHandler = new ConnectedStateHandler();

  /**
   * Handle an event in the current state
   * Routes to appropriate state handler
   */
  async handleEvent(state: ClientState, event: StreamEvent): Promise<StateTransitionResult> {
    switch (state.state) {
      case 'Disconnected':
        return this.disconnectedHandler.handle(state, event);

      case 'ConnectingNewSession':
        return this.connectingNewSessionHandler.handle(state, event);

      case 'ConnectingExistingSession':
        return this.connectingExistingSessionHandler.handle(state, event);

      case 'Connected':
        return this.connectedHandler.handle(state, event);

      default:
        // Exhaustive check
        const _exhaustive: never = state;
        throw new Error(`Unknown state: ${(_exhaustive as ClientState).state}`);
    }
  }
}
