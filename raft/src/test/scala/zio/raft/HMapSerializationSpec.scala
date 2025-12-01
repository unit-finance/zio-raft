package zio.raft

import zio.test.*
import zio.prelude.Newtype

/** Test for HMap serialization utilities - extractPrefix and fromRaw.
  *
  * These are essential for implementing serialization/deserialization.
  */
object HMapSerializationSpec extends ZIOSpecDefault:

  object UserId extends Newtype[String]
  type UserId = UserId.Type
  given HMap.KeyLike[UserId] = HMap.KeyLike.forNewtype(UserId)

  case class UserData(name: String, age: Int)

  type TestSchema = ("users", UserId, UserData) *: EmptyTuple

  def spec = suite("HMap Serialization Utilities")(
    test("extractPrefix returns correct prefix from full key") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("alice"), UserData("Alice", 25))

      // Access internal map to get full keys
      val fullKeys = hmap.toRaw.keys

      // Extract prefix from first key
      val maybePrefix = fullKeys.headOption.flatMap(HMap.extractPrefix)

      assertTrue(maybePrefix.contains("users"))
    },
    test("extractPrefix returns None for invalid key") {
      val invalidKey = "no-separator".getBytes(java.nio.charset.StandardCharsets.UTF_8)

      val maybePrefix = HMap.extractPrefix(invalidKey)

      assertTrue(maybePrefix.isEmpty)
    },
    test("fromRaw creates HMap from TreeMap") {
      // Create an HMap normally
      val hmap1 = HMap.empty[TestSchema]
        .updated["users"](UserId("bob"), UserData("Bob", 30))
        .updated["users"](UserId("charlie"), UserData("Charlie", 35))

      // Extract raw map
      val rawMap = hmap1.toRaw

      // Recreate HMap from raw map
      val hmap2 = HMap.fromRaw[TestSchema](rawMap)

      // Should have same data
      assertTrue(
        hmap2.get["users"](UserId("bob")).exists(_.name == "Bob") &&
          hmap2.get["users"](UserId("charlie")).exists(_.name == "Charlie")
      )
    },
    test("extractPrefix works with multiple prefixes") {
      type MultiSchema =
        ("users", UserId, UserData) *:
          ("products", UserId, String) *:
          ("orders", UserId, Int) *:
          EmptyTuple

      val hmap = HMap.empty[MultiSchema]
        .updated["users"](UserId("u1"), UserData("Alice", 25))
        .updated["products"](UserId("p1"), "Widget")
        .updated["orders"](UserId("o1"), 100)

      // Get all full keys
      val rawMap = hmap.toRaw
      val prefixes = rawMap.keys.flatMap(HMap.extractPrefix).toSet

      assertTrue(
        prefixes == Set("users", "products", "orders")
      )
    },
    test("round-trip: serialize and deserialize preserves data") {
      val original = HMap.empty[TestSchema]
        .updated["users"](UserId("alice"), UserData("Alice", 25))
        .updated["users"](UserId("bob"), UserData("Bob", 30))

      // Simulate serialization: extract raw map
      val serialized = original.toRaw

      // Simulate deserialization: recreate from raw map
      val deserialized = HMap.fromRaw[TestSchema](serialized)

      // Verify data is preserved
      val alice = deserialized.get["users"](UserId("alice"))
      val bob = deserialized.get["users"](UserId("bob"))

      assertTrue(
        alice.exists(u => u.name == "Alice" && u.age == 25) &&
          bob.exists(u => u.name == "Bob" && u.age == 30)
      )
    }
  )
end HMapSerializationSpec
