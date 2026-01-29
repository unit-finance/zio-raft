package zio.raft.protocol

import zio.test.*
import scodec.bits.ByteVector
import java.time.Instant
import zio.raft.protocol.Codecs.{clientMessageCodec, serverMessageCodec}
import scala.io.Source

/** Cross-language compatibility tests for ZIO Raft client-server protocol.
  *
  * These tests verify that Scala and TypeScript implementations encode/decode messages identically.
  * Scala is the source of truth - hex fixtures are stored in src/test/resources/fixtures/.
  *
  * The tests use fixed test data (timestamps, IDs, etc.) to ensure reproducible binary output.
  *
  * Fixture files are simple hex strings (one per message type). Both Scala and TypeScript tests
  * read from these files to ensure alignment.
  */
object ClientCompatibilitySpec extends ZIOSpecDefault {

  /** Convert ByteVector to hex string for comparison */
  private def toHex(bytes: ByteVector): String =
    bytes.toHex
  
  /** Read hex fixture from resources */
  private def readFixture(filename: String): String =
    Source.fromResource(s"fixtures/$filename").mkString.trim

  // Fixed test data for reproducible encoding
  private val testTimestamp = Instant.ofEpochMilli(1705680000000L) // 2024-01-19 12:00:00 UTC
  private val testSessionId = SessionId("test-session-123")
  private val testMemberId = MemberId.fromString("node-1")
  private val testNonce = Nonce.fromLong(42L)
  private val testRequestId = RequestId(100L)
  private val testCorrelationId = CorrelationId("corr-123")
  private val testPayload = ByteVector("test-payload".getBytes("UTF-8"))
  private val testCapabilities = Map("capability1" -> "value1", "capability2" -> "value2")

  def spec: Spec[Any, Nothing] = suite("ClientCompatibilitySpec")(
    suite("typescript compatibility - client messages")(
      test("CreateSession encodes correctly") {
        val message = CreateSession(
          capabilities = testCapabilities,
          nonce = testNonce
        )
        val encoded = clientMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("CreateSession.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("ContinueSession encodes correctly") {
        val message = ContinueSession(
          sessionId = testSessionId,
          nonce = testNonce
        )
        val encoded = clientMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("ContinueSession.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("KeepAlive encodes correctly") {
        val message = KeepAlive(
          timestamp = testTimestamp
        )
        val encoded = clientMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("KeepAlive.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("ClientRequest encodes correctly") {
        val message = ClientRequest(
          requestId = testRequestId,
          lowestPendingRequestId = testRequestId,
          payload = testPayload,
          createdAt = testTimestamp
        )
        val encoded = clientMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("ClientRequest.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("Query encodes correctly") {
        val message = Query(
          correlationId = testCorrelationId,
          payload = testPayload,
          createdAt = testTimestamp
        )
        val encoded = clientMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("Query.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("ServerRequestAck encodes correctly") {
        val message = ServerRequestAck(
          requestId = testRequestId
        )
        val encoded = clientMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("ServerRequestAck.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("CloseSession encodes correctly") {
        val message = CloseSession(
          reason = CloseReason.ClientShutdown
        )
        val encoded = clientMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("CloseSession.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("ConnectionClosed encodes correctly") {
        val message = ConnectionClosed
        val encoded = clientMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("ConnectionClosed.hex")
        
        assertTrue(hex == expectedHex)
      }
    ),
    
    suite("typescript compatibility - server messages")(
      test("SessionCreated encodes correctly") {
        val message = SessionCreated(
          sessionId = testSessionId,
          nonce = testNonce
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("SessionCreated.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("SessionContinued encodes correctly") {
        val message = SessionContinued(
          nonce = testNonce
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("SessionContinued.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("SessionRejected encodes correctly") {
        val message = SessionRejected(
          reason = RejectionReason.NotLeader,
          nonce = testNonce,
          leaderId = Some(testMemberId)
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("SessionRejected.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("SessionRejected with no leaderId encodes correctly") {
        val message = SessionRejected(
          reason = RejectionReason.InvalidCapabilities,
          nonce = testNonce,
          leaderId = None
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("SessionRejectedNoLeader.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("SessionClosed encodes correctly") {
        val message = SessionClosed(
          reason = SessionCloseReason.Shutdown,
          leaderId = Some(testMemberId)
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("SessionClosed.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("KeepAliveResponse encodes correctly") {
        val message = KeepAliveResponse(
          timestamp = testTimestamp
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("KeepAliveResponse.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("ClientResponse encodes correctly") {
        val message = ClientResponse(
          requestId = testRequestId,
          result = testPayload
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("ClientResponse.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("QueryResponse encodes correctly") {
        val message = QueryResponse(
          correlationId = testCorrelationId,
          result = testPayload
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("QueryResponse.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("ServerRequest encodes correctly") {
        val message = ServerRequest(
          requestId = testRequestId,
          payload = testPayload,
          createdAt = testTimestamp
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("ServerRequest.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("RequestError encodes correctly") {
        val message = RequestError(
          requestId = testRequestId,
          reason = RequestErrorReason.ResponseEvicted
        )
        val encoded = serverMessageCodec.encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("RequestError.hex")
        
        assertTrue(hex == expectedHex)
      }
    )
  )

}
