package zio.raft

import java.time.Instant
import zio.UIO
import zio.ZIO
import zio.raft.PendingReadEntry.PendingCommand
import zio.raft.PendingReadEntry.PendingHeartbeat

case class PendingReads[S](
  readsPendingCommands: InsertSortList[PendingReadEntry.PendingCommand[S]],
  readsPendingHeartbeats: InsertSortList[PendingReadEntry.PendingHeartbeat[S]]
):
  def withPendingCommand(continuation: ReadContinuation[S], commandIndex: Index): PendingReads[S] =
    this.copy(readsPendingCommands =
      readsPendingCommands.withSortedInsert(PendingReadEntry.PendingCommand(continuation, commandIndex))
    )

  def withPendingHeartbeat(continuation: ReadContinuation[S], timestamp: Instant): PendingReads[S] =
    this.copy(readsPendingHeartbeats =
      readsPendingHeartbeats.withSortedInsert(PendingReadEntry.PendingHeartbeat(continuation, timestamp))
    )

  def resolveReadsForCommand(
    queue: zio.Queue[RaftAction],
    commandIndex: Index,
    stateAfterApply: S
  ): UIO[PendingReads[S]] =
    if readsPendingCommands.isEmpty || readsPendingCommands.head.enqueuedAtIndex > commandIndex then ZIO.succeed(this)
    else
      readsPendingCommands.span(_.enqueuedAtIndex <= commandIndex) match
        case (completed, remaining) =>
          ZIO.foreach(completed)(entry =>
            queue.offer(
              RaftAction.ReadContinuation(
                entry.continuation(Right(stateAfterApply))
              )
            )
          ).as(this.copy(readsPendingCommands = remaining))

  def resolveReadsForHeartbeat(
    queue: zio.Queue[RaftAction],
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
                .foreach(withMajority) { entry =>
                  queue.offer(
                    RaftAction.ReadContinuation(
                      entry.continuation(Right(state))
                    )
                  )
                }
                .as(this.copy(readsPendingHeartbeats = InsertSortList(remaining.list ++ heartbeatStillRequired)))

  def stepDown(queue: zio.Queue[RaftAction], leaderId: Option[MemberId]): UIO[Unit] =
    ZIO.foreach(readsPendingCommands ++ readsPendingHeartbeats)(entry =>
      queue.offer(
        RaftAction.ReadContinuation(
          entry.continuation(Left(NotALeaderError(leaderId)))
        )
      )
    ).unit
end PendingReads

object PendingReads:
  def empty[S]: PendingReads[S] = PendingReads(InsertSortList.empty, InsertSortList.empty)

private enum PendingReadEntry[S](val continuation: ReadContinuation[S]):
  case PendingCommand(override val continuation: ReadContinuation[S], enqueuedAtIndex: Index)
      extends PendingReadEntry[S](continuation)
  case PendingHeartbeat(
    override val continuation: ReadContinuation[S],
    timestamp: Instant,
    peersHeartbeats: Peers = Set.empty
  ) extends PendingReadEntry[S](continuation)

object PendingReadEntry:
  extension [S](pendingHeartbeat: PendingHeartbeat[S])
    def hasMajority(numberOfServers: Int): Boolean = pendingHeartbeat.peersHeartbeats.size + 1 > numberOfServers / 2

private given pendingHeartbeatOrdering[S]: Ordering[PendingReadEntry.PendingHeartbeat[S]] = Ordering.by(_.timestamp)
private given pendingCommandOrdering[S]: Ordering[PendingReadEntry.PendingCommand[S]] =
  Ordering.by(_.enqueuedAtIndex.value)
