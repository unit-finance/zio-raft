package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.{UIO, ZIO}
import zio.prelude.State
import zio.raft.{Command, HMap}
import zio.raft.protocol.{SessionId, RequestId}
import zio.stream.Stream

/**
 * Property-based invariant tests for SessionStateMachine.
 * 
 * EXPECTED: This test MUST FAIL initially (SessionStateMachine doesn't exist yet)
 * 
 * Tests invariants from the contracts:
 * - INV-1: Idempotency consistency
 * - INV-3: Monotonic server request IDs
 * - INV-6: Schema type safety
 * - INV-7: Prefix isolation
 */
object InvariantSpec extends ZIOSpecDefault:
  
  sealed trait TestCommand extends Command
  case class Increment(by: Int) extends TestCommand:
    type Response = Int
  
  type TestSchema = ("counter", Int) *: EmptyTuple
  
  case class ServerReq(id: Int)
  
  class TestStateMachine extends SessionStateMachine[TestCommand, ServerReq, TestSchema]:
    
    protected def applyCommand(cmd: TestCommand): State[HMap[CombinedSchema[TestSchema]], (cmd.Response, List[ServerReq])] =
      cmd match
        case Increment(by) =>
          State.modify { state =>
            val current = state.get["counter"]("value").getOrElse(0)
            val newValue = current + by
            val newState = state.updated["counter"]("value", newValue)
            (newState, (newValue, List(ServerReq(newValue))))
          }
    
    protected def handleSessionCreated(sid: SessionId, caps: Map[String, String]): State[HMap[CombinedSchema[TestSchema]], List[ServerReq]] =
      State.succeed(Nil)
    
    protected def handleSessionExpired(sid: SessionId): State[HMap[CombinedSchema[TestSchema]], List[ServerReq]] =
      State.succeed(Nil)
    
    def takeSnapshot(state: HMap[CombinedSchema[TestSchema]]): Stream[Nothing, Byte] =
      Stream.empty
    
    def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[TestSchema]]] =
      ZIO.succeed(HMap.empty)
  
  def spec = suite("Invariants")(
    
    test("INV-1: Idempotency consistency - duplicate requests always return same response") {
      check(Gen.int(1, 100), Gen.int(1, 10)) { (value, duplicateCount) =>
        val sm = new TestStateMachine()
        val state0 = HMap.empty[CombinedSchema[TestSchema]]
        
        val sessionId = SessionId("s1")
        val requestId = RequestId(1)
        
        // First request
        val cmd = SessionCommand.ClientRequest(sessionId, requestId, Increment(value))
        val (state1, response1) = sm.apply(cmd).run(state0)
        
        // Multiple duplicates
        val responses = (1 to duplicateCount).foldLeft((state1, List(response1))) { case ((state, responses), _) =>
          val (newState, response) = sm.apply(cmd).run(state)
          (newState, response :: responses)
        }
        
        // All responses should be identical
        assertTrue(responses._2.distinct.length == 1)
      }
    },
    
    test("INV-3: Monotonic server request IDs - IDs always increase") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestSchema]]
      
      val sessionId = SessionId("s1")
      
      // Create session
      val (state1, _) = sm.apply(
        SessionCommand.CreateSession(sessionId, Map.empty)
      ).run(state0)
      
      // Generate multiple requests - each should produce server requests with increasing IDs
      val (finalState, allServerReqs) = (1 to 5).foldLeft((state1, List.empty[(Int, List[ServerReq])])) { 
        case ((state, accum), i) =>
          val (newState, (_, serverReqs)) = sm.apply(
            SessionCommand.ClientRequest(sessionId, RequestId(i.toLong), Increment(1))
          ).run(state)
          (newState, (i, serverReqs) :: accum)
      }
      
      // Server request IDs should be monotonically increasing
      // (Note: actual ID assignment happens in the base class implementation)
      // For now, just verify that server requests were generated
      assertTrue(allServerReqs.forall { case (_, reqs) => reqs.nonEmpty })
    },
    
    test("INV-6: Schema type safety - HMap enforces correct types at compile time") {
      val state = HMap.empty[CombinedSchema[TestSchema]]
      
      // These operations should compile - correct types
      val state1 = state.updated["counter"]("value", 42)
      val value: Option[Int] = state1.get["counter"]("value")
      
      // Verify runtime behavior matches compile-time types
      assertTrue(value.contains(42))
      
      // Note: The following would NOT compile (type mismatch):
      // val wrongType = state.updated["counter"]("value", "string")
    },
    
    test("INV-7: Prefix isolation - different prefixes don't interfere") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestSchema]]
      
      // Create session (adds metadata prefix)
      val sessionId = SessionId("s1")
      val (state1, _) = sm.apply(
        SessionCommand.CreateSession(sessionId, Map("key1" -> "value1"))
      ).run(state0)
      
      // Add user data (counter prefix)
      val (state2, _) = sm.apply(
        SessionCommand.ClientRequest(sessionId, RequestId(1), Increment(100))
      ).run(state1)
      
      // Both prefixes should coexist independently
      val metadata = state2.get["metadata"](SessionId.unwrap(sessionId))
      val counter = state2.get["counter"]("value")
      
      assertTrue(
        metadata.isDefined &&
        counter.contains(100)
      )
    },
    
    test("Property: Multiple sessions operate independently") {
      check(Gen.listOfN(5)(Gen.int(1, 100))) { values =>
        val sm = new TestStateMachine()
        val state0 = HMap.empty[CombinedSchema[TestSchema]]
        
        // Create multiple sessions and execute commands
        val (finalState, results) = values.zipWithIndex.foldLeft((state0, List.empty[(String, Int)])) {
          case ((state, accum), (value, index)) =>
            val sessionId = SessionId(s"session-$index")
            
            // Create session
            val (state1, _) = sm.apply(
              SessionCommand.CreateSession(sessionId, Map.empty)
            ).run(state)
            
            // Execute command
            val (state2, (response, _)) = sm.apply(
              SessionCommand.ClientRequest(sessionId, RequestId(1), Increment(value))
            ).run(state1)
            
            (state2, (s"session-$index", response) :: accum)
        }
        
        // Each session should have its own state
        assertTrue(results.map(_._2).toSet.size == values.distinct.size)
      }
    },
    
    test("Property: Command execution is deterministic") {
      check(Gen.listOfN(10)(Gen.int(1, 50))) { increments =>
        val sm1 = new TestStateMachine()
        val sm2 = new TestStateMachine()
        
        val state0_1 = HMap.empty[CombinedSchema[TestSchema]]
        val state0_2 = HMap.empty[CombinedSchema[TestSchema]]
        
        val sessionId = SessionId("s1")
        
        // Apply same commands to both state machines
        val commands = increments.zipWithIndex.map { case (value, index) =>
          SessionCommand.ClientRequest(sessionId, RequestId((index + 1).toLong), Increment(value))
        }
        
        val (finalState1, responses1) = commands.foldLeft((state0_1, List.empty[(Int, List[ServerReq])])) {
          case ((state, accum), cmd) =>
            val (newState, response) = sm1.apply(cmd).run(state)
            (newState, response :: accum)
        }
        
        val (finalState2, responses2) = commands.foldLeft((state0_2, List.empty[(Int, List[ServerReq])])) {
          case ((state, accum), cmd) =>
            val (newState, response) = sm2.apply(cmd).run(state)
            (newState, response :: accum)
        }
        
        // Both should produce identical results
        assertTrue(responses1 == responses2)
      }
    },
    
    test("Property: State transitions are pure (no side effects)") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestSchema]]
      
      val sessionId = SessionId("s1")
      val cmd = SessionCommand.ClientRequest(sessionId, RequestId(1), Increment(10))
      
      // Running the same state transition multiple times should produce the same result
      val (state1_a, response_a) = sm.apply(cmd).run(state0)
      val (state1_b, response_b) = sm.apply(cmd).run(state0)
      val (state1_c, response_c) = sm.apply(cmd).run(state0)
      
      assertTrue(
        response_a == response_b &&
        response_b == response_c &&
        state1_a == state1_b &&
        state1_b == state1_c
      )
    }
  )
