package zio.raft

import scala.util.NotGiven
import scala.Conversion
import zio.prelude.Newtype
import zio.raft.HMap.{SubSchema, KeyAt, ValueAt, Contains, KeyLike}

/** 
 * HMap: A type-safe heterogeneous map with prefix-based namespacing and typed keys
 * 
 * ## Overview
 * HMap is a compile-time type-safe map that allows different prefixes to store
 * different key and value types. The schema is defined at the type level as a tuple of
 * (Prefix, KeyType, ValueType) triples, and all type checking happens at compile time.
 * 
 * ## Basic Usage
 * 
 * ```scala
 * // Define newtypes for type-safe keys
 * object UserId extends Newtype[String]
 * type UserId = UserId.Type
 * 
 * object OrderId extends Newtype[Long]
 * type OrderId = OrderId.Type
 * 
 * // Define a schema: each prefix maps to specific key and value types
 * type MySchema = 
 *   ("users", UserId, UserData) *:
 *   ("orders", OrderId, OrderData) *:
 *   EmptyTuple
 * 
 * // Create an empty HMap with the schema
 * val hmap = HMap.empty[MySchema]
 * 
 * // Store values with type safety - each prefix enforces its key and value types
 * val hmap1 = hmap
 *   .updated["users"](UserId("u123"), UserData(...))     // UserId key, UserData value
 *   .updated["orders"](OrderId(456L), OrderData(...))    // OrderId key, OrderData value
 * 
 * // Retrieve values - types are automatically inferred from the schema
 * val user: Option[UserData] = hmap1.get["users"](UserId("u123"))    // Option[UserData]
 * val order: Option[OrderData] = hmap1.get["orders"](OrderId(456L))  // Option[OrderData]
 * 
 * // Compile-time errors for invalid operations:
 * // hmap1.updated["users"](OrderId(1L), ...)    // ERROR: wrong key type
 * // hmap1.updated["users"](UserId("u1"), 123)   // ERROR: wrong value type
 * // hmap1.get["invalid"](...)                    // ERROR: prefix not in schema
 * ```
 * 
 * ## How It Works
 * 
 * Internally, keys are stored as "prefix\key" strings in a Map[String, Any].
 * The KeyLike typeclass handles conversion between typed keys (like newtypes) and strings.
 * The type system ensures:
 * 1. Only prefixes defined in the schema can be used
 * 2. Keys must match the type associated with their prefix
 * 3. Values must match the type associated with their prefix
 * 4. Retrieved values have the correct type without manual casting
 * 
 * ## Schema Subtyping
 * 
 * HMap supports schema narrowing - you can treat an HMap with a larger schema
 * as one with a smaller schema (subset of prefixes):
 * 
 * ```scala
 * type FullSchema = 
 *   ("users", UserId, UserData) *: 
 *   ("orders", OrderId, OrderData) *: 
 *   EmptyTuple
 * type PartialSchema = ("users", UserId, UserData) *: EmptyTuple
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
 * - Any scenario requiring a single map with heterogeneous key and value types
 */

/** 
 * A heterogeneous map with compile-time type safety for keys and values.
 * 
 * @tparam M The schema as a tuple of (Prefix, KeyType, ValueType) triples
 * 
 * @param m Internal storage using "prefix\key" as the full key
 * 
 * The HMap provides immutable operations that preserve type safety. All methods
 * that access or modify the map verify at compile time that the prefix exists
 * in the schema and enforce the correct key and value types for that prefix.
 */
