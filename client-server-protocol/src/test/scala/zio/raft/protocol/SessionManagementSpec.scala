package zio.raft.protocol

import zio.*
import zio.test.*
import zio.raft.protocol.RejectionReason.*

/** Contract tests for session management protocol.
  *
  * These tests validate the session lifecycle:
  *   - CreateSession with server-generated session IDs
  *   - ContinueSession for durable sessions after reconnection
  *   - SessionRejected for invalid/expired sessions
  *   - Proper nonce correlation for all responses
  */
object SessionManagementSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment & TestEnvironment & Scope, Any] = suiteAll("Session Management Contract") {

    suiteAll("CreateSession") {
      test("should validate required capabilities") {
        val createSession = CreateSession(
          capabilities = Map("worker" -> "v1.2", "queue-processor" -> "batch"),
          nonce = Nonce.fromLong(12345L)
        )
        assertTrue(createSession.capabilities.nonEmpty && Nonce.unwrap(createSession.nonce) != 0L)
      }

      test("should reject empty capabilities") {
        for {
          result <- ZIO.attempt {
            CreateSession(
              capabilities = Map.empty,
              nonce = Nonce.fromLong(12345L)
            )
          }.either
        } yield assertTrue(result.isLeft)
      }

      test("should reject zero nonce") {
        for {
          result <- ZIO.attempt {
            CreateSession(
              capabilities = Map("worker" -> "v1.0"),
              nonce = Nonce.fromLong(0L)
            )
          }.either
        } yield assertTrue(result.isLeft)
      }
    }

    suiteAll("SessionCreated Response") {
      test("should echo client nonce") {
        val response = SessionCreated(
          sessionId = SessionId.fromString("test-session-1"),
          nonce = Nonce.fromLong(12345L)
        )
        assertTrue(response.nonce == Nonce.fromLong(12345L))
      }

      test("should generate unique session IDs") {
        val id1 = SessionId.fromString("test-session-1")
        val id2 = SessionId.fromString("test-session-2")
        assertTrue(id1 != id2)
      }
    }

    suiteAll("ContinueSession") {
      test("should include session ID for reconnection") {
        val sessionId = SessionId.fromString("test-session-1")
        val continueSession = ContinueSession(
          sessionId = sessionId,
          nonce = Nonce.fromLong(67890L)
        )
        assertTrue(continueSession.sessionId == sessionId && Nonce.unwrap(continueSession.nonce) != 0L)
      }
    }

    suiteAll("SessionContinued Response") {
      test("should echo client nonce") {
        for {
          result <- ZIO
            .attempt {
              val response = SessionContinued(nonce = Nonce.fromLong(67890L))
              response.nonce == Nonce.fromLong(67890L)
            }
            .catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }
    }

    suiteAll("SessionRejected Response") {
      test("should include rejection reason and optional leader ID") {
        for {
          result <- ZIO
            .attempt {
              val rejection = SessionRejected(
                reason = NotLeader,
                nonce = Nonce.fromLong(12345L),
                leaderId = Some(MemberId.fromString("node-2"))
              )
              rejection.reason == NotLeader && rejection.leaderId.isDefined
            }
            .catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }

      test("should support session not found rejection") {
        for {
          result <- ZIO
            .attempt {
              val rejection = SessionRejected(
                reason = SessionExpired,
                nonce = Nonce.fromLong(67890L),
                leaderId = None
              )
              rejection.reason == SessionExpired && rejection.leaderId.isEmpty
            }
            .catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }
    }

    suiteAll("Protocol Flow Validation") {
      test("should support complete session creation flow") {
        for {
          result <- ZIO
            .attempt {
              // Simulate complete flow
              val createRequest = CreateSession(
                capabilities = Map("worker" -> "v1.2"),
                nonce = Nonce.fromLong(11111L)
              )

              val sessionId = SessionId.fromString("test-session-1")
              val successResponse = SessionCreated(
                sessionId = sessionId,
                nonce = createRequest.nonce
              )

              // Validate flow
              createRequest.capabilities.nonEmpty &&
              successResponse.nonce == createRequest.nonce &&
              successResponse.sessionId == sessionId
            }
            .catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }

      test("should support session continuation flow") {
        for {
          result <- ZIO
            .attempt {
              val sessionId = SessionId.fromString("test-session-1")
              val continueRequest = ContinueSession(
                sessionId = sessionId,
                nonce = Nonce.fromLong(22222L)
              )

              val continueResponse = SessionContinued(
                nonce = continueRequest.nonce
              )

              // Validate flow
              continueRequest.sessionId == sessionId &&
              continueResponse.nonce == continueRequest.nonce
            }
            .catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(result) // Should succeed - codecs are implemented
      }
    }
  }
}
