package zio.raft

import zio.{Ref, ZIO, UIO}
import java.time.Instant

sealed trait State:
  val commintIndex: Index
  val lastApplied: Index

  def withCommitIndex(commintIndex: Index) =
    this match
      case f: State.Follower  => f.copy(commintIndex = commintIndex)
      case f: State.Candidate => f.copy(commintIndex = commintIndex)
      case f: State.Leader    => f.copy(commintIndex = commintIndex)

object State:
  case class Follower(commintIndex: Index, lastApplied: Index, electionTimeout: Instant) extends State
  case class Candidate(
      rpcDue: RPCDue,
      voteGranted: Int,
      commintIndex: Index,
      lastApplied: Index,
      electionTimeout: Instant
  ) extends State:
    def addVote(peer: MemberId) = 
        this.copy(voteGranted = voteGranted + 1, rpcDue = rpcDue.ack(peer))

    def withRPCDue(from: MemberId, when: Instant) =
      this.copy(rpcDue = rpcDue.set(from, when))

  case class Leader(
      nextIndex: NextIndex,
      matchIndex: MatchIndex,
      rpcDue: RPCDue,
      heartbeatDue: HeartbeatDue,
      commintIndex: Index,
      lastApplied: Index
  ) extends State {
    def withMatchIndex(from: MemberId, index: Index) =
      this.copy(matchIndex = matchIndex.set(from, index))

    def withNextIndex(from: MemberId, index: Index) =
      this.copy(nextIndex = nextIndex.set(from, index))

    def withRPCDueNow(from: MemberId) =
      this.copy(rpcDue = rpcDue.now(from))

    def withRPCDue(from: MemberId, when: Instant) =
      this.copy(rpcDue = rpcDue.set(from, when))

    def withHeartbeaetDue(from: MemberId, when: Instant) =
      this.copy(heartbeatDue = heartbeatDue.set(from, when))
  }
