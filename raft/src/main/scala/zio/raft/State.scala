package zio.raft

import java.time.Instant

sealed trait State[S]:
  val commitIndex: Index
  val lastApplied: Index

  def withCommitIndex(commitIndex: Index): State[S] =
    this match
      case f: State.Follower[S]  => f.copy(commitIndex = commitIndex)
      case f: State.Candidate[S] => f.copy(commitIndex = commitIndex)
      case f: State.Leader[S]    => f.copy(commitIndex = commitIndex)

  def increaseLastApplied: State[S] = this match
    case f: State.Follower[S]  => f.copy(lastApplied = lastApplied.plusOne)
    case f: State.Candidate[S] => f.copy(lastApplied = lastApplied.plusOne)
    case f: State.Leader[S]    => f.copy(lastApplied = lastApplied.plusOne)

object State:
  case class Follower[S](commitIndex: Index, lastApplied: Index, electionTimeout: Instant, leaderId: Option[MemberId])
      extends State[S]
  case class Candidate[S](
      rpcDue: RPCDue,
      voteGranted: Int,
      commitIndex: Index,
      lastApplied: Index,
      electionTimeout: Instant
  ) extends State[S]:
    def addVote(peer: MemberId): Candidate[S] =
      this.copy(voteGranted = voteGranted + 1)

    def ackRpc(peer: MemberId): Candidate[S] =
      this.copy(rpcDue = rpcDue.ack(peer))

    def withRPCDue(from: MemberId, when: Instant): Candidate[S] =
      this.copy(rpcDue = rpcDue.set(from, when))

  case class Leader[S](
      nextIndex: NextIndex,
      matchIndex: MatchIndex,
      // rpcDue: RPCDue,
      heartbeatDue: HeartbeatDue,
      replicationStatus: ReplicationStatus,
      commitIndex: Index,
      lastApplied: Index,
      pendingReads: PendingReads[S],
      pendingCommands: PendingCommands
  ) extends State[S]:

    def withMatchIndex(from: MemberId, index: Index): Leader[S] =
      this.copy(matchIndex = matchIndex.set(from, index))

    def withNextIndex(from: MemberId, index: Index): Leader[S] =
      this.copy(nextIndex = nextIndex.set(from, index))

    def withPause(from: MemberId): Leader[S] =
      this.copy(replicationStatus = replicationStatus.pause(from))

    def withResume(from: MemberId): Leader[S] =
      this.copy(replicationStatus = replicationStatus.resume(from))

    def withSnapshot(from: MemberId, now: Instant, index: Index): Leader[S] =
      this.copy(replicationStatus = replicationStatus.snapshot(from, now, index))

    def withSnaphotResponse(from: MemberId, now: Instant, responseIndex: Index, done: Boolean): Leader[S] =
      this.copy(replicationStatus = replicationStatus.snapshotResponse(from, now, responseIndex, done))

    def withSnapshotFailure(from: MemberId, now: Instant, responseIndex: Index): Leader[S] =
      this.copy(replicationStatus = replicationStatus.snapshotFailure(from, now, responseIndex))

    // def withRPCDueNow(from: MemberId) =
    //   this.copy(rpcDue = rpcDue.now(from))

    // def withRPCDue(from: MemberId, when: Instant) =
    //   this.copy(rpcDue = rpcDue.set(from, when))

    def withHeartbeatDue(from: MemberId, when: Instant): Leader[S] =
      this.copy(heartbeatDue = heartbeatDue.set(from, when))

    // Pending reads management methods
    def withPendingRead(entry: PendingReadEntry[S]): Leader[S] =
      this.copy(pendingReads = pendingReads.enqueue(entry))

    def withCompletedReads(upToIndex: Index, state: S): Leader[S] =
      this.copy(pendingReads = pendingReads.complete(upToIndex, state))

    def getPendingReadsUpToIndex(index: Index): List[PendingReadEntry[S]] =
      pendingReads.filterByIndex(index)
