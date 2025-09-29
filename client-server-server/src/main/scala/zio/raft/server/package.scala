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

  // Server action types for Raft forwarding
  sealed trait ServerAction
  case class CreateSessionAction(sessionId: protocol.SessionId, capabilities: Map[String, String]) extends ServerAction
  case class ClientMessageAction(sessionId: protocol.SessionId, message: protocol.ClientMessage) extends ServerAction  
  case class ExpireSessionAction(sessionId: protocol.SessionId) extends ServerAction

  // Local action types for unified stream processing
  sealed trait LocalAction
  case class MessageAction(routingId: RoutingId, message: protocol.ClientMessage) extends LocalAction
  case object CleanupAction extends LocalAction

  // Connection state for server-local session management
  sealed trait ConnectionState {    
    def capabilities: Map[String, String]
    def createdAt: java.time.Instant
    def expiredAt: java.time.Instant
  }
  
  case class Connected(    
    routingId: RoutingId,
    capabilities: Map[String, String], 
    createdAt: java.time.Instant,
    expiredAt: java.time.Instant
  ) extends ConnectionState

  case class Disconnected(
    capabilities: Map[String, String],
    createdAt: java.time.Instant,
    expiredAt: java.time.Instant
  ) extends ConnectionState

  // Server-local connection state management
  case class ServerConnectionState(
    sessions: Map[protocol.SessionId, ConnectionState] = Map.empty,
    routingIdToSession: Map[RoutingId, protocol.SessionId] = Map.empty
  )
}
