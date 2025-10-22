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
    
    test("schema narrowing with typed keys") {
      type FullSchema = 
        ("users", UserId, UserData) *:
        ("orders", OrderId, OrderData) *:
        EmptyTuple
      
      type PartialSchema = ("users", UserId, UserData) *: EmptyTuple
      
      val full: HMap[FullSchema] = HMap.empty[FullSchema]
        .updated["users"](UserId("u1"), UserData("Alice", "alice@example.com"))
        .updated["orders"](OrderId("o1"), OrderData(1, 10.0))
      
      val partial: HMap[PartialSchema] = full.narrowTo[PartialSchema]
      
      val user = partial.get["users"](UserId("u1"))
      
      assertTrue(user == Some(UserData("Alice", "alice@example.com")))
    }
  )

