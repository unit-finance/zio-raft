package zio.raft

import zio.prelude.State as ZState
import zio.Promise
import zio.{ZIO, Chunk, Task}
import scala.util.Try
import zio.stream.Stream

case class Term(value: Long):
  def <(other: Term) = value < other.value
  def >(other: Term) = value > other.value
  def >=(other: Term) = value >= other.value
  def <=(other: Term) = value <= other.value

  def plusOne = Term(value + 1L)
object Term:
  val zero = Term(0L)

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
type Peers = Array[MemberId]

case class ClusterConfiguration(
    server: MemberId,
    peers: Array[MemberId]
):
  def numberOfServers = 1 + peers.length

  def quorum = numberOfServers / 2

trait Command:
  type Response

case class NotALeaderError(leaderId: Option[MemberId])

type CommandPromise[A] = Promise[NotALeaderError, A]

case class EntryKey(term: Term, index: Index)
case class LogEntry[A <: Command](command: A, term: Term, index: Index)

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
  case class Granted[A <: Command](from: MemberId, term: Term)
      extends RequestVoteResult[A]
  case class Rejected[A <: Command](from: MemberId, term: Term)
      extends RequestVoteResult[A]

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
  case class Failure[A <: Command](from: MemberId, term: Term, index: Index)
      extends AppendEntriesResult[A]

case class HeartbeatRequest[A <: Command](term: Term, leaderId: MemberId, leaderCommitIndex: Index) extends RPCMessage[A]

case class HeartbeatResponse[A <: Command](from: MemberId, term: Term) extends RPCMessage[A]

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
  case class Failure[A <: Command](from: MemberId, term: Term, index: Index)
      extends InstallSnapshotResult[A]