package zio.raft

import zio.{UIO, ZIO}

case class PendingCommands(map: Map[Index, Any]):
  def withCompleted[R](index: Index, response: R): UIO[PendingCommands] =
    ZIO
      .foreach(map.get(index).asInstanceOf[Option[CommandPromise[R]]])(_.succeed(response))
      .as(PendingCommands(map.removed(index)))

  def withAdded[R](index: Index, promise: CommandPromise[R]): PendingCommands =
    PendingCommands(map + (index -> promise))

  def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
    ZIO
      .foreach(map.values.map(_.asInstanceOf[CommandPromise[Any]]))(_.fail(NotALeaderError(leaderId)))
      .unit

  def lastIndex: Option[Index] = Option.when(map.keys.nonEmpty)(map.keys.max(using Ordering.by(_.value)))

object PendingCommands:
  def empty: PendingCommands = PendingCommands(Map.empty[Index, Any])
