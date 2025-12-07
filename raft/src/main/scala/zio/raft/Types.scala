package zio.raft

import zio.Chunk
import zio.ZIO

case class Term(value: Long):
  def <(other: Term) = value < other.value
  def >(other: Term) = value > other.value
  def >=(other: Term) = value >= other.value
  def <=(other: Term) = value <= other.value

  def plusOne = Term(value + 1L)

  def isZero = value == 0L
object Term:
  val zero = Term(0L)
  val one = Term(1L)

case class Index(value: Long):
  def <(other: Index) = value < other.value
  def >(other: Index) = value > other.value
  def >=(other: Index) = value >= other.value
  def <=(other: Index) = value <= other.value

  def plus(add: Long) = Index(value + add)
  def plusOne = Index(value + 1L)
  def minusOne = Index(value - 1L)

  val isZero = value == 0L
object Index:
  val zero = Index(0L)
  val one = Index(1L)
  def range(fromInclusive: Index, toInclusive: Index) =
    Seq.range(fromInclusive.value, toInclusive.plusOne.value).map(i => Index(i))
  def min(a: Index, b: Index) =
    val m = Math.min(a.value, b.value)
    Index(m)
  def max(a: Index, b: Index) =
    val m = Math.max(a.value, b.value)
    Index(m)

case class MemberId(value: String)
type Peers = Set[MemberId]

case class ClusterConfiguration(
  server: MemberId,
  peers: Peers
):
  def numberOfServers = 1 + peers.size

  // TODO (eran): go over the code and fix peers and numberOfServers so it would mean the same everywhere
  def quorum = numberOfServers / 2

trait Command:
  type Response

object Command:
  type Aux[C <: Command, R] = C { type Response = R }

case class NotALeaderError(leaderId: Option[MemberId])

/** A user-supplied callback that will be invoked when a command completes.
  *
  * Rationale:
  *   - Using `Promise` would force callers to block (or manually fork) on `sendCommand`, which is problematic when the
  *     caller drives Raft from a single stream/fiber.
  *   - Raft is stream-based and batches commands. With continuations, callers can enqueue many commands without
  *     awaiting each one. Raft will batch, replicate, and when a result is available it will enqueue a
  *     `RaftAction.CommandContinuation` containing the effect to run the continuation on the caller's stream.
  *
  * Contract:
  *   - The continuation must be total and non-blocking; it will be scheduled back to the user via the `raftActions`
  *     stream as an effect to run.
  *   - On success: receives `Right(response)`. On leadership loss: receives `Left(NotALeaderError)`.
  */
type CommandContinuation[A] = Either[NotALeaderError, A] => ZIO[Any, Nothing, Unit]

type ReadContinuation[S] = Either[NotALeaderError, S] => ZIO[Any, Nothing, Unit]
sealed trait LogEntry[+A <: Command]:
  val term: Term
  val index: Index
object LogEntry:
  case class CommandLogEntry[A <: Command](command: A, val term: Term, val index: Index)
      extends LogEntry[A]
  case class NoopLogEntry(val term: Term, val index: Index) extends LogEntry[Nothing]

sealed trait RPCMessage[A <: Command]:
  val term: Term

case class RequestVoteRequest[A <: Command](
  term: Term,
  candidateId: MemberId,
  lastLogIndex: Index,
  lastLogTerm: Term
) extends RPCMessage[A]

sealed trait RequestVoteResult[A <: Command] extends RPCMessage[A]
object RequestVoteResult:
  case class Granted[A <: Command](from: MemberId, term: Term) extends RequestVoteResult[A]
  case class Rejected[A <: Command](from: MemberId, term: Term) extends RequestVoteResult[A]

case class AppendEntriesRequest[A <: Command](
  term: Term,
  leaderId: MemberId,
  previousIndex: Index,
  previousTerm: Term,
  entries: List[LogEntry[A]],
  leaderCommitIndex: Index
) extends RPCMessage[A]

sealed trait AppendEntriesResult[A <: Command] extends RPCMessage[A]
object AppendEntriesResult:
  case class Success[A <: Command](
    from: MemberId,
    term: Term,
    matchIndex: Index
  ) extends AppendEntriesResult[A]
  case class Failure[A <: Command](from: MemberId, term: Term, index: Index, hint: Option[(Term, Index)])
      extends AppendEntriesResult[A]

case class HeartbeatRequest[A <: Command](
  term: Term,
  leaderId: MemberId,
  leaderCommitIndex: Index /*, timestamp: Instant*/
) extends RPCMessage[A]

case class HeartbeatResponse[A <: Command](from: MemberId, term: Term /*, timestamp: Instant*/ ) extends RPCMessage[A]

case class InstallSnapshotRequest[A <: Command](
  term: Term,
  leaderId: MemberId,
  lastIndex: Index,
  lastTerm: Term,
  offset: Long,
  done: Boolean,
  data: Chunk[Byte]
) extends RPCMessage[A]

sealed trait InstallSnapshotResult[A <: Command] extends RPCMessage[A]
object InstallSnapshotResult:
  case class Success[A <: Command](
    from: MemberId,
    term: Term,
    index: Index,
    done: Boolean
  ) extends InstallSnapshotResult[A]
  case class Failure[A <: Command](from: MemberId, term: Term, index: Index) extends InstallSnapshotResult[A]

/** Outbound actions emitted by the Raft runtime to be handled by the embedding application.
  *
  * Stream:
  *   - Use `Raft#raftActions` to subscribe. Actions include leadership changes and continuations to resume user flows
  *     when commands complete.
  */
sealed trait RaftAction
object RaftAction:
  case object SteppedUp extends RaftAction
  case class SteppedDown(leaderId: Option[MemberId]) extends RaftAction
  case class LeaderChanged(leaderId: MemberId) extends RaftAction

  /** An effect that, when run by the embedding application, will invoke the user's command continuation with either a
    * response or `NotALeaderError`.
    *
    * Delivery model:
    *   - Raft enqueues this when a command completes (success or leadership loss).
    *   - Your integration should drain `raftActions` and execute these effects on your desired execution
    *     context/stream.
    */
  case class CommandContinuation(continuation: ZIO[Any, Nothing, Unit]) extends RaftAction

  /** An effect that, when run by the embedding application, will invoke the user's read continuation with either a
    * state or `NotALeaderError`.
    */
  case class ReadContinuation(continuation: ZIO[Any, Nothing, Unit]) extends RaftAction
