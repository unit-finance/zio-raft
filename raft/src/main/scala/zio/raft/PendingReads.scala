package zio.raft

import zio.Promise
import java.time.Instant
import zio.UIO
import zio.ZIO
import zio.raft.PendingReadEntry.PendingCommand
import zio.raft.PendingReadEntry.PendingHeartbeat

case class PendingReads[S](
  readsPendingCommands: InsertSortList[PendingReadEntry.PendingCommand[S]],
  readsPendingHeartbeats: InsertSortList[PendingReadEntry.PendingHeartbeat[S]]
):
  def withPendingCommand(promise: Promise[NotALeaderError, S], commandIndex: Index): PendingReads[S] =
    this.copy(readsPendingCommands =
      readsPendingCommands.withSortedInsert(PendingReadEntry.PendingCommand(promise, commandIndex))
    )

  def withPendingHeartbeat(promise: Promise[NotALeaderError, S], timestamp: Instant): PendingReads[S] =
    this.copy(readsPendingHeartbeats =
      readsPendingHeartbeats.withSortedInsert(PendingReadEntry.PendingHeartbeat(promise, timestamp))
    )

  def resolveReadsForCommand(commandIndex: Index, stateAfterApply: S): UIO[PendingReads[S]] =
    if readsPendingCommands.isEmpty || readsPendingCommands.head.enqueuedAtIndex > commandIndex then ZIO.succeed(this)
    else
      readsPendingCommands.span(_.enqueuedAtIndex <= commandIndex) match
        case (completed, remaining) =>
          ZIO.foreach(completed)(_.promise.succeed(stateAfterApply)).as(this.copy(readsPendingCommands = remaining))

  def resolveReadsForHeartbeat(
    memberId: MemberId,
    timestamp: Instant,
    state: S,
    numberOfServers: Int
  ): UIO[PendingReads[S]] =
    if readsPendingHeartbeats.isEmpty then ZIO.succeed(this)
    else
      readsPendingHeartbeats.span(_.timestamp.compareTo(timestamp) <= 0) match
        case (relevantForHeartbeat, remaining) =>
          relevantForHeartbeat
            .map(i => i.copy(peersHeartbeats = i.peersHeartbeats + memberId))
            .partition(_.hasMajority(numberOfServers)) match
            case (withMajority, heartbeatStillRequired) =>
              ZIO
                .foreach(withMajority)(_.promise.succeed(state))
                .as(this.copy(readsPendingHeartbeats = InsertSortList(remaining.list ++ heartbeatStillRequired)))

  def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
    ZIO.foreach(readsPendingCommands ++ readsPendingHeartbeats)(_.promise.fail(NotALeaderError(leaderId))).unit

object PendingReads:
  def empty[S]: PendingReads[S] = PendingReads(InsertSortList.empty, InsertSortList.empty)

private enum PendingReadEntry[S](val promise: Promise[NotALeaderError, S]):
  case PendingCommand(override val promise: Promise[NotALeaderError, S], enqueuedAtIndex: Index)
      extends PendingReadEntry[S](promise)
  case PendingHeartbeat(
    override val promise: Promise[NotALeaderError, S],
    timestamp: Instant,
    peersHeartbeats: Peers = Set.empty
  ) extends PendingReadEntry[S](promise)

object PendingReadEntry:
  extension [S](pendingHeartbeat: PendingHeartbeat[S])
    def hasMajority(numberOfServers: Int): Boolean = pendingHeartbeat.peersHeartbeats.size + 1 > numberOfServers / 2

private given pendingHeartbeatOrdering[S]: Ordering[PendingReadEntry.PendingHeartbeat[S]] = Ordering.by(_.timestamp)
private given pendingCommandOrdering[S]: Ordering[PendingReadEntry.PendingCommand[S]] =
  Ordering.by(_.enqueuedAtIndex.value)
