package zio.raft

import zio.{UIO, ZIO}

case class PendingCommands(map: Map[Index, Any]):
  def complete[R](index: Index, response: R): UIO[PendingCommands] =
    ZIO
      .foreach(map.get(index).asInstanceOf[Option[CommandPromise[R]]])(_.succeed(response))
      .as(PendingCommands(map.removed(index)))

  def add[R](index: Index, promise: CommandPromise[R]): PendingCommands =
    PendingCommands(map + (index -> promise))

  def reset(leaderId: Option[MemberId]): UIO[PendingCommands] =
    ZIO
      .foreach(map.values.map(_.asInstanceOf[CommandPromise[Any]]))(_.fail(NotALeaderError(leaderId)))
      .as(PendingCommands(Map.empty[Index, Any]))

object PendingCommands:
  def empty: PendingCommands = PendingCommands(Map.empty[Index, Any])
