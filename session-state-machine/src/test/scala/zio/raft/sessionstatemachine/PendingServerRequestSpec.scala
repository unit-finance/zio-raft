package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

/**
 * Contract test for PendingServerRequest[SR].
 * 
 * EXPECTED: This test MUST FAIL initially (PendingServerRequest doesn't exist yet)
 * 
 * Tests:
 * - PendingServerRequest creation with all fields
 * - Generic type parameter SR
 * - lastSentAt is NOT optional (required field)
 * - Immutability
 */
object PendingServerRequestSpec extends ZIOSpecDefault:
  
  // Test payload type
  case class TestServerRequest(message: String, priority: Int)
  
  def spec = suite("PendingServerRequest")(
    
    test("should create PendingServerRequest with all fields") {
      val requestId = RequestId(42)
      val sessionId = SessionId("session-123")
      val payload = TestServerRequest("test message", 1)
      val lastSentAt = Instant.parse("2025-10-22T10:00:00Z")
      
      val pending = PendingServerRequest(
        id = requestId,
        sessionId = sessionId,
        payload = payload,
        lastSentAt = lastSentAt
      )
      
      assertTrue(
        pending.id == requestId &&
        pending.sessionId == sessionId &&
        pending.payload == payload &&
        pending.lastSentAt == lastSentAt
      )
    },
    
    test("should support generic type parameter SR") {
      case class CustomPayload(data: String)
      
      val pending1 = PendingServerRequest(
        id = RequestId(1),
        sessionId = SessionId("s1"),
        payload = CustomPayload("test"),
        lastSentAt = Instant.now()
      )
      
      val pending2 = PendingServerRequest(
        id = RequestId(2),
        sessionId = SessionId("s2"),
        payload = TestServerRequest("msg", 2),
        lastSentAt = Instant.now()
      )
      
      assertTrue(
        pending1.payload.isInstanceOf[CustomPayload] &&
        pending2.payload.isInstanceOf[TestServerRequest]
      )
    },
    
    test("lastSentAt must NOT be optional (required field)") {
      // This should compile - lastSentAt is required
      val pending = PendingServerRequest(
        id = RequestId(1),
        sessionId = SessionId("s1"),
        payload = TestServerRequest("test", 1),
        lastSentAt = Instant.now()
      )
      
      // Verify it's an Instant, not Option[Instant]
      val timestamp: Instant = pending.lastSentAt
      
      assertTrue(timestamp != null)
    },
    
    test("should be immutable (case class)") {
      val original = PendingServerRequest(
        id = RequestId(1),
        sessionId = SessionId("s1"),
        payload = TestServerRequest("original", 1),
        lastSentAt = Instant.parse("2025-10-22T10:00:00Z")
      )
      
      val updated = original.copy(
        lastSentAt = Instant.parse("2025-10-22T11:00:00Z")
      )
      
      assertTrue(
        original.lastSentAt == Instant.parse("2025-10-22T10:00:00Z") &&
        updated.lastSentAt == Instant.parse("2025-10-22T11:00:00Z")
      )
    }
  )
