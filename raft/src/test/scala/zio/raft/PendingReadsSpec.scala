package zio.raft

import zio.{Promise, ZIO}
import zio.test.ZIOSpecDefault
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.assertTrue
import java.time.Instant

object PendingReadsSpec extends ZIOSpecDefault:

  private def makeTestPromise[S](): ZIO[Any, Nothing, Promise[NotALeaderError, S]] =
    Promise.make[NotALeaderError, S]

  override def spec: Spec[TestEnvironment, Any] = suite("PendingReads Spec")(
    
    test("withReadPendingCommand - adds commands in sorted order") {
      for {
        promise1 <- makeTestPromise[String]()
        promise2 <- makeTestPromise[String]()
        promise3 <- makeTestPromise[String]()
        
        pendingReads = PendingReads.empty[String]
          .withReadPendingCommand(promise1, Index(5))
          .withReadPendingCommand(promise2, Index(2))
          .withReadPendingCommand(promise3, Index(8))
        
        commandsList = pendingReads.readsPendingCommands.list
      } yield assertTrue(
        commandsList.size == 3,
        commandsList(0).enqueuedAtIndex == Index(2),
        commandsList(1).enqueuedAtIndex == Index(5), 
        commandsList(2).enqueuedAtIndex == Index(8)
      )
    },

    test("withReadPendingHeartbeat - adds heartbeats in sorted order") {
      for {
        promise1 <- makeTestPromise[String]()
        promise2 <- makeTestPromise[String]()
        promise3 <- makeTestPromise[String]()
        
        now = Instant.now()
        earlier = now.minusSeconds(60)
        later = now.plusSeconds(60)
        
        pendingReads = PendingReads.empty[String]
          .withReadPendingHeartbeat(promise1, later)
          .withReadPendingHeartbeat(promise2, earlier)
          .withReadPendingHeartbeat(promise3, now)
        
        heartbeatsList = pendingReads.readsPendingHeartbeats.list
      } yield assertTrue(
        heartbeatsList.size == 3,
        heartbeatsList(0).timestamp == earlier,
        heartbeatsList(1).timestamp == now,
        heartbeatsList(2).timestamp == later
      )
    },

    test("withCommandCompleted - completes commands up to index and preserves remaining") {
      for {
        promise1 <- makeTestPromise[String]()
        promise2 <- makeTestPromise[String]()
        promise3 <- makeTestPromise[String]()
        promise4 <- makeTestPromise[String]()
        
        initialReads = PendingReads.empty[String]
          .withReadPendingCommand(promise1, Index(1))
          .withReadPendingCommand(promise2, Index(3))
          .withReadPendingCommand(promise3, Index(5))
          .withReadPendingCommand(promise4, Index(7))
        
        updatedReads <- initialReads.withCommandCompleted(Index(4), "completed_state")
        
        // Check which promises are completed
        isDone1 <- promise1.isDone
        isDone2 <- promise2.isDone
        isDone3 <- promise3.isDone
        isDone4 <- promise4.isDone
        
        remainingCommands = updatedReads.readsPendingCommands.list
      } yield assertTrue(
        // Commands with index <= 4 should be completed
        isDone1,
        isDone2,
        // Commands with index > 4 should still be pending
        !isDone3,
        !isDone4,
        // Only commands with index > 4 should remain
        remainingCommands.size == 2,
        remainingCommands(0).enqueuedAtIndex == Index(5),
        remainingCommands(1).enqueuedAtIndex == Index(7)
      )
    },

    test("withHeartbeatResponse - completes heartbeats with majority and preserves others") {
      for {
        promise1 <- makeTestPromise[String]()
        promise2 <- makeTestPromise[String]()
        promise3 <- makeTestPromise[String]()
        
        now = Instant.now()
        earlier = now.minusSeconds(30)
        later = now.plusSeconds(30)
        
        member1 = MemberId("member1")
        member2 = MemberId("member2")
        
        initialReads = PendingReads.empty[String]
          .withReadPendingHeartbeat(promise1, earlier)
          .withReadPendingHeartbeat(promise2, now)
          .withReadPendingHeartbeat(promise3, later)
        
        // First heartbeat response (not enough for majority in 3-node cluster)
        afterFirst <- initialReads.withHeartbeatResponse(member1, now, "heartbeat_state", 3)
        
        // Second heartbeat response (should complete heartbeats <= now)
        afterSecond <- afterFirst.withHeartbeatResponse(member2, now, "heartbeat_state", 3)
        
        // Check promises with timeout to avoid hanging
        result1 <- promise1.await.either.timeout(zio.Duration.fromMillis(100)).map(_.getOrElse(Left(NotALeaderError(None))))
        result2 <- promise2.await.either.timeout(zio.Duration.fromMillis(100)).map(_.getOrElse(Left(NotALeaderError(None))))
        isDone3 <- promise3.isDone
        
        remainingHeartbeats = afterSecond.readsPendingHeartbeats.list
      } yield assertTrue(
        // Heartbeats with timestamp <= now should be completed (have majority: self + 2 responses = 3 > 3/2)
        result1.isRight && result1.getOrElse("") == "heartbeat_state",
        result2.isRight && result2.getOrElse("") == "heartbeat_state",
        // Heartbeat with timestamp > now should still be pending
        !isDone3,
        // Only future heartbeat should remain
        remainingHeartbeats.size == 1,
        remainingHeartbeats(0).timestamp == later
      )
    },

    test("stepDown - fails all pending operations with NotALeaderError") {
      for {
        commandPromise1 <- makeTestPromise[String]()
        commandPromise2 <- makeTestPromise[String]()
        heartbeatPromise1 <- makeTestPromise[String]()
        heartbeatPromise2 <- makeTestPromise[String]()
        
        leaderId = Some(MemberId("new_leader"))
        now = Instant.now()
        
        pendingReads = PendingReads.empty[String]
          .withReadPendingCommand(commandPromise1, Index(1))
          .withReadPendingCommand(commandPromise2, Index(2))
          .withReadPendingHeartbeat(heartbeatPromise1, now)
          .withReadPendingHeartbeat(heartbeatPromise2, now.plusSeconds(10))
        
        _ <- pendingReads.stepDown(leaderId)
        
        commandResult1 <- commandPromise1.await.either.timeoutFail("timeout error")(zio.Duration.fromMillis(100))
        commandResult2 <- commandPromise2.await.either.timeoutFail("timeout error")(zio.Duration.fromMillis(100))
        heartbeatResult1 <- heartbeatPromise1.await.either.timeoutFail("timeout error")(zio.Duration.fromMillis(100))
        heartbeatResult2 <- heartbeatPromise2.await.either.timeoutFail("timeout error")(zio.Duration.fromMillis(100))
      } yield assertTrue(
        commandResult1.isLeft && commandResult1.swap.contains(NotALeaderError(leaderId)),
        commandResult2.isLeft && commandResult2.swap.contains(NotALeaderError(leaderId)),
        heartbeatResult1.isLeft && heartbeatResult1.swap.contains(NotALeaderError(leaderId)),
        heartbeatResult2.isLeft && heartbeatResult2.swap.contains(NotALeaderError(leaderId))
      )
    },
    
    test("withHeartbeatResponse - maintains sorted order after partial completion") {
      for {
        promise1 <- makeTestPromise[String]()
        promise2 <- makeTestPromise[String]()
        promise3 <- makeTestPromise[String]()
        promise4 <- makeTestPromise[String]()
        promise5 <- makeTestPromise[String]()
        
        now = Instant.now()
        t1 = now.minusSeconds(50)  // oldest
        t2 = now.minusSeconds(30)  
        t3 = now.minusSeconds(10)  // this will be completed
        t4 = now.plusSeconds(10)   // this will remain
        t5 = now.plusSeconds(30)   // newest
        
        member1 = MemberId("member1")
        member2 = MemberId("member2")
        
        initialReads = PendingReads.empty[String]
          .withReadPendingHeartbeat(promise1, t1)
          .withReadPendingHeartbeat(promise2, t2)
          .withReadPendingHeartbeat(promise3, t3)
          .withReadPendingHeartbeat(promise4, t4)
          .withReadPendingHeartbeat(promise5, t5)
        
        // Add some heartbeat responses to build up majority for earlier timestamps
        withFirstResponse <- initialReads.withHeartbeatResponse(member1, t3, "state", 3)
        finalReads <- withFirstResponse.withHeartbeatResponse(member2, t3, "state", 3)
        
        // Check that some promises were completed (with timeout)
        result1 <- promise1.await.either.timeout(zio.Duration.fromMillis(100)).map(_.getOrElse(Left(NotALeaderError(None))))
        result2 <- promise2.await.either.timeout(zio.Duration.fromMillis(100)).map(_.getOrElse(Left(NotALeaderError(None))))
        result3 <- promise3.await.either.timeout(zio.Duration.fromMillis(100)).map(_.getOrElse(Left(NotALeaderError(None))))
        isDone4 <- promise4.isDone
        isDone5 <- promise5.isDone
        
        remainingHeartbeats = finalReads.readsPendingHeartbeats.list
      } yield assertTrue(
        // First three heartbeats should be completed (timestamps <= t3 with majority)
        result1.isRight,
        result2.isRight,
        result3.isRight,
        // Last two should still be pending
        !isDone4,
        !isDone5,
        // Remaining heartbeats should be in sorted order
        remainingHeartbeats.size == 2,
        remainingHeartbeats(0).timestamp == t4,
        remainingHeartbeats(1).timestamp == t5,
        // Verify strict ordering is maintained
        remainingHeartbeats(0).timestamp.isBefore(remainingHeartbeats(1).timestamp)
      )
    }

  )


