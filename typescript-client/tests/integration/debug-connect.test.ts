/**
 * Minimal test to debug connection issue
 */

import { describe, it, expect } from 'vitest';
import { RaftClient } from '../../src/client';
import { MockTransport } from '../../src/testing/MockTransport';
import { MemberId } from '../../src/types';

describe('Debug Connect', () => {
  it('should connect successfully', async () => {
    console.log('[DEBUG] Creating MockTransport');
    const mockTransport = new MockTransport();
    mockTransport.autoRespondToCreateSession = false; // Manual control
    
    console.log('[DEBUG] Creating RaftClient');
    const client = new RaftClient({
      clusterMembers: new Map([[MemberId.fromString('node1'), 'tcp://localhost:5555']]),
      capabilities: new Map([['version', '1.0.0']]),
      connectionTimeout: 5000,
      requestTimeout: 10000,
    }, mockTransport);
    
    console.log('[DEBUG] Calling client.connect()');
    const connectPromise = client.connect();
    console.log('[DEBUG] Connect promise created');
    
    // Wait a bit
    await new Promise(resolve => setTimeout(resolve, 500));
    
    console.log('[DEBUG] After 500ms');
    console.log('[DEBUG] Sent messages count:', mockTransport.sentMessages.length);
    console.log('[DEBUG] Sent message types:', mockTransport.sentMessages.map(m => m.type));
    
    if (mockTransport.sentMessages.length > 0) {
      console.log('[DEBUG] CreateSession found! Injecting SessionCreated...');
      const createSession = mockTransport.getSentMessagesOfType('CreateSession')[0];
      
      mockTransport.injectMessage({
        type: 'SessionCreated',
        sessionId: 'debug-session-123',
        nonce: createSession.nonce,
      });
      
      console.log('[DEBUG] SessionCreated injected. Waiting for connect to resolve...');
      
      // Wait for connect with timeout
      const timeout = new Promise((_, reject) => 
        setTimeout(() => reject(new Error('Connect timeout')), 2000)
      );
      
      try {
        await Promise.race([connectPromise, timeout]);
        console.log('[DEBUG] ✅ Connect resolved successfully!');
      } catch (err) {
        console.log('[DEBUG] ❌ Connect failed:', err);
        throw err;
      }
    } else {
      console.log('[DEBUG] ❌ No CreateSession sent!');
      throw new Error('CreateSession not sent');
    }
    
    await client.disconnect();
  }, 30000);
});
