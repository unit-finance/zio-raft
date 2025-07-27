package zio.raft

import zio.Promise
import java.time.Instant
import zio.UIO
import zio.ZIO
import zio.raft.PendingReadEntry.PendingCommand
import zio.raft.PendingReadEntry.PendingHeartbeat

 // TODO (eran): can this be converted to enum? check if compiles to scala 2 and if looks better

private sealed trait PendingReadEntry[S]:
  val promise: Promise[NotALeaderError, S]

private object PendingReadEntry:
  case class PendingCommand[S](promise: Promise[NotALeaderError, S], enqueuedAtIndex: Index) extends PendingReadEntry[S]
  case class PendingHeartbeat[S](promise: Promise[NotALeaderError, S], timestamp: Instant, numberOfPeers: Int, peersHeartbeats: Peers = Set.empty) extends PendingReadEntry[S]

private implicit def pendingHeartbeatOrdering[S]: Ordering[PendingHeartbeat[S]] = Ordering.by(_.timestamp)
private implicit def pendingCommandOrdering[S]: Ordering[PendingCommand[S]] = Ordering.by(_.enqueuedAtIndex.value)

object InsertSortList:
  def empty[A](implicit ordering: Ordering[A]): InsertSortList[A] = InsertSortList(List.empty)

case class InsertSortList[A](list: List[A])(implicit ordering: Ordering[A]) extends Iterable[A]:

  override def iterator: Iterator[A] = list.iterator

  def withSortedInsert(a: A): InsertSortList[A] =
    if (list.isEmpty || ordering.gteq(a, list.last)) then InsertSortList(list :+ a)
    else
      val (before, after) = list.span(ordering.lteq(_, a))
      InsertSortList(before ++ (a :: after))

  override def isEmpty: Boolean = list.isEmpty
  override def last: A = list.last
  override def span(p: A => Boolean): (InsertSortList[A], InsertSortList[A]) =
    val (before, after) = list.span(p)
    (InsertSortList(before), InsertSortList(after))

  def ++(other: InsertSortList[A]): InsertSortList[A] = InsertSortList(list ++ other.list)

// TODO (Eran): fix all naming

case class PendingReads[S](readsPendingCommands: InsertSortList[PendingCommand[S]], readsPendingHeartbeats: InsertSortList[PendingHeartbeat[S]]):
  def withReadPendingCommand(promise: Promise[NotALeaderError, S], commandIndex: Index): PendingReads[S] =
    this.copy(readsPendingCommands = readsPendingCommands.withSortedInsert(PendingCommand(promise, commandIndex)))

  def withReadPendingHeartbeat(promise: Promise[NotALeaderError, S], timestamp: Instant, members: Peers): PendingReads[S] =
    this.copy(readsPendingHeartbeats = readsPendingHeartbeats.withSortedInsert(PendingHeartbeat(promise, timestamp, members.size)))

  def withCommandCompleted(commandIndex: Index, stateAfterApply: S): UIO[PendingReads[S]] =
    if readsPendingCommands.isEmpty || readsPendingCommands.last.enqueuedAtIndex > commandIndex then ZIO.succeed(this)
    else
      readsPendingCommands.span(_.enqueuedAtIndex <= commandIndex) match
        case (completed, remaining) => ZIO.foreach(completed)(_.promise.succeed(stateAfterApply)).as(this.copy(readsPendingCommands = remaining))

  def withHeartbeatResponse(memberId: MemberId, timestamp: Instant, state: S): UIO[PendingReads[S]] = 
    if readsPendingHeartbeats.isEmpty then ZIO.succeed(this)
    else
      readsPendingHeartbeats.span(_.timestamp.compareTo(timestamp) <= 0) match
        case (relevantForHeartbeat, remaining) => 
          relevantForHeartbeat
          .map(i => i.copy(peersHeartbeats = i.peersHeartbeats + memberId))
          .partition(s => s.peersHeartbeats.size <= s.numberOfPeers / 2) match
            case (heartbeatCompleted, heartbeatStillRequired) => 
              ZIO.foreach(heartbeatCompleted)(_.promise.succeed(state)).as(this.copy(readsPendingHeartbeats = InsertSortList(remaining.list ++ heartbeatStillRequired)))
          

  def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
    // TODO (eran): improve this by using a stream
    ZIO.foreach(readsPendingCommands ++ readsPendingHeartbeats)(_.promise.fail(NotALeaderError(leaderId))).unit

object PendingReads:
  def empty[S]: PendingReads[S] = PendingReads(InsertSortList.empty, InsertSortList.empty)
