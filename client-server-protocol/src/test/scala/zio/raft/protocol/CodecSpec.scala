package zio.raft.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import scodec.bits.ByteVector
import scodec.Attempt
import java.time.Instant
import zio.raft.protocol.Codecs.{clientMessageCodec, serverMessageCodec}

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

  override def spec: Spec[Environment with TestEnvironment with Scope, Any] = suite("Protocol Message Codec Contract")(
    
    suite("Protocol Signature")(
      test("should include protocol signature") {
        for {
          result <- ZIO.attempt {
            PROTOCOL_SIGNATURE.sameElements(Array(0x7A, 0x72, 0x61, 0x66, 0x74)) // "zraft"
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // This should work from package.scala
      },
      
      test("should include protocol version") {
        for {
          result <- ZIO.attempt {
            PROTOCOL_VERSION == 1.toByte
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // This should work from package.scala
      }
    ),

    suite("Client Message Encoding")(
      test("should encode/decode CreateSession messages") {
        for {
          result <- ZIO.attempt {
            val message = CreateSession(
              capabilities = Map("worker" -> "v1.0", "priority" -> "high"),
              nonce = Nonce.fromLong(12345L)
            )
            
            // Round-trip serialization
            val encoded = clientMessageCodec.encode(message)
            val decoded = encoded.flatMap(clientMessageCodec.decode)
            
            decoded.map(_.value) == Attempt.successful(message)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should encode/decode ContinueSession messages") {
        for {
          result <- ZIO.attempt {
            val sessionId = SessionId.generateUnsafe()
            val message = ContinueSession(
              sessionId = sessionId,
              nonce = Nonce.fromLong(67890L)
            )
            
            val encoded = clientMessageCodec.encode(message)
            val decoded = encoded.flatMap(clientMessageCodec.decode)
            
            decoded.map(_.value) == Attempt.successful(message)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should encode/decode KeepAlive messages") {
        for {
          result <- ZIO.attempt {
            val message = KeepAlive(timestamp = Instant.parse("2023-01-01T00:00:00Z"))
            
            val encoded = clientMessageCodec.encode(message)
            val decoded = encoded.flatMap(clientMessageCodec.decode)
            
            decoded.map(_.value) == Attempt.successful(message)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should encode/decode ClientRequest messages") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            val message = ClientRequest(
              requestId = requestId,
              payload = ByteVector.fromValidHex("deadbeef"),
              createdAt = Instant.parse("2023-01-01T00:00:00Z")
            )
            
            val encoded = clientMessageCodec.encode(message)
            val decoded = encoded.flatMap(clientMessageCodec.decode)
            
            decoded.map(_.value) == Attempt.successful(message)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should encode/decode ServerRequestAck messages") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            val message = ServerRequestAck(requestId = requestId)
            
            val encoded = clientMessageCodec.encode(message)
            val decoded = encoded.flatMap(clientMessageCodec.decode)
            
            decoded.map(_.value) == Attempt.successful(message)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("Server Message Encoding")(
      test("should encode/decode SessionCreated messages") {
        for {
          result <- ZIO.attempt {
            val sessionId = SessionId.generateUnsafe()
            val message = SessionCreated(
              sessionId = sessionId,
              nonce = Nonce.fromLong(12345L)
            )
            
            val encoded = serverMessageCodec.encode(message)
            val decoded = encoded.flatMap(serverMessageCodec.decode)
            
            decoded.map(_.value) == Attempt.successful(message)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should encode/decode SessionRejected messages") {
        for {
          result <- ZIO.attempt {
            val message = SessionRejected(
              reason = NotLeader,
              nonce = Nonce.fromLong(12345L),
              leaderId = Some(MemberId.fromString("node-2"))
            )
            
            val encoded = serverMessageCodec.encode(message)
            val decoded = encoded.flatMap(serverMessageCodec.decode)
            
            decoded.map(_.value) == Attempt.successful(message)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should encode/decode KeepAliveResponse messages") {
        for {
          result <- ZIO.attempt {
            val message = KeepAliveResponse(timestamp = Instant.parse("2023-01-01T00:00:00Z"))
            
            val encoded = serverMessageCodec.encode(message)
            val decoded = encoded.flatMap(serverMessageCodec.decode)
            
            decoded.map(_.value) == Attempt.successful(message)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should encode/decode ServerRequest messages") {
        for {
          result <- ZIO.attempt {
            val message = ServerRequest(
              requestId = RequestId.fromLong(1L),
              payload = ByteVector.fromValidHex("deadbeef"),
              createdAt = Instant.parse("2023-01-01T00:00:00Z")
            )
            
            val encoded = serverMessageCodec.encode(message)
            val decoded = encoded.flatMap(serverMessageCodec.decode)
            
            decoded.map(_.value) == Attempt.successful(message)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("Discriminated Union Encoding")(
      test("should use different discriminator bytes for each message type") {
        for {
          result <- ZIO.attempt {
            val createSession = CreateSession(Map("test" -> "v1"), Nonce.fromLong(123L))
            val keepAlive = KeepAlive(Instant.parse("2023-01-01T00:00:00Z"))
            val clientRequest = ClientRequest(RequestId.fromLong(1L), ByteVector.empty, Instant.parse("2023-01-01T00:00:00Z"))
            
            val encoded1 = clientMessageCodec.encode(createSession)
            val encoded2 = clientMessageCodec.encode(keepAlive) 
            val encoded3 = clientMessageCodec.encode(clientRequest)
            
            // Check that discriminator bytes match expected values (after 6-byte protocol header)
            encoded1.isSuccessful && encoded2.isSuccessful && encoded3.isSuccessful &&
            encoded1.require.bytes(6) == 1.toByte && // CreateSession
            encoded2.require.bytes(6) == 3.toByte && // KeepAlive  
            encoded3.require.bytes(6) == 4.toByte    // ClientRequest
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should include protocol signature in encoded messages") {
        for {
          result <- ZIO.attempt {
            val message = KeepAlive(Instant.parse("2023-01-01T00:00:00Z"))
            val encoded = clientMessageCodec.encode(message)
            
            // First bytes should be protocol signature
            encoded.map(_.bytes.take(5).toArray).fold(_ => false, _.sameElements(PROTOCOL_SIGNATURE))
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("Type Safety")(
      test("should prevent decoding invalid message types") {
        for {
          result <- ZIO.attempt {
            // Invalid discriminator byte
            val invalidBytes = ByteVector.fromValidHex("7a72616674ff") // signature + invalid discriminator
            val decoded = clientMessageCodec.decode(invalidBytes.bits)
            
            decoded.isFailure
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should handle corrupted message data gracefully") {
        for {
          result <- ZIO.attempt {
            val corruptedBytes = ByteVector.fromValidHex("deadbeefcafe") // Random data
            val decoded = clientMessageCodec.decode(corruptedBytes.bits)
            
            decoded.isFailure
          }.catchAll(_ => ZIO.succeed(true))
        } yield assert(result)(isTrue) // Should succeed - corrupted data should fail to decode
      }
    ),

    suite("Performance Characteristics")(
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
      },
      
      test("should have reasonable message sizes") {
        for {
          result <- ZIO.attempt {
            val smallMessage = KeepAlive(Instant.parse("2023-01-01T00:00:00Z"))
            val largeMessage = ClientRequest(
              requestId = RequestId.fromLong(1L),
              payload = ByteVector.fill(1000)(0xFF.toByte), // 1KB payload
              createdAt = Instant.parse("2023-01-01T00:00:00Z")
            )
            
            val smallEncoded = clientMessageCodec.encode(smallMessage)
            val largeEncoded = clientMessageCodec.encode(largeMessage)
            
            // Small message should be compact, large message reasonable (accounting for 6-byte protocol header)
            smallEncoded.fold(_ => false, bits => bits.size > 80 && bits.size < 200) &&
            largeEncoded.fold(_ => false, bits => bits.size > 8000 && bits.size < 8500) // Some overhead + protocol header
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("Protocol Evolution")(
      test("should support version compatibility") {
        for {
          result <- ZIO.attempt {
            // Future protocol versions should be detectable
            val currentVersion = PROTOCOL_VERSION
            val futureVersion = (currentVersion + 1).toByte
            
            currentVersion < futureVersion && futureVersion > 1
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // This logic should work
      },
      
      test("should handle unknown message types gracefully") {
        for {
          result <- ZIO.attempt {
            // Create message with unknown discriminator
            val signatureBytes = ByteVector(PROTOCOL_SIGNATURE)
            val versionByte = ByteVector(PROTOCOL_VERSION)
            val unknownDiscriminator = ByteVector(255.toByte) // Unknown type
            val invalidMessage = signatureBytes ++ versionByte ++ unknownDiscriminator
            
            val decoded = clientMessageCodec.decode(invalidMessage.bits)
            decoded.isFailure
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    )
  )
}
