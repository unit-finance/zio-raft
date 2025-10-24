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
 * - Schema: Type-level concatenation of SessionSchema + UserSchema
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
   * - "cache": ((SessionId, RequestId), Any) - cached responses with composite key for efficient range queries and streaming
   * - "serverRequests": ((SessionId, RequestId), PendingServerRequest[?]) - pending requests with composite key for efficiency
   * - "lastServerRequestId": (SessionId, RequestId) - last assigned server request ID per session
   * 
   * Both cache and serverRequests use composite keys (SessionId, RequestId) for better performance:
   * - Direct key access: O(log n) lookups
   * - Range queries: Efficient iteration over session-specific entries
   * - Session expiration: Use range to find all entries for a session
   * - Streaming-friendly: Each entry is a separate key-value pair, not nested collections
   * - Proper ordering: RequestId ordering is numeric (big-endian encoding), not lexicographic
   * - No data duplication: sessionId and requestId are not stored in the value, only in the key
   */
  type SessionSchema = 
    ("metadata", SessionId, SessionMetadata) *:
    ("cache", (SessionId, RequestId), Any) *:
    ("serverRequests", (SessionId, RequestId), PendingServerRequest[?]) *:
    ("lastServerRequestId", SessionId, RequestId) *:
    EmptyTuple
  
  /**
   * KeyLike instance for SessionId keys.
   * Used by metadata, serverRequests, and lastServerRequestId prefixes.
   */
  given HMap.KeyLike[SessionId] = HMap.KeyLike.forNewtype(SessionId)
  
  /**
   * KeyLike instance for composite (SessionId, RequestId) keys.
   * Used by the cache prefix for efficient range queries and proper numeric ordering of RequestIds.
   * 
   * Encoding format:
   * - 4 bytes: length of SessionId (big-endian Int)
   * - N bytes: SessionId as UTF-8 string
   * - 8 bytes: RequestId as big-endian Long
   * 
   * This encoding ensures:
   * 1. Sessions are grouped together (sorted by SessionId first)
   * 2. Within a session, RequestIds are ordered numerically (not lexicographically)
   * 3. Range queries work efficiently: range((sid, rid1), (sid, rid2)) selects requests within the range
   */
  given HMap.KeyLike[(SessionId, RequestId)] = new HMap.KeyLike[(SessionId, RequestId)]:
    import java.nio.charset.StandardCharsets
    
    def asBytes(key: (SessionId, RequestId)): Array[Byte] =
      // Encode SessionId as UTF-8
      val sessionBytes = SessionId.unwrap(key._1).getBytes(StandardCharsets.UTF_8)
      
      // Encode RequestId as 8-byte big-endian long
      val requestId = RequestId.unwrap(key._2)
      val requestBytes = Array(
        (requestId >> 56).toByte, (requestId >> 48).toByte, 
        (requestId >> 40).toByte, (requestId >> 32).toByte,
        (requestId >> 24).toByte, (requestId >> 16).toByte, 
        (requestId >> 8).toByte,  requestId.toByte
      )
      
      // Length-prefix the SessionId (4 bytes big-endian)
      val lengthBytes = Array(
        (sessionBytes.length >> 24).toByte,
        (sessionBytes.length >> 16).toByte,
        (sessionBytes.length >> 8).toByte,
        sessionBytes.length.toByte
      )
      
      lengthBytes ++ sessionBytes ++ requestBytes
    
    def fromBytes(bytes: Array[Byte]): (SessionId, RequestId) =
      // Read length prefix
      val length = 
        ((bytes(0) & 0xFF) << 24) |
        ((bytes(1) & 0xFF) << 16) |
        ((bytes(2) & 0xFF) << 8)  |
        (bytes(3) & 0xFF)
      
      // Extract SessionId
      val sessionBytes = bytes.slice(4, 4 + length)
      val sessionId = SessionId(new String(sessionBytes, StandardCharsets.UTF_8))
      
      // Extract RequestId (8 bytes big-endian)
      val offset = 4 + length
      val requestId = 
        ((bytes(offset).toLong & 0xFF) << 56) |
        ((bytes(offset + 1).toLong & 0xFF) << 48) |
        ((bytes(offset + 2).toLong & 0xFF) << 40) |
        ((bytes(offset + 3).toLong & 0xFF) << 32) |
        ((bytes(offset + 4).toLong & 0xFF) << 24) |
        ((bytes(offset + 5).toLong & 0xFF) << 16) |
        ((bytes(offset + 6).toLong & 0xFF) << 8)  |
        (bytes(offset + 7).toLong & 0xFF)
      
      (sessionId, RequestId(requestId))
}
