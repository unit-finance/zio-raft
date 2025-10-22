package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.Command
import zio.raft.protocol.{SessionId, RequestId}

/**
 * Contract test for SessionCommand ADT with dependent types.
 * 
 * EXPECTED: This test MUST FAIL initially (SessionCommand doesn't exist yet)
 * 
 * Tests:
 * - SessionCommand is sealed trait
 * - All cases: ClientRequest, ServerRequestAck, SessionCreationConfirmed, SessionExpired, GetRequestsForRetry
 * - ClientRequest has dependent type Response based on UC (user command)
 * - Pattern matching works correctly
 */
object SessionCommandSpec extends ZIOSpecDefault:
  
  // Test user command types
  sealed trait TestCommand extends Command
  case class IncrementCounter(by: Int) extends TestCommand:
    type Response = Int
  
  case class GetValue() extends TestCommand:
    type Response = String
  
  def spec = suite("SessionCommand")(
    
    test("ClientRequest should accept generic user command UC") {
      val sessionId = SessionId("session-1")
      val requestId = RequestId(1)
      val userCommand = IncrementCounter(5)
      
      val clientRequest = SessionCommand.ClientRequest[IncrementCounter, Nothing](
        sessionId = sessionId,
        requestId = requestId,
        command = userCommand
      )
      
      assertTrue(
        clientRequest.sessionId == sessionId &&
        clientRequest.requestId == requestId &&
        clientRequest.command == userCommand
      )
    },
    
    test("ServerRequestAck should contain sessionId and requestId") {
      val ack = SessionCommand.ServerRequestAck[Nothing](
        sessionId = SessionId("s1"),
        requestId = RequestId(10)
      )
      
      assertTrue(
        ack.sessionId == SessionId("s1") &&
        ack.requestId == RequestId(10)
      )
    },
    
    test("CreateSession should contain sessionId and capabilities") {
      val createSession = SessionCommand.CreateSession[Nothing](
        sessionId = SessionId("new-session"),
        capabilities = Map("version" -> "1.0")
      )
      
      assertTrue(
        createSession.sessionId == SessionId("new-session") &&
        createSession.capabilities == Map("version" -> "1.0")
      )
    },
    
    test("SessionExpired should contain sessionId") {
      val expired = SessionCommand.SessionExpired[Nothing](
        sessionId = SessionId("expired-session")
      )
      
      assertTrue(expired.sessionId == SessionId("expired-session"))
    },
    
    test("GetRequestsForRetry should contain sessionId, lastSentBefore, and currentTime") {
      val threshold = java.time.Instant.parse("2025-10-22T10:00:00Z")
      val now = java.time.Instant.parse("2025-10-22T11:00:00Z")
      
      val retry = SessionCommand.GetRequestsForRetry[Nothing](
        sessionId = SessionId("retry-session"),
        lastSentBefore = threshold,
        currentTime = now
      )
      
      assertTrue(
        retry.sessionId == SessionId("retry-session") &&
        retry.lastSentBefore == threshold &&
        retry.currentTime == now
      )
    },
    
    test("should support pattern matching") {
      val commands: List[SessionCommand[TestCommand]] = List(
        SessionCommand.ClientRequest[SessionId("s1"), RequestId(1), RequestId(1), RequestId(1), IncrementCounter(1)),
        SessionCommand.ServerRequestAck[Nothing](SessionId("s1"), RequestId(1)),
        SessionCommand.CreateSession[Nothing](SessionId("s2"), Map.empty),
        SessionCommand.SessionExpired[Nothing](SessionId("s3")),
        SessionCommand.GetRequestsForRetry[Nothing](SessionId("s4"), java.time.Instant.now(), java.time.Instant.now())
      )
      
      val types = commands.map {
        case _: SessionCommand.ClientRequest[_, _] => "ClientRequest"
        case _: SessionCommand.ServerRequestAck[_] => "ServerRequestAck"
        case _: SessionCommand.CreateSession[_] => "CreateSession"
        case _: SessionCommand.SessionExpired[_] => "SessionExpired"
        case _: SessionCommand.GetRequestsForRetry[_] => "GetRequestsForRetry"
      }
      
      assertTrue(types == List(
        "ClientRequest",
        "ServerRequestAck",
        "CreateSession",
        "SessionExpired",
        "GetRequestsForRetry"
      ))
    },
    
    test("ClientRequest should preserve user command's Response type") {
      val incrementCmd = IncrementCounter(5)
      val clientRequest = SessionCommand.ClientRequest[IncrementCounter, Nothing](
        SessionId("s1"),
        RequestId(1),
        RequestId(1),  // lowestRequestId
        incrementCmd
      )
      
      // The response type should be Int (from IncrementCounter)
      // This is a compile-time check - if it compiles, dependent types work
      val _: incrementCmd.Response = 42  // Should be Int
      
      assertTrue(clientRequest.command == incrementCmd)
    }
  )
