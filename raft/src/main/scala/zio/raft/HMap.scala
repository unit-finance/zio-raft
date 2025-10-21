package zio.raft

import scala.compiletime.{constValue, erasedValue, error}
import scala.Conversion
import zio.raft.HMap.{SubSchema, ValueAt, Contains}

/** 
 * HMap: A type-safe heterogeneous map with prefix-based namespacing
 * 
 * ## Overview
 * HMap is a compile-time type-safe map that allows different prefixes to store
 * different value types. The schema is defined at the type level as a tuple of
 * (Prefix, ValueType) pairs, and all type checking happens at compile time.
 * 
 * ## Basic Usage
 * 
 * ```scala
 * // Define a schema: each prefix maps to a specific value type
 * type MySchema = 
 *   ("users", String) *:
 *   ("orders", Int) *:
 *   ("flags", Boolean) *:
 *   EmptyTuple
 * 
 * // Create an empty HMap with the schema
 * val hmap = HMap.empty[MySchema]
 * 
 * // Store values with type safety - each prefix enforces its value type
 * val hmap1 = hmap
 *   .updated["users"]("john", "John Doe")      // String value for "users" prefix
 *   .updated["orders"]("order123", 42)         // Int value for "orders" prefix
 *   .updated["flags"]("enabled", true)         // Boolean value for "flags" prefix
 * 
 * // Retrieve values - return type is automatically inferred from the schema
 * val name: Option[String] = hmap1.get["users"]("john")      // Option[String]
 * val order: Option[Int] = hmap1.get["orders"]("order123")   // Option[Int]
 * val flag: Option[Boolean] = hmap1.get["flags"]("enabled")  // Option[Boolean]
 * 
 * // Compile-time errors for invalid operations:
 * // hmap1.updated["users"]("alice", 123)     // ERROR: type mismatch
 * // hmap1.get["invalid"]("key")               // ERROR: prefix not in schema
 * ```
 * 
 * ## How It Works
 * 
 * Internally, keys are stored as "prefix\key" strings in a Map[String, Any].
 * The type system ensures:
 * 1. Only prefixes defined in the schema can be used
 * 2. Values must match the type associated with their prefix
 * 3. Retrieved values have the correct type without manual casting
 * 
 * ## Schema Subtyping
 * 
 * HMap supports schema narrowing - you can treat an HMap with a larger schema
 * as one with a smaller schema (subset of prefixes):
 * 
 * ```scala
 * type FullSchema = ("users", String) *: ("orders", Int) *: EmptyTuple
 * type PartialSchema = ("users", String) *: EmptyTuple
 * 
 * val full: HMap[FullSchema] = HMap.empty[FullSchema]
 * val partial: HMap[PartialSchema] = full.narrowTo[PartialSchema]
 * 
 * // Or use implicit conversion:
 * def needsPartial(h: HMap[PartialSchema]): Unit = ()
 * needsPartial(full)  // automatic conversion
 * ```
 * 
 * ## Use Cases
 * 
 * HMap is particularly useful for:
 * - State machines with multiple typed state categories
 * - Session management with different data types per namespace
 * - Configuration systems with type-safe sections
 * - Any scenario requiring a single map with heterogeneous value types
 */

/** 
 * A heterogeneous map with compile-time type safety.
 * 
 * @tparam M The schema as a tuple of (Prefix, ValueType) pairs
 * 
 * @param m Internal storage using "prefix\key" as the full key
 * 
 * The HMap provides immutable operations that preserve type safety. All methods
 * that access or modify the map verify at compile time that the prefix exists
 * in the schema and enforce the correct value type for that prefix.
 */
final case class HMap[M <: Tuple](private val m: Map[String, Any] = Map.empty):
  
  /** Constructs the internal key by combining prefix and key with a backslash separator */
  private def fullKey[P <: String & Singleton : ValueOf](key: String): String =
    s"${valueOf[P]}\\$key"

  /** 
   * Retrieve a value for the given prefix and key.
   * 
   * The return type is automatically determined from the schema - if the schema
   * defines ("users", String), then get["users"](...) returns Option[String].
   * 
   * @tparam P The prefix (must be a string literal type in the schema)
   * @param key The key within the prefix namespace
   * @return Some(value) if found, None otherwise
   * 
   * @example
   * {{{
   * type Schema = ("users", String) *: EmptyTuple
   * val hmap = HMap.empty[Schema].updated["users"]("alice", "Alice Smith")
   * hmap.get["users"]("alice")  // Some("Alice Smith")
   * hmap.get["users"]("bob")    // None
   * }}}
   * 
   * Compile error if P is not in the schema:
   * {{{
   * hmap.get["invalid"]("key")  // ERROR: Prefix 'invalid' is not allowed
   * }}}
   */
  def get[P <: String & Singleton : ValueOf](key: String)
    (using Contains[M, P]): Option[ValueAt[M, P]] =
    m.get(fullKey[P](key)).asInstanceOf[Option[ValueAt[M, P]]]

  /** 
   * Create a new HMap with the value at the given prefix and key updated.
   * 
   * The value type is enforced by the schema. If the schema defines 
   * ("orders", Int), you can only pass Int values for the "orders" prefix.
   * 
   * @tparam P The prefix (must be a string literal type in the schema)
   * @param key The key within the prefix namespace
   * @param value The value to store (type must match schema)
   * @return A new HMap with the updated value
   * 
   * @example
   * {{{
   * type Schema = ("count", Int) *: EmptyTuple
   * val hmap = HMap.empty[Schema]
   * val updated = hmap.updated["count"]("total", 42)
   * }}}
   * 
   * Compile error if value type doesn't match:
   * {{{
   * hmap.updated["count"]("total", "not an int")  // ERROR: type mismatch
   * }}}
   */
  def updated[P <: String & Singleton : ValueOf](key: String, value: ValueAt[M, P])
    (using Contains[M, P]): HMap[M] =
    copy(m = m.updated(fullKey[P](key), value))

  /** 
   * Create a new HMap with the value at the given prefix and key removed.
   * 
   * @tparam P The prefix (must be in the schema)
   * @param key The key within the prefix namespace
   * @return A new HMap with the entry removed (or unchanged if not present)
   * 
   * @example
   * {{{
   * val hmap = HMap.empty[Schema].updated["users"]("temp", "Temporary")
   * val cleaned = hmap.removed["users"]("temp")
   * }}}
   */
  def removed[P <: String & Singleton : ValueOf](key: String)
    (using Contains[M, P]): HMap[M] =
    copy(m = m.removed(fullKey[P](key)))

  /** 
   * Explicitly narrow the HMap to a subset schema.
   * 
   * This is useful when you need to pass an HMap to a function that expects
   * a smaller schema. The compiler will verify that every prefix in Sub exists
   * in M with the same value type.
   * 
   * @tparam Sub The subset schema (must be a valid subset of M)
   * @return A view of this HMap with the narrower schema
   * 
   * @example
   * {{{
   * type Full = ("users", String) *: ("orders", Int) *: EmptyTuple
   * type Partial = ("users", String) *: EmptyTuple
   * 
   * val full: HMap[Full] = HMap.empty[Full]
   * val partial: HMap[Partial] = full.narrowTo[Partial]
   * }}}
   */
  def narrowTo[Sub <: Tuple](using SubSchema[M, Sub]): HMap[Sub] =
    HMap[Sub](m)

/** 
 * Companion object for HMap providing factory methods and implicit conversions.
 */
object HMap:
  
  /** 
   * Create an empty HMap with the given schema.
   * 
   * @tparam M The schema as a tuple of (Prefix, ValueType) pairs
   * @return An empty HMap with schema M
   * 
   * @example
   * {{{
   * type MySchema = ("users", String) *: ("count", Int) *: EmptyTuple
   * val hmap = HMap.empty[MySchema]
   * }}}
   */
  def empty[M <: Tuple]: HMap[M] = HMap[M]()

  // ---------------------------------------------
  // Type-level machinery for compile-time schema validation
  // ---------------------------------------------

  /** 
   * Type-level function that extracts the value type for a given prefix P in schema M.
   * 
   * This is a match type that recursively searches through the schema tuple:
   * - If the head matches (P, v), return v (intersected with Matchable)
   * - Otherwise, recurse on the tail
   * - If we reach EmptyTuple, return Nothing (prefix not found)
   * 
   * @tparam M The schema tuple of (Prefix, ValueType) pairs
   * @tparam P The prefix to look up
   * @return The value type associated with P, or Nothing if not found
   * 
   * @example
   * {{{
   * type Schema = ("users", String) *: ("orders", Int) *: EmptyTuple
   * type UserType = HMap.ValueAt[Schema, "users"]    // String
   * type OrderType = HMap.ValueAt[Schema, "orders"]  // Int
   * type BadType = HMap.ValueAt[Schema, "invalid"]   // Nothing
   * }}}
   */
  type ValueAt[M <: Tuple, P <: String] <: Matchable = M match
    case (P, v) *: t => v & Matchable
    case (?, ?) *: t => ValueAt[t, P]
    case EmptyTuple  => Nothing

  /** 
   * Evidence that prefix P exists in schema M.
   * 
   * This trait acts as a compile-time proof that a prefix is valid for a given schema.
   * The compiler will automatically search for a given instance when you call HMap
   * methods, and if the prefix doesn't exist in the schema, you'll get a compile error
   * with a helpful message.
   * 
   * Users don't need to interact with this trait directly - it's used implicitly
   * by HMap's methods via the `using Contains[M, P]` context parameter.
   */
  trait Contains[M <: Tuple, P <: String]

  object Contains:
    private object evidence extends Contains[Nothing, Nothing]
    
    /** 
     * Provides Contains evidence if the prefix P exists in schema M.
     * 
     * This works by checking if ValueAt[M, P] is Nothing:
     * - If Nothing: prefix not found → compile error with helpful message
     * - Otherwise: prefix exists → provide the evidence
     * 
     * The inline and erasedValue allow this check to happen at compile time without
     * any runtime overhead.
     */
    inline given [M <: Tuple, P <: String]: Contains[M, P] =
      inline erasedValue[ValueAt[M, P]] match
        case _: Nothing => error("Prefix '" + constValue[P] + "' is not allowed by this HMap schema.")
        case _          => evidence.asInstanceOf[Contains[M, P]]

  /** 
   * Implicit conversion from HMap[Sup] to HMap[Sub] when Sub is a subset of Sup.
   * 
   * This allows you to pass an HMap with a larger schema to functions expecting
   * a smaller schema, as long as the smaller schema's prefixes are all present
   * in the larger schema with matching types.
   * 
   * @example
   * {{{
   * type Full = ("users", String) *: ("orders", Int) *: EmptyTuple
   * type Partial = ("users", String) *: EmptyTuple
   * 
   * def processUsers(h: HMap[Partial]): Unit = 
   *   h.get["users"]("admin")
   * 
   * val full: HMap[Full] = HMap.empty[Full]
   * processUsers(full)  // Implicit conversion happens here
   * }}}
   */
  given [Sup <: Tuple, Sub <: Tuple](using SubSchema[Sup, Sub]): Conversion[HMap[Sup], HMap[Sub]] with
    def apply(h: HMap[Sup]): HMap[Sub] = HMap[Sub](h.m)

  // ---------------------------------------------
  // Subset machinery (internal type-level proofs)
  // ---------------------------------------------
  
  /** 
   * Evidence that schema Sup contains prefix P with exactly type V.
   * This is used internally to construct SubSchema proofs.
   */
  trait Elem[Sup <: Tuple, P <: String, V]
  
  object Elem:
    /** 
     * Provides Elem evidence if:
     * 1. Prefix P exists in Sup (via Contains)
     * 2. The value type at P in Sup is exactly V (via =:=)
     * 
     * Note: Change =:= to <:< if you want covariant value types.
     */
    given [Sup <: Tuple, P <: String, V](using Contains[Sup, P])(using ev: ValueAt[Sup, P] =:= V): Elem[Sup, P, V] = 
      new Elem[Sup, P, V] {}

  /** 
   * Evidence that Sub is a valid subset of Sup.
   * 
   * Sub is a subset of Sup if every (Prefix, ValueType) pair in Sub
   * appears in Sup with the same value type.
   */
  trait SubSchema[Sup <: Tuple, Sub <: Tuple]
  
  object SubSchema:
    /** Base case: EmptyTuple is always a subset of any schema */
    given [Sup <: Tuple]: SubSchema[Sup, EmptyTuple] = new {}
    
    /** 
     * Inductive case: (P, V) *: T is a subset of Sup if:
     * 1. Sup contains P with value type V (via Elem)
     * 2. T is a subset of Sup (via recursive SubSchema)
     */
    given [Sup <: Tuple, P <: String, V, T <: Tuple]
        (using Elem[Sup, P, V], SubSchema[Sup, T]): SubSchema[Sup, (P, V) *: T] = 
        new {}