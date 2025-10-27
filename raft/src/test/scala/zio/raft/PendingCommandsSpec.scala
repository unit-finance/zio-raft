package zio.raft

import zio.{Promise, ZIO}
import zio.test.ZIOSpecDefault
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.assertTrue

object PendingCommandsSpec extends ZIOSpecDefault:

  private def makeTestPromise[A](): ZIO[Any, Nothing, CommandPromise[A]] =
    Promise.make[NotALeaderError, A]

  override def spec: Spec[TestEnvironment, Any] = suite("PendingCommands Spec")(
    test("withAdded - multiple command promises") {
      for
        promise1 <- makeTestPromise[String]()
        promise2 <- makeTestPromise[Int]()
        promise3 <- makeTestPromise[Boolean]()
        updated = PendingCommands.empty
          .withAdded(Index(1), promise1)
          .withAdded(Index(3), promise2)
          .withAdded(Index(2), promise3)
      yield assertTrue(
        updated.map.size == 3,
        updated.map.contains(Index(1)),
        updated.map.contains(Index(2)),
        updated.map.contains(Index(3)),
        updated.lastIndex.contains(Index(3))
      )
    },
    test("withCompleted - existing command succeeds and is removed") {
      for
        promise <- makeTestPromise[String]()
        initial = PendingCommands.empty.withAdded(Index(1), promise)
        completed <- initial.withCompleted(Index(1), "test-response")
        promiseResult <- promise.await
      yield assertTrue(
        completed.map.isEmpty,
        completed.lastIndex.isEmpty,
        promiseResult == "test-response"
      )
    },
    test("withCompleted - non-existent command returns unchanged") {
      for
        promise <- makeTestPromise[String]()
        initial = PendingCommands.empty.withAdded(Index(1), promise)
        completed <- initial.withCompleted(Index(2), "test-response")
      yield assertTrue(
        completed.map.size == 1,
        completed.map.contains(Index(1)),
        completed.lastIndex.contains(Index(1))
      )
    },
    test("stepDown - empty commands has no effect") {
      for
        _ <- PendingCommands.empty.stepDown(Some(MemberId("leader1")))
      yield assertTrue(true) // No exceptions thrown
    },
    test("stepDown - pending commands fail with NotALeaderError") {
      for
        promise1 <- makeTestPromise[String]()
        promise2 <- makeTestPromise[Int]()
        leaderId = Some(MemberId("leader1"))
        pendingCommands = PendingCommands.empty
          .withAdded(Index(1), promise1)
          .withAdded(Index(2), promise2)
        _ <- pendingCommands.stepDown(leaderId)
        result1 <- promise1.await.either
        result2 <- promise2.await.either
      yield assertTrue(
        result1.isLeft,
        result2.isLeft,
        result1.left.getOrElse(NotALeaderError(None)) == NotALeaderError(leaderId),
        result2.left.getOrElse(NotALeaderError(None)) == NotALeaderError(leaderId)
      )
    },
    test("lastIndex - empty commands returns None") {
      val empty = PendingCommands.empty
      assertTrue(empty.lastIndex.isEmpty)
    },
    test("lastIndex - multiple commands returns highest index") {
      for
        promise1 <- makeTestPromise[String]()
        promise2 <- makeTestPromise[Int]()
        promise3 <- makeTestPromise[Boolean]()
        promise4 <- makeTestPromise[Boolean]()
        pendingCommands = PendingCommands.empty
          .withAdded(Index(10), promise1)
          .withAdded(Index(5), promise2)
          .withAdded(Index(15), promise3)
          .withAdded(Index(1), promise4)
      yield assertTrue(
        pendingCommands.lastIndex.contains(Index(15)),
        pendingCommands.map.size == 4
      )
    }
  )
end PendingCommandsSpec