final case class HMap[M <: Tuple](m: Map[String, Any] = Map.empty):
  
  /** Constructs the internal key by combining prefix and key with a backslash separator */
  private def fullKey[P <: String & Singleton : ValueOf](key: KeyAt[M, P])
    (using kl: KeyLike[KeyAt[M, P]]): String =
    s"${valueOf[P]}\\${kl.asString(key)}"

  /** 
   * Retrieve a value for the given prefix and key.
   * 
   * The return type is automatically determined from the schema - if the schema
   * defines ("users", UserId, UserData), then get["users"](...) returns Option[UserData].
   * 
   * @tparam P The prefix (must be a string literal type in the schema)
   * @param key The typed key within the prefix namespace
   * @return Some(value) if found, None otherwise
   * 
   * @example
   * {{{
   * type Schema = ("users", UserId, UserData) *: EmptyTuple
   * val hmap = HMap.empty[Schema].updated["users"](UserId("alice"), UserData(...))
   * hmap.get["users"](UserId("alice"))  // Some(UserData(...))
   * hmap.get["users"](UserId("bob"))    // None
   * }}}
   * 
   * Compile error if P is not in the schema:
   * {{{
   * hmap.get["invalid"](...)  // ERROR: Prefix 'invalid' is not allowed
   * }}}
   */
  def get[P <: String & Singleton : ValueOf](key: KeyAt[M, P])
    (using Contains[M, P], KeyLike[KeyAt[M, P]]): Option[ValueAt[M, P]] =
    m.get(fullKey[P](key)).asInstanceOf[Option[ValueAt[M, P]]]

  /** 
   * Create a new HMap with the value at the given prefix and key updated.
   * 
   * The key and value types are enforced by the schema. If the schema defines 
   * ("orders", OrderId, OrderData), you must pass an OrderId key and OrderData value.
   * 
   * @tparam P The prefix (must be a string literal type in the schema)
   * @param key The typed key within the prefix namespace
   * @param value The value to store (type must match schema)
   * @return A new HMap with the updated value
   * 
   * @example
   * {{{
   * type Schema = ("orders", OrderId, OrderData) *: EmptyTuple
   * val hmap = HMap.empty[Schema]
   * val updated = hmap.updated["orders"](OrderId(123L), OrderData(...))
   * }}}
   * 
   * Compile error if key or value type doesn't match:
   * {{{
   * hmap.updated["orders"](UserId("123"), ...)     // ERROR: wrong key type
   * hmap.updated["orders"](OrderId(123L), "text")  // ERROR: wrong value type
   * }}}
   */
  inline def updated[P <: String & Singleton : ValueOf](key: KeyAt[M, P], value: ValueAt[M, P])
    (using Contains[M, P], KeyLike[KeyAt[M, P]]): HMap[M] =
    copy(m = m.updated(fullKey[P](key), value))

  /** 
   * Create a new HMap with the value at the given prefix and key removed.
   * 
   * @tparam P The prefix (must be in the schema)
   * @param key The typed key within the prefix namespace
   * @return A new HMap with the entry removed (or unchanged if not present)
   * 
   * @example
   * {{{
   * val hmap = HMap.empty[Schema].updated["users"](UserId("temp"), UserData(...))
   * val cleaned = hmap.removed["users"](UserId("temp"))
   * }}}
   */
  def removed[P <: String & Singleton : ValueOf](key: KeyAt[M, P])
    (using Contains[M, P], KeyLike[KeyAt[M, P]]): HMap[M] =
    copy(m = m.removed(fullKey[P](key)))


  /** 
   * Returns an iterator over (key, value) pairs for the specified prefix.
   * Only entries belonging to prefix P are included; key is the typed key (without prefix).
   * 
   * @tparam P The prefix (must be present in the schema)
   * @return Iterator of (KeyType, ValueType) pairs for the prefix P
   * 
   * @example
   * {{{
   * type Schema = ("users", UserId, UserData) *: ("orders", OrderId, OrderData) *: EmptyTuple
   * val hmap = HMap.empty[Schema]
   *   .updated["users"](UserId("u1"), UserData(...))
   *   .updated["users"](UserId("u2"), UserData(...))
   * 
   * hmap.iterator["users"].toList // List((UserId("u1"), UserData(...)), (UserId("u2"), UserData(...)))
   * }}}
   */
  def iterator[P <: String & Singleton : ValueOf](using c: Contains[M, P], kl: KeyLike[KeyAt[M, P]]): Iterator[(KeyAt[M, P], ValueAt[M, P])] = {
    val prefix = valueOf[P] + "\\"
    m.iterator.collect {
      case (k, v) if k.startsWith(prefix) =>
        val logicalKeyStr = k.substring(prefix.length)
        val logicalKey = kl.fromString(logicalKeyStr)
        (logicalKey, v.asInstanceOf[ValueAt[M, P]])
    }
  }


  /** 
   * Explicitly narrow the HMap to a subset schema.
   * 
   * This is useful when you need to pass an HMap to a function that expects
   * a smaller schema. The compiler will verify that every prefix in Sub exists
   * in M with the same key and value types.
   * 
   * @tparam Sub The subset schema (must be a valid subset of M)
   * @return A view of this HMap with the narrower schema
   * 
   * @example
   * {{{
   * type Full = ("users", UserId, UserData) *: ("orders", OrderId, OrderData) *: EmptyTuple
   * type Partial = ("users", UserId, UserData) *: EmptyTuple
   * 
   * val full: HMap[Full] = HMap.empty[Full]
   * val partial: HMap[Partial] = full.narrowTo[Partial]
   * }}}
   */
  def narrowTo[Sub <: Tuple](using SubSchema[M, Sub]): HMap[Sub] =
    HMap[Sub](m)

