package zio.raft

import java.time.Instant
import zio.UIO
import zio.Promise

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

    def withHeartbeatDueFromAll: Leader[S] =
      this.copy(heartbeatDue = HeartbeatDue.empty)

    def withReadPendingCommand(promise: Promise[NotALeaderError, S], commandIndex: Index): Leader[S] =
      this.copy(pendingReads = pendingReads.withReadPendingCommand(promise, commandIndex))

    def withReadPendingHeartbeat(promise: Promise[NotALeaderError, S], timestamp: Instant): Leader[S] =
      this.copy(pendingReads = pendingReads.withReadPendingHeartbeat(promise, timestamp))

    def withPendingCommand[R](index: Index, promise: CommandPromise[R]): Leader[S] =
      this.copy(pendingCommands = pendingCommands.withAdded(index, promise))

    def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
      for
        _ <- pendingReads.stepDown(leaderId)
        _ <- pendingCommands.stepDown(leaderId)
      yield ()

    def completeCommands[R](index: Index, commandResponse: R, readState: S): UIO[Leader[S]] =
      for
        pendingCommands <- pendingCommands.withCompleted(index, commandResponse)
        pendingReads <- pendingReads.withCommandCompleted(index, readState)
      yield this.copy(pendingCommands = pendingCommands, pendingReads = pendingReads)

    def withHeartbeatResponse(memberId: MemberId, timestamp: Instant, state: S, numberOfServers: Int): UIO[Leader[S]] =
      for pendingReads <- pendingReads.withHeartbeatResponse(memberId, timestamp, state, numberOfServers)
      yield this.copy(pendingReads = pendingReads)

    def completeReads(index: Index, readState: S): UIO[Leader[S]] =
      for pendingReads <- pendingReads.withCommandCompleted(index, readState)
      yield this.copy(pendingReads = pendingReads)
