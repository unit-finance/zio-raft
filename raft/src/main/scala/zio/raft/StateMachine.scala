package zio.raft

import zio.raft.Command
import zio.{ZIO, UIO, Ref}
import zio.stream.Stream

trait StateMachine[A <: Command]:
  def apply(command: A): (command.Response, StateMachine[A]) // TODO: should we use zpure here?
  
  def takeSnapshot: Stream[Nothing, Byte]

  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[StateMachine[A]]

  def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean

