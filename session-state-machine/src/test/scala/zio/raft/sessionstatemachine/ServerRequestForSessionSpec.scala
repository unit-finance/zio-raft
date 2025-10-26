package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.protocol.SessionId

/** Contract test for ServerRequestForSession wrapper.
  *
  * Tests that server requests must include target sessionId, enabling cross-session communication.
  */
object ServerRequestForSessionSpec extends ZIOSpecDefault:

  case class TestServerRequest(action: String, data: Int)

  def spec = suite("ServerRequestForSession")(
    test("wrap server request with target sessionId") {
      val targetSession = SessionId("session-123")
      val payload = TestServerRequest("notify", 42)

      val wrapped = ServerRequestForSession(
        sessionId = targetSession,
        payload = payload
      )

      assertTrue(
        wrapped.sessionId == targetSession &&
          wrapped.payload == payload
      )
    },
    test("different sessions can receive same payload type") {
      val payload = TestServerRequest("broadcast", 100)

      val req1 = ServerRequestForSession(SessionId("session-1"), payload)
      val req2 = ServerRequestForSession(SessionId("session-2"), payload)

      assertTrue(
        req1.sessionId != req2.sessionId &&
          req1.payload == req2.payload
      )
    },
    test("enables cross-session communication - expiring session notifies admin") {
      val expiringSession = SessionId("user-session-456")
      val adminSession = SessionId("admin-session-1")

      // When user session expires, notify admin session
      val notifyAdmin = ServerRequestForSession(
        sessionId = adminSession, // Targets different session!
        payload = TestServerRequest("userSessionExpired", 456)
      )

      assertTrue(
        notifyAdmin.sessionId == adminSession &&
          notifyAdmin.sessionId != expiringSession
      )
    }
  )
