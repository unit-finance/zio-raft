package zio.raft

import zio.raft.Command
import zio.{ZIO, UIO, Ref}

trait StateMachine[A <: Command]:
  def apply(command: A): (command.Response, StateMachine[A]) // TODO: should we use zpure here?

