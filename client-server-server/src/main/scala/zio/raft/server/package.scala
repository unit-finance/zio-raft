package zio.raft

import zio.*

/** Server-side implementation for ZIO Raft client-server communication.
  *
  * Simplified functional server with:
  *   - State machine pattern for leader/follower states
  *   - Immutable session management
  *   - Stream-based event processing
  *   - Clean separation of concerns
  */
package object server {
  // No additional types needed - everything is in RaftServer.scala
}
