package zio.raft

import zio.UIO
import zio.stream.{Stream, ZStream}
import zio.prelude.fx.ZPure

sealed trait TestCommands extends Command:
  type Response = Int
case object Increase extends TestCommands
case object Get extends TestCommands

case class TestStateMachine(enableSnapshot: Boolean) extends StateMachine[Any, Int, TestCommands]:
  override def emptyState: Int = 0

  def apply(command: TestCommands): ZPure[Any, Int, Int, Any, Nothing, command.Response] =
    command match
      case Increase => ZPure.modify(s => (s + 1, s + 1))
      case Get      => ZPure.get

  override def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[Int] =
    stream.runCollect.map(b => new String(b.toArray).toInt)

  override def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
    enableSnapshot

  override def takeSnapshot(state: Int): Stream[Nothing, Byte] = ZStream.fromIterable(state.toString().getBytes())

object TestStateMachine:
  def make(enableSnapshot: Boolean) = TestStateMachine(enableSnapshot)
