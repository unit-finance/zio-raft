package zio.raft

import zio.{UIO, ZIO}

case class PendingCommands(map: Map[Index, Any]):
  def withCompleted[R](index: Index, response: R): UIO[PendingCommands] =
    ZIO
      .foreach(map.get(index).asInstanceOf[Option[CommandPromise[R]]])(_.succeed(response))
      .as(PendingCommands(map.removed(index)))

  // TODO (eran): we need Option[CommandPromise[R]] instead of CommandPromise[R] to handle NoopCommandMessage
  def withAdded[R](index: Index, promise: CommandPromise[R]): PendingCommands =
    PendingCommands(map + (index -> promise))

  def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
    ZIO
      .foreach(map.values.map(_.asInstanceOf[CommandPromise[Any]]))(_.fail(NotALeaderError(leaderId)))
      .unit

object PendingCommands:
  def empty: PendingCommands = PendingCommands(Map.empty[Index, Any])
