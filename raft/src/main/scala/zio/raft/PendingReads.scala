package zio.raft

import zio.Promise
import java.time.Instant
import zio.UIO
import zio.ZIO

case class PendingReadEntry[S](
    promise: Promise[NotALeaderError, S],
    enqueuedAtIndex: Index,
    timestamp: Instant
)

case class PendingReads[S](entries: List[PendingReadEntry[S]]):
  def withAdded(entry: PendingReadEntry[S]): PendingReads[S] =
    PendingReads(entries :+ entry)

  def withCompleted(upToIndex: Index, state: S): UIO[PendingReads[S]] =
    entries.partition(_.enqueuedAtIndex <= upToIndex) match
      case (completed, remaining) =>
        ZIO
          .foreach(completed)(_.promise.succeed(state))
          .as(PendingReads(remaining))

  def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
    ZIO
      .foreach(entries)(_.promise.fail(NotALeaderError(leaderId)))
      .unit

object PendingReads:
  def empty[S]: PendingReads[S] = PendingReads(List.empty)
