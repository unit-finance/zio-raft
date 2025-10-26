package zio.raft

import zio.raft.protocol.{RequestId, SessionId}
import zio.prelude.fx.ZPure
import zio.Chunk
import java.time.Instant

/** Session State Machine Framework package.
  *
  * This package provides the SessionStateMachine trait and related types for implementing linearizable, exactly-once
  * state machines based on Chapter 6.3 of the Raft dissertation.
  *
  * Key types:
  *   - SessionStateMachine: Trait using template pattern
  *   - SessionSchema: Fixed 4-prefix schema for session management
  *   - Schema: Type-level concatenation of SessionSchema + UserSchema
  *   - SessionCommand: ADT of commands the session state machine accepts
  */
package object sessionstatemachine:

  /** Metadata associated with a client session.
    *
    * @param capabilities
    *   Key-value pairs describing client capabilities
    * @param createdAt
    *   Timestamp when the session was created
    * @note
    *   sessionId is NOT stored here - it's the key in the HMap
    */
  case class SessionMetadata(
    capabilities: Map[String, String],
    createdAt: Instant
  )

  /** Server-initiated request awaiting acknowledgment.
    *
    * @param payload
    *   The actual request data
    * @param lastSentAt
    *   Timestamp when request was last sent
    * @note
    *   id and sessionId are in the HMap key, not duplicated here
    */
  case class PendingServerRequest[SR](
    payload: SR,
    lastSentAt: Instant
  )

  /** Wrapper for server requests emitted via StateWriter.log(). Allows targeting ANY session, not just the current one.
    */
  case class ServerRequestForSession[SR](
    sessionId: SessionId,
    payload: SR
  )

  /** Server request with assigned ID after being added to state.
    */
  case class ServerRequestEnvelope[SR](
    sessionId: SessionId,
    requestId: RequestId,
    payload: SR
  )

  /** Type alias combining State monad with Writer monad for server requests.
    *
    * StateWriter allows state machine implementations to:
    *   - Modify state using ZPure's state operations
    *   - Emit server requests using `.log(serverRequest)`
    *   - Compose operations in for-comprehensions
    *
    * The log channel (W) accumulates server requests which are automatically collected and processed by the
    * SessionStateMachine base class.
    *
    * @tparam S
    *   The state type
    * @tparam W
    *   The log type (server request type)
    * @tparam A
    *   The return value type
    *
    * @example
    *   {{{
    * def myCommand(cmd: MyCmd): StateWriter[MyState, ServerRequest, Response] =
    *   for {
    *     state <- ZPure.get[MyState]
    *     newState = state.updated["counter"](key, value)
    *     _ <- ZPure.set(newState)
    *     _ <- ZPure.log(ServerRequest(...))  // Emit server request
    *   } yield CommandResponse(...)
    *   }}}
    */
  type StateWriter[S, W, A] = ZPure[W, S, S, Any, Nothing, A]
  val StateWriter: zio.prelude.fx.ZPure.type = zio.prelude.fx.ZPure

  /** Extension methods for ZPure to simplify working with state + log.
    */
  extension [W, S1, S2, A](zpure: ZPure[W, S1, S2, Any, Nothing, A])
    /** Run ZPure and extract (log, state, value) as a clean tuple.
      *
      * This is a convenience method for the common case where:
      *   - R is Any (no environment)
      *   - E is Nothing (no errors)
      *   - We want both the accumulated log and final state
      *
      * @param s
      *   Initial state
      * @return
      *   (log, finalState, value)
      */
    def runStateLog(s: S1): (Chunk[W], S2, A) =
      // Safe: E = Nothing, so ZPure cannot fail and Right is always present. The @unchecked annotation is justified.
      val (log, Right((state, value))) = zpure.runAll(s): @unchecked
      (log, state, value)

    /** Convert StateWriter to State with log moved to return value.
      *
      * Transforms ZPure[W, S, S, Any, Nothing, A] to State[S, (Chunk[W], A)]
      *
      * This allows composing StateWriter operations with State operations:
      *   - StateWriter accumulates log entries via .log(...)
      *   - withLog extracts the log and returns it alongside the value
      *   - Result can be used in for-comprehensions with other State operations
      *
      * @example
      *   {{{
      * for {
      *   (serverRequests, _) <- handleSessionCreated(...).withLog
      *   assignedRequests <- addServerRequests(createdAt, serverRequests)
      * } yield assignedRequests
      *   }}}
      */
    def withLog(using ev: S1 =:= S2): zio.prelude.State[S1, (Chunk[W], A)] =
      zio.prelude.State.modify { s =>
        val (log, newState, value) = zpure.runStateLog(s)
        ((log, value), ev.flip(newState))
      }

  /** Error type for request handling failures.
    */
  enum RequestError:
    /** Response was cached but has been evicted. Client must create a new session.
      *
      * This error occurs when:
      *   1. Client retries a request with requestId < highestLowestPendingRequestIdSeen for the session 2. The response
      *      is not in the cache (was evicted via cleanupCache)
      *
      * Per Raft dissertation Chapter 6.3, the client should create a new session and retry the operation.
      */
    case ResponseEvicted(sessionId: SessionId, requestId: RequestId)

  /** Fixed schema for session management state with typed keys.
    *
    * This schema defines 5 prefixes with their key and value types:
    *   - "metadata": (SessionId, SessionMetadata) - session information
    *   - "cache": ((SessionId, RequestId), R) - cached responses with composite key for efficient range queries and
    *     streaming
    *   - "serverRequests": ((SessionId, RequestId), PendingServerRequest[?]) - pending requests with composite key for
    *     efficiency
    *   - "lastServerRequestId": (SessionId, RequestId) - last assigned server request ID per session
    *   - "highestLowestPendingRequestIdSeen": (SessionId, RequestId) - highest lowestPendingRequestId observed (for
    *     eviction detection)
    *
    * Both cache and serverRequests use composite keys (SessionId, RequestId) for better performance:
    *   - Direct key access: O(log n) lookups
    *   - Range queries: Efficient iteration over session-specific entries
    *   - Session expiration: Use range to find all entries for a session
    *   - Streaming-friendly: Each entry is a separate key-value pair, not nested collections
    *   - Proper ordering: RequestId ordering is numeric (big-endian encoding), not lexicographic
    *   - No data duplication: sessionId and requestId are not stored in the value, only in the key
    *
    * The highestLowestPendingRequestIdSeen prefix enables detection of evicted responses:
    *   - Client sends lowestPendingRequestId indicating the lowest sequence number without a response
    *   - We track the highest such value received from the client
    *   - When a ClientRequest arrives, we check if requestId < highestLowestPendingRequestIdSeen
    *   - If yes and response is not in cache, we know it was evicted (client already acknowledged it)
    *   - This correctly handles out-of-order requests while preventing re-execution of acknowledged commands
    */
  type SessionSchema[R, SR] =
    ("metadata", SessionId, SessionMetadata) *:
      ("cache", (SessionId, RequestId), R) *:
      ("serverRequests", (SessionId, RequestId), PendingServerRequest[SR]) *:
      ("lastServerRequestId", SessionId, RequestId) *:
      ("highestLowestPendingRequestIdSeen", SessionId, RequestId) *:
      EmptyTuple

  /** KeyLike instance for SessionId keys. Used by metadata, serverRequests, and lastServerRequestId prefixes.
    */
  given HMap.KeyLike[SessionId] = HMap.KeyLike.forNewtype(SessionId)

  /** Helper type to concatenate SessionSchema with user schema.
    *
    * @tparam R
    *   Response marker type
    * @tparam SR
    *   Server request type
    * @tparam UserSchema
    *   User-defined schema
    */
  type Schema[R, SR, UserSchema <: Tuple] = Tuple.Concat[SessionSchema[R, SR], UserSchema]

  given HMap.KeyLike[(SessionId, RequestId)] = new HMap.KeyLike[(SessionId, RequestId)]:
    import java.nio.charset.StandardCharsets

    def asBytes(key: (SessionId, RequestId)): Array[Byte] =
      // Encode SessionId as UTF-8
      val sessionBytes = SessionId.unwrap(key._1).getBytes(StandardCharsets.UTF_8)

      // Encode RequestId as 8-byte big-endian long
      val requestId = RequestId.unwrap(key._2)
      val requestBytes = Array(
        (requestId >> 56).toByte,
        (requestId >> 48).toByte,
        (requestId >> 40).toByte,
        (requestId >> 32).toByte,
        (requestId >> 24).toByte,
        (requestId >> 16).toByte,
        (requestId >> 8).toByte,
        requestId.toByte
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
        ((bytes(0) & 0xff) << 24) |
          ((bytes(1) & 0xff) << 16) |
          ((bytes(2) & 0xff) << 8) |
          (bytes(3) & 0xff)

      // Extract SessionId
      val sessionBytes = bytes.slice(4, 4 + length)
      val sessionId = SessionId(new String(sessionBytes, StandardCharsets.UTF_8))

      // Extract RequestId (8 bytes big-endian)
      val offset = 4 + length
      val requestId =
        ((bytes(offset).toLong & 0xff) << 56) |
          ((bytes(offset + 1).toLong & 0xff) << 48) |
          ((bytes(offset + 2).toLong & 0xff) << 40) |
          ((bytes(offset + 3).toLong & 0xff) << 32) |
          ((bytes(offset + 4).toLong & 0xff) << 24) |
          ((bytes(offset + 5).toLong & 0xff) << 16) |
          ((bytes(offset + 6).toLong & 0xff) << 8) |
          (bytes(offset + 7).toLong & 0xff)

      (sessionId, RequestId(requestId))

  /** Extension method to convert a response value to the intersection type required by SessionStateMachine.
    *
    * The intersection type `command.Response & R` ensures that:
    *   - The response satisfies the specific command's Response type (compile-time checked via evidence)
    *   - The response is compatible with the marker type R (for serialization)
    *
    * This provides a type-safe way to satisfy the intersection type constraint without manual casts.
    *
    * @param base
    *   The base command (used to capture the base command type)
    * @param c
    *   The specific command instance (used to get c.Response type)
    * @param ev
    *   Evidence that A equals c.Response (ensures type safety)
    * @tparam A
    *   The response value type
    * @tparam B
    *   The base command type
    * @tparam C
    *   The specific command type (subtype of B)
    * @return
    *   The response cast to base.Response & A
    *
    * @example
    *   {{{
    * sealed trait KVResponse
    * case class GetResult(value: String) extends KVResponse
    *
    * case class Get(key: String) extends Command:
    *   type Response = GetResult
    *
    * protected def applyCommand(cmd: MyCommand, ...): StateWriter[..., cmd.Response & KVResponse] =
    *   cmd match
    *     case get @ Get(key) =>
    *       val result = GetResult(lookupKey(key))
    *       yield result.asResponseType(cmd, get)  // ✅ cmd is base, get is specific
    *   }}}
    */
  extension [A](a: A)
    def asResponseType[B <: Command, C <: B](base: B, c: C)(using ev: A =:= (c.Response)): base.Response & A =
      a.asInstanceOf[base.Response & A]

end sessionstatemachine
