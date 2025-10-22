package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.{UIO, ZIO}
import zio.prelude.State
import zio.raft.{Command, HMap}
import zio.raft.protocol.{SessionId, RequestId}
import zio.stream.Stream

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
  
  type CounterSchema = ("counter", Int) *: EmptyTuple
  
  // Test implementation with call tracking
  class CounterStateMachine extends SessionStateMachine[CounterCommand, String, CounterSchema]:
    var callCount = 0
    
    protected def applyCommand(cmd: CounterCommand): State[HMap[CombinedSchema[CounterSchema]], (cmd.Response, List[String])] =
      callCount += 1
      cmd match
        case Set(value) =>
          State.modify { state =>
            val newState = state.updated["counter"]("value", value)
            (newState, (value, Nil))
          }
        case Get() =>
          State.get.map { state =>
            val value = state.get["counter"]("value").getOrElse(0)
            (value, Nil)
          }
    
    protected def handleSessionCreated(sid: SessionId, caps: Map[String, String]): State[HMap[CombinedSchema[CounterSchema]], List[String]] =
      State.succeed(Nil)
    
    protected def handleSessionExpired(sid: SessionId): State[HMap[CombinedSchema[CounterSchema]], List[String]] =
      State.succeed(Nil)
    
    def takeSnapshot(state: HMap[CombinedSchema[CounterSchema]]): Stream[Nothing, Byte] =
      Stream.empty
    
    def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[CounterSchema]]] =
      ZIO.succeed(HMap.empty)
  
  def spec = suite("Idempotency Checking")(
    
    test("PC-1: Cache hit returns cached response without calling applyCommand") {
      val sm = new CounterStateMachine()
      val state0 = HMap.empty[CombinedSchema[CounterSchema]]
      
      // First request - should call applyCommand
      val cmd1 = SessionCommand.ClientRequest(SessionId("s1"), RequestId(1), Set(42))
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      val callsAfterFirst = sm.callCount
      assertTrue(callsAfterFirst == 1)
      
      // Duplicate request - should NOT call applyCommand
      val cmd2 = SessionCommand.ClientRequest(SessionId("s1"), RequestId(1), Set(999))
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        sm.callCount == callsAfterFirst &&  // No additional calls
        response1 == response2 &&  // Same response
        response1._1 == 42  // Original value, not 999
      )
    },
    
    test("Different request IDs should NOT be cached") {
      val sm = new CounterStateMachine()
      val state0 = HMap.empty[CombinedSchema[CounterSchema]]
      
      val cmd1 = SessionCommand.ClientRequest(SessionId("s1"), RequestId(1), Set(10))
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      val cmd2 = SessionCommand.ClientRequest(SessionId("s1"), RequestId(2), Set(20))
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
      val state0 = HMap.empty[CombinedSchema[CounterSchema]]
      
      val cmd1 = SessionCommand.ClientRequest(SessionId("s1"), RequestId(1), Set(10))
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      val cmd2 = SessionCommand.ClientRequest(SessionId("s2"), RequestId(1), Set(20))
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        sm.callCount == 2 &&  // Both called
        response1 != response2
      )
    },
    
    test("State should be unchanged on duplicate request") {
      val sm = new CounterStateMachine()
      val state0 = HMap.empty[CombinedSchema[CounterSchema]]
      
      // First request
      val cmd1 = SessionCommand.ClientRequest(SessionId("s1"), RequestId(1), Set(42))
      val (state1, _) = sm.apply(cmd1).run(state0)
      
      val counterBefore = state1.get["counter"]("value")
      
      // Duplicate request with different value
      val cmd2 = SessionCommand.ClientRequest(SessionId("s1"), RequestId(1), Set(999))
      val (state2, _) = sm.apply(cmd2).run(state1)
      
      val counterAfter = state2.get["counter"]("value")
      
      assertTrue(
        counterBefore == counterAfter &&  // State unchanged
        counterAfter.contains(42)  // Still original value
      )
    },
    
    test("Idempotency works across different command types") {
      val sm = new CounterStateMachine()
      val state0 = HMap.empty[CombinedSchema[CounterSchema]]
      
      // First: Set command
      val cmd1 = SessionCommand.ClientRequest(SessionId("s1"), RequestId(1), Set(42))
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      // Duplicate with Get command (different type, same IDs)
      val cmd2 = SessionCommand.ClientRequest(SessionId("s1"), RequestId(1), Get())
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        sm.callCount == 1 &&  // Only first called
        response1 == response2  // Cached response from Set, not Get
      )
    }
  )
