package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.{Command, HMap, Index}
import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

/** Contract test for idempotency checking (PC-1).
  *
  * Tests that duplicate requests return cached responses without calling applyCommand.
  */
object IdempotencySpec extends ZIOSpecDefault:

  sealed trait TestCommand extends Command
  object TestCommand:
    case class Increment(by: Int) extends TestCommand:
      type Response = Int

  // Response marker type - all command responses must be Int
  type TestResponse = Int

  import zio.prelude.Newtype
  object CounterKey extends Newtype[String]
  type CounterKey = CounterKey.Type
  given HMap.KeyLike[CounterKey] = HMap.KeyLike.forNewtype(CounterKey)

  type TestSchema = ("counter", CounterKey, Int) *: EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema[TestResponse, String], TestSchema]

  val counterKey = CounterKey("value")

  // SR = String (the actual server request payload type)
  // Provide codecs for all value types in schema
  import scodec.codecs.*

  // Simple dummy codec for Any (cache can store any type)
  given scodec.Codec[Any] = scodec.Codec[String].upcast[Any]

  // Codec for Int (our counter value)
  given scodec.Codec[Int] = int32

  // Use provided codecs from Codecs object
  import zio.raft.sessionstatemachine.Codecs.{sessionMetadataCodec, requestIdCodec, pendingServerRequestCodec}

  // PendingServerRequest[String] codec is automatically derived from utf8_32!
  // Just need to cast for the ? wildcard in schema
  given scodec.Codec[PendingServerRequest[?]] =
    summon[scodec.Codec[PendingServerRequest[String]]].asInstanceOf[scodec.Codec[PendingServerRequest[?]]]

  class TestStateMachine extends SessionStateMachine[TestCommand, TestResponse, String, TestSchema]
      with ScodecSerialization[TestResponse, String, TestSchema]:

    val codecs = summon[HMap.TypeclassMap[CombinedSchema, scodec.Codec]]
    var callCount = 0 // Track how many times applyCommand is called

    protected def applyCommand(
      createdAt: Instant,
      sessionId: SessionId,
      cmd: TestCommand
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], cmd.Response & TestResponse] =
      callCount += 1
      cmd match
        case TestCommand.Increment(by) =>
          for
            state <- StateWriter.get[HMap[CombinedSchema]]
            current = state.get["counter"](counterKey).getOrElse(0)
            newValue = current + by
            newState = state.updated["counter"](counterKey, newValue)
            _ <- StateWriter.set(newState)
          yield newValue.asInstanceOf[cmd.Response & TestResponse]

    protected def handleSessionCreated(
      createdAt: Instant,
      sid: SessionId,
      caps: Map[String, String]
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Unit] =
      StateWriter.succeed(())

    protected def handleSessionExpired(
      createdAt: Instant,
      sid: SessionId,
      capabilities: Map[String, String]
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Unit] =
      StateWriter.succeed(())

    // takeSnapshot and restoreFromSnapshot are now provided by SessionStateMachine base class!

    override def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
      false

  def spec = suite("Idempotency with Composite Keys")(
    test("PC-1: First request calls applyCommand, second request returns cached without calling") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()
      val sessionId = SessionId("s1")

      // Create session first (cast to match state machine type)
      val createCmd: SessionCommand[TestCommand, String] =
        SessionCommand.CreateSession[String](now, sessionId, Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String]]
      val (state1, _) = sm.apply(createCmd).run(state0)

      // First request - should call applyCommand
      val cmd1: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(1), RequestId(1), TestCommand.Increment(10))
      val (state2, result1) = sm.apply(cmd1).run(state1)
      val Right((response1, _)) = (result1.asInstanceOf[Either[RequestError, (Int, List[Any])]]): @unchecked

      assertTrue(sm.callCount == 1) &&
      assertTrue(response1 == 10)

      // Second request with same ID - should NOT call applyCommand
      val cmd2: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(1), RequestId(1), TestCommand.Increment(999))
      val (state3, result2) = sm.apply(cmd2).run(state2)
      val Right((response2, _)) = (result2.asInstanceOf[Either[RequestError, (Int, List[Any])]]): @unchecked

      assertTrue(
        sm.callCount == 1 && // Still 1, not called again!
          response2 == 10 // Cached response, not 999!
      )
    },
    test("Different requestIds call applyCommand separately") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()
      val sessionId = SessionId("s1")

      val createCmd: SessionCommand[TestCommand, String] =
        SessionCommand.CreateSession[String](now, sessionId, Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String]]
      val (state1, _) = sm.apply(createCmd).run(state0)

      // First request
      val cmd1: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(1), RequestId(1), TestCommand.Increment(5))
      val (state2, result1) = sm.apply(cmd1).run(state1)
      val Right((response1, _)) = (result1.asInstanceOf[Either[RequestError, (Int, List[Any])]]): @unchecked

      // Second request with DIFFERENT ID
      val cmd2: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(2), RequestId(1), TestCommand.Increment(3))
      val (state3, result2) = sm.apply(cmd2).run(state2)
      val Right((response2, _)) = (result2.asInstanceOf[Either[RequestError, (Int, List[Any])]]): @unchecked

      assertTrue(
        sm.callCount == 2 && // Both requests processed
          response1 == 5 &&
          response2 == 8 // 5 + 3
      )
    },
    test("PC-2: Evicted response returns RequestError.ResponseEvicted") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()
      val sessionId = SessionId("s1")

      // Create session
      val createCmd: SessionCommand[TestCommand, String] =
        SessionCommand.CreateSession[String](now, sessionId, Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String]]
      val (state1, _) = sm.apply(createCmd).run(state0)

      // First request (requestId=1, lowestPendingRequestId=1)
      val cmd1: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(1), RequestId(1), TestCommand.Increment(10))
      val (state2, result1) = sm.apply(cmd1).run(state1)
      val Right((response1, _)) = (result1.asInstanceOf[Either[RequestError, (Int, List[Any])]]): @unchecked

      // Second request (requestId=2, lowestPendingRequestId=1)
      val cmd2: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(2), RequestId(1), TestCommand.Increment(5))
      val (state3, result2) = sm.apply(cmd2).run(state2)
      val Right((response2, _)) = (result2.asInstanceOf[Either[RequestError, (Int, List[Any])]]): @unchecked

      // Third request (requestId=3, lowestPendingRequestId=2)
      // Client says "lowestPendingRequestId=2", so responses with requestId < 2 can be evicted (only 1)
      // This triggers cache cleanup AND updates highestLowestPendingRequestIdSeen to 2
      val cmd3: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(3), RequestId(2), TestCommand.Increment(1))
      val (state4, _) = sm.apply(cmd3).run(state3)

      // Now retry request 1 - should fail with ResponseEvicted (evicted)
      val cmd4: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(1), RequestId(2), TestCommand.Increment(999))
      val (state5, result4) = sm.apply(cmd4).run(state4)

      (result4.asInstanceOf[Either[RequestError, (Int, List[Any])]]: @unchecked) match
        case Left(RequestError.ResponseEvicted) =>
          assertTrue(
              sm.callCount == 3 // Command was NOT executed again (only 3 commands processed)
          )
        case Right(_) =>
          assertTrue(false) // Should not succeed
    },
    test("PC-3: Cache cleanup removes responses based on lowestPendingRequestId (exclusive)") {
      val sm = new TestStateMachine()
      val state0: HMap[CombinedSchema] = HMap.empty[CombinedSchema]
      val now = Instant.now()
      val sessionId = SessionId("s1")

      // Create session
      val createCmd: SessionCommand[TestCommand, String] =
        SessionCommand.CreateSession[String](now, sessionId, Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String]]
      val (state1, _) = sm.apply(createCmd).run(state0)

      // Execute 3 requests (requestIds 1, 2, 3)
      val cmd1: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(1), RequestId(1), TestCommand.Increment(10))
      val (state2, _) = sm.apply(cmd1).run(state1)

      val cmd2: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(2), RequestId(1), TestCommand.Increment(5))
      val (state3, _) = sm.apply(cmd2).run(state2)

      val cmd3: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(3), RequestId(1), TestCommand.Increment(3))
      val (state4, _) = sm.apply(cmd3).run(state3)

      // All 3 should be cached at this point
      val cache4 = state4.asInstanceOf[HMap[CombinedSchema]]
      assertTrue(cache4.get["cache"]((sessionId, RequestId(1))).isDefined) &&
      assertTrue(cache4.get["cache"]((sessionId, RequestId(2))).isDefined) &&
      assertTrue(cache4.get["cache"]((sessionId, RequestId(3))).isDefined)

      // Execute request 4 with lowestPendingRequestId=2 (client says "lowest pending is 2")
      val cmd4: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(4), RequestId(2), TestCommand.Increment(1))
      val (state5, _) = sm.apply(cmd4).run(state4)

      // Requests with id < 2 should be cleaned up (only 1). 2, 3, 4 should still be cached.
      val cache5 = state5.asInstanceOf[HMap[CombinedSchema]]
      assertTrue(cache5.get["cache"]((sessionId, RequestId(1))).isEmpty) &&
      assertTrue(cache5.get["cache"]((sessionId, RequestId(2))).isDefined) &&
      assertTrue(cache5.get["cache"]((sessionId, RequestId(3))).isDefined) &&
      assertTrue(cache5.get["cache"]((sessionId, RequestId(4))).isDefined)
    }
  )
end IdempotencySpec
