package zio.raft

import zio.UIO
import zio.stream.{Stream, ZStream}
import zio.prelude.EState

sealed trait TestCommands extends Command:
  type Response = Int
case object Increase extends TestCommands
case object Get extends TestCommands

case class TestStateMachine(state: Int, enableSnapshot: Boolean) extends StateMachine[Int, Nothing, TestCommands]:
  def apply(command: TestCommands): EState[Int, Nothing, Any] =
    command match
      case Increase => EState.succeed(((), state + 1))
      case Get      => EState.succeed(((), state))

  override def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[Int] =
    stream.runCollect.map(b => new String(b.toArray).toInt)

  override def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
    enableSnapshot

  override def takeSnapshot: Stream[Nothing, Byte] = ZStream.fromIterable(state.toString().getBytes())

object TestStateMachine:
  def make(enableSnapshot: Boolean) = TestStateMachine(0, enableSnapshot)
