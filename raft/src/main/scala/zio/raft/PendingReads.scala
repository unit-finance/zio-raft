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
    // Optimize for the common case: new entry has higher index than all existing entries
    if (entries.isEmpty || entry.enqueuedAtIndex >= entries.last.enqueuedAtIndex) then
      PendingReads(entries :+ entry)
    else
      // Find the correct insertion point to maintain sorted order
      val (before, after) = entries.span(_.enqueuedAtIndex <= entry.enqueuedAtIndex)
      PendingReads(before ++ (entry :: after))

  def withCompleted(upToIndex: Index, state: S): UIO[PendingReads[S]] =
    entries.span(_.enqueuedAtIndex <= upToIndex) match
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
