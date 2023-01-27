package zio.raft

import zio.raft.Command
import zio.{ZIO, UIO, Ref}

trait StateMachine[A <: Command]:
  def apply(command: A): UIO[StateMachine[A]]

