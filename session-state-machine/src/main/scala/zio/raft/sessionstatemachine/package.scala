package zio.raft

import zio.raft.protocol.{SessionId, RequestId}

/**
 * Session State Machine Framework package.
 * 
 * This package provides the SessionStateMachine abstract base class and
 * related types for implementing linearizable, exactly-once state machines
 * based on Chapter 6.3 of the Raft dissertation.
 * 
 * Key types:
 * - SessionStateMachine: Abstract base class (template pattern)
 * - SessionSchema: Fixed 4-prefix schema for session management
 * - CombinedSchema: Type-level concatenation of SessionSchema + UserSchema
 * - SessionCommand: ADT of commands the session state machine accepts
 */
package object sessionstatemachine {
  
  /**
   * Fixed schema for session management state.
   * 
   * This schema defines 4 prefixes with their value types:
   * - "metadata": SessionMetadata - session information (key = sessionId)
   * - "cache": Any - cached responses for idempotency (key = "sessionId-requestId")
   * - "serverRequests": List[PendingServerRequest[?]] - pending requests per session (key = sessionId)
   * - "lastServerRequestId": RequestId - last assigned server request ID per session (key = sessionId)
   * 
   * The SessionStateMachine base class uses this schema to manage session state
   * automatically. Users don't interact with this schema directly - it's an
   * implementation detail of the template pattern.
   */
  type SessionSchema = 
    ("metadata", SessionMetadata) *:
    ("cache", Any) *:
    ("serverRequests", List[PendingServerRequest[?]]) *:
    ("lastServerRequestId", RequestId) *:
    EmptyTuple
  
  /**
   * Combined schema that concatenates SessionSchema and UserSchema.
   * 
   * This type alias uses Tuple.Concat to merge the session management prefixes
   * with the user-defined schema at the type level. The result is a schema with
   * both session prefixes and user prefixes, all with compile-time type safety.
   * 
   * @tparam UserSchema The user-defined schema (tuple of prefix-type pairs)
   * 
   * Example:
   * {{{
   * type MyUserSchema = ("counter", Int) *: ("name", String) *: EmptyTuple
   * type MyCombined = CombinedSchema[MyUserSchema]
   * // MyCombined has all 4 SessionSchema prefixes plus "counter" and "name"
   * }}}
   */
  type CombinedSchema[UserSchema <: Tuple] = Tuple.Concat[SessionSchema, UserSchema]
}
