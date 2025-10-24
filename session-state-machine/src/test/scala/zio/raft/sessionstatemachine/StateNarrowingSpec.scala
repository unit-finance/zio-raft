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
  
  // Define typed keys
  import zio.prelude.Newtype
  object CounterKey extends Newtype[String]
  type CounterKey = CounterKey.Type
  given HMap.KeyLike[CounterKey] = HMap.KeyLike.forNewtype(CounterKey)
  
  object NameKey extends Newtype[String]
  type NameKey = NameKey.Type
  given HMap.KeyLike[NameKey] = HMap.KeyLike.forNewtype(NameKey)
  
  // User schema with multiple prefixes
  type TestUserSchema = 
    ("counter", CounterKey, Int) *:
    ("name", NameKey, String) *:
    EmptyTuple
  
  val counterKey = CounterKey("value")
  val nameKey = NameKey("user")
  
  class TestStateMachine extends SessionStateMachine[TestCommand, String, TestUserSchema]:
    
    // Track what state the user method receives
    var receivedStateType: Option[String] = None
    
    protected def applyCommand(cmd: TestCommand, createdAt: Instant): StateWriter[HMap[TestUserSchema], String, cmd.Response] =
      cmd match
        case SetCounter(value) =>
          for {
            userState <- StateWriter.get[HMap[TestUserSchema]]
            _ = { receivedStateType = Some("UserSchema") }
            // User should only see their own prefixes
            newState = userState.updated["counter"](counterKey, value)
            _ <- StateWriter.set(newState)
          } yield value
        
        case SetName(name) =>
          for {
            userState <- StateWriter.get[HMap[TestUserSchema]]
            _ = { receivedStateType = Some("UserSchema") }
            newState = userState.updated["name"](nameKey, name)
            _ <- StateWriter.set(newState)
          } yield name
    
    protected def handleSessionCreated(sid: SessionId, caps: Map[String, String], createdAt: Instant): StateWriter[HMap[TestUserSchema], String, Unit] =
      for {
        _ <- StateWriter.get[HMap[TestUserSchema]]
        _ = { receivedStateType = Some("UserSchema") }
      } yield ()
    
    protected def handleSessionExpired(sid: SessionId, capabilities: Map[String, String], createdAt: Instant): StateWriter[HMap[TestUserSchema], String, Unit] =
      for {
        _ <- StateWriter.get[HMap[TestUserSchema]]
        _ = { receivedStateType = Some("UserSchema") }
      } yield ()
    
    def takeSnapshot(state: HMap[CombinedSchema[TestUserSchema]]): Stream[Nothing, Byte] =
      zio.stream.ZStream.empty
    
    def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[TestUserSchema]]] =
      ZIO.succeed(HMap.empty)
    
    def shouldTakeSnapshot(lastSnapshotIndex: zio.raft.Index, lastSnapshotSize: Long, commitIndex: zio.raft.Index): Boolean =
      false
  
  def spec = suite("State Narrowing and Merging")(
    
    test("User methods receive HMap[UserSchema]") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      val cmd = SessionCommand.ClientRequest[TestCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), SetCounter(42)
      )
      
      val (state1, _) = sm.apply(cmd).run(state0)
      
      assertTrue(sm.receivedStateType.contains("UserSchema"))
    },
    
    test("Changes to user state are merged back into combined state") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      // Set counter
      val cmd1 = SessionCommand.ClientRequest[TestCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), SetCounter(42)
      )
      val (state1, _) = sm.apply(cmd1).run(state0)
      
      // Verify user state was merged back - counter should be accessible
      val counterValue = state1.get["counter"](counterKey)
      
      assertTrue(counterValue.contains(42))
    },
    
    test("Multiple user prefixes can be updated independently") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      // Set counter
      val cmd1 = SessionCommand.ClientRequest[TestCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), SetCounter(100)
      )
      val (state1, _) = sm.apply(cmd1).run(state0)
      
      // Set name
      val cmd2 = SessionCommand.ClientRequest[TestCommand, String](
        now, SessionId("s1"), RequestId(2), RequestId(2), SetName("Alice")
      )
      val (state2, _) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        state2.get["counter"](counterKey).contains(100) &&
        state2.get["name"](nameKey).contains("Alice")
      )
    },
    
    test("Session state and user state coexist in combined state") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      // Create session (adds session metadata)
      val createCmd: SessionCommand[TestCommand, String] = SessionCommand.CreateSession[String](
        now, SessionId("s1"), Map("version" -> "1.0")
      )
      val (state1, _) = sm.apply(createCmd).run(state0)
      
      // Set user data
      val clientCmd = SessionCommand.ClientRequest[TestCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), SetCounter(42)
      )
      val (state2, _) = sm.apply(clientCmd).run(state1)
      
      // Both session metadata and user data should be accessible
      val sessionMetadata = state2.get["metadata"](SessionId("s1"))
      val counterValue = state2.get["counter"](counterKey)
      
      assertTrue(
        sessionMetadata.isDefined &&
        counterValue.contains(42)
      )
    },
    
    test("User methods work correctly for handleSessionCreated") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      sm.receivedStateType = None
      
      val cmd: SessionCommand[TestCommand, String] = SessionCommand.CreateSession[String](
        now, SessionId("s1"), Map.empty
      )
      val (state1, _) = sm.apply(cmd).run(state0)
      
      assertTrue(sm.receivedStateType.contains("UserSchema"))
    },
    
    test("User methods work correctly for handleSessionExpired") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      // Create session first
      val createCmd: SessionCommand[TestCommand, String] = SessionCommand.CreateSession[String](now, SessionId("s1"), Map.empty)
      val (state1, _) = sm.apply(createCmd).run(state0)
      
      sm.receivedStateType = None
      
      // Expire session
      val expireCmd: SessionCommand[TestCommand, String] = SessionCommand.SessionExpired[String](now, SessionId("s1"))
      val (state2, _) = sm.apply(expireCmd).run(state1)
      
      assertTrue(sm.receivedStateType.contains("UserSchema"))
    },
    
    test("User state changes persist across multiple commands") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestUserSchema]]
      val now = Instant.now()
      
      // Multiple updates
      val (state1, _) = sm.apply(
        SessionCommand.ClientRequest[TestCommand, String](now, SessionId("s1"), RequestId(1), RequestId(1), SetCounter(10))
      ).run(state0)
      
      val (state2, _) = sm.apply(
        SessionCommand.ClientRequest[TestCommand, String](now, SessionId("s1"), RequestId(2), RequestId(2), SetCounter(20))
      ).run(state1)
      
      val (state3, _) = sm.apply(
        SessionCommand.ClientRequest[TestCommand, String](now, SessionId("s1"), RequestId(3), RequestId(3), SetCounter(30))
      ).run(state2)
      
      // Final value should be 30
      assertTrue(state3.get["counter"](counterKey).contains(30))
    }
  )
