package zio.raft

import zio.{UIO, ZIO}
import zio.Has
import zio.stream.Stream

trait RPC[A <: Command]:
  def sendRequestVoteResponse(
      candidateId: MemberId,
      response: RequestVoteResult[A]
  ): UIO[Unit]
  def sendAppendEntriesResponse(
      leaderId: MemberId,
      response: AppendEntriesResult[A]
  ): UIO[Unit]
  def sendAppendEntires(
      peer: MemberId,
      request: AppendEntriesRequest[A]
  ): UIO[Unit]
  def sendRequestVote(peer: MemberId, m: RequestVoteRequest[A]): UIO[Unit]
  def incomingMessages: Stream[Nothing, RPCMessage[A]]

