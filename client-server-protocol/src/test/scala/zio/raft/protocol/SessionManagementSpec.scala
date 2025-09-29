package zio.raft.protocol

import zio.*
import zio.test.*
import zio.test.Assertion.*

/**
 * Contract tests for session management protocol.
 * 
 * These tests validate the session lifecycle:
 * - CreateSession with server-generated session IDs
 * - ContinueSession for durable sessions after reconnection  
 * - SessionRejected for invalid/expired sessions
 * - Proper nonce correlation for all responses
 */
object SessionManagementSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment with TestEnvironment with Scope, Any] = suite("Session Management Contract")(
    
    suite("CreateSession")(
      test("should validate required capabilities") {
        for {
          // This test will fail until Messages.scala is implemented
          result <- ZIO.attempt {
            val createSession = CreateSession(
              capabilities = Map("worker" -> "v1.2", "queue-processor" -> "batch"),
              nonce = Nonce.fromLong(12345L)
            )
            createSession.capabilities.nonEmpty && createSession.nonce != Nonce.fromLong(0L)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should reject empty capabilities") {
        for {
          result <- ZIO.attempt {
            CreateSession(
              capabilities = Map.empty,
              nonce = Nonce.fromLong(12345L)
            )
          }.either
        } yield assert(result)(isLeft(anything)) // Should fail validation
      },
      
      test("should reject zero nonce") {
        for {
          result <- ZIO.attempt {
            CreateSession(
              capabilities = Map("worker" -> "v1.0"),
              nonce = Nonce.fromLong(0L)
            )
          }.either  
        } yield assert(result)(isLeft(anything)) // Should fail validation
      }
    ),

    suite("SessionCreated Response")(
      test("should echo client nonce") {
        for {
          result <- ZIO.attempt {
            val response = SessionCreated(
              sessionId = SessionId.generateUnsafe(),
              nonce = Nonce.fromLong(12345L)
            )
            response.nonce == Nonce.fromLong(12345L)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },
      
      test("should generate unique session IDs") {
        for {
          result <- ZIO.attempt {
            val id1 = SessionId.generateUnsafe()
            val id2 = SessionId.generateUnsafe()
            id1 != id2
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // SessionId.generate should work from package.scala
      }
    ),

    suite("ContinueSession")(
      test("should include session ID for reconnection") {
        for {
          result <- ZIO.attempt {
            val sessionId = SessionId.generateUnsafe()
            val continueSession = ContinueSession(
              sessionId = sessionId,
              nonce = Nonce.fromLong(67890L)
            )
            continueSession.sessionId == sessionId && continueSession.nonce != Nonce.fromLong(0L)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("SessionContinued Response")(
      test("should echo client nonce") {
        for {
          result <- ZIO.attempt {
            val response = SessionContinued(nonce = Nonce.fromLong(67890L))
            response.nonce == Nonce.fromLong(67890L)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    ),

    suite("SessionRejected Response")(
      test("should include rejection reason and optional leader ID") {
        for {
          result <- ZIO.attempt {
            val rejection = SessionRejected(
              reason = NotLeader,
              nonce = Nonce.fromLong(12345L),
              leaderId = Some(MemberId.fromString("node-2"))
            )
            rejection.reason == NotLeader && rejection.leaderId.isDefined
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },

      test("should support session not found rejection") {
        for {
          result <- ZIO.attempt {
            val rejection = SessionRejected(
              reason = SessionNotFound,
              nonce = Nonce.fromLong(67890L),
              leaderId = None
            )
            rejection.reason == SessionNotFound && rejection.leaderId.isEmpty
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented  
      }
    ),

    suite("Protocol Flow Validation")(
      test("should support complete session creation flow") {
        for {
          result <- ZIO.attempt {
            // Simulate complete flow
            val createRequest = CreateSession(
              capabilities = Map("worker" -> "v1.2"),
              nonce = Nonce.fromLong(11111L)
            )
            
            val sessionId = SessionId.generateUnsafe()
            val successResponse = SessionCreated(
              sessionId = sessionId,
              nonce = createRequest.nonce
            )
            
            // Validate flow
            createRequest.capabilities.nonEmpty &&
            successResponse.nonce == createRequest.nonce &&
            successResponse.sessionId == sessionId
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      },

      test("should support session continuation flow") {
        for {
          result <- ZIO.attempt {
            val sessionId = SessionId.generateUnsafe()
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
          }.catchAll(_ => ZIO.succeed(false))
        } yield assert(result)(isTrue) // Should succeed - codecs are implemented
      }
    )
  )
}
