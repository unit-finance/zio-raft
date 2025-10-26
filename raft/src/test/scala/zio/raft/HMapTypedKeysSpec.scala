package zio.raft

import zio.prelude.Newtype
import zio.test.*

object HMapTypedKeysSpec extends ZIOSpecDefault:

  // Define test newtypes
  object UserId extends Newtype[String]
  type UserId = UserId.Type

  object OrderId extends Newtype[String]
  type OrderId = OrderId.Type

  // Provide KeyLike instances for our newtypes using the helper
  given HMap.KeyLike[UserId] = HMap.KeyLike.forNewtype(UserId)
  given HMap.KeyLike[OrderId] = HMap.KeyLike.forNewtype(OrderId)

  // Test data types
  case class UserData(name: String, email: String)
  case class OrderData(itemCount: Int, total: Double)

  // Define a schema with typed keys
  type TestSchema =
    ("users", UserId, UserData) *:
      ("orders", OrderId, OrderData) *:
      EmptyTuple

  def spec = suite("HMap with Typed Keys")(
    test("create empty HMap with typed schema") {
      val hmap = HMap.empty[TestSchema]
      assertTrue(true)
    },
    test("store and retrieve values with typed keys") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u123"), UserData("Alice", "alice@example.com"))
        .updated["orders"](OrderId("o456"), OrderData(3, 99.99))

      val user = hmap.get["users"](UserId("u123"))
      val order = hmap.get["orders"](OrderId("o456"))

      assertTrue(
        user == Some(UserData("Alice", "alice@example.com")),
        order == Some(OrderData(3, 99.99))
      )
    },
    test("return None for non-existent keys") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u123"), UserData("Alice", "alice@example.com"))

      val missing = hmap.get["users"](UserId("u999"))

      assertTrue(missing.isEmpty)
    },
    test("remove entries with typed keys") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u123"), UserData("Alice", "alice@example.com"))
        .updated["users"](UserId("u456"), UserData("Bob", "bob@example.com"))
        .removed["users"](UserId("u123"))

      val removed = hmap.get["users"](UserId("u123"))
      val remaining = hmap.get["users"](UserId("u456"))

      assertTrue(
        removed.isEmpty,
        remaining == Some(UserData("Bob", "bob@example.com"))
      )
    },
    test("iterate over prefix with typed keys") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u1"), UserData("Alice", "alice@example.com"))
        .updated["users"](UserId("u2"), UserData("Bob", "bob@example.com"))
        .updated["orders"](OrderId("o1"), OrderData(1, 10.0))

      val users = hmap.iterator["users"].toList

      assertTrue(
        users.size == 2,
        users.exists((k, v) => k == UserId("u1") && v.name == "Alice"),
        users.exists((k, v) => k == UserId("u2") && v.name == "Bob")
      )
    },
    test("different prefixes with same key values don't interfere") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u1"), UserData("Alice", "alice@example.com"))
        .updated["orders"](OrderId("u1"), OrderData(5, 50.0)) // Same string "u1" but different typed key

      val user = hmap.get["users"](UserId("u1"))
      val order = hmap.get["orders"](OrderId("u1"))

      assertTrue(
        user == Some(UserData("Alice", "alice@example.com")),
        order == Some(OrderData(5, 50.0))
      )
    },
    test("range queries return entries within key range") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("user001"), UserData("Alice", "alice@example.com"))
        .updated["users"](UserId("user005"), UserData("Bob", "bob@example.com"))
        .updated["users"](UserId("user010"), UserData("Charlie", "charlie@example.com"))
        .updated["users"](UserId("user015"), UserData("Diana", "diana@example.com"))

      // Get users from "user003" (inclusive) to "user012" (exclusive)
      val rangeResults = hmap.range["users"](UserId("user003"), UserId("user012")).toList

      assertTrue(
        rangeResults.length == 2,
        rangeResults.exists((k, v) => k == UserId("user005") && v.name == "Bob"),
        rangeResults.exists((k, v) => k == UserId("user010") && v.name == "Charlie")
      )
    },
    test("removedAll removes multiple entries in one operation") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u1"), UserData("Alice", "alice@example.com"))
        .updated["users"](UserId("u2"), UserData("Bob", "bob@example.com"))
        .updated["users"](UserId("u3"), UserData("Charlie", "charlie@example.com"))
        .updated["users"](UserId("u4"), UserData("David", "david@example.com"))

      // Remove multiple users at once
      val cleaned = hmap.removedAll["users"](List(UserId("u1"), UserId("u3")))

      assertTrue(
        cleaned.get["users"](UserId("u1")).isEmpty &&
          cleaned.get["users"](UserId("u2")).isDefined &&
          cleaned.get["users"](UserId("u3")).isEmpty &&
          cleaned.get["users"](UserId("u4")).isDefined
      )
    },
    test("exists checks if any entry satisfies predicate with short-circuit") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u1"), UserData("Alice", "alice@example.com"))
        .updated["users"](UserId("u2"), UserData("Bob", "bob@example.com"))
        .updated["users"](UserId("u3"), UserData("Charlie", "charlie@example.com"))

      // Check if any user has name "Bob"
      val hasBob = hmap.exists["users"] { (_, userData) =>
        userData.name == "Bob"
      }

      // Check if any user has name "Zoe" (doesn't exist)
      val hasZoe = hmap.exists["users"] { (_, userData) =>
        userData.name == "Zoe"
      }

      assertTrue(hasBob && !hasZoe)
    }
  )
end HMapTypedKeysSpec
