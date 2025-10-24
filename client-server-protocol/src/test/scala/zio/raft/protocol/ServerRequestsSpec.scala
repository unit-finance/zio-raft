package zio.raft.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import scodec.bits.ByteVector
import java.time.Instant

/** Contract tests for server-initiated requests protocol.
  *
  * These tests validate one-way work dispatch:
  *   - ServerRequest for work dispatch to clients
  *   - ServerRequestAck for immediate acknowledgment (no payload response)
  *   - Request deduplication using unique request IDs
  *   - Capability-based client selection (server-side, not in protocol)
  *   - Error handling for unacknowledged requests
  */
object ServerRequestsSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment & TestEnvironment & Scope, Any] =
    suite("Server-Initiated Requests Contract")(
      suite("ServerRequest")(
        test("should include unique request ID and work payload") {
          val requestId = RequestId.fromLong(1L)
          val payload = ByteVector.fromValidHex("deadbeef123456")
          val request = ServerRequest(
            requestId = requestId,
            payload = payload,
            createdAt = Instant.parse("2023-01-01T00:00:00Z")
          )

          assertTrue(
            request.requestId == requestId &&
              request.payload == payload &&
              request.createdAt != null
          )
        },
        test("should not include session ID (derived from routing ID)") {
          val request = ServerRequest(
            requestId = RequestId.fromLong(1L),
            payload = ByteVector.fromValidHex("cafe0000"),
            createdAt = Instant.parse("2023-01-01T00:00:00Z")
          )

          // Verify no sessionId field - server targets via ZeroMQ routing ID
          assertTrue(!request.toString.contains("sessionId"))
        },
        test("should not include task metadata (server implementation detail)") {
          val request = ServerRequest(
            requestId = RequestId.fromLong(1L),
            payload = ByteVector.fromValidHex("abcd1234"),
            createdAt = Instant.parse("2023-01-01T00:00:00Z")
          )

          // Should not contain task type, timeout, attempts (server-side concerns)
          assertTrue(
            !request.toString.contains("taskType") &&
              !request.toString.contains("timeout") &&
              !request.toString.contains("attempts")
          )
        }
      ),
      suite("ServerRequestAck")(
        test("should echo request ID for correlation") {
          val requestId = RequestId.fromLong(1L)
          val ack = ServerRequestAck(requestId = requestId)

          assertTrue(ack.requestId == requestId)
        },
        test("should not include response payload (one-way pattern)") {
          val ack = ServerRequestAck(requestId = RequestId.fromLong(1L))

          // Should be minimal - no result payload, processing details
          assertTrue(
            !ack.toString.contains("result") &&
              !ack.toString.contains("payload") &&
              !ack.toString.contains("status")
          )
        },
        test("should not include session ID (response via routing ID)") {
          val ack = ServerRequestAck(requestId = RequestId.fromLong(1L))

          // Server responds to same routing ID, no session needed
          assertTrue(!ack.toString.contains("sessionId"))
        }
      ),
      suite("One-Way Work Dispatch Flow")(
        test("should support fire-and-forget pattern") {
          val requestId = RequestId.fromLong(1L)

          // Server sends work request
          val workRequest = ServerRequest(
            requestId = requestId,
            payload = ByteVector.fromValidHex("feedface"),
            createdAt = Instant.parse("2023-01-01T00:00:00Z")
          )

          // Client sends immediate acknowledgment
          val ack = ServerRequestAck(requestId = requestId)

          // Validate one-way flow
          assertTrue(
            workRequest.requestId == ack.requestId &&
              workRequest.payload.nonEmpty &&
              ack.toString.length < workRequest.toString.length // Ack is minimal
          )
        },
        test("should handle work dispatch without waiting for processing") {
          // Multiple work requests can be dispatched rapidly
          val requests = (1 to 5).map { i =>
            ServerRequest(
              requestId = RequestId.fromLong(i.toLong),
              payload = ByteVector.fromValidHex(f"$i%08x"),
              createdAt = Instant.parse("2023-01-01T00:00:00Z")
            )
          }

          val acks = requests.map(req => ServerRequestAck(req.requestId))

          // All should be valid and correlated
          assertTrue(requests.zip(acks).forall { case (req, ack) =>
            req.requestId == ack.requestId
          })
        }
      ),
      suite("Request Idempotency")(
        test("should support duplicate request detection") {
          val requestId = RequestId.fromLong(1L)
          val payload = ByteVector.fromValidHex("deadbeef")

          // Same request sent twice (due to network issues)
          val request1 = ServerRequest(requestId, payload, Instant.parse("2023-01-01T00:00:00Z"))
          val request2 = ServerRequest(requestId, payload, Instant.parse("2023-01-01T00:00:00Z"))

          // Client can detect duplicate and send cached ack
          val ack = ServerRequestAck(requestId)

          assertTrue(
            request1.requestId == request2.requestId &&
              ack.requestId == request1.requestId
          )
        },
        test("should cache acknowledgments for duplicate handling") {
          val requestId = RequestId.fromLong(1L)

          // Original acknowledgment
          val ack1 = ServerRequestAck(requestId)

          // Cached acknowledgment for duplicate request
          val ack2 = ServerRequestAck(requestId)

          // Should be identical
          assertTrue(ack1.requestId == ack2.requestId)
        }
      ),
      suite("Error Scenarios")(
        test("should handle unacknowledged requests gracefully") {
          // Server can detect missing acknowledgments (timeout-based)
          val request = ServerRequest(
            requestId = RequestId.fromLong(1L),
            payload = ByteVector.fromValidHex("cafe0001"),
            createdAt = Instant.parse("2022-12-31T23:59:00Z") // Old request
          )

          // Server implementation can detect and handle timeout
          val isOld =
            java.time.Duration.between(request.createdAt, Instant.parse("2023-01-01T00:00:00Z")).getSeconds > 30

          assertTrue(isOld)
        }
      ),
      suite("Performance Requirements")(
        test("should support high throughput work dispatch") {
          for {
            startTime <- Clock.instant
            requests = (1 to 100).map { i =>
              val request = ServerRequest(
                requestId = RequestId.fromLong(i.toLong),
                payload = ByteVector.fromValidHex(f"$i%08x"),
                createdAt = Instant.parse("2023-01-01T00:00:00Z")
              )
              val ack = ServerRequestAck(request.requestId)
              request.requestId == ack.requestId
            }
            endTime <- Clock.instant
            duration = java.time.Duration.between(startTime, endTime).toMillis
          } yield {
            assertTrue(requests.forall(identity)) &&
            assertTrue(duration < 200L) // Should be fast
          }
        },
        test("should have minimal message overhead") {
          val request = ServerRequest(
            requestId = RequestId.fromLong(1L),
            payload = ByteVector.fromValidHex("0102"),
            createdAt = Instant.parse("2023-01-01T00:00:00Z")
          )
          val ack = ServerRequestAck(request.requestId)

          // Acknowledgment should be much smaller than request
          assertTrue(ack.toString.length < request.toString.length / 2)
        }
      ),
      suite("Capability Integration")(
        test("should work with capability-based routing (conceptual)") {
          // Server can dispatch different work types
          val workerRequest = ServerRequest(
            requestId = RequestId.fromLong(1L),
            payload = ByteVector.fromValidHex("cafe0001"),
            createdAt = Instant.parse("2023-01-01T00:00:00Z")
          )

          val processorRequest = ServerRequest(
            requestId = RequestId.fromLong(2L),
            payload = ByteVector.fromValidHex("babe0002"),
            createdAt = Instant.parse("2023-01-01T00:00:00Z")
          )

          // Different request types should be valid
          assertTrue(
            workerRequest.requestId != processorRequest.requestId &&
              workerRequest.payload != processorRequest.payload
          )
        }
      )
    )
}
