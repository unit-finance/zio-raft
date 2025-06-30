package zio.raft

import zio.UIO
import zio.stream.Stream
import zio.prelude.State

trait StateMachine[S, A <: Command]:

  def emptyState: S

  def apply(command: A): State[S, command.Response]

  def takeSnapshot(state: S): Stream[Nothing, Byte]

  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[S]

  def shouldTakeSnapshot(lastSnaphotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean
