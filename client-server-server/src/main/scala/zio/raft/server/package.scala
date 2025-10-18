package zio.raft

import zio.*
import zio.stream.*
import zio.zmq.RoutingId

/**
 * Server-side implementation for ZIO Raft client-server communication.
 * 
 * This package provides:
 * - Leader-aware server implementation with automatic redirection
 * - Session management with durable state and local connection tracking
 * - ZStream-based action forwarding to the Raft state machine
 * - ZeroMQ transport integration with CLIENT/SERVER pattern
 * - Keep-alive monitoring and session cleanup
 */
package object server {

  // Server configuration defaults
  val DEFAULT_SESSION_TIMEOUT: Duration = 30.seconds
  val DEFAULT_LEADER_TRANSITION_TIMEOUT: Duration = 60.seconds
  val DEFAULT_CLEANUP_INTERVAL: Duration = 1.second
  val DEFAULT_KEEP_ALIVE_TIMEOUT: Duration = 90.seconds

  // Note: ServerAction, LocalAction, and ConnectionState types are defined in their respective modules:
  // - ServerAction types: ActionStream.scala
  // - LocalAction types: ActionStream.scala  
  // - ConnectionState types: SessionManager.scala
}
