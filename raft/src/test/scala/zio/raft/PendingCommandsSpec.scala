package zio.raft

import zio.{Queue, Ref, ZIO}
import zio.test.ZIOSpecDefault
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.assertTrue

object PendingCommandsSpec extends ZIOSpecDefault:

  private def makeRef[A](): ZIO[Any, Nothing, Ref[Option[Either[NotALeaderError, A]]]] =
    Ref.make[Option[Either[NotALeaderError, A]]](None)

  override def spec: Spec[TestEnvironment, Any] = suite("PendingCommands Spec")(
    test("withAdded - multiple command promises") {
      val updated = PendingCommands.empty
        .withAdded(Index(1), (_: Either[NotALeaderError, String]) => ZIO.unit)
        .withAdded(Index(3), (_: Either[NotALeaderError, Int]) => ZIO.unit)
        .withAdded(Index(2), (_: Either[NotALeaderError, Boolean]) => ZIO.unit)
      ZIO.succeed(
        assertTrue(
          updated.map.size == 3,
          updated.map.contains(Index(1)),
          updated.map.contains(Index(2)),
          updated.map.contains(Index(3)),
          updated.lastIndex.contains(Index(3))
        )
      )
    },
    test("withCompleted - existing command succeeds and is removed") {
      for
        ref <- makeRef[String]()
        queue <- Queue.unbounded[RaftAction]
        continuation: CommandContinuation[String] = (r: Either[NotALeaderError, String]) => ref.set(Some(r))
        initial = PendingCommands.empty.withAdded(Index(1), continuation)
        completed <- initial.withCompleted(queue, Index(1), "test-response")
        // run the produced continuation effect
        action <- queue.take
        _ <- action match
          case RaftAction.CommandContinuation(eff) => eff
          case _                                   => ZIO.unit
        promiseResult <- ref.get
      yield assertTrue(
        completed.map.isEmpty,
        completed.lastIndex.isEmpty,
        promiseResult.contains(Right("test-response"))
      )
    },
    test("withCompleted - non-existent command returns unchanged") {
      for
        queue <- Queue.unbounded[RaftAction]
        continuation: CommandContinuation[String] = (_: Either[NotALeaderError, String]) => ZIO.unit
        initial = PendingCommands.empty.withAdded(Index(1), continuation)
        completed <- initial.withCompleted(queue, Index(2), "test-response")
        maybeAction <- queue.poll
      yield assertTrue(
        completed.map.size == 1,
        completed.map.contains(Index(1)),
        completed.lastIndex.contains(Index(1)),
        maybeAction.isEmpty
      )
    },
    test("stepDown - empty commands has no effect") {
      for
        queue <- Queue.unbounded[RaftAction]
        _ <- PendingCommands.empty.stepDown(queue, Some(MemberId("leader1")))
        isEmpty <- queue.isEmpty
      yield assertTrue(isEmpty) // No actions enqueued
    },
    test("stepDown - pending commands fail with NotALeaderError") {
      for
        ref1 <- makeRef[String]()
        ref2 <- makeRef[Int]()
        c1: CommandContinuation[String] = (r: Either[NotALeaderError, String]) => ref1.set(Some(r))
        c2: CommandContinuation[Int] = (r: Either[NotALeaderError, Int]) => ref2.set(Some(r))
        queue <- Queue.unbounded[RaftAction]
        leaderId = Some(MemberId("leader1"))
        pendingCommands = PendingCommands.empty
          .withAdded(Index(1), c1)
          .withAdded(Index(2), c2)
        _ <- pendingCommands.stepDown(queue, leaderId)
        // two actions expected, run both
        a1 <- queue.take
        _ <- a1 match
          case RaftAction.CommandContinuation(eff) => eff
          case _                                   => ZIO.unit
        a2 <- queue.take
        _ <- a2 match
          case RaftAction.CommandContinuation(eff) => eff
          case _                                   => ZIO.unit
        result1 <- ref1.get
        result2 <- ref2.get
      yield assertTrue(
        result1.exists(_.isLeft),
        result2.exists(_.isLeft),
        result1.contains(Left(NotALeaderError(leaderId))),
        result2.contains(Left(NotALeaderError(leaderId)))
      )
    },
    test("lastIndex - empty commands returns None") {
      val empty = PendingCommands.empty
      assertTrue(empty.lastIndex.isEmpty)
    },
    test("lastIndex - multiple commands returns highest index") {
      val pendingCommands = PendingCommands.empty
        .withAdded(Index(10), (_: Either[NotALeaderError, String]) => ZIO.unit)
        .withAdded(Index(5), (_: Either[NotALeaderError, Int]) => ZIO.unit)
        .withAdded(Index(15), (_: Either[NotALeaderError, Boolean]) => ZIO.unit)
        .withAdded(Index(1), (_: Either[NotALeaderError, Boolean]) => ZIO.unit)
      ZIO.succeed(
        assertTrue(
          pendingCommands.lastIndex.contains(Index(15)),
          pendingCommands.map.size == 4
        )
      )
    }
  )
end PendingCommandsSpec
