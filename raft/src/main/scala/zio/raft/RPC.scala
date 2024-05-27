package zio.raft

import zio.UIO
import zio.stream.Stream

trait RPC[A <: Command]:
  def sendRequestVote(peer: MemberId, m: RequestVoteRequest[A]): UIO[Unit]
  def sendRequestVoteResponse(
      candidateId: MemberId,
      response: RequestVoteResult[A]
  ): UIO[Unit]
  def sendHeartbeat(peer: MemberId, m: HeartbeatRequest[A]): UIO[Unit]
  def sendHeartbeatResponse(leaderId: MemberId, m: HeartbeatResponse[A]): UIO[Unit]
  def sendAppendEntriesResponse(
      leaderId: MemberId,
      response: AppendEntriesResult[A]
  ): UIO[Unit]
  def sendAppendEntries(
      peer: MemberId,
      request: AppendEntriesRequest[A]
  ): UIO[Boolean]

  def sendInstallSnapshot(peer: MemberId, m: InstallSnapshotRequest[A]): UIO[Unit]
  def sendInstallSnapshotResponse(peer: MemberId, m: InstallSnapshotResult[A]): UIO[Unit]
  def incomingMessages: Stream[Nothing, RPCMessage[A]]
