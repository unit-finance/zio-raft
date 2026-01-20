package zio.kvstore.protocol

import zio.test.*
import scodec.Codec
import scodec.bits.ByteVector
import scala.io.Source

/** Cross-language compatibility tests for KVStore protocol.
  *
  * These tests verify that Scala and TypeScript implementations encode/decode messages identically.
  * Scala is the source of truth - hex fixtures are stored in src/test/resources/fixtures/.
  *
  * Fixture files are simple hex strings (one per message type). Both Scala and TypeScript tests
  * read from these files to ensure alignment.
  */
object ClientCompatibilitySpec extends ZIOSpecDefault:

  /** Convert ByteVector to hex string for comparison */
  private def toHex(bytes: ByteVector): String =
    bytes.toHex
  
  /** Read hex fixture from resources */
  private def readFixture(filename: String): String =
    Source.fromResource(s"fixtures/$filename").mkString.trim

  def spec = suite("ClientCompatibilitySpec")(
    suite("typescript compatibility")(
      test("KVClientRequest.Set encodes correctly") {
        val message = KVClientRequest.Set("test-key", "test-value")
        val encoded = summon[Codec[KVClientRequest]].encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("Set.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("KVQuery.Get encodes correctly") {
        val message = KVQuery.Get("test-key")
        val encoded = summon[Codec[KVQuery]].encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("Get.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("KVClientRequest.Watch encodes correctly") {
        val message = KVClientRequest.Watch("test-key")
        val encoded = summon[Codec[KVClientRequest]].encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("Watch.hex")
        
        assertTrue(hex == expectedHex)
      },
      
      test("KVServerRequest.Notification encodes correctly") {
        val message = KVServerRequest.Notification("test-key", "test-value")
        val encoded = summon[Codec[KVServerRequest]].encode(message).require
        val hex = toHex(encoded.bytes)
        
        val expectedHex = readFixture("Notification.hex")
        
        assertTrue(hex == expectedHex)
      }
    )
  )

end ClientCompatibilitySpec
