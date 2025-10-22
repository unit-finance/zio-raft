package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.HMap
import zio.raft.protocol.{SessionId, RequestId}

/**
 * Contract test for Schema type safety.
 * 
 * EXPECTED: This test MUST FAIL initially (SessionSchema and CombinedSchema don't exist yet)
 * 
 * Tests:
 * - SessionSchema has 4 fixed prefixes with correct types
 * - CombinedSchema concatenates SessionSchema and UserSchema
 * - HMap provides compile-time type checking
 * - Prefix validation works at compile time
 */
object SchemaSpec extends ZIOSpecDefault:
  
  // Test user schema
  type TestUserSchema = 
    ("counter", Int) *:
    ("name", String) *:
    EmptyTuple
  
  def spec = suite("Schema Type Safety")(
    
    test("SessionSchema should have 4 fixed prefixes") {
      // SessionSchema should define these prefixes:
      // - "metadata" -> SessionMetadata
      // - "cache" -> Any
      // - "serverRequests" -> PendingServerRequest[?]
      // - "lastServerRequestId" -> RequestId
      
      val state = HMap.empty[SessionSchema]
      
      // These should compile with correct types
      val withMetadata = state.updated["metadata"](
        "session-1",
        SessionMetadata(Map.empty, java.time.Instant.now())
      )
      
      val withCache = withMetadata.updated["cache"](
        "cache-key",
        "cached-response"  // Any type
      )
      
      val withServerReq = withCache.updated["serverRequests"](
        "req-1",
        PendingServerRequest(
          RequestId(1),
          SessionId("s1"),
          "test-payload",
          java.time.Instant.now()
        )
      )
      
      val withRequestId = withServerReq.updated["lastServerRequestId"](
        "session-1",
        RequestId(100)
      )
      
      // Verify type safety - should get back correct types
      val metadata: Option[SessionMetadata] = withRequestId.get["metadata"]("session-1")
      val cacheVal: Option[Any] = withRequestId.get["cache"]("cache-key")
      val requestId: Option[RequestId] = withRequestId.get["lastServerRequestId"]("session-1")
      
      assertTrue(
        metadata.isDefined &&
        cacheVal.contains("cached-response") &&
        requestId.contains(RequestId(100))
      )
    },
    
    test("CombinedSchema should concatenate SessionSchema and UserSchema") {
      // CombinedSchema[TestUserSchema] should have all prefixes from both schemas
      val state = HMap.empty[CombinedSchema[TestUserSchema]]
      
      // Should support SessionSchema prefixes
      val withSession = state
        .updated["metadata"](
          "s1",
          SessionMetadata(Map.empty, java.time.Instant.now())
        )
        .updated["cache"]("key1", "value1")
      
      // Should support UserSchema prefixes
      val withUser = withSession
        .updated["counter"]("main", 42)
        .updated["name"]("user1", "Alice")
      
      // Verify both schema types are accessible
      val metadata: Option[SessionMetadata] = withUser.get["metadata"]("s1")
      val counter: Option[Int] = withUser.get["counter"]("main")
      val name: Option[String] = withUser.get["name"]("user1")
      
      assertTrue(
        metadata.isDefined &&
        counter.contains(42) &&
        name.contains("Alice")
      )
    },
    
    test("HMap should provide compile-time type checking") {
      val state = HMap.empty[CombinedSchema[TestUserSchema]]
      
      // This should compile - correct type for "counter" prefix
      val withCounter = state.updated["counter"]("c1", 100)
      
      // Getting value should return correct type
      val value: Option[Int] = withCounter.get["counter"]("c1")
      
      assertTrue(value.contains(100))
      
      // Note: The following would NOT compile (type mismatch):
      // val wrongType = state.updated["counter"]("c1", "string")  // Error: String is not Int
    },
    
    test("Prefix isolation - different prefixes don't interfere") {
      val state = HMap.empty[CombinedSchema[TestUserSchema]]
        .updated["counter"]("key1", 42)
        .updated["name"]("key1", "Alice")  // Same key, different prefix
      
      val counterVal: Option[Int] = state.get["counter"]("key1")
      val nameVal: Option[String] = state.get["name"]("key1")
      
      assertTrue(
        counterVal.contains(42) &&
        nameVal.contains("Alice")
      )
    },
    
    test("SessionSchema type alias should be defined") {
      // Verify SessionSchema is a type alias to a tuple type
      // This is a compile-time check
      val schema: SessionSchema = (
        ("metadata", SessionMetadata(Map.empty, java.time.Instant.now())),
        ("cache", "test"),
        ("serverRequests", PendingServerRequest(RequestId(1), SessionId("s1"), "payload", java.time.Instant.now())),
        ("lastServerRequestId", RequestId(1)),
      )
      
      assertTrue(schema != null)
    }
  )
