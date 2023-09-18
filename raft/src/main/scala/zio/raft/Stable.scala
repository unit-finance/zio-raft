package zio.raft

import zio.{UIO, ZIO, URIO, Has}
import zio.Ref

trait Stable:
  def currentTerm: UIO[Term]
  def newTerm(
      term: Term,
      voteFor: Option[MemberId] = None
  ): UIO[Term] // Should also set the voteFor atomitcally
  def voteFor(memberId: MemberId): UIO[MemberId]
  def votedFor: UIO[Option[MemberId]]

object Stable:
  def makeInMemoryManaged = 
    for 
      term <- Ref.make(Term(0)).toManaged_
      voteFor <- Ref.make(Option.empty[MemberId]).toManaged_
    yield new InMemoryStable(term, voteFor)

class InMemoryStable(term: Ref[Term], voteFor: Ref[Option[MemberId]]) extends Stable:
  override def currentTerm = term.get
  override def newTerm(term: Term, voteFor: Option[MemberId]) = 
    this.term.set(term) *> this.voteFor.set(voteFor) *> this.term.get
  override def voteFor(memberId: MemberId) = voteFor.set(Some(memberId)) *> voteFor.get.map(_.get)
  override def votedFor = voteFor.get

