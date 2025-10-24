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
  
  // Test newtypes for keys
  import zio.prelude.Newtype
  
  object CounterKey extends Newtype[String]
  type CounterKey = CounterKey.Type
  given HMap.KeyLike[CounterKey] = HMap.KeyLike.forNewtype(CounterKey)
  
  object NameKey extends Newtype[String]
  type NameKey = NameKey.Type
  given HMap.KeyLike[NameKey] = HMap.KeyLike.forNewtype(NameKey)
  
  // Test user schema with typed keys
  type TestUserSchema = 
    ("counter", CounterKey, Int) *:
    ("name", NameKey, String) *:
    EmptyTuple
  
  def spec = suite("Schema Type Safety")(
    
    test("SessionSchema should have 4 fixed prefixes with typed keys") {
      // SessionSchema should define these prefixes:
      // - "metadata" -> (SessionId, SessionMetadata)
      // - "cache" -> ((SessionId, RequestId), Any) - composite key for efficient range queries
      // - "serverRequests" -> (SessionId, List[PendingServerRequest[?]])
      // - "lastServerRequestId" -> (SessionId, RequestId)
      
      val sessionId = SessionId("session-1")
      val state = HMap.empty[SessionSchema]
      
      // These should compile with correct types
      val withMetadata = state.updated["metadata"](
        sessionId,
        SessionMetadata(Map.empty, java.time.Instant.now())
      )
      
      // Cache now uses composite key (SessionId, RequestId)
      val withCache = withMetadata.updated["cache"](
        (sessionId, RequestId(1)),
        "cached-response"  // Direct value, not Map
      )
      
      val withServerReq = withCache.updated["serverRequests"](
        sessionId,
        List(PendingServerRequest(
          RequestId(1),
          SessionId("s1"),
          "test-payload",
          java.time.Instant.now()
        ))
      )
      
      val withRequestId = withServerReq.updated["lastServerRequestId"](
        sessionId,
        RequestId(100)
      )
      
      // Verify type safety - should get back correct types
      val metadata: Option[SessionMetadata] = withRequestId.get["metadata"](sessionId)
      val cachedResponse: Option[Any] = withRequestId.get["cache"]((sessionId, RequestId(1)))
      val requestId: Option[RequestId] = withRequestId.get["lastServerRequestId"](sessionId)
      
      assertTrue(
        metadata.isDefined &&
        cachedResponse.contains("cached-response") &&
        requestId.contains(RequestId(100))
      )
    },
    
    test("CombinedSchema should concatenate SessionSchema and UserSchema") {
      // Schema[TestUserSchema] should have all prefixes from both schemas
      val state = HMap.empty[Schema[TestUserSchema]]
      
      val sessionId = SessionId("s1")
      
      // Should support SessionSchema prefixes with composite key for cache
      val withSession = state
        .updated["metadata"](
          sessionId,
          SessionMetadata(Map.empty, java.time.Instant.now())
        )
        .updated["cache"]((sessionId, RequestId(1)), "value1")  // Composite key
      
      // Should support UserSchema prefixes with typed keys
      val withUser = withSession
        .updated["counter"](CounterKey("main"), 42)
        .updated["name"](NameKey("user1"), "Alice")
      
      // Verify both schema types are accessible
      val metadata: Option[SessionMetadata] = withUser.get["metadata"](sessionId)
      val cachedValue: Option[Any] = withUser.get["cache"]((sessionId, RequestId(1)))
      val counter: Option[Int] = withUser.get["counter"](CounterKey("main"))
      val name: Option[String] = withUser.get["name"](NameKey("user1"))
      
      assertTrue(
        metadata.isDefined &&
        cachedValue.contains("value1") &&
        counter.contains(42) &&
        name.contains("Alice")
      )
    },
    
    test("HMap should provide compile-time type checking") {
      val state = HMap.empty[Schema[TestUserSchema]]
      
      // This should compile - correct type for "counter" prefix with typed key
      val withCounter = state.updated["counter"](CounterKey("c1"), 100)
      
      // Getting value should return correct type
      val value: Option[Int] = withCounter.get["counter"](CounterKey("c1"))
      
      assertTrue(value.contains(100))
      
      // Note: The following would NOT compile (type mismatch):
      // val wrongType = state.updated["counter"](CounterKey("c1"), "string")  // Error: String is not Int
      // val wrongKey = state.updated["counter"](NameKey("c1"), 100)  // Error: NameKey is not CounterKey
    },
    
    test("Prefix isolation - different prefixes don't interfere") {
      val state = HMap.empty[Schema[TestUserSchema]]
        .updated["counter"](CounterKey("key1"), 42)
        .updated["name"](NameKey("key1"), "Alice")  // Same string value, different typed keys
      
      val counterVal: Option[Int] = state.get["counter"](CounterKey("key1"))
      val nameVal: Option[String] = state.get["name"](NameKey("key1"))
      
      assertTrue(
        counterVal.contains(42) &&
        nameVal.contains("Alice")
      )
    },
    
    test("SessionSchema uses triple tuples with typed keys") {
      // Verify SessionSchema structure is triple tuples: (Prefix, KeyType, ValueType)
      // This is a compile-time structure check
      
      val state = HMap.empty[SessionSchema]
      val sessionId = SessionId("test-session")
      
      // Each operation demonstrates the triple structure is correct
      val withMetadata = state.updated["metadata"](sessionId, SessionMetadata(Map.empty, java.time.Instant.now()))
      val withCache = withMetadata.updated["cache"]((sessionId, RequestId(1)), "some-value")  // Composite key
      val withServerReqs = withCache.updated["serverRequests"](sessionId, List.empty[PendingServerRequest[String]])
      val withReqId = withServerReqs.updated["lastServerRequestId"](sessionId, RequestId(0))
      
      assertTrue(withReqId != null)
    }
  )
