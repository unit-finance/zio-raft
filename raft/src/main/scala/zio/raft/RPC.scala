package zio.raft

import zio.{UIO, ZIO, Queue}

trait RPC:  
  def sendRequestVoteResponse(candidateId: MemberId, response: RequestVoteResult): UIO[Unit]
  def sendAppendEntriesResponse(leaderId: MemberId, response: AppendEntriesResult): UIO[Unit]
  def sendAppendEntires(peer: MemberId, request: AppendEntriesRequest) : UIO[Unit]
  def sendRequestVote(peer: MemberId, m: RequestVoteRequest): UIO[Unit]
  def broadcastAppendEntires(request: AppendEntriesRequest) : UIO[Unit]
  def incomingMessages: Queue[RPCMessage]

object RPC:
  def sendRequestVoteResponse(candidateId: MemberId, response: RequestVoteResult) =
    for
      rpc <- ZIO.service[RPC]
      _ <- rpc.sendRequestVoteResponse(candidateId, response)
    yield ()

  def sendAppendEntriesResponse(leaderId: MemberId, response: AppendEntriesResult) =
    for
      rpc <- ZIO.service[RPC]
      _ <- rpc.sendAppendEntriesResponse(leaderId, response)
    yield ()

  def sendAppendEntires(peer: MemberId, request: AppendEntriesRequest) =
    for
      rpc <- ZIO.service[RPC]
      _ <- rpc.sendAppendEntires(peer, request)
    yield ()    

  def broadcastAppendEntires(request: AppendEntriesRequest) =
    for
      rpc <- ZIO.service[RPC]
      _ <- rpc.broadcastAppendEntires(request)
    yield ()    

  def sendRequestVote(peer: MemberId, m: RequestVoteRequest) = 
    for
      rpc <- ZIO.service[RPC]
      _ <- rpc.sendRequestVote(peer, m)
    yield ()

  def incomingMessages: Queue[RPCMessage] = ZIO.serviceWith[RPC](_.incomingMessages)