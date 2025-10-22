package zio.raft

import zio.raft.protocol.{RequestId, SessionId}
import zio.prelude.fx.ZPure
import zio.Chunk

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
   * Type alias combining State monad with Writer monad for server requests.
   * 
   * StateWriter allows state machine implementations to:
   * - Modify state using ZPure's state operations
   * - Emit server requests using `.log(serverRequest)`
   * - Compose operations in for-comprehensions
   * 
   * The log channel (W) accumulates server requests which are automatically
   * collected and processed by the SessionStateMachine base class.
   * 
   * @tparam S The state type
   * @tparam W The log type (server request type)
   * @tparam A The return value type
   * 
   * @example
   * {{{
   * def myCommand(cmd: MyCmd): StateWriter[MyState, ServerRequest, Response] =
   *   for {
   *     state <- ZPure.get[MyState]
   *     newState = state.updated["counter"](key, value)
   *     _ <- ZPure.set(newState)
   *     _ <- ZPure.log(ServerRequest(...))  // Emit server request
   *   } yield CommandResponse(...)
   * }}}
   */
  type StateWriter[S, W, A] = ZPure[W, S, S, Any, Nothing, A]
  val StateWriter: zio.prelude.fx.ZPure.type = zio.prelude.fx.ZPure

  
  /**
   * Extension methods for ZPure to simplify working with state + log.
   */
  extension [W, S1, S2, A](zpure: ZPure[W, S1, S2, Any, Nothing, A])
    /**
     * Run ZPure and extract (log, state, value) as a clean tuple.
     * 
     * This is a convenience method for the common case where:
     * - R is Any (no environment)
     * - E is Nothing (no errors)
     * - We want both the accumulated log and final state
     * 
     * @param s Initial state
     * @return (log, finalState, value)
     */
    def runStateLog(s: S1): (Chunk[W], S2, A) =
      val (log, Right((state, value))) = zpure.runAll(s): @unchecked
      (log, state, value)
  
  /**
   * Fixed schema for session management state with typed keys.
   * 
   * This schema defines 4 prefixes with their key and value types:
   * - "metadata": (SessionId, SessionMetadata) - session information
   * - "cache": (SessionId, Map[RequestId, Any]) - cached responses per session for idempotency
   * - "serverRequests": (SessionId, List[PendingServerRequest[?]]) - pending requests per session
   * - "lastServerRequestId": (SessionId, RequestId) - last assigned server request ID per session
   * 
   * All keys use SessionId for type safety and efficiency.
   * 
   * The cache design groups responses by session, making cleanup O(1) instead of O(n):
   * - Cache lookup: get session's map, then lookup requestId
   * - Cache cleanup: get session's map, filter by lowestRequestId, update once
   * - Session expiration: remove single cache entry (not iterate all requests)
   * 
   * The SessionStateMachine base class uses this schema to manage session state
   * automatically. Users don't interact with this schema directly - it's an
   * implementation detail of the template pattern.
   */
  type SessionSchema = 
    ("metadata", SessionId, SessionMetadata) *:
    ("cache", SessionId, Map[RequestId, Any]) *:
    ("serverRequests", SessionId, List[PendingServerRequest[?]]) *:
    ("lastServerRequestId", SessionId, RequestId) *:
    EmptyTuple
  
  /**
   * Combined schema that concatenates SessionSchema and UserSchema.
   * 
   * This type alias uses Tuple.Concat to merge the session management prefixes
   * with the user-defined schema at the type level. The result is a schema with
   * both session prefixes and user prefixes, all with compile-time type safety.
   * 
   * @tparam UserSchema The user-defined schema (tuple of (Prefix, KeyType, ValueType) triples)
   * 
   * Example:
   * {{{
   * type MyUserSchema = ("counter", CounterId, Int) *: ("name", NameId, String) *: EmptyTuple
   * type MyCombined = CombinedSchema[MyUserSchema]
   * // MyCombined has all 4 SessionSchema prefixes plus "counter" and "name"
   * }}}
   */
  type CombinedSchema[UserSchema <: Tuple] = Tuple.Concat[SessionSchema, UserSchema]
  
  /**
   * KeyLike instance for SessionId keys.
   * All session management prefixes use SessionId as the key type.
   */
  given HMap.KeyLike[SessionId] = HMap.KeyLike.forNewtype(SessionId)
}