/** 
 * Companion object for HMap providing factory methods, type-level functions, and implicit conversions.
 */
object HMap:
  
  /** 
   * Create an empty HMap with the given schema.
   * 
   * @tparam M The schema as a tuple of (Prefix, KeyType, ValueType) triples
   * @return An empty HMap with schema M
   * 
   * @example
   * {{{
   * type MySchema = ("users", UserId, UserData) *: ("orders", OrderId, OrderData) *: EmptyTuple
   * val hmap = HMap.empty[MySchema]
   * }}}
   */
  def empty[M <: Tuple]: HMap[M] = HMap[M]()

  // ---------------------------------------------
  // Type-level machinery for compile-time schema validation
  // ---------------------------------------------

  /**
   * Typeclass for converting typed keys to/from strings for internal storage.
   * 
   * This enables HMap to work with newtypes and other custom key types while
   * storing them as strings internally.
   * 
   * ## Usage with ZIO Prelude Newtype[String]
   * 
   * For each newtype used as a key, provide a KeyLike instance:
   * 
   * ```scala
   * object UserId extends Newtype[String]
   * type UserId = UserId.Type
   * 
   * given HMap.KeyLike[UserId] with
   *   def asString(key: UserId): String = UserId.unwrap(key)
   *   def fromString(str: String): UserId = UserId(str)
   * ```
   */
  trait KeyLike[K]:
    /** Convert a typed key to a string for storage */
    def asString(key: K): String
    
    /** Convert a string back to a typed key */
    def fromString(str: String): K

  object KeyLike:
    /**
     * Helper method to create KeyLike instances for Newtype[String] types.
     * 
     * @example
     * {{{
     * object UserId extends Newtype[String]
     * type UserId = UserId.Type
     * 
     * given HMap.KeyLike[UserId] = HMap.KeyLike.forNewtype(UserId)
     * }}}
     */
    def forNewtype[A](nt: Newtype[String] { type Type = A }): KeyLike[A] = new KeyLike[A]:
      def asString(key: A): String = nt.unwrap(key)
      def fromString(str: String): A = nt.wrap(str)

  /** 
   * Type-level function that extracts the key type for a given prefix P in schema M.
   * 
   * This is a match type that recursively searches through the schema tuple:
   * - If the head matches (P, k, v), return k
   * - Otherwise, recurse on the tail
   * - If we reach EmptyTuple, return Nothing (prefix not found)
   * 
   * @tparam M The schema tuple of (Prefix, KeyType, ValueType) triples
   * @tparam P The prefix to look up
   * @return The key type associated with P, or Nothing if not found
   * 
   * @example
   * {{{
   * type Schema = ("users", UserId, UserData) *: ("orders", OrderId, OrderData) *: EmptyTuple
   * type UserKeyType = HMap.KeyAt[Schema, "users"]    // UserId
   * type OrderKeyType = HMap.KeyAt[Schema, "orders"]  // OrderId
   * type BadType = HMap.KeyAt[Schema, "invalid"]      // Nothing
   * }}}
   */
  type KeyAt[M <: Tuple, P <: String] = M match
    case (P, k, v) *: t => k
    case (?, ?, ?) *: t => KeyAt[t, P]
    case EmptyTuple => Nothing

  /** 
   * Type-level function that extracts the value type for a given prefix P in schema M.
   * 
   * This is a match type that recursively searches through the schema tuple:
   * - If the head matches (P, k, v), return v
   * - Otherwise, recurse on the tail
   * - If we reach EmptyTuple, return Nothing (prefix not found)
   * 
   * @tparam M The schema tuple of (Prefix, KeyType, ValueType) triples
   * @tparam P The prefix to look up
   * @return The value type associated with P, or Nothing if not found
   * 
   * @example
   * {{{
   * type Schema = ("users", UserId, UserData) *: ("orders", OrderId, OrderData) *: EmptyTuple
   * type UserValueType = HMap.ValueAt[Schema, "users"]    // UserData
   * type OrderValueType = HMap.ValueAt[Schema, "orders"]  // OrderData
   * type BadType = HMap.ValueAt[Schema, "invalid"]        // Nothing
   * }}}
   */
  type ValueAt[M <: Tuple, P <: String] = M match
    case (P, k, v) *: t => v
    case (?, ?, ?) *: t => ValueAt[t, P]
    case EmptyTuple => Nothing

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
     * Uses NotGiven to ensure ValueAt[M, P] is not Nothing:
     * - If ValueAt[M, P] is Nothing: NotGiven evidence is absent → custom error
     * - Otherwise: prefix exists in schema → provide the evidence
     * 
     * This is cleaner than pattern matching on erasedValue which triggers
     * Matchable warnings for non-Matchable types.
     */
    inline given [M <: Tuple, P <: String](using NotGiven[ValueAt[M, P] =:= Nothing]): Contains[M, P] =
      evidence.asInstanceOf[Contains[M, P]]

  /** 
   * Implicit conversion from HMap[Sup] to HMap[Sub] when Sub is a subset of Sup.
   * 
   * This allows you to pass an HMap with a larger schema to functions expecting
   * a smaller schema, as long as the smaller schema's prefixes are all present
   * in the larger schema with matching key and value types.
   * 
   * @example
   * {{{
   * type Full = ("users", UserId, UserData) *: ("orders", OrderId, OrderData) *: EmptyTuple
   * type Partial = ("users", UserId, UserData) *: EmptyTuple
   * 
   * def processUsers(h: HMap[Partial]): Unit = 
   *   h.get["users"](UserId("admin"))
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
   * Evidence that schema Sup contains prefix P with exactly key type K and value type V.
   * This is used internally to construct SubSchema proofs.
   */
  trait Elem[Sup <: Tuple, P <: String, K, V]
  
  object Elem:
    /** 
     * Provides Elem evidence if:
     * 1. Prefix P exists in Sup (via Contains)
     * 2. The key type at P in Sup is exactly K (via =:=)
     * 3. The value type at P in Sup is exactly V (via =:=)
     * 
     * Note: Change =:= to <:< if you want covariant key/value types.
     */
    given [Sup <: Tuple, P <: String, K, V](using Contains[Sup, P])
        (using evK: KeyAt[Sup, P] =:= K, evV: ValueAt[Sup, P] =:= V): Elem[Sup, P, K, V] = 
      new Elem[Sup, P, K, V] {}

  /** 
   * Evidence that Sub is a valid subset of Sup.
   * 
   * Sub is a subset of Sup if every (Prefix, KeyType, ValueType) triple in Sub
   * appears in Sup with the same key and value types.
   */
  trait SubSchema[Sup <: Tuple, Sub <: Tuple]
  
  object SubSchema:
    /** Base case: EmptyTuple is always a subset of any schema */
    given [Sup <: Tuple]: SubSchema[Sup, EmptyTuple] = new {}
    
    /** 
     * Inductive case: (P, K, V) *: T is a subset of Sup if:
     * 1. Sup contains P with key type K and value type V (via Elem)
     * 2. T is a subset of Sup (via recursive SubSchema)
     */
    given [Sup <: Tuple, P <: String, K, V, T <: Tuple]
        (using Elem[Sup, P, K, V], SubSchema[Sup, T]): SubSchema[Sup, (P, K, V) *: T] = 
        new {}