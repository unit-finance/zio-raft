package zio.raft

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Newtype

/**
 * Test for TypeclassMap - demonstrates deriving typeclass instances for HMap schemas.
 */
object TypeclassMapSpec extends ZIOSpecDefault:
  
  // Define a custom typeclass for testing
  trait Validator[A]:
    def validate(a: A): Boolean
    def errorMessage: String
  
  object Validator:
    def apply[A](predicate: A => Boolean, msg: String): Validator[A] = new Validator[A]:
      def validate(a: A): Boolean = predicate(a)
      def errorMessage: String = msg
  
  // Test newtypes
  object UserId extends Newtype[String]
  type UserId = UserId.Type
  given HMap.KeyLike[UserId] = HMap.KeyLike.forNewtype(UserId)
  
  object ProductId extends Newtype[String]
  type ProductId = ProductId.Type
  given HMap.KeyLike[ProductId] = HMap.KeyLike.forNewtype(ProductId)
  
  // Test value types
  case class UserData(name: String, age: Int)
  case class ProductData(name: String, price: Double)
  
  // Define validators for our types
  given Validator[UserData] = Validator(
    user => user.age >= 0 && user.name.nonEmpty,
    "User must have non-empty name and non-negative age"
  )
  
  given Validator[ProductData] = Validator(
    product => product.price > 0,
    "Product price must be positive"
  )
  
  // Schema
  type TestSchema = 
    ("users", UserId, UserData) *:
    ("products", ProductId, ProductData) *:
    EmptyTuple
  
  def spec = suite("TypeclassMap")(
    
    test("derive typeclass instances for schema") {
      // TypeclassMap is automatically derived from schema and given Validator instances
      val validators = summon[HMap.TypeclassMap[TestSchema, Validator]]
      
      // Get validator for each prefix with correct type
      val userValidator: Validator[UserData] = validators.forPrefix["users"]
      val productValidator: Validator[ProductData] = validators.forPrefix["products"]
      
      assertTrue(
        userValidator.errorMessage.contains("User") &&
        productValidator.errorMessage.contains("Product")
      )
    },
    
    test("validators work with correct types") {
      val validators = summon[HMap.TypeclassMap[TestSchema, Validator]]
      
      val userValidator = validators.forPrefix["users"]
      val productValidator = validators.forPrefix["products"]
      
      val validUser = UserData("Alice", 25)
      val invalidUser = UserData("", -5)
      
      val validProduct = ProductData("Widget", 9.99)
      val invalidProduct = ProductData("Free", 0.0)
      
      assertTrue(
        userValidator.validate(validUser) &&
        !userValidator.validate(invalidUser) &&
        productValidator.validate(validProduct) &&
        !productValidator.validate(invalidProduct)
      )
    },
    
    test("can validate HMap entries using derived validators") {
      val validators = summon[HMap.TypeclassMap[TestSchema, Validator]]
      
      val hmap = HMap.empty[TestSchema]
        .updated["users"](UserId("u1"), UserData("Bob", 30))
        .updated["products"](ProductId("p1"), ProductData("Gadget", 19.99))
      
      // Validate user entry
      val maybeUser = hmap.get["users"](UserId("u1"))
      val userValid = maybeUser.exists(validators.forPrefix["users"].validate)
      
      // Validate product entry
      val maybeProduct = hmap.get["products"](ProductId("p1"))
      val productValid = maybeProduct.exists(validators.forPrefix["products"].validate)
      
      assertTrue(userValid && productValid)
    },
    
    test("TypeclassMap works with different typeclasses - Ordering example") {
      // Define custom ordering for our types
      given Ordering[UserData] = Ordering.by(_.age)
      given Ordering[ProductData] = Ordering.by(_.price)
      
      val orderings = summon[HMap.TypeclassMap[TestSchema, Ordering]]
      
      val userOrdering = orderings.forPrefix["users"]
      val productOrdering = orderings.forPrefix["products"]
      
      val user1 = UserData("Alice", 25)
      val user2 = UserData("Bob", 30)
      
      val product1 = ProductData("Cheap", 5.0)
      val product2 = ProductData("Expensive", 100.0)
      
      assertTrue(
        userOrdering.lt(user1, user2) &&
        productOrdering.lt(product1, product2)
      )
    }
  )
