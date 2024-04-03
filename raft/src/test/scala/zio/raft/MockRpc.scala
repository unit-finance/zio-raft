package zio.raft

import zio.UIO

import zio.stream
import zio.raft.StreamItem.Message
import zio.stream.ZStream

class MockRpc[A <: Command](val queue: zio.Queue[(MemberId, RPCMessage[A])]) extends RPC[A]:

  override def sendHeartbeat(peer: MemberId, m: HeartbeatRequest[A]): UIO[Unit] = queue.offer((peer, m)).unit

  override def sendRequestVote(peer: MemberId, m: RequestVoteRequest[A]): UIO[Unit] = queue.offer((peer, m)).unit

  override def sendAppendEntriesResponse(leaderId: MemberId, response: AppendEntriesResult[A]): UIO[Unit] = queue.offer((leaderId, response)).unit

  override def sendHeartbeatResponse(leaderId: MemberId, m: HeartbeatResponse[A]): UIO[Unit] = queue.offer((leaderId, m)).unit

  override def sendAppendEntries(peer: MemberId, request: AppendEntriesRequest[A]): UIO[Boolean] = queue.offer((peer, request)).unit.as(true)

  override def sendInstallSnapshotResponse(peer: MemberId, m: InstallSnapshotResult[A]): UIO[Unit] = queue.offer((peer, m)).unit

  override def incomingMessages = ZStream.empty

  override def sendInstallSnapshot(peer: MemberId, m: InstallSnapshotRequest[A]): UIO[Unit] = queue.offer((peer, m)).unit

  override def sendRequestVoteResponse(candidateId: MemberId, response: RequestVoteResult[A]): UIO[Unit] = queue.offer((candidateId, response)).unit

  
