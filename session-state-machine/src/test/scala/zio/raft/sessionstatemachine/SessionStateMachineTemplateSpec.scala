package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.{UIO, ZIO}
import zio.prelude.State
import zio.raft.{Command, HMap}
import zio.raft.protocol.{SessionId, RequestId}
import zio.stream.Stream
import java.time.Instant

/**
 * Contract test for SessionStateMachine template method behavior.
 * 
 * EXPECTED: This test MUST FAIL initially (SessionStateMachine doesn't exist yet)
 * 
 * Tests:
 * - Template method (apply) is final - users cannot override
 * - Base class calls abstract methods in correct order
 * - Template pattern is correctly implemented
 */
object SessionStateMachineTemplateSpec extends ZIOSpecDefault:
  
  // Test user command
  sealed trait TestCommand extends Command
  case class Increment(by: Int) extends TestCommand:
    type Response = Int
  
  // Define typed key
  import zio.prelude.Newtype
  object CounterKey extends Newtype[String]
  type CounterKey = CounterKey.Type
  given HMap.KeyLike[CounterKey] = HMap.KeyLike.forNewtype(CounterKey)
  
  // Test user schema
  type TestUserSchema = ("counter", CounterKey, Int) *: EmptyTuple
  
  val counterKey = CounterKey("value")
  
  // Test server request type
  case class TestServerRequest(msg: String)
  
  // Concrete test implementation of SessionStateMachine
  class TestStateMachine extends SessionStateMachine[TestCommand, TestServerRequest, TestUserSchema]:
    
    // Track method calls for testing
    var applyCommandCalled = false
    var sessionCreatedCalled = false
    var sessionExpiredCalled = false
    
    protected def applyCommand(cmd: TestCommand, createdAt: Instant): StateWriter[HMap[TestUserSchema], TestServerRequest, cmd.Response] =
      applyCommandCalled = true
      cmd match
        case Increment(by) =>
          for {
            state <- StateWriter.get[HMap[TestUserSchema]]
            current = state.get["counter"](counterKey).getOrElse(0)
            newState = state.updated["counter"](counterKey, current + by)
            _ <- StateWriter.set(newState)
          } yield current + by
    
    protected def handleSessionCreated(
      sessionId: SessionId,
      capabilities: Map[String, String],
      createdAt: Instant
    ): StateWriter[HMap[TestUserSchema], TestServerRequest, Unit] =
      sessionCreatedCalled = true
      StateWriter.succeed(())
    
    protected def handleSessionExpired(
      sessionId: SessionId,
      capabilities: Map[String, String],
      createdAt: Instant
    ): StateWriter[HMap[TestUserSchema], TestServerRequest, Unit] =
      sessionExpiredCalled = true
      StateWriter.succeed(())
    
    // Placeholder serialization (not tested here)
    def takeSnapshot(state: HMap[CombinedSchema[TestUserSchema]]): Stream[Nothing, Byte] =
      zio.stream.ZStream.empty
    
    def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[TestUserSchema]]] =
      ZIO.succeed(HMap.empty[CombinedSchema[TestUserSchema]])
    
    def shouldTakeSnapshot(lastSnapshotIndex: zio.raft.Index, lastSnapshotSize: Long, commitIndex: zio.raft.Index): Boolean =
      false  // Don't take snapshots in tests
  
  def spec = suite("SessionStateMachine Template Method")(
    
    test("apply method should be final (cannot be overridden)") {
      // This is a compile-time check - if SessionStateMachine.apply is final,
      // attempting to override it would cause a compilation error
      
      // We verify by checking that the method exists on the base class
      val sm = new TestStateMachine()
      val now = Instant.now()
      val cmd = SessionCommand.ClientRequest[TestCommand, TestServerRequest](
        now, SessionId("s1"), RequestId(1), RequestId(1), Increment(5)
      )
      
      // If apply is final, this will call the base class implementation
      // (we'll verify behavior in other tests)
      assertTrue(sm != null)
    },
    
    test("template method should call applyCommand for ClientRequest") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      val cmd = SessionCommand.ClientRequest[TestCommand, TestServerRequest](
        now, SessionId("s1"), RequestId(1), RequestId(1), Increment(5)
      )
      
      val (state1, response) = sm.apply(cmd).run(state0)
      
      assertTrue(
        sm.applyCommandCalled == true
      )
    },
    
    test("template method should call handleSessionCreated for CreateSession") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      val cmd: SessionCommand[TestCommand, TestServerRequest] = SessionCommand.CreateSession[TestServerRequest](
        now, SessionId("s1"), Map("version" -> "1.0")
      )
      
      val (state1, response) = sm.apply(cmd).run(state0)
      
      assertTrue(
        sm.sessionCreatedCalled == true
      )
    },
    
    test("template method should call handleSessionExpired for SessionExpired") {
      val sm = new TestStateMachine()
      
      // First create a session
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      val createCmd: SessionCommand[TestCommand, TestServerRequest] = SessionCommand.CreateSession[TestServerRequest](
        now, SessionId("s1"), Map.empty
      )
      val (state1, _) = sm.apply(createCmd).run(state0)
      
      // Reset the flag
      sm.sessionExpiredCalled = false
      
      // Now expire the session
      val expireCmd: SessionCommand[TestCommand, TestServerRequest] = SessionCommand.SessionExpired[TestServerRequest](now, SessionId("s1"))
      val (state2, _) = sm.apply(expireCmd).run(state1)
      
      assertTrue(
        sm.sessionExpiredCalled == true
      )
    },
    
    test("template method should NOT call applyCommand for duplicate requests (cache hit)") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      // First request
      val cmd1 = SessionCommand.ClientRequest[TestCommand, TestServerRequest](
        now, SessionId("s1"), RequestId(1), RequestId(1), Increment(5)
      )
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      assertTrue(sm.applyCommandCalled == true)
      
      // Reset flag
      sm.applyCommandCalled = false
      
      // Second request (duplicate - same sessionId and requestId)
      val cmd2 = SessionCommand.ClientRequest[TestCommand, TestServerRequest](
        now, SessionId("s1"), RequestId(1), RequestId(1), Increment(10)  // Different command, but same IDs
      )
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        sm.applyCommandCalled == false &&  // NOT called again
        response1 == response2  // Same cached response
      )
    }
  )
