package zio.raft

import zio.Promise
import zio.UIO
import zio.ZIO

case class PendingReadEntry[S](
    promise: Promise[NotALeaderError, S],
    enqueuedAtIndex: Index
)

case class PendingReads[S](entries: List[PendingReadEntry[S]]):
  def withAdded(entry: PendingReadEntry[S]): PendingReads[S] =
    if (entries.isEmpty || entry.enqueuedAtIndex >= entries.last.enqueuedAtIndex) then PendingReads(entries :+ entry)
    else
      val (before, after) = entries.span(_.enqueuedAtIndex <= entry.enqueuedAtIndex)
      PendingReads(before ++ (entry :: after))

  def withCompleted(upToIndex: Index, state: S): UIO[PendingReads[S]] =
          .as(PendingReads(remaining))
    if entries.isEmpty || entries.last.enqueuedAtIndex > upToIndex then ZIO.succeed(this)
    else
      entries.span(_.enqueuedAtIndex <= upToIndex) match
        case (completed, remaining) =>
          for
            _ <- ZIO.debug(s"withCompleted completed=$completed remaining=$remaining")
            _ <- ZIO.foreach(completed)(_.promise.succeed(state))

  def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
    ZIO
      .foreach(entries)(_.promise.fail(NotALeaderError(leaderId)))
      .unit

object PendingReads:
  def empty[S]: PendingReads[S] = PendingReads(List.empty)
