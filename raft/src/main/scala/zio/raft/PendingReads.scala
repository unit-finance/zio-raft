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
  def enqueue(entry: PendingReadEntry[S]): PendingReads[S] =
    PendingReads(entries :+ entry)

  def dequeue(upToIndex: Index): (List[PendingReadEntry[S]], PendingReads[S]) =
    val (completed, remaining) = entries.partition(_.enqueuedAtIndex <= upToIndex)
    (completed, PendingReads(remaining))

  def complete(upToIndex: Index, state: S): UIO[PendingReads[S]] =
    dequeue(upToIndex) match
      case (completed, remaining) =>
        ZIO
          .foreach(completed)(_.promise.succeed(state))
          .as(remaining)

  def reset(leaderId: Option[MemberId]): UIO[PendingReads[S]] =
    ZIO
      .foreach(entries)(_.promise.fail(NotALeaderError(leaderId)))
      .as(PendingReads(List.empty))

  def isEmpty: Boolean = entries.isEmpty

  def size: Int = entries.size

object PendingReads:
  def empty[S]: PendingReads[S] = PendingReads(List.empty)
