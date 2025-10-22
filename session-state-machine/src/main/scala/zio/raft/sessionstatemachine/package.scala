package zio.raft

import zio.raft.protocol.{RequestId, SessionId}
import zio.prelude.Newtype

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
 * - CacheKey: Newtype for cache keys (composite of sessionId-requestId)
 */
package object sessionstatemachine {
  
  /**
   * Type-safe cache key for idempotency checking.
   * 
   * Internally stores "sessionId-requestId" as a string but provides type safety.
   */
  object CacheKey extends Newtype[String]:
    /**
     * Create a cache key from sessionId and requestId.
     */
    def apply(sessionId: SessionId, requestId: RequestId): CacheKey =
      CacheKey(s"${SessionId.unwrap(sessionId)}-${RequestId.unwrap(requestId)}")
  
  type CacheKey = CacheKey.Type
  
  /**
   * Fixed schema for session management state with typed keys.
   * 
   * This schema defines 4 prefixes with their key and value types:
   * - "metadata": (SessionId, SessionMetadata) - session information
   * - "cache": (CacheKey, Any) - cached responses for idempotency
   * - "serverRequests": (SessionId, List[PendingServerRequest[?]]) - pending requests per session
   * - "lastServerRequestId": (SessionId, RequestId) - last assigned server request ID per session
   * 
   * All keys use newtypes for type safety.
   * 
   * The SessionStateMachine base class uses this schema to manage session state
   * automatically. Users don't interact with this schema directly - it's an
   * implementation detail of the template pattern.
   */
  type SessionSchema = 
    ("metadata", SessionId, SessionMetadata) *:
    ("cache", CacheKey, Any) *:
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
   * KeyLike instances for session management keys.
   */
  given HMap.KeyLike[SessionId] = HMap.KeyLike.forNewtype(SessionId)
  given HMap.KeyLike[CacheKey] = HMap.KeyLike.forNewtype(CacheKey)
}
