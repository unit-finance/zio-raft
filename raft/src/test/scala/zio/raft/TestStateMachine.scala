package zio.raft

import zio.UIO
import zio.stream.Stream
import zio.stream.ZStream


sealed trait TestCommands extends Command:
  type Response = Int
case object Increase extends TestCommands
case object Get extends TestCommands

case class TestStateMachine(state: Int, enableSnapshot: Boolean) extends StateMachine[TestCommands]:
  def apply(command: TestCommands): (command.Response, StateMachine[TestCommands]) = 
    command match
      case Increase => (state + 1, TestStateMachine(state + 1, enableSnapshot))
      case Get => (state, TestStateMachine(state, enableSnapshot))     

  override def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[StateMachine[TestCommands]] = 
    stream.runCollect.map(b => TestStateMachine(new String(b.toArray).toInt, enableSnapshot))

  override def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean = enableSnapshot

  override def takeSnapshot: Stream[Nothing, Byte] = ZStream.fromIterable(state.toString().getBytes())


object TestStateMachine:
    def make(enableSnapshot: Boolean) = TestStateMachine(0, enableSnapshot)
        