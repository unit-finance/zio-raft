package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.protocol.SessionId
import java.time.Instant

/**
 * Contract test for SessionMetadata immutability.
 * 
 * EXPECTED: This test MUST FAIL initially (SessionMetadata doesn't exist yet)
 * 
 * Tests:
 * - SessionMetadata creation with all fields
 * - Field access
 * - Immutability (case class)
 */
object SessionMetadataSpec extends ZIOSpecDefault:
  
  def spec = suite("SessionMetadata")(
    
    test("should create SessionMetadata with all fields") {
      val capabilities = Map("version" -> "1.0", "feature" -> "enabled")
      val createdAt = Instant.parse("2025-10-22T10:00:00Z")
      
      val metadata = SessionMetadata(
        capabilities = capabilities,
        createdAt = createdAt
      )
      
      assertTrue(
        metadata.capabilities == capabilities &&
        metadata.createdAt == createdAt
      )
    },
    
    test("should be immutable (case class)") {
      val metadata1 = SessionMetadata(
        capabilities = Map.empty,
        createdAt = Instant.now()
      )
      
      // Creating a copy should not modify the original
      val metadata2 = metadata1.copy(capabilities = Map("updated" -> "true"))
      
      assertTrue(
        metadata1.capabilities.isEmpty &&
        metadata2.capabilities.nonEmpty &&
        metadata1 != metadata2
      )
    },
    
    test("should have proper equality semantics") {
      val timestamp = Instant.parse("2025-10-22T10:00:00Z")
      val metadata1 = SessionMetadata(
        capabilities = Map("key" -> "value"),
        createdAt = timestamp
      )
      
      val metadata2 = SessionMetadata(
        capabilities = Map("key" -> "value"),
        createdAt = timestamp
      )
      
      assertTrue(metadata1 == metadata2)
    }
  )
