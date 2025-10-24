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
 * Contract test for response caching (PC-2).
 * 
 * EXPECTED: This test MUST FAIL initially (SessionStateMachine doesn't exist yet)
 * 
 * Postcondition PC-2: First request caches, second request returns cached
 * 
 * Tests:
 * - Response is cached after first execution
 * - Cached response includes both result and server requests
 * - Cache persists across multiple lookups
 * - Multiple sessions maintain separate caches
 */
object ResponseCachingSpec extends ZIOSpecDefault:
  
  sealed trait TestCommand extends Command
  case class DoWork(result: String) extends TestCommand:
    type Response = String
  
  case class ServerReq(data: String)
  
  // Define typed key
  import zio.prelude.Newtype
  object DataKey extends Newtype[String]
  type DataKey = DataKey.Type
  given HMap.KeyLike[DataKey] = HMap.KeyLike.forNewtype(DataKey)
  
  type TestSchema = ("data", DataKey, String) *: EmptyTuple
  
  class TestStateMachine extends SessionStateMachine[TestCommand, ServerReq, TestSchema]:
    
    protected def applyCommand(cmd: TestCommand, createdAt: Instant): StateWriter[HMap[TestSchema], ServerReq, cmd.Response] =
      cmd match
        case DoWork(result) =>
          for {
            _ <- StateWriter.log(ServerReq("notification"))
          } yield result
    
    protected def handleSessionCreated(sid: SessionId, caps: Map[String, String], createdAt: Instant): StateWriter[HMap[TestSchema], ServerReq, Unit] =
      StateWriter.succeed(())
    
    protected def handleSessionExpired(sid: SessionId, capabilities: Map[String, String], createdAt: Instant): StateWriter[HMap[TestSchema], ServerReq, Unit] =
      StateWriter.succeed(())
    
    def takeSnapshot(state: HMap[CombinedSchema[TestSchema]]): Stream[Nothing, Byte] =
      zio.stream.ZStream.empty
    
    def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[TestSchema]]] =
      ZIO.succeed(HMap.empty)
    
    def shouldTakeSnapshot(lastSnapshotIndex: zio.raft.Index, lastSnapshotSize: Long, commitIndex: zio.raft.Index): Boolean =
      false
  
  def spec = suite("Response Caching")(
    
    test("PC-2: First request caches, second request returns cached") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestSchema]]
      val now = Instant.now()
      
      // First request
      val cmd1 = SessionCommand.ClientRequest[TestCommand, ServerReq](
        now, SessionId("s1"), RequestId(1), RequestId(1), DoWork("result1")
      )
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      // Second request (same IDs, different command)
      val cmd2 = SessionCommand.ClientRequest[TestCommand, ServerReq](
        now, SessionId("s1"), RequestId(1), RequestId(1), DoWork("result2")
      )
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        response1 == response2 &&  // Cached
        response1._1 == "result1"  // Original result
      )
    },
    
    test("Cached response does NOT include server requests (only response is cached)") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestSchema]]
      val now = Instant.now()
      
      val cmd = SessionCommand.ClientRequest[TestCommand, ServerReq](
        now, SessionId("s1"), RequestId(1), RequestId(1), DoWork("test")
      )
      val (state1, response1) = sm.apply(cmd).run(state0)
      
      // Duplicate request
      val (state2, response2) = sm.apply(cmd).run(state1)
      
      assertTrue(
        response1._2.nonEmpty &&  // First execution has server requests
        response2._2.isEmpty &&  // Cached response has empty server requests list
        response1._1 == response2._1  // But response value is the same
      )
    },
    
    test("Cache persists across multiple duplicate requests") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestSchema]]
      val now = Instant.now()
      
      val cmd = SessionCommand.ClientRequest[TestCommand, ServerReq](
        now, SessionId("s1"), RequestId(1), RequestId(1), DoWork("original")
      )
      
      // First request
      val (state1, response1) = sm.apply(cmd).run(state0)
      
      // Multiple duplicates
      val (state2, response2) = sm.apply(cmd).run(state1)
      val (state3, response3) = sm.apply(cmd).run(state2)
      val (state4, response4) = sm.apply(cmd).run(state3)
      
      assertTrue(
        response1 == response2 &&
        response2 == response3 &&
        response3 == response4 &&
        response1._1 == "original"
      )
    },
    
    test("Multiple sessions maintain separate caches") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestSchema]]
      val now = Instant.now()
      
      // Session 1, Request 1
      val cmd1 = SessionCommand.ClientRequest[TestCommand, ServerReq](
        now, SessionId("s1"), RequestId(1), RequestId(1), DoWork("session1")
      )
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      // Session 2, Request 1 (same request ID, different session)
      val cmd2 = SessionCommand.ClientRequest[TestCommand, ServerReq](
        now, SessionId("s2"), RequestId(1), RequestId(1), DoWork("session2")
      )
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      assertTrue(
        response1._1 == "session1" &&
        response2._1 == "session2" &&
        response1 != response2  // Different caches
      )
    },
    
    test("Multiple requests in same session maintain separate caches") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[CombinedSchema[TestSchema]]
      val now = Instant.now()
      
      // Request 1
      val cmd1 = SessionCommand.ClientRequest[TestCommand, ServerReq](
        now, SessionId("s1"), RequestId(1), RequestId(1), DoWork("request1")
      )
      val (state1, response1) = sm.apply(cmd1).run(state0)
      
      // Request 2 (same session, different request ID)
      val cmd2 = SessionCommand.ClientRequest[TestCommand, ServerReq](
        now, SessionId("s1"), RequestId(2), RequestId(2), DoWork("request2")
      )
      val (state2, response2) = sm.apply(cmd2).run(state1)
      
      // Duplicate of request 1
      val (state3, response1_dup) = sm.apply(cmd1).run(state2)
      
      // Duplicate of request 2
      val (state4, response2_dup) = sm.apply(cmd2).run(state3)
      
      assertTrue(
        response1._1 == "request1" &&
        response2._1 == "request2" &&
        response1_dup == response1 &&
        response2_dup == response2
      )
    }
  )
