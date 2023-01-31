package zio.raft

import zio.{UIO, ZIO, URIO, Has}

trait Stable:
  def currentTerm: UIO[Term]
  def newTerm(
      term: Term,
      voteFor: Option[MemberId] = None
  ): UIO[Term] // Should also set the voteFor atomitcally
  def voteFor(memberId: MemberId): UIO[MemberId]
  def votedFor: UIO[Option[MemberId]]

