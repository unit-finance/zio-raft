package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.{UIO, ZIO}
import zio.prelude.State
import zio.prelude.fx.ZPure
import zio.raft.{Command, HMap}
import zio.raft.protocol.{SessionId, RequestId}
import zio.stream.Stream
import java.time.Instant

/**
 * Contract test for idempotency checking (PC-1).
 * 
 * EXPECTED: This test MUST FAIL initially (SessionStateMachine doesn't exist yet)
 * 
 * Postcondition PC-1: Cache hit returns cached response without calling user method
 * 
 * Tests:
 * - Same (sessionId, requestId) returns cached response
 * - User's applyCommand NOT called on duplicate
 * - State unchanged on duplicate request
 * - Works across different user commands
 */
object IdempotencySpec extends ZIOSpecDefault:
  
  // Test user commands
  sealed trait CounterCommand extends Command
  case class Set(value: Int) extends CounterCommand:
    type Response = Int
  case class Get() extends CounterCommand:
    type Response = Int
  
  // Define typed key for counter
  import zio.prelude.Newtype
  object CounterKey extends Newtype[String]
  type CounterKey = CounterKey.Type
  given HMap.KeyLike[CounterKey] = HMap.KeyLike.forNewtype(CounterKey)
  
  type CounterSchema = ("counter", CounterKey, Int) *: EmptyTuple
  
  val counterKey = CounterKey("value")
  
  // Test implementation with call tracking
  class CounterStateMachine extends SessionStateMachine[CounterCommand, String, CounterSchema]:
    var callCount = 0
    
    protected def applyCommand(cmd: CounterCommand, createdAt: Instant): StateWriter[HMap[CounterSchema], String, cmd.Response] =
      callCount += 1
      cmd match
        case Set(value) =>
          for {
            state <- StateWriter.get[HMap[CounterSchema]]
            newState = state.updated["counter"](counterKey, value)
            _ <- StateWriter.set(newState)
          } yield value
        case Get() =>
          for {
            state <- StateWriter.get[HMap[CounterSchema]]
            value = state.get["counter"](counterKey).getOrElse(0)
          } yield value
    
    protected def handleSessionCreated(sid: SessionId, caps: Map[String, String], createdAt: Instant): StateWriter[HMap[CounterSchema], String, Unit] =
      StateWriter.succeed(())
    
    protected def handleSessionExpired(sid: SessionId, capabilities: Map[String, String], createdAt: Instant): StateWriter[HMap[CounterSchema], String, Unit] =
      StateWriter.succeed(())
    
    def takeSnapshot(state: HMap[Schema[CounterSchema]]): Stream[Nothing, Byte] =
      zio.stream.ZStream.empty
    
    def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[Schema[CounterSchema]]] =
      ZIO.succeed(HMap.empty)
    
    def shouldTakeSnapshot(lastSnapshotIndex: zio.raft.Index, lastSnapshotSize: Long, commitIndex: zio.raft.Index): Boolean =
      false
  
  def spec = suite("Idempotency Checking")(
    
    test("PC-1: Cache hit returns cached response without calling applyCommand") {
      val sm = new CounterStateMachine()
      val state0 = HMap.empty[Schema[CounterSchema]]
      val now = Instant.now()
      
      // First request - should call applyCommand
      val cmd1 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), Set(42)
      )
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      val callsAfterFirst = sm.callCount
      assertTrue(callsAfterFirst == 1)
      
      // Duplicate request - should NOT call applyCommand
      val cmd2 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), Set(999)
      )
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        sm.callCount == callsAfterFirst &&  // No additional calls
        response1 == response2 &&  // Same response
        response1._1 == 42  // Original value, not 999
      )
    },
    
    test("Different request IDs should NOT be cached") {
      val sm = new CounterStateMachine()
      val state0 = HMap.empty[Schema[CounterSchema]]
      val now = Instant.now()
      
      val cmd1 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), Set(10)
      )
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      val cmd2 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s1"), RequestId(2), RequestId(2), Set(20)
      )
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        sm.callCount == 2 &&  // Both called
        response1 != response2 &&  // Different responses
        response1._1 == 10 &&
        response2._1 == 20
      )
    },
    
    test("Different session IDs should NOT be cached") {
      val sm = new CounterStateMachine()
      val state0 = HMap.empty[Schema[CounterSchema]]
      val now = Instant.now()
      
      val cmd1 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), Set(10)
      )
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      val cmd2 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s2"), RequestId(1), RequestId(1), Set(20)
      )
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        sm.callCount == 2 &&  // Both called
        response1 != response2
      )
    },
    
    test("State should be unchanged on duplicate request") {
      val sm = new CounterStateMachine()
      val state0 = HMap.empty[Schema[CounterSchema]]
      val now = Instant.now()
      
      // First request
      val cmd1 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), Set(42)
      )
      val (state1, _) = sm.apply(cmd1).run(state0)
      
      val counterBefore = state1.get["counter"](counterKey)
      
      // Duplicate request with different value
      val cmd2 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), Set(999)
      )
      val (state2, _) = sm.apply(cmd2).run(state1)
      
      val counterAfter = state2.get["counter"](counterKey)
      
      assertTrue(
        counterBefore == counterAfter &&  // State unchanged
        counterAfter.contains(42)  // Still original value
      )
    },
    
    test("Idempotency works across different command types") {
      val sm = new CounterStateMachine()
      val state0 = HMap.empty[Schema[CounterSchema]]
      val now = Instant.now()
      
      // First: Set command
      val cmd1 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), Set(42)
      )
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      // Duplicate with Get command (different type, same IDs)
      val cmd2 = SessionCommand.ClientRequest[CounterCommand, String](
        now, SessionId("s1"), RequestId(1), RequestId(1), Get()
      )
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        sm.callCount == 1 &&  // Only first called
        response1 == response2  // Cached response from Set, not Get
      )
    }
  )
