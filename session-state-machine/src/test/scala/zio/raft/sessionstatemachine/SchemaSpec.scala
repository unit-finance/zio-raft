package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.HMap
import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

/**
 * Contract test for SessionSchema with composite keys.
 * 
 * Tests the new architecture:
 * - Composite keys (SessionId, RequestId) for cache and serverRequests
 * - Byte-based HMap keys with proper numeric ordering
 * - Schema concatenation with user schema
 */
object SchemaSpec extends ZIOSpecDefault:
  
  // Test newtypes for user schema
  import zio.prelude.Newtype
  
  object CounterKey extends Newtype[String]
  type CounterKey = CounterKey.Type
  given HMap.KeyLike[CounterKey] = HMap.KeyLike.forNewtype(CounterKey)
  
  type TestUserSchema = 
    ("counter", CounterKey, Int) *:
    EmptyTuple
  
  // Use concrete types for R and SR
  type TestResponse = String  // Simple marker type for test
  type TestServerReq = String
  
  type CombinedSchema = Tuple.Concat[SessionSchema[TestResponse, TestServerReq], TestUserSchema]
  
  def spec = suite("Schema with Composite Keys")(
    
    test("SessionSchema has composite key for cache") {
      val sessionId = SessionId("session-1")
      val state = HMap.empty[SessionSchema[TestResponse, TestServerReq]]
      
      // Cache uses composite key (SessionId, RequestId)
      val withCache = state.updated["cache"](
        (sessionId, RequestId(1)),
        "cached-response"
      )
      
      val retrieved: Option[Any] = withCache.get["cache"]((sessionId, RequestId(1)))
      
      assertTrue(retrieved.contains("cached-response"))
    },
    
    test("SessionSchema has composite key for serverRequests") {
      val sessionId = SessionId("session-1")
      val state = HMap.empty[SessionSchema[TestResponse, TestServerReq]]
      
      // serverRequests uses composite key (SessionId, RequestId)
      val pending = PendingServerRequest(
        payload = "test-request",
        lastSentAt = Instant.now()
      )
      
      val withRequest = state.updated["serverRequests"](
        (sessionId, RequestId(5)),
        pending
      )
      
      val retrieved: Option[PendingServerRequest[String]] = 
        withRequest.get["serverRequests"]((sessionId, RequestId(5)))
          .map(_.asInstanceOf[PendingServerRequest[String]])
      
      assertTrue(
        retrieved.isDefined &&
        retrieved.get.payload == "test-request"
      )
    },
    
    test("Combined schema concatenates SessionSchema and UserSchema") {
      val state = HMap.empty[CombinedSchema]
      
      val sessionId = SessionId("s1")
      
      // Can use SessionSchema prefixes with composite keys
      val withSession = state
        .updated["metadata"](sessionId, SessionMetadata(Map.empty, Instant.now()))
        .updated["cache"]((sessionId, RequestId(1)), "value1")
      
      // Can use UserSchema prefixes
      val withUser = withSession
        .updated["counter"](CounterKey("main"), 42)
      
      val metadata: Option[SessionMetadata] = withUser.get["metadata"](sessionId)
      val cached: Option[Any] = withUser.get["cache"]((sessionId, RequestId(1)))
      val counter: Option[Int] = withUser.get["counter"](CounterKey("main"))
      
      assertTrue(
        metadata.isDefined &&
        cached.contains("value1") &&
        counter.contains(42)
      )
    },
    
    test("Composite keys enable range queries for session") {
      val sessionId = SessionId("session-1")
      val state = HMap.empty[SessionSchema[TestResponse, TestServerReq]]
        .updated["cache"]((sessionId, RequestId(1)), "resp1")
        .updated["cache"]((sessionId, RequestId(5)), "resp5")
        .updated["cache"]((sessionId, RequestId(10)), "resp10")
      
      // Range query: get all cache entries for session with RequestId in [0, 7)
      val rangeResults = state.range["cache"](
        (sessionId, RequestId.zero),
        (sessionId, RequestId(7))
      ).toList
      
      assertTrue(
        rangeResults.length == 2 &&  // RequestId 1 and 5, not 10
        rangeResults.map(_._1._2).toSet == Set(RequestId(1), RequestId(5))
      )
    },
    
    test("Numeric ordering works correctly for RequestIds") {
      val sessionId = SessionId("session-1")
      val state = HMap.empty[SessionSchema[TestResponse, TestServerReq]]
        .updated["cache"]((sessionId, RequestId(9)), "nine")
        .updated["cache"]((sessionId, RequestId(42)), "forty-two")
        .updated["cache"]((sessionId, RequestId(100)), "hundred")
      
      // Range should use numeric ordering, not lexicographic
      // RequestId uses big-endian encoding, so 9 < 42 < 100
      val all = state.range["cache"](
        (sessionId, RequestId.zero),
        (sessionId, RequestId.max)
      ).toList.map(_._1._2)
      
      assertTrue(
        all == List(RequestId(9), RequestId(42), RequestId(100))  // Numeric order!
      )
    }
  )
