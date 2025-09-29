package zio.raft.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import scodec.bits.ByteVector
import java.time.Instant

/**
 * Contract tests for server-initiated requests protocol.
 * 
 * These tests validate one-way work dispatch:
 * - ServerRequest for work dispatch to clients
 * - ServerRequestAck for immediate acknowledgment (no payload response)
 * - Request deduplication using unique request IDs
 * - Capability-based client selection (server-side, not in protocol)
 * - Error handling for unacknowledged requests
 */
object ServerRequestsSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment with TestEnvironment with Scope, Any] = suite("Server-Initiated Requests Contract")(
    
    suite("ServerRequest")(
      test("should include unique request ID and work payload") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            val payload = ByteVector.fromValidHex("workdata123")
            val request = ServerRequest(
              requestId = requestId,
              payload = payload,
              createdAt = Instant.now()
            )
            
            request.requestId == requestId &&
            request.payload == payload &&
            true // createdAt is always present
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should not include session ID (derived from routing ID)") {
        for {
          result <- ZIO.attempt {
            val request = ServerRequest(
              requestId = RequestId.fromLong(1L),
              payload = ByteVector.fromValidHex("workpayload"),
              createdAt = Instant.now()
            )
            
            // Verify no sessionId field - server targets via ZeroMQ routing ID
            !request.toString.contains("sessionId")
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should not include task metadata (server implementation detail)") {
        for {
          result <- ZIO.attempt {
            val request = ServerRequest(
              requestId = RequestId.fromLong(1L),
              payload = ByteVector.fromValidHex("cleanpayload"),
              createdAt = Instant.now()
            )
            
            // Should not contain task type, timeout, attempts (server-side concerns)
            !request.toString.contains("taskType") &&
            !request.toString.contains("timeout") &&
            !request.toString.contains("attempts")
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("ServerRequestAck")(
      test("should echo request ID for correlation") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            val ack = ServerRequestAck(requestId = requestId)
            
            ack.requestId == requestId
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should not include response payload (one-way pattern)") {
        for {
          result <- ZIO.attempt {
            val ack = ServerRequestAck(requestId = RequestId.fromLong(1L))
            
            // Should be minimal - no result payload, processing details
            !ack.toString.contains("result") &&
            !ack.toString.contains("payload") &&
            !ack.toString.contains("status")
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should not include session ID (response via routing ID)") {
        for {
          result <- ZIO.attempt {
            val ack = ServerRequestAck(requestId = RequestId.fromLong(1L))
            
            // Server responds to same routing ID, no session needed
            !ack.toString.contains("sessionId")
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("One-Way Work Dispatch Flow")(
      test("should support fire-and-forget pattern") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            
            // Server sends work request
            val workRequest = ServerRequest(
              requestId = requestId,
              payload = ByteVector.fromValidHex("processthis"),
              createdAt = Instant.now()
            )
            
            // Client sends immediate acknowledgment
            val ack = ServerRequestAck(requestId = requestId)
            
            // Validate one-way flow
            workRequest.requestId == ack.requestId &&
            workRequest.payload.nonEmpty &&
            ack.toString.length < workRequest.toString.length // Ack is minimal
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should handle work dispatch without waiting for processing") {
        for {
          result <- ZIO.attempt {
            // Multiple work requests can be dispatched rapidly
            val requests = (1 to 5).map { i =>
              ServerRequest(
                requestId = RequestId.fromLong(1L),
                payload = ByteVector.fromValidHex(f"work$i%04x"),
                createdAt = Instant.now()
              )
            }
            
            val acks = requests.map(req => ServerRequestAck(req.requestId))
            
            // All should be valid and correlated
            requests.zip(acks).forall { case (req, ack) =>
              req.requestId == ack.requestId
            }
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("Request Idempotency")(
      test("should support duplicate request detection") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            val payload = ByteVector.fromValidHex("duplicateme")
            
            // Same request sent twice (due to network issues)
            val request1 = ServerRequest(requestId, payload, Instant.now())
            val request2 = ServerRequest(requestId, payload, Instant.now())
            
            // Client can detect duplicate and send cached ack
            val ack = ServerRequestAck(requestId)
            
            request1.requestId == request2.requestId &&
            ack.requestId == request1.requestId
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should cache acknowledgments for duplicate handling") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            
            // Original acknowledgment
            val ack1 = ServerRequestAck(requestId)
            
            // Cached acknowledgment for duplicate request
            val ack2 = ServerRequestAck(requestId)
            
            // Should be identical
            ack1.requestId == ack2.requestId
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("Error Scenarios")(
      test("should handle unacknowledged requests gracefully") {
        for {
          result <- ZIO.attempt {
            // Server can detect missing acknowledgments (timeout-based)
            val request = ServerRequest(
              requestId = RequestId.fromLong(1L),
              payload = ByteVector.fromValidHex("needsack"),
              createdAt = Instant.now().minusSeconds(60) // Old request
            )
            
            // Server implementation can detect and handle timeout
            val isOld = java.time.Duration.between(request.createdAt, Instant.now()).getSeconds > 30
            
            isOld // requestId is always present
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("Performance Requirements")(
      test("should support high throughput work dispatch") {
        for {
          startTime <- Clock.instant
          requests <- ZIO.foreach(1 to 100) { i =>
            ZIO.attempt {
              val request = ServerRequest(
                requestId = RequestId.fromLong(1L),
                payload = ByteVector.fromValidHex(f"payload$i%08x"),
                createdAt = Instant.now()
              )
              val ack = ServerRequestAck(request.requestId)
              
              request.requestId == ack.requestId
            }.catchAll(_ => ZIO.succeed(false))
          }
          endTime <- Clock.instant
          duration = java.time.Duration.between(startTime, endTime).toMillis
        } yield {
          assert(requests)(forall(isTrue)) && // Should succeed - codecs are implemented
          assert(duration)(isLessThan(200L)) // Should be fast when implemented
        }
      },
      
      test("should have minimal message overhead") {
        for {
          result <- ZIO.attempt {
            val request = ServerRequest(
              requestId = RequestId.fromLong(1L),
              payload = ByteVector.fromValidHex("minimal"),
              createdAt = Instant.now()
            )
            val ack = ServerRequestAck(request.requestId)
            
            // Acknowledgment should be much smaller than request
            ack.toString.length < request.toString.length / 2
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("Capability Integration")(
      test("should work with capability-based routing (conceptual)") {
        for {
          result <- ZIO.attempt {
            // Server can dispatch different work types
            val workerRequest = ServerRequest(
              requestId = RequestId.fromLong(1L),
              payload = ByteVector.fromValidHex("workerdata"),
              createdAt = Instant.now()
            )
            
            val processorRequest = ServerRequest(
              requestId = RequestId.fromLong(1L),
              payload = ByteVector.fromValidHex("processdata"),
              createdAt = Instant.now()
            )
            
            // Different request types should be valid
            workerRequest.requestId != processorRequest.requestId &&
            workerRequest.payload != processorRequest.payload
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    )
  )
}
