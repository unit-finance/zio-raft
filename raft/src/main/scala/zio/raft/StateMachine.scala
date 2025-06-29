package zio.raft

import zio.UIO
import zio.stream.Stream
import zio.prelude.EState

trait StateMachine[S, E, A <: Command]:
  def apply(command: A): EState[S, E, command.Response]

  def takeSnapshot: Stream[Nothing, Byte]

  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[S]

  def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean
