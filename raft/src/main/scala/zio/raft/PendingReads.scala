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
  case class PendingHeartbeat[S](promise: Promise[NotALeaderError, S], timestamp: Instant, peersHeartbeats: Peers = Set.empty) extends PendingReadEntry[S] {
    def hasMajority(numberOfServers: Int): Boolean = peersHeartbeats.size + 1 > numberOfServers / 2
  }

private implicit def pendingHeartbeatOrdering[S]: Ordering[PendingHeartbeat[S]] = Ordering.by(_.timestamp)
private implicit def pendingCommandOrdering[S]: Ordering[PendingCommand[S]] = Ordering.by(_.enqueuedAtIndex.value)

// TODO (Eran): fix all naming
case class PendingReads[S](readsPendingCommands: InsertSortList[PendingCommand[S]], readsPendingHeartbeats: InsertSortList[PendingHeartbeat[S]]):
  def withReadPendingCommand(promise: Promise[NotALeaderError, S], commandIndex: Index): PendingReads[S] =
    this.copy(readsPendingCommands = readsPendingCommands.withSortedInsert(PendingCommand(promise, commandIndex)))

  def withReadPendingHeartbeat(promise: Promise[NotALeaderError, S], timestamp: Instant, members: Peers): PendingReads[S] =
    this.copy(readsPendingHeartbeats = readsPendingHeartbeats.withSortedInsert(PendingHeartbeat(promise, timestamp)))

  def withCommandCompleted(commandIndex: Index, stateAfterApply: S): UIO[PendingReads[S]] =
    if readsPendingCommands.isEmpty || readsPendingCommands.head.enqueuedAtIndex > commandIndex then ZIO.succeed(this)
    else
      readsPendingCommands.span(_.enqueuedAtIndex <= commandIndex) match
        case (completed, remaining) => ZIO.foreach(completed)(_.promise.succeed(stateAfterApply)).as(this.copy(readsPendingCommands = remaining))

  def withHeartbeatResponse(memberId: MemberId, timestamp: Instant, state: S, numberOfServers: Int): UIO[PendingReads[S]] = 
    if readsPendingHeartbeats.isEmpty then ZIO.succeed(this)
    else
      readsPendingHeartbeats.span(_.timestamp.compareTo(timestamp) <= 0) match
        case (relevantForHeartbeat, remaining) => 
          relevantForHeartbeat
          .map(i => i.copy(peersHeartbeats = i.peersHeartbeats + memberId))
          .partition(_.hasMajority(numberOfServers)) match
            case (withMajority, heartbeatStillRequired) => 
              ZIO.foreach(withMajority)(_.promise.succeed(state)).as(this.copy(readsPendingHeartbeats = InsertSortList(remaining.list ++ heartbeatStillRequired)))
          

  def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
    // TODO (eran): improve this by using a stream
    ZIO.foreach(readsPendingCommands ++ readsPendingHeartbeats)(_.promise.fail(NotALeaderError(leaderId))).unit

object PendingReads:
  def empty[S]: PendingReads[S] = PendingReads(InsertSortList.empty, InsertSortList.empty)
