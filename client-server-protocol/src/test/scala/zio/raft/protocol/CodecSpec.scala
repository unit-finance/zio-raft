package zio.raft.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import scodec.bits.ByteVector
import scodec.Attempt
import java.time.Instant
import zio.raft.protocol.Codecs.{clientMessageCodec, serverMessageCodec}
import zio.raft.protocol.RejectionReason.*

/**
 * Contract tests for protocol message serialization using scodec.
 * 
 * These tests validate binary protocol compatibility:
 * - Message serialization round-trip integrity  
 * - Protocol signature and version handling
 * - Discriminated union encoding for message types
 * - Forward/backward compatibility for protocol evolution
 * - Performance characteristics of encoding/decoding
 */
object CodecSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment with TestEnvironment with Scope, Any] = suiteAll("Protocol Message Codec Contract") {
    
    suiteAll("Protocol Signature") {
      test("should include protocol signature") {
        assertTrue(PROTOCOL_SIGNATURE.sameElements(Array(0x7A, 0x72, 0x61, 0x66, 0x74))) // "zraft"
      }
      
      test("should include protocol version") {
        assertTrue(PROTOCOL_VERSION == 1.toByte)
      }
    }

    suiteAll("Client Message Encoding") {
      test("should encode/decode CreateSession messages") {
        val message = CreateSession(
          capabilities = Map("worker" -> "v1.0", "priority" -> "high"),
          nonce = Nonce.fromLong(12345L)
        )
        
        // Round-trip serialization
        val encoded = clientMessageCodec.encode(message)
        val decoded = encoded.flatMap(clientMessageCodec.decode)
        
        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
      
      test("should encode/decode ContinueSession messages") {
        val sessionId = SessionId.fromString("test-session-1")
        val message = ContinueSession(
          sessionId = sessionId,
          nonce = Nonce.fromLong(67890L)
        )
        
        val encoded = clientMessageCodec.encode(message)
        val decoded = encoded.flatMap(clientMessageCodec.decode)
        
        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
      
      test("should encode/decode KeepAlive messages") {
        val message = KeepAlive(timestamp = Instant.parse("2023-01-01T00:00:00Z"))
        
        val encoded = clientMessageCodec.encode(message)
        val decoded = encoded.flatMap(clientMessageCodec.decode)
        
        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
      
      test("should encode/decode ClientRequest messages") {
        val requestId = RequestId.fromLong(1L)
        val message = ClientRequest(
          requestId = requestId,
          payload = ByteVector.fromValidHex("deadbeef"),
          createdAt = Instant.parse("2023-01-01T00:00:00Z")
        )
        
        val encoded = clientMessageCodec.encode(message)
        val decoded = encoded.flatMap(clientMessageCodec.decode)
        
        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
      
      test("should encode/decode ServerRequestAck messages") {
        val requestId = RequestId.fromLong(1L)
        val message = ServerRequestAck(requestId = requestId)
        
        val encoded = clientMessageCodec.encode(message)
        val decoded = encoded.flatMap(clientMessageCodec.decode)
        
        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
    }

    suiteAll("Server Message Encoding") {
      test("should encode/decode SessionCreated messages") {
        val sessionId = SessionId.fromString("test-session-1")
        val message = SessionCreated(
          sessionId = sessionId,
          nonce = Nonce.fromLong(12345L)
        )
        
        val encoded = serverMessageCodec.encode(message)
        val decoded = encoded.flatMap(serverMessageCodec.decode)
        
        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
      
      test("should encode/decode SessionRejected messages") {
        val message = SessionRejected(
          reason = NotLeader,
          nonce = Nonce.fromLong(12345L),
          leaderId = Some(MemberId.fromString("node-2"))
        )
        
        val encoded = serverMessageCodec.encode(message)
        val decoded = encoded.flatMap(serverMessageCodec.decode)
        
        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
      
      test("should encode/decode KeepAliveResponse messages") {
        val message = KeepAliveResponse(timestamp = Instant.parse("2023-01-01T00:00:00Z"))
        
        val encoded = serverMessageCodec.encode(message)
        val decoded = encoded.flatMap(serverMessageCodec.decode)
        
        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
      
      test("should encode/decode ServerRequest messages") {
        val message = ServerRequest(
          requestId = RequestId.fromLong(1L),
          payload = ByteVector.fromValidHex("deadbeef"),
          createdAt = Instant.parse("2023-01-01T00:00:00Z")
        )
        
        val encoded = serverMessageCodec.encode(message)
        val decoded = encoded.flatMap(serverMessageCodec.decode)
        
        assertTrue(decoded.map(_.value) == Attempt.successful(message))
      }
    }

    suiteAll("Discriminated Union Encoding") {
      test("should use different discriminator bytes for each message type") {
        val createSession = CreateSession(Map("test" -> "v1"), Nonce.fromLong(123L))
        val keepAlive = KeepAlive(Instant.parse("2023-01-01T00:00:00Z"))
        val clientRequest = ClientRequest(RequestId.fromLong(1L), ByteVector.empty, Instant.parse("2023-01-01T00:00:00Z"))
        
        val encoded1 = clientMessageCodec.encode(createSession)
        val encoded2 = clientMessageCodec.encode(keepAlive) 
        val encoded3 = clientMessageCodec.encode(clientRequest)
        
        // Check that discriminator bytes match expected values (after 6-byte protocol header)
        assertTrue(
          encoded1.isSuccessful && encoded2.isSuccessful && encoded3.isSuccessful &&
          encoded1.require.bytes(6) == 1.toByte && // CreateSession
          encoded2.require.bytes(6) == 3.toByte && // KeepAlive  
          encoded3.require.bytes(6) == 4.toByte    // ClientRequest
        )
      }
      
      test("should include protocol signature in encoded messages") {
        val message = KeepAlive(Instant.parse("2023-01-01T00:00:00Z"))
        val encoded = clientMessageCodec.encode(message)
        
        // First bytes should be protocol signature
        assertTrue(encoded.map(_.bytes.take(5).toArray).fold(_ => false, _.sameElements(PROTOCOL_SIGNATURE)))
      }
    }
    
    suiteAll("Type Safety") {
      test("should prevent decoding invalid message types") {
        // Invalid discriminator byte
        val invalidBytes = ByteVector.fromValidHex("7a72616674ff") // signature + invalid discriminator
        val decoded = clientMessageCodec.decode(invalidBytes.bits)
        
        assertTrue(decoded.isFailure)
      }
      
      test("should handle corrupted message data gracefully") {
        val corruptedBytes = ByteVector.fromValidHex("deadbeefcafe") // Random data
        val decoded = clientMessageCodec.decode(corruptedBytes.bits)
        
        assertTrue(decoded.isFailure)
      }
    }

    suiteAll("Performance Characteristics") {
      test("should encode messages efficiently") {
        for {
          startTime <- Clock.instant
          results <- ZIO.foreach(1 to 1000) { i =>
            ZIO.attempt {
              val message = ClientRequest(
                requestId = RequestId.fromLong(1L),
                payload = ByteVector.fromValidHex(f"$i%08x"),
                createdAt = Instant.parse("2023-01-01T00:00:00Z")
              )
              
              clientMessageCodec.encode(message).isSuccessful
            }.catchAll(_ => ZIO.succeed(false))
          }
          endTime <- Clock.instant
          duration = java.time.Duration.between(startTime, endTime).toMillis
        } yield {
          assert(results)(forall(isTrue)) && // Should succeed - encoding should work
          assert(duration)(isLessThan(500L)) // Should be fast when implemented
        }
      }
      
      test("should have reasonable message sizes") {
        val smallMessage = KeepAlive(Instant.parse("2023-01-01T00:00:00Z"))
        val largeMessage = ClientRequest(
          requestId = RequestId.fromLong(1L),
          payload = ByteVector.fill(1000)(0xFF.toByte), // 1KB payload
          createdAt = Instant.parse("2023-01-01T00:00:00Z")
        )
        
        val smallEncoded = clientMessageCodec.encode(smallMessage)
        val largeEncoded = clientMessageCodec.encode(largeMessage)
        
        // Small message should be compact, large message reasonable (accounting for 6-byte protocol header)
        assertTrue(
          smallEncoded.fold(_ => false, bits => bits.size > 80 && bits.size < 200) &&
          largeEncoded.fold(_ => false, bits => bits.size > 8000 && bits.size < 8500) // Some overhead + protocol header
        )
      }
    }

    suiteAll("Protocol Evolution") {
      test("should support version compatibility") {
        // Future protocol versions should be detectable
        val currentVersion = PROTOCOL_VERSION
        val futureVersion = (currentVersion + 1).toByte
        
        assertTrue(currentVersion < futureVersion && futureVersion > 1)
      }
      
      test("should handle unknown message types gracefully") {
        // Create message with unknown discriminator
        val signatureBytes = ByteVector(PROTOCOL_SIGNATURE)
        val versionByte = ByteVector(PROTOCOL_VERSION)
        val unknownDiscriminator = ByteVector(255.toByte) // Unknown type
        val invalidMessage = signatureBytes ++ versionByte ++ unknownDiscriminator
        
        val decoded = clientMessageCodec.decode(invalidMessage.bits)
        assertTrue(decoded.isFailure)
      }
    }
  }
}
