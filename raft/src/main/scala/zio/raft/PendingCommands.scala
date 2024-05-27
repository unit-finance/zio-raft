package zio.raft

import zio.{Ref, ZIO}

case class PendingCommands(ref: Ref[Map[Index, Any]]):
  def complete[R](index: Index, response: R) =
    for
      maybePromise <- ref
        .modify(map => (map.get(index), map.removed(index)))
        .map(_.asInstanceOf[Option[CommandPromise[R]]])
      _ <- ZIO.foreachDiscard(maybePromise)(promise => promise.succeed(response))
    yield ()

  def add[R](index: Index, promise: CommandPromise[R]) =
    ref.update(_ + (index -> promise))

  def reset(leaderId: Option[MemberId]) =
    for
      promises <- ref.getAndSet(Map.empty).map(_.values.map(_.asInstanceOf[CommandPromise[Any]]))
      _ <- ZIO.foreach(promises)(_.fail(NotALeaderError(leaderId)))
    yield ()

object PendingCommands:
  def make =
    for ref <- Ref.make(Map.empty[Index, Any])
    yield PendingCommands(ref)
