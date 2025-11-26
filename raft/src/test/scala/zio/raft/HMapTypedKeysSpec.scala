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
    },
    test("filter keeps only entries matching predicate") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u1"), UserData("Alice", "alice@example.com"))
        .updated["users"](UserId("u2"), UserData("Bob", "bob@example.com"))
        .updated["users"](UserId("u3"), UserData("Charlie", "charlie@example.com"))

      // Filter to keep only users with names starting with 'A' or 'B'
      val filtered = hmap.filter["users"] { (_, userData) =>
        userData.name.startsWith("A") || userData.name.startsWith("B")
      }

      val alice = filtered.get["users"](UserId("u1"))
      val bob = filtered.get["users"](UserId("u2"))
      val charlie = filtered.get["users"](UserId("u3"))

      assertTrue(
        alice.isDefined && alice.get.name == "Alice",
        bob.isDefined && bob.get.name == "Bob",
        charlie.isEmpty
      )
    },
    test("filter returns empty HMap when no entries match") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u1"), UserData("Alice", "alice@example.com"))
        .updated["users"](UserId("u2"), UserData("Bob", "bob@example.com"))

      // Filter with predicate that matches nothing
      val filtered = hmap.filter["users"] { (_, userData) =>
        userData.name == "Zoe"
      }

      val users = filtered.iterator["users"].toList

      assertTrue(users.isEmpty)
    },
    test("filter returns all entries when all match") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u1"), UserData("Alice", "alice@example.com"))
        .updated["users"](UserId("u2"), UserData("Bob", "bob@example.com"))
        .updated["users"](UserId("u3"), UserData("Charlie", "charlie@example.com"))

      // Filter with predicate that matches everything
      val filtered = hmap.filter["users"] { (_, _) => true }

      val users = filtered.iterator["users"].toList

      assertTrue(users.size == 3)
    },
    test("filter preserves entries in other prefixes") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u1"), UserData("Alice", "alice@example.com"))
        .updated["users"](UserId("u2"), UserData("Bob", "bob@example.com"))
        .updated["orders"](OrderId("o1"), OrderData(5, 50.0))
        .updated["orders"](OrderId("o2"), OrderData(10, 100.0))

      // Filter users but keep only Alice
      val filtered = hmap.filter["users"] { (_, userData) =>
        userData.name == "Alice"
      }

      val alice = filtered.get["users"](UserId("u1"))
      val bob = filtered.get["users"](UserId("u2"))
      val order1 = filtered.get["orders"](OrderId("o1"))
      val order2 = filtered.get["orders"](OrderId("o2"))

      assertTrue(
        alice.isDefined && alice.get.name == "Alice",
        bob.isEmpty,
        order1.isDefined && order1.get.itemCount == 5,
        order2.isDefined && order2.get.itemCount == 10
      )
    },
    test("filter can use key in predicate") {
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("alice"), UserData("Alice Smith", "alice@example.com"))
        .updated["users"](UserId("bob"), UserData("Bob Jones", "bob@example.com"))
        .updated["users"](UserId("charlie"), UserData("Charlie Brown", "charlie@example.com"))

      // Filter based on key starting with 'a' or 'b'
      val filtered = hmap.filter["users"] { (key, _) =>
        val id = UserId.unwrap(key)
        id.startsWith("a") || id.startsWith("b")
      }

      val alice = filtered.get["users"](UserId("alice"))
      val bob = filtered.get["users"](UserId("bob"))
      val charlie = filtered.get["users"](UserId("charlie"))

      assertTrue(
        alice.isDefined,
        bob.isDefined,
        charlie.isEmpty
      )
    }
  )
end HMapTypedKeysSpec
