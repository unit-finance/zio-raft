package zio.raft.sessionstatemachine

import zio.Ref
import zio.raft.{HMap, NotALeaderError}
import zio.test.*

object ContinuationBuilderSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("ContinuationBuilder")(
    suite("EitherContinuationBuilder - success/error/failure semantics")(
      test("invokes onSuccess on successful result") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .onSuccess[Any, String] { (_, _) => ref.set(1) }
              .onFailure[String] { (_, _) => ref.set(2) }
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Right((Nil, Right("ok"))))
          r <- ref.get
        yield assertTrue(r == 1)
      },
      test("invokes onFailure on domain error") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .onSuccess[Any, String] { (_, _) => ref.set(1) }
              .onFailure[String] { (_, _) => ref.set(2) }
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Right((Nil, Left(RequestError.UserError("boom")))))
          r <- ref.get
        yield assertTrue(r == 2)
      },
      test("invokes onNotALeader on leadership error") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .onSuccess[Any, String] { (_, _) => ref.set(1) }
              .onFailure[String] { (_, _) => ref.set(2) }
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Left(NotALeaderError(None)))
          r <- ref.get
        yield assertTrue(r == 3)
      },
      test("ignoreError makes domain error a no-op") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .onSuccess[Any, Int] { (_, _) => ref.set(1) }
              .ignoreError[Nothing]()
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Right((Nil, Left(RequestError.ResponseEvicted))))
          r <- ref.get
        yield assertTrue(r == 0)
      }
    ),
    suite("ResultOnlyContinuationBuilder - success/failure semantics")(
      test("invokes onResult on success") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .onResult[Any, Int] { (_, _) => ref.set(1) }
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Right((Nil, 3)))
          r <- ref.get
        yield assertTrue(r == 1)
      },
      test("invokes onNotALeader on failure") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .onResult[Any, Int] { (_, _) => ref.set(1) }
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Left(NotALeaderError(None)))
          r <- ref.get
        yield assertTrue(r == 3)
      }
    ),
    suite("WithoutResultContinuationBuilder - success/failure semantics")(
      test("invokes onSuccess on success") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .withoutResult[Any](_ => ref.set(1))
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Right(Nil))
          r <- ref.get
        yield assertTrue(r == 1)
      },
      test("invokes onNotALeader on failure") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .withoutResult[Any](_ => ref.set(1))
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Left(NotALeaderError(None)))
          r <- ref.get
        yield assertTrue(r == 3)
      }
    ),
    suite("QueryContinuationBuilder - success/failure semantics")(
      test("invokes onSuccess on success") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .query[EmptyTuple](_ => ref.set(1))
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Right(HMap.empty[EmptyTuple]))
          r <- ref.get
        yield assertTrue(r == 1)
      },
      test("invokes onNotALeader on failure") {
        for
          ref <- Ref.make(0)
          handler =
            ContinuationBuilder
              .query[EmptyTuple](_ => ref.set(1))
              .onNotALeader(_ => ref.set(3))
              .make
          _ <- handler(Left(NotALeaderError(None)))
          r <- ref.get
        yield assertTrue(r == 3)
      }
    )
  )
end ContinuationBuilderSpec
