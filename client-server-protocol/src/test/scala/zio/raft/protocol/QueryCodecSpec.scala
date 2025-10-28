package zio.raft.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import scodec.Attempt
import scodec.bits.ByteVector
import java.time.Instant
import zio.raft.protocol.Codecs.{clientMessageCodec, serverMessageCodec}

object QueryCodecSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment & TestEnvironment & Scope, Any] =
    suiteAll("Query Codec Contract") {

      test("should encode/decode Query messages (client → server)") {
        val correlationId = CorrelationId.fromString("corr-123")
        val payload       = ByteVector.fromValidHex("c0ffee")
        val createdAt     = Instant.parse("2025-01-01T00:00:00Z")

        val message = Query(correlationId = correlationId, payload = payload, createdAt = createdAt)

        val encoded = clientMessageCodec.encode(message)
        val decoded = encoded.flatMap(clientMessageCodec.decode)

        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }

      test("should encode/decode QueryResponse messages (server → client)") {
        val correlationId = CorrelationId.fromString("corr-123")
        val result        = ByteVector.fromValidHex("beaded")

        val message = QueryResponse(correlationId = correlationId, result = result)

        val encoded = serverMessageCodec.encode(message)
        val decoded = encoded.flatMap(serverMessageCodec.decode)

        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
    }
}


