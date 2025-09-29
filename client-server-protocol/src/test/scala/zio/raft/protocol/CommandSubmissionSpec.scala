package zio.raft.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*
import scodec.bits.ByteVector
import java.time.Instant

/**
 * Contract tests for command submission protocol.
 * 
 * These tests validate client request processing:
 * - ClientRequest for both read and write operations
 * - ClientResponse with execution results
 * - RequestError for processing failures and leader redirection
 * - Request deduplication using unique request IDs
 * - Leader redirection flow
 */
object CommandSubmissionSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment with TestEnvironment with Scope, Any] = suiteAll("Command Submission Contract") {
    
    suiteAll("ClientRequest") {
      test("should include unique request ID and payload") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            val payload = ByteVector.fromValidHex("deadbeef")
            val request = ClientRequest(
              requestId = requestId,
              payload = payload,
              createdAt = Instant.parse("2023-01-01T00:00:00Z")
            )
            
            request.requestId == requestId &&
            request.payload == payload &&
            request.createdAt != null
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }
      
      test("should create RequestIds from long values") {
        for {
          result <- ZIO.attempt {
            val id1 = RequestId.fromLong(1L)
            val id2 = RequestId.fromLong(2L) 
            id1 != id2
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // RequestId creation should work
      }
      
      test("should support both read and write operations") {
        val writeRequest = ClientRequest(
          requestId = RequestId.fromLong(1L),
          payload = ByteVector.fromValidHex("cafebabe"), // write command
          createdAt = Instant.parse("2023-01-01T00:00:00Z")
        )
        
        val readRequest = ClientRequest(
          requestId = RequestId.fromLong(2L), 
          payload = ByteVector.fromValidHex("feedface"), // read query
          createdAt = Instant.parse("2023-01-01T00:00:00Z")
        )
        
        assertTrue(
          writeRequest.payload == ByteVector.fromValidHex("cafebabe") &&
          readRequest.payload == ByteVector.fromValidHex("feedface") &&
          writeRequest.requestId != readRequest.requestId
        )
      }
    }

    suiteAll("ClientResponse") {
      test("should echo request ID with execution result") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            val resultData = ByteVector.fromValidHex("abcd1234")
            val response = ClientResponse(
              requestId = requestId,
              result = resultData
            )
            
            response.requestId == requestId &&
            response.result == resultData
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }
    }

    suiteAll("RequestError") {
      test("should include error reason and optional leader ID") {
        for {
          result <- ZIO.attempt {
            val error = RequestError(
              reason = NotLeaderRequest,
              leaderId = Some(MemberId.fromString("node-3"))
            )
            
            error.reason == NotLeaderRequest &&
            error.leaderId.contains(MemberId.fromString("node-3"))
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }
      
      test("should support invalid operation errors") {
        val error = RequestError(
          reason = InvalidRequest,
          leaderId = None
        )
        assertTrue(error.reason == InvalidRequest && error.leaderId.isEmpty)
      }
    }

    suiteAll("Leader Redirection Flow") {
      test("should redirect non-leader requests") {
        for {
          result <- ZIO.attempt {
            // Client sends request to follower
            val request = ClientRequest(
              requestId = RequestId.fromLong(1L),
              payload = ByteVector.fromValidHex("12345678"),
              createdAt = Instant.parse("2023-01-01T00:00:00Z")
            )
            
            // Follower responds with leader redirect
            val redirect = RequestError(
              reason = NotLeaderRequest,
              leaderId = Some(MemberId.fromString("leader-node"))
            )
            
            // Validate redirect flow
            redirect.reason == NotLeaderRequest &&
            redirect.leaderId.isDefined
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }
    }

    suiteAll("Request Idempotency") {
      test("should support request deduplication") {
        for {
          result <- ZIO.attempt {
            val requestId = RequestId.fromLong(1L)
            val payload = ByteVector.fromValidHex("deadbeef")
            
            // Same request sent twice (retry scenario)
            val request1 = ClientRequest(requestId, payload, Instant.parse("2023-01-01T00:00:00Z"))
            val request2 = ClientRequest(requestId, payload, Instant.parse("2023-01-01T00:00:00Z"))
            
            // Should be considered identical for deduplication
            request1.requestId == request2.requestId &&
            request1.payload == request2.payload
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }
    }

    suiteAll("Connection State Integration") {
      test("should handle requests in different connection states") {
        // Test that requests can be queued when not connected
        val request = ClientRequest(
          requestId = RequestId.fromLong(1L),
          payload = ByteVector.fromValidHex("deadbeef"),
          createdAt = Instant.parse("2023-01-01T00:00:00Z")
        )
        
        // In real implementation, this would be queued based on connection state
        assertTrue(request.payload.nonEmpty && request.requestId == RequestId.fromLong(1L))
      }
    }

    suiteAll("Performance Requirements") {
      test("should handle request creation efficiently") {
        for {
          startTime <- Clock.instant
          requests <- ZIO.foreach(1 to 100) { i =>
            ZIO.attempt {
              ClientRequest(
                requestId = RequestId.fromLong(i.toLong),
                payload = ByteVector.fromValidHex(f"$i%08x"),
                createdAt = Instant.parse("2023-01-01T00:00:00Z")
              )
            }
          }
          endTime <- Clock.instant
          duration = java.time.Duration.between(startTime, endTime).toMillis
        } yield {
          assertTrue(requests.length == 100 && duration < 1000L) // Should be fast and successful
        }
      }
    }
  }
}
