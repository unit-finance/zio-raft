package zio.raft

import zio.ZIO

extension [R, E, A](self: ZIO[R, E, A])
  def withFilter(predicate: A => Boolean) =
    self.flatMap { a =>
      if (predicate(a)) ZIO.succeed(a)
      else ZIO.die(new NoSuchElementException("The value doesn't satisfy the predicate"))
    }
