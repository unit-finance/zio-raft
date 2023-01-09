package zio.raft

import zio.raft.Command
import zio.{ZIO, UIO, Ref}

trait StateMachine:
  def apply(command: Command): StateMachine

