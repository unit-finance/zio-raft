package zio.raft

import zio.{Ref, ZIO, UIO}
import java.time.Instant

sealed trait State:
  val commitIndex: Index
  val lastApplied: Index

  def withCommitIndex(commitIndex: Index) =
    this match
      case f: State.Follower  => f.copy(commitIndex = commitIndex)
      case f: State.Candidate => f.copy(commitIndex = commitIndex)
      case f: State.Leader    => f.copy(commitIndex = commitIndex)

  def increaseLastApplied: State = this match
    case f: State.Follower  => f.copy(lastApplied = lastApplied.plusOne)
    case f: State.Candidate => f.copy(lastApplied = lastApplied.plusOne)
    case f: State.Leader    => f.copy(lastApplied = lastApplied.plusOne)

object State:
  case class Follower(commitIndex: Index, lastApplied: Index, electionTimeout: Instant, leaderId: Option[MemberId])
      extends State
  case class Candidate(
      rpcDue: RPCDue,
      voteGranted: Int,
      commitIndex: Index,
      lastApplied: Index,
      electionTimeout: Instant
  ) extends State:
    def addVote(peer: MemberId) =
      this.copy(voteGranted = voteGranted + 1)

    def ackRpc(peer: MemberId) =
      this.copy(rpcDue = rpcDue.ack(peer))

    def withRPCDue(from: MemberId, when: Instant) =
      this.copy(rpcDue = rpcDue.set(from, when))

  case class Leader(
      nextIndex: NextIndex,
      matchIndex: MatchIndex,
      // rpcDue: RPCDue,
      heartbeatDue: HeartbeatDue,
      replicationStatus: ReplicationStatus,
      commitIndex: Index,
      lastApplied: Index
  ) extends State:

    def withMatchIndex(from: MemberId, index: Index) =
      this.copy(matchIndex = matchIndex.set(from, index))

    def withNextIndex(from: MemberId, index: Index) =
      this.copy(nextIndex = nextIndex.set(from, index))

    def withPause(from: MemberId) =
      this.copy(replicationStatus = replicationStatus.pause(from))

    def withResume(from: MemberId) =
      this.copy(replicationStatus = replicationStatus.resume(from))

    def withSnapshot(from: MemberId, now: Instant, index: Index) =
      this.copy(replicationStatus = replicationStatus.snapshot(from, now, index))

    def withSnaphotResponse(from: MemberId, now: Instant, responseIndex: Index, done: Boolean) =
      this.copy(replicationStatus = replicationStatus.snapshotResponse(from, now, responseIndex, done))

    def withSnapshotFailure(from: MemberId, now: Instant, responseIndex: Index) =
      this.copy(replicationStatus = replicationStatus.snapshotFailure(from, now, responseIndex))

    // def withRPCDueNow(from: MemberId) =
    //   this.copy(rpcDue = rpcDue.now(from))

    // def withRPCDue(from: MemberId, when: Instant) =
    //   this.copy(rpcDue = rpcDue.set(from, when))

    def withHeartbeatDue(from: MemberId, when: Instant) =
      this.copy(heartbeatDue = heartbeatDue.set(from, when))
