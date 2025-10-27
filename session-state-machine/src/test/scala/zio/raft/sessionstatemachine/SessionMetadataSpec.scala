package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import java.time.Instant

/** Contract test for SessionMetadata.
  *
  * Tests the SessionMetadata case class structure after removing redundant sessionId field. sessionId is now stored
  * only in the HMap key, not duplicated in the value.
  */
object SessionMetadataSpec extends ZIOSpecDefault:

  def spec = suite("SessionMetadata")(
    test("create SessionMetadata without sessionId field") {
      val now = Instant.now()
      val metadata = SessionMetadata(
        capabilities = Map("version" -> "1.0", "features" -> "xyz"),
        createdAt = now
      )

      assertTrue(
        metadata.capabilities == Map("version" -> "1.0", "features" -> "xyz") &&
          metadata.createdAt == now
      )
    },
    test("SessionMetadata is immutable") {
      val metadata = SessionMetadata(
        capabilities = Map("key" -> "value"),
        createdAt = Instant.now()
      )

      // copy should create new instance
      val updated = metadata.copy(capabilities = Map("newKey" -> "newValue"))

      assertTrue(
        metadata.capabilities == Map("key" -> "value") &&
          updated.capabilities == Map("newKey" -> "newValue")
      )
    },
    test("SessionMetadata with empty capabilities") {
      val metadata = SessionMetadata(
        capabilities = Map.empty,
        createdAt = Instant.now()
      )

      assertTrue(metadata.capabilities.isEmpty)
    }
  )
