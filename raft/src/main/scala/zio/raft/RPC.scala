package zio.raft

import zio.{UIO, ZIO, Queue}
import zio.Has

trait RPC[A <: Command]:
  def sendRequestVoteResponse(
      candidateId: MemberId,
      response: RequestVoteResult
  ): UIO[Unit]
  def sendAppendEntriesResponse(
      leaderId: MemberId,
      response: AppendEntriesResult
  ): UIO[Unit]
  def sendAppendEntires(
      peer: MemberId,
      request: AppendEntriesRequest[A]
  ): UIO[Unit]
  def sendRequestVote(peer: MemberId, m: RequestVoteRequest): UIO[Unit]
  def broadcastAppendEntires(request: AppendEntriesRequest[A]): UIO[Unit]
  def incomingMessages: Queue[RPCMessage]

