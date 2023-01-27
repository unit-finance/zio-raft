package zio.raft

import zio.prelude.State as ZState
import zio.Promise
import zio.{ZIO, Chunk, Task}

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
  peers: Array[MemberId],  
):
  def numberOfServers = 1 + peers.length

  def quorum = numberOfServers / 2

trait Command:
  type Response

  val promise: Option[Promise[Nothing, Response]]

  def complete(response: Response) = promise.fold(ZIO.unit)(_.succeed(response))

case class EntryKey(term: Term, index: Index)
case class LogEntry(command: Command, term: Term, index: Index)

sealed trait RPCMessage:
  val term: Term

case class RequestVoteRequest(term: Term, candidateId: MemberId, lastLogIndex: Index, lastLogTerm: Term) extends RPCMessage

sealed trait RequestVoteResult extends RPCMessage
object RequestVoteResult:
  case class Granted(from: MemberId, term: Term) extends RequestVoteResult
  case class Rejected(from: MemberId, term: Term) extends RequestVoteResult

case class AppendEntriesRequest(
                          term: Term,
                          leaderId: MemberId,
                          previousIndex: Index,
                          previousTerm: Term,
                          entries: List[LogEntry],
                          leaderCommitIndex: Index
                        ) extends RPCMessage

sealed trait AppendEntriesResult extends RPCMessage
object AppendEntriesResult:
  case class Success(from: MemberId, term: Term, matchIndex: Index) extends AppendEntriesResult
  case class Failure(from: MemberId, term: Term) extends AppendEntriesResult

trait Codec[A <: Command]:
  def decode(bytes: Chunk[Byte]): Task[A]
  def encode(a: A): Task[Chunk[Byte]]