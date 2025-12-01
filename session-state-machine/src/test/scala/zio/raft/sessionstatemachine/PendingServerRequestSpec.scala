package zio.raft.sessionstatemachine

import zio.test.*
import java.time.Instant

/** Contract test for PendingServerRequest.
  *
  * Tests the PendingServerRequest case class after removing redundant id and sessionId fields. Both id and sessionId
  * are now stored only in the HMap composite key, not duplicated in the value.
  */
object PendingServerRequestSpec extends ZIOSpecDefault:

  case class TestPayload(data: String, value: Int)

  def spec = suite("PendingServerRequest")(
    test("create PendingServerRequest with only payload and lastSentAt") {
      val now = Instant.now()
      val payload = TestPayload("test-data", 42)

      val pending = PendingServerRequest(
        payload = payload,
        lastSentAt = now
      )

      assertTrue(
        pending.payload == payload &&
          pending.lastSentAt == now
      )
    },
    test("lastSentAt is not optional - always has a value") {
      val payload = TestPayload("data", 123)
      val pending = PendingServerRequest(
        payload = payload,
        lastSentAt = Instant.now()
      )

      // Verify lastSentAt is not Option[Instant], just Instant
      val timestamp: Instant = pending.lastSentAt

      assertTrue(timestamp != null)
    },
    test("PendingServerRequest is immutable") {
      val original = PendingServerRequest(
        payload = TestPayload("original", 1),
        lastSentAt = Instant.now()
      )

      val newTime = Instant.now().plusSeconds(60)
      val updated = original.copy(lastSentAt = newTime)

      assertTrue(
        original.lastSentAt != updated.lastSentAt &&
          original.payload == updated.payload
      )
    },
    test("PendingServerRequest works with different payload types") {
      val stringPending = PendingServerRequest(
        payload = "string-payload",
        lastSentAt = Instant.now()
      )

      val intPending = PendingServerRequest(
        payload = 42,
        lastSentAt = Instant.now()
      )

      assertTrue(
        stringPending.payload == "string-payload" &&
          intPending.payload == 42
      )
    }
  )
end PendingServerRequestSpec
