package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.{UIO, ZIO}
import zio.prelude.State
import zio.raft.{Command, HMap}
import zio.raft.protocol.{SessionId, RequestId}
import zio.stream.Stream

/**
 * Contract test for state narrowing and merging.
 * 
 * EXPECTED: This test MUST FAIL initially (SessionStateMachine doesn't exist yet)
 * 
 * Tests:
 * - User methods receive HMap[UserSchema] (narrowed state)
 * - Changes to user state are merged back into combined state
 * - Session state prefixes are not visible in user methods
 * - Both session and user state coexist correctly
 */
object StateNarrowingSpec extends ZIOSpecDefault:
  
  sealed trait TestCommand extends Command
  case class SetCounter(value: Int) extends TestCommand:
    type Response = Int
  case class SetName(name: String) extends TestCommand:
    type Response = String
  
  // User schema with multiple prefixes
  type TestUserSchema = 
    ("counter", Int) *:
    ("name", String) *:
    EmptyTuple
  
  class TestStateMachine extends SessionStateMachine[TestCommand, String, TestUserSchema]:
    
    // Track what state the user method receives
    var receivedStateType: Option[String] = None
    
    protected def applyCommand(cmd: TestCommand): State[HMap[CombinedSchema[TestUserSchema]], (cmd.Response, List[String])] =
      cmd match
        case SetCounter(value) =>
          State.modify { userState =>
            // This should be HMap[TestUserSchema], not HMap[CombinedSchema[TestUserSchema]]
            receivedStateType = Some("UserSchema")
            
            // User should only see their own prefixes
            val newState = userState.updated["counter"]("value", value)
            (newState, (value, Nil))
          }
        
        case SetName(name) =>
          State.modify { userState =>
            receivedStateType = Some("UserSchema")
            val newState = userState.updated["name"]("user", name)
            (newState, (name, Nil))
          }
    
    protected def handleSessionCreated(sid: SessionId, caps: Map[String, String]): State[HMap[CombinedSchema[TestUserSchema]], List[String]] =
      State.modify { userState =>
        receivedStateType = Some("UserSchema")
        (userState, Nil)
      }
    
    protected def handleSessionExpired(sid: SessionId, capabilities: Map[String, String]): State[HMap[CombinedSchema[TestUserSchema]], List[String]] =
      State.modify { userState =>
        receivedStateType = Some("UserSchema")
        (userState, Nil)
      }
    
    def takeSnapshot(state: HMap[CombinedSchema[TestUserSchema]]): Stream[Nothing, Byte] =
      Stream.empty
    
    def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[TestUserSchema]]] =
      ZIO.succeed(HMap.empty)
    
    def shouldTakeSnapshot(lastSnapshotIndex: zio.raft.Index, lastSnapshotSize: Long, commitIndex: zio.raft.Index): Boolean =
      false
  
  def spec = suite("State Narrowing and Merging")(
    
    test("User methods receive HMap[CombinedSchema[UserSchema]]") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      
      val cmd = SessionCommand.ClientRequest[IncrementCounter, Nothing](
        SessionId("s1"),
        RequestId(1),
        SetCounter(42)
      )
      
      val (state1, _) = sm.apply(cmd).run(state0)
      
      assertTrue(sm.receivedStateType.contains("UserSchema"))
    },
    
    test("Changes to user state are merged back into combined state") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      
      // Set counter
      val cmd1 = SessionCommand.ClientRequest[IncrementCounter, Nothing](
        SessionId("s1"),
        RequestId(1),
        SetCounter(42)
      )
      val (state1, _) = sm.apply(cmd1).run(state0)
      
      // Verify user state was merged back - counter should be accessible
      val counterValue = state1.get["counter"]("value")
      
      assertTrue(counterValue.contains(42))
    },
    
    test("Multiple user prefixes can be updated independently") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      
      // Set counter
      val cmd1 = SessionCommand.ClientRequest[SessionId("s1"), RequestId(1), RequestId(1), SetCounter(100))
      val (state1, _) = sm.apply(cmd1).run(state0)
      
      // Set name
      val cmd2 = SessionCommand.ClientRequest[SessionId("s1"), RequestId(2), RequestId(2), SetName("Alice"))
      val (state2, _) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        state2.get["counter"]("value").contains(100) &&
        state2.get["name"]("user").contains("Alice")
      )
    },
    
    test("Session state and user state coexist in combined state") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      
      // Create session (adds session metadata)
      val createCmd = SessionCommand.CreateSession[Nothing](
        SessionId("s1"),
        Map("version" -> "1.0")
      )
      val (state1, _) = sm.apply(createCmd).run(state0)
      
      // Set user data
      val clientCmd = SessionCommand.ClientRequest[SessionId("s1"), RequestId(1), RequestId(1), SetCounter(42))
      val (state2, _) = sm.apply(clientCmd).run(state1)
      
      // Both session metadata and user data should be accessible
      val sessionMetadata = state2.get["metadata"](SessionId.unwrap(SessionId("s1")))
      val counterValue = state2.get["counter"]("value")
      
      assertTrue(
        sessionMetadata.isDefined &&
        counterValue.contains(42)
      )
    },
    
    test("User methods work correctly for handleSessionCreated") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      
      sm.receivedStateType = None
      
      val cmd = SessionCommand.CreateSession[Nothing](
        SessionId("s1"),
        Map.empty
      )
      val (state1, _) = sm.apply(cmd).run(state0)
      
      assertTrue(sm.receivedStateType.contains("UserSchema"))
    },
    
    test("User methods work correctly for handleSessionExpired") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      
      // Create session first
      val (state1, _) = sm.apply(
        SessionCommand.CreateSession[Nothing](SessionId("s1"), Map.empty)
      ).run(state0)
      
      sm.receivedStateType = None
      
      // Expire session
      val (state2, _) = sm.apply(
        SessionCommand.SessionExpired[Nothing](SessionId("s1"))
      ).run(state1)
      
      assertTrue(sm.receivedStateType.contains("UserSchema"))
    },
    
    test("User state changes persist across multiple commands") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      
      // Multiple updates
      val (state1, _) = sm.apply(
        SessionCommand.ClientRequest[SessionId("s1"), RequestId(1), RequestId(1), SetCounter(10))
      ).run(state0)
      
      val (state2, _) = sm.apply(
        SessionCommand.ClientRequest[SessionId("s1"), RequestId(2), RequestId(2), SetCounter(20))
      ).run(state1)
      
      val (state3, _) = sm.apply(
        SessionCommand.ClientRequest[SessionId("s1"), RequestId(3), RequestId(3), SetCounter(30))
      ).run(state2)
      
      // Final value should be 30
      assertTrue(state3.get["counter"]("value").contains(30))
    }
  )
