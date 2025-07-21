package zio.raft

import zio.Promise
import java.time.Instant
import zio.UIO
import zio.ZIO
import zio.raft.PendingReadEntry.PendingCommand
import zio.raft.PendingReadEntry.PendingHeartbeat

sealed trait PendingReadEntry[S]:
  val promise: Promise[NotALeaderError, S]

object PendingReadEntry:
  case class PendingCommand[S](promise: Promise[NotALeaderError, S], enqueuedAtIndex: Index) extends PendingReadEntry[S]
  case class PendingHeartbeat[S](promise: Promise[NotALeaderError, S], timestamp: Instant, members: Peers) extends PendingReadEntry[S]



case class PendingReads[S](readsPendingCommands: List[PendingCommand[S]], readsPendingHeartbeats: List[PendingHeartbeat[S]]):
  private def addReadPendingCommand(entry: PendingCommand[S]): PendingReads[S] =
    if (readsPendingCommands.isEmpty || entry.enqueuedAtIndex >= readsPendingCommands.last.enqueuedAtIndex) then 
      this.copy(readsPendingCommands = readsPendingCommands :+ entry)
    else
      val (before, after) = readsPendingCommands.span(_.enqueuedAtIndex <= entry.enqueuedAtIndex)
      this.copy(readsPendingCommands = before ++ (entry :: after))

  def addReadPendingHeartbeat(entry: PendingHeartbeat[S]): PendingReads[S] =
    // TODO (eran): how do we want to use timestamp? do we want to sort the list?
    this.copy(readsPendingHeartbeats = readsPendingHeartbeats :+ entry)

  def withAdded(entry: PendingReadEntry[S]): PendingReads[S] = entry match
    case c: PendingCommand[S]   => addReadPendingCommand(c)
    case h: PendingHeartbeat[S] => addReadPendingHeartbeat(h)

  def withCommandCompleted(upToIndex: Index, state: S): UIO[PendingReads[S]] =
    if readsPendingCommands.isEmpty || readsPendingCommands.last.enqueuedAtIndex > upToIndex then ZIO.succeed(this)
    else
      readsPendingCommands.span(_.enqueuedAtIndex <= upToIndex) match
        case (completed, remaining) => ZIO.foreach(completed)(_.promise.succeed(state)).as(this.copy(readsPendingCommands = remaining))

  // TODO (eran): implement this
  def withHeartbeatReceived(memberId: MemberId, timestamp: Instant): UIO[PendingReads[S]] = ???

  def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
    // TODO (eran): improve this by using a stream
    ZIO.foreach(readsPendingCommands ++ readsPendingHeartbeats)(_.promise.fail(NotALeaderError(leaderId))).unit

object PendingReads:
  def empty[S]: PendingReads[S] = PendingReads(List.empty, List.empty)
