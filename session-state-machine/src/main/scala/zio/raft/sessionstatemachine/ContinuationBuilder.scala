package zio.raft.sessionstatemachine

import zio.ZIO
import zio.raft.sessionstatemachine.RequestError
import zio.raft.NotALeaderError
import zio.raft.HMap

/** Builders for continuations that handle Raft session-state-machine responses.
  *
  * These helpers construct total handlers for command/query results that may include:
  *   - successful results
  *   - domain failures (wrapped in [[RequestError]])
  *   - leadership errors ([[NotALeaderError]])
  *
  * Use the specific entry point based on the shape of your result and then call `make` to obtain a function you can
  * apply to the returned `Either` from your protocol.
  *
  * Example (either success or domain error):
  *
  * ```scala
  * val handler =
  *   ContinuationBuilder
  *     .onSuccess[MyReq, String] { (envelopes, value) =>
  *       ZIO.logInfo(s"OK (${envelopes.size}): $value")
  *     }
  *     .onFailure[MyDomainError] { (envelopes, err) =>
  *       ZIO.logWarning(s"Domain failure (${envelopes.size}): $err")
  *     }
  *     .onNotALeader { nal =>
  *       ZIO.logWarning(s"Not a leader: $nal")
  *     }
  *     .make
  *
  * // Later, when you have a result:
  * val result
  *   : Either[NotALeaderError, (List[ServerRequestEnvelope[MyReq]], Either[RequestError[MyDomainError], String])] = ???
  * val effect: ZIO[Any, Nothing, Unit] = handler(result)
  * ```
  */
