package zio.raft

import zio.{UIO, ZIO}

case class PendingCommands(map: Map[Index, Any]):
  def withCompleted[R](queue: zio.Queue[RaftAction], index: Index, response: R): UIO[PendingCommands] =
    ZIO
      .foreach(map.get(index).asInstanceOf[Option[CommandContinuation[R]]]) { continuation =>
        queue.offer(RaftAction.CommandContinuation(continuation(Right(response))))
      }
      .as(PendingCommands(map.removed(index)))

  def withAdded[R](index: Index, continuation: CommandContinuation[R]): PendingCommands =
    PendingCommands(map + (index -> continuation))

  def stepDown(queue: zio.Queue[RaftAction], leaderId: Option[MemberId]): UIO[Unit] =
    ZIO
      .foreach(map.values.map(_.asInstanceOf[CommandContinuation[Any]])) { continuation =>
        queue.offer(RaftAction.CommandContinuation(continuation(Left(NotALeaderError(leaderId)))))
      }
      .unit

  def lastIndex: Option[Index] = Option.when(map.keys.nonEmpty)(map.keys.max(using Ordering.by(_.value)))

object PendingCommands:
  def empty: PendingCommands = PendingCommands(Map.empty[Index, Any])
