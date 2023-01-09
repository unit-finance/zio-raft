package zio.raft

import zio.{UIO, ZIO, URIO, Has}

trait Stable
object Stable:
  def currentTerm: URIO[Has[Stable], Term] = ???
  def newTerm(term: Term, voteFor: Option[MemberId] = None) : URIO[Has[Stable], Term] = ??? // Should also set the voteFor atomitcally
  def voteFor(memberId: MemberId): URIO[Has[Stable], MemberId] = ???
  def votedFor: URIO[Has[Stable], Option[MemberId]] = ???