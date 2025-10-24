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
 * Contract test for cumulative acknowledgment (PC-3).
 * 
 * EXPECTED: This test MUST FAIL initially (SessionStateMachine doesn't exist yet)
 * 
 * Postcondition PC-3: Ack N removes all pending requests ≤ N
 * 
 * Tests:
 * - Property-based: ack N removes all ≤ N
 * - Specific cases: ack middle, ack all, ack subset
 * - Edge cases: ack non-existent, ack duplicate
 */
object CumulativeAckSpec extends ZIOSpecDefault:
  
  sealed trait TestCommand extends Command
  case class NoOp() extends TestCommand:
    type Response = Unit
  
  case class ServerReq(id: Int, data: String)
  
  type TestSchema = EmptyTuple
  
  class TestStateMachine extends SessionStateMachine[TestCommand, ServerReq, TestSchema]:
    
    // Return server requests for testing
    protected def applyCommand(cmd: TestCommand, createdAt: Instant): StateWriter[HMap[TestSchema], ServerReq, cmd.Response] =
      cmd match
        case NoOp() =>
          // Return multiple server requests to test cumulative ack
          for {
            _ <- StateWriter.log(ServerReq(1, "req1"))
            _ <- StateWriter.log(ServerReq(2, "req2"))
            _ <- StateWriter.log(ServerReq(3, "req3"))
          } yield ()
    
    protected def handleSessionCreated(sid: SessionId, caps: Map[String, String], createdAt: Instant): StateWriter[HMap[TestSchema], ServerReq, Unit] =
      StateWriter.succeed(())
    
    protected def handleSessionExpired(sid: SessionId, capabilities: Map[String, String], createdAt: Instant): StateWriter[HMap[TestSchema], ServerReq, Unit] =
      StateWriter.succeed(())
    
    def takeSnapshot(state: HMap[Schema[TestSchema]]): Stream[Nothing, Byte] =
      zio.stream.ZStream.empty
    
    def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[Schema[TestSchema]]] =
      ZIO.succeed(HMap.empty)
    
    def shouldTakeSnapshot(lastSnapshotIndex: zio.raft.Index, lastSnapshotSize: Long, commitIndex: zio.raft.Index): Boolean =
      false
    
    // Helper to check pending requests
    def getPendingRequests(state: HMap[Schema[TestSchema]], sid: SessionId): List[PendingServerRequest[ServerReq]] =
      state.get["serverRequests"](sid).getOrElse(List.empty).asInstanceOf[List[PendingServerRequest[ServerReq]]]
  
  def spec = suite("Cumulative Acknowledgment")(
    
    test("PC-3: Ack N removes all pending requests with ID ≤ N") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[Schema[TestSchema]]
      val now = Instant.now()
      
      // Create session
      val sessionId = SessionId("s1")
      val createCmd: SessionCommand[TestCommand, ServerReq] = SessionCommand.CreateSession[ServerReq](now, sessionId, Map.empty)
      val (state1, _) = sm.apply(createCmd).run(state0)
      
      // Execute command that generates server requests
      val clientCmd = SessionCommand.ClientRequest[TestCommand, ServerReq](
        now, sessionId, RequestId(1), RequestId(1), NoOp()
      )
      val (state2, (_, serverReqs)) = sm.apply(clientCmd).run(state1)
      
      // Verify server requests were generated
      assertTrue(serverReqs.length == 3)
      
      // Now acknowledge request 2 - should remove requests 1 and 2, keep 3
      val ackCmd: SessionCommand[TestCommand, ServerReq] = SessionCommand.ServerRequestAck[ServerReq](now, sessionId, RequestId(2))
      val (state3, _) = sm.apply(ackCmd).run(state2)
      
      // Check remaining pending requests
      val pending = sm.getPendingRequests(state3, sessionId)
      
      assertTrue(
        pending.length == 1 &&  // Only one left
        pending.head.id == RequestId(3)  // Request 3 remains
      )
    },
    
    test("Acknowledging all requests clears pending list") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[Schema[TestSchema]]
      val now = Instant.now()
      
      val sessionId = SessionId("s1")
      
      // Create session and generate requests
      val (state1, _) = sm.apply(SessionCommand.CreateSession[ServerReq](now, sessionId, Map.empty): SessionCommand[TestCommand, ServerReq]).run(state0)
      val (state2, _) = sm.apply(
        SessionCommand.ClientRequest[TestCommand, ServerReq](now, sessionId, RequestId(1), RequestId(1), NoOp())
      ).run(state1)
      
      // Ack the highest request ID (3)
      val (state3, _) = sm.apply(SessionCommand.ServerRequestAck[ServerReq](now, sessionId, RequestId(3)): SessionCommand[TestCommand, ServerReq]).run(state2)
      
      val pending = sm.getPendingRequests(state3, sessionId)
      
      assertTrue(pending.isEmpty)
    },
    
    test("Acknowledging first request only removes that request") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[Schema[TestSchema]]
      val now = Instant.now()
      
      val sessionId = SessionId("s1")
      val (state1, _) = sm.apply(SessionCommand.CreateSession[ServerReq](now, sessionId, Map.empty): SessionCommand[TestCommand, ServerReq]).run(state0)
      val (state2, _) = sm.apply(
        SessionCommand.ClientRequest[TestCommand, ServerReq](now, sessionId, RequestId(1), RequestId(1), NoOp())
      ).run(state1)
      
      // Ack request 1
      val (state3, _) = sm.apply(SessionCommand.ServerRequestAck[ServerReq](now, sessionId, RequestId(1)): SessionCommand[TestCommand, ServerReq]).run(state2)
      
      val pending = sm.getPendingRequests(state3, sessionId)
      
      assertTrue(
        pending.length == 2 &&
        pending.forall(p => RequestId.unwrap(p.id) > 1L)  // Only 2 and 3 remain
      )
    },
    
    test("Acknowledging non-existent high ID clears all requests") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[Schema[TestSchema]]
      val now = Instant.now()
      
      val sessionId = SessionId("s1")
      val (state1, _) = sm.apply(SessionCommand.CreateSession[ServerReq](now, sessionId, Map.empty): SessionCommand[TestCommand, ServerReq]).run(state0)
      val (state2, _) = sm.apply(
        SessionCommand.ClientRequest[TestCommand, ServerReq](now, sessionId, RequestId(1), RequestId(1), NoOp())
      ).run(state1)
      
      // Ack request 999 (higher than any existing)
      val (state3, _) = sm.apply(SessionCommand.ServerRequestAck[ServerReq](now, sessionId, RequestId(999)): SessionCommand[TestCommand, ServerReq]).run(state2)
      
      val pending = sm.getPendingRequests(state3, sessionId)
      
      assertTrue(pending.isEmpty)  // All removed
    },
    
    test("Acknowledging same request twice is idempotent") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[Schema[TestSchema]]
      val now = Instant.now()
      
      val sessionId = SessionId("s1")
      val (state1, _) = sm.apply(SessionCommand.CreateSession[ServerReq](now, sessionId, Map.empty): SessionCommand[TestCommand, ServerReq]).run(state0)
      val (state2, _) = sm.apply(
        SessionCommand.ClientRequest[TestCommand, ServerReq](now, sessionId, RequestId(1), RequestId(1), NoOp())
      ).run(state1)
      
      // First ack
      val (state3, _) = sm.apply(SessionCommand.ServerRequestAck[ServerReq](now, sessionId, RequestId(2)): SessionCommand[TestCommand, ServerReq]).run(state2)
      val pending1 = sm.getPendingRequests(state3, sessionId)
      
      // Second ack (same ID)
      val (state4, _) = sm.apply(SessionCommand.ServerRequestAck[ServerReq](now, sessionId, RequestId(2)): SessionCommand[TestCommand, ServerReq]).run(state3)
      val pending2 = sm.getPendingRequests(state4, sessionId)
      
      assertTrue(pending1 == pending2)  // Same result
    },
    
    test("Property: Ack N where N < max removes only requests ≤ N") {
      check(Gen.int(1, 10)) { ackN =>
        val sm = new TestStateMachine()
        val state0 = HMap.empty[Schema[TestSchema]]
        val now = Instant.now()
        
        val sessionId = SessionId("s1")
        
        // Create session with multiple requests
        val (state1, _) = sm.apply(SessionCommand.CreateSession[ServerReq](now, sessionId, Map.empty): SessionCommand[TestCommand, ServerReq]).run(state0)
        
        // Generate multiple commands to create more requests
        val stateWithRequests = (1 to 3).foldLeft(state1) { (state, i) =>
          val (newState, _) = sm.apply(
            SessionCommand.ClientRequest[TestCommand, ServerReq](
              now, sessionId, RequestId(i.toLong), RequestId(i.toLong), NoOp()
            )
          ).run(state)
          newState
        }
        
        // Ack request N
        val (finalState, _) = sm.apply(
          SessionCommand.ServerRequestAck[ServerReq](now, sessionId, RequestId(ackN.toLong)): SessionCommand[TestCommand, ServerReq]
        ).run(stateWithRequests)
        
        val pending = sm.getPendingRequests(finalState, sessionId)
        
        // All remaining requests should have ID > N
        assertTrue(pending.forall(p => RequestId.unwrap(p.id) > ackN.toLong))
      }
    }
  )