object ContinuationBuilder:
  /** Start building a continuation for a result that can be either a domain error or success.
    *
    *   - Initializes a builder where success is handled by `onSuccess`.
    *   - Domain error and not-a-leader are initially ignored; you can override them using `onFailure` and
    *     `onNotALeader`.
    *
    * Example:
    * ```scala
    * val builder =
    *   ContinuationBuilder
    *     .onSuccess[MyReq, Int] { (envs, a) => ZIO.logInfo(s"OK: $a") }
    *     .onFailure[MyErr] { (envs, e) => ZIO.logWarning(e.toString) }
    *     .onNotALeader { nal => ZIO.logWarning(nal.toString) }
    * ```
    */
  def onSuccess[SR, A](onSuccess: (List[ServerRequestEnvelope[SR]], A) => ZIO[Any, Nothing, Unit])
    : EitherContinuationBuilder[SR, Any, A] =
    EitherContinuationBuilder(onSuccess, (_, _) => ZIO.unit, _ => ZIO.unit)

  /** Build a continuation for a result that cannot contain a domain error, only success or a leadership error.
    *
    *   - `onResult` runs when a result arrives.
    *   - You may customize not-a-leader handling via `.onNotALeader(...)`.
    *
    * Example:
    * ```scala
    * val handle =
    *   ContinuationBuilder
    *     .onResult[MyReq, String] { (envs, value) => ZIO.logInfo(s"Got: $value") }
    *     .onNotALeader(nal => ZIO.logWarning(s"NAL: $nal"))
    *     .make
    *
    * val envelopes: List[ServerRequestEnvelope[MyReq]] = ???
    * handle(Right((envelopes, "value")))
    * ```
    */
  def onResult[SR, A](onResult: (List[ServerRequestEnvelope[SR]], A) => ZIO[Any, Nothing, Unit])
    : ResultOnlyContinuationBuilder[SR, A] =
    ResultOnlyContinuationBuilder(onResult, _ => ZIO.unit)

  /** Build a continuation for read-only queries that return an `HMap[M]` state snapshot.
    *
    *   - `onSuccess` runs when the state is returned.
    *   - Customize not-a-leader handling with `.onNotALeader(...)`.
    *
    * Example:
    * ```scala
    * val handle =
    *   ContinuationBuilder
    *     .query[(KeyA, KeyB)] { state =>
    *       ZIO.logInfo(s"Keys: ${state.keys}")
    *     }
    *     .onNotALeader(nal => ZIO.logWarning(s"NAL: $nal"))
    *     .make
    *
    * val state: HMap[(KeyA, KeyB)] = ???
    * handle(Right(state))
    * ```
    */
  def query[M <: Tuple](onSuccess: HMap[M] => ZIO[Any, Nothing, Unit]): QueryContinuationBuilder[M] =
    QueryContinuationBuilder(onSuccess, _ => ZIO.unit)

  /** Build a continuation for commands that succeed without a return value (apart from envelopes), or fail due to
    * leadership.
    *
    * Example:
    * ```scala
    * val handle =
    *   ContinuationBuilder
    *     .withoutResult[MyReq](envs => ZIO.logInfo(s"Ack ${envs.size}"))
    *     .onNotALeader(nal => ZIO.logWarning(s"NAL: $nal"))
    *     .make
    *
    * val envs: List[ServerRequestEnvelope[MyReq]] = ???
    * handle(Right(envs))
    * ```
    */
  def withoutResult[SR](onSuccess: List[ServerRequestEnvelope[SR]] => ZIO[Any, Nothing, Unit])
    : WithoutResultContinuationBuilder[SR] =
    WithoutResultContinuationBuilder(onSuccess, _ => ZIO.unit)

  /** Builder for results that are guaranteed to be successful (no domain `RequestError`), but may fail with
    * [[NotALeaderError]] before reaching the leader.
    *
    * @tparam SR
    *   server request envelope payload type
    * @tparam A
    *   successful result type
    */
  class ResultOnlyContinuationBuilder[SR, A](
    onResult: (List[ServerRequestEnvelope[SR]], A) => ZIO[Any, Nothing, Unit],
    onNotALeader: NotALeaderError => ZIO[Any, Nothing, Unit]
  ):
    /** Set the handler for [[NotALeaderError]]. */
    def onNotALeader(onNotALeader: NotALeaderError => ZIO[Any, Nothing, Unit]): ResultOnlyContinuationBuilder[SR, A] =
      ResultOnlyContinuationBuilder(onResult, onNotALeader)

    /** Create the total continuation function.
      *
      *   - `Left(NotALeaderError)` → calls the configured `onNotALeader`
      *   - `Right((envelopes, a))` → calls `onResult`
      */
    def make: Either[NotALeaderError, (List[ServerRequestEnvelope[SR]], A)] => ZIO[Any, Nothing, Unit] =
      case Left(notALeaderError) => onNotALeader(notALeaderError)
      case Right(envelopes, a)   => onResult(envelopes, a)

  /** Builder for commands that carry no result other than the envelopes on success. */
  class WithoutResultContinuationBuilder[SR](
    onSuccess: List[ServerRequestEnvelope[SR]] => ZIO[Any, Nothing, Unit],
    onNotALeader: NotALeaderError => ZIO[Any, Nothing, Unit]
  ):
    /** Set the handler for [[NotALeaderError]]. */
    def onNotALeader(onNotALeader: NotALeaderError => ZIO[Any, Nothing, Unit]): WithoutResultContinuationBuilder[SR] =
      WithoutResultContinuationBuilder(onSuccess, onNotALeader)

    /** Create the total continuation function.
      *
      *   - `Left(NotALeaderError)` → calls the configured `onNotALeader`
      *   - `Right(envelopes)` → calls `onSuccess`
      */
    def make: Either[NotALeaderError, List[ServerRequestEnvelope[SR]]] => ZIO[Any, Nothing, Unit] =
      case Left(notALeaderError) => onNotALeader(notALeaderError)
      case Right(envelopes)      => onSuccess(envelopes)

  /** Builder for results that may contain either a domain error ([[RequestError]]) or a success.
    *
    * @tparam SR
    *   server request envelope payload type
    * @tparam E
    *   domain error type inside [[RequestError]]
    * @tparam A
    *   successful result type
    */
  class EitherContinuationBuilder[SR, E, A](
    onSuccess: (List[ServerRequestEnvelope[SR]], A) => ZIO[Any, Nothing, Unit],
    onFailure: (List[ServerRequestEnvelope[SR]], RequestError[E]) => ZIO[Any, Nothing, Unit],
    onNotALeader: NotALeaderError => ZIO[Any, Nothing, Unit]
  ):
    /** Replace the handler for [[NotALeaderError]]. */
    def onNotALeader(onNotALeader: NotALeaderError => ZIO[Any, Nothing, Unit]): EitherContinuationBuilder[SR, E, A] =
      EitherContinuationBuilder(onSuccess, onFailure, onNotALeader)

    /** Replace the domain error handler and optionally change its error type `E2`. */
    def onFailure[E2](onFailure: (List[ServerRequestEnvelope[SR]], RequestError[E2]) => ZIO[Any, Nothing, Unit])
      : EitherContinuationBuilder[SR, E2, A] =
      EitherContinuationBuilder(onSuccess, onFailure, onNotALeader)

    /** Ignore any domain error ([[RequestError]]) and keep only the success branch. */
    def ignoreError[E2](): EitherContinuationBuilder[SR, E2, A] =
      EitherContinuationBuilder(onSuccess, (_, _) => ZIO.unit, onNotALeader)

    /** Create the total continuation function.
      *
      *   - `Left(NotALeaderError)` → calls the configured `onNotALeader`
      *   - `Right((envelopes, Left(requestError)))` → calls `onFailure`
      *   - `Right((envelopes, Right(a)))` → calls `onSuccess`
      *
      * Example:
      * ```scala
      * val handle =
      *   ContinuationBuilder
      *     .onSuccess[MyReq, String] { (envs, a) => ZIO.logInfo(a) }
      *     .onFailure[MyErr] { (envs, e) => ZIO.logWarning(e.toString) }
      *     .make
      * ```
      */
    def make: Either[NotALeaderError, (List[ServerRequestEnvelope[SR]], Either[RequestError[E], A])] => ZIO[
      Any,
      Nothing,
      Unit
    ] =
      case Left(notALeaderError)                => onNotALeader(notALeaderError)
      case Right(envelopes, Left(requestError)) => onFailure(envelopes, requestError)
      case Right(envelopes, Right(a))           => onSuccess(envelopes, a)

  /** Builder for query continuations that return `HMap[M]` on success. */
  class QueryContinuationBuilder[M <: Tuple](
    onSuccess: HMap[M] => ZIO[Any, Nothing, Unit],
    onNotALeader: NotALeaderError => ZIO[Any, Nothing, Unit]
  ):
    /** Replace the handler for [[NotALeaderError]]. */
    def onNotALeader(onNotALeader: NotALeaderError => ZIO[Any, Nothing, Unit]): QueryContinuationBuilder[M] =
      QueryContinuationBuilder(onSuccess, onNotALeader)

    /** Create the total continuation function.
      *
      *   - `Left(NotALeaderError)` → calls the configured `onNotALeader`
      *   - `Right(state)` → calls `onSuccess`
      *
      * Example:
      * ```scala
      * val handle =
      *   ContinuationBuilder
      *     .query[(KeyA, KeyB)](state => ZIO.logInfo(state.toString))
      *     .onNotALeader(nal => ZIO.logWarning(s"NAL: $nal"))
      *     .make
      * ```
      */
    def make: Either[NotALeaderError, HMap[M]] => ZIO[Any, Nothing, Unit] =
      case Left(notALeaderError) => onNotALeader(notALeaderError)
      case Right(state)          => onSuccess(state)
end ContinuationBuilder
