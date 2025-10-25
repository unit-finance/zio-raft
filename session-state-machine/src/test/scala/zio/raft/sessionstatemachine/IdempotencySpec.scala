package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.{UIO, ZIO}
import zio.raft.{Command, HMap, Index}
import zio.raft.protocol.{SessionId, RequestId}
import zio.stream.{Stream, ZStream}
import java.time.Instant

/**
 * Contract test for idempotency checking (PC-1).
 * 
 * Tests that duplicate requests return cached responses without calling applyCommand.
 */
object IdempotencySpec extends ZIOSpecDefault:
  
  sealed trait TestCommand extends Command
  object TestCommand:
    case class Increment(by: Int) extends TestCommand:
      type Response = Int
  
  import zio.prelude.Newtype
  object CounterKey extends Newtype[String]
  type CounterKey = CounterKey.Type
  given HMap.KeyLike[CounterKey] = HMap.KeyLike.forNewtype(CounterKey)
  
  type TestSchema = ("counter", CounterKey, Int) *: EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema, TestSchema]
  
  val counterKey = CounterKey("value")
  
  // SR = String (the actual server request payload type)
  class TestStateMachine extends SessionStateMachine[TestCommand, String, TestSchema]:
    var callCount = 0  // Track how many times applyCommand is called
    
    protected def applyCommand(cmd: TestCommand, createdAt: Instant): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], cmd.Response] =
      callCount += 1
      cmd match
        case TestCommand.Increment(by) =>
          for {
            state <- StateWriter.get[HMap[CombinedSchema]]
            current = state.get["counter"](counterKey).getOrElse(0)
            newValue = current + by
            newState = state.updated["counter"](counterKey, newValue)
            _ <- StateWriter.set(newState)
          } yield newValue.asInstanceOf[cmd.Response]
    
    protected def handleSessionCreated(sid: SessionId, caps: Map[String, String], createdAt: Instant): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Unit] =
      StateWriter.succeed(())
    
    protected def handleSessionExpired(sid: SessionId, capabilities: Map[String, String], createdAt: Instant): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Unit] =
      StateWriter.succeed(())
    
    def takeSnapshot(state: HMap[CombinedSchema]): Stream[Nothing, Byte] =
      ZStream.empty
    
    def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema]] =
      ZIO.succeed(HMap.empty)
    
    def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
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
      val response1 = result1.asInstanceOf[(Int, List[Any])]._1
      
      assertTrue(sm.callCount == 1) &&
      assertTrue(response1 == 10)
      
      // Second request with same ID - should NOT call applyCommand
      val cmd2: SessionCommand[TestCommand, String] = 
        SessionCommand.ClientRequest(now, sessionId, RequestId(1), RequestId(1), TestCommand.Increment(999))
      val (state3, result2) = sm.apply(cmd2).run(state2)
      val response2 = result2.asInstanceOf[(Int, List[Any])]._1
      
      assertTrue(
        sm.callCount == 1 &&  // Still 1, not called again!
        response2 == 10  // Cached response, not 999!
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
      val response1 = result1.asInstanceOf[(Int, List[Any])]._1
      
      // Second request with DIFFERENT ID
      val cmd2: SessionCommand[TestCommand, String] = 
        SessionCommand.ClientRequest(now, sessionId, RequestId(2), RequestId(1), TestCommand.Increment(3))
      val (state3, result2) = sm.apply(cmd2).run(state2)
      val response2 = result2.asInstanceOf[(Int, List[Any])]._1
      
      assertTrue(
        sm.callCount == 2 &&  // Both requests processed
        response1 == 5 &&
        response2 == 8  // 5 + 3
      )
    }
  )
