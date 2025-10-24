package zio.raft

import scala.util.NotGiven
import scala.collection.immutable.TreeMap
import zio.prelude.Newtype
import zio.raft.HMap.{KeyAt, ValueAt, Contains, KeyLike}
import java.nio.charset.StandardCharsets

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
 * Uses TreeMap internally for sorted key storage, enabling efficient range queries.
 * 
 * @tparam M The schema as a tuple of (Prefix, KeyType, ValueType) triples
 * 
 * @param m Internal storage using byte arrays (prefix + separator + key bytes) as the full key, maintained in sorted order
 * 
 * The HMap provides immutable operations that preserve type safety. All methods
 * that access or modify the map verify at compile time that the prefix exists
 * in the schema and enforce the correct key and value types for that prefix.
 */
final case class HMap[M <: Tuple](private val m: TreeMap[Array[Byte], Any] = TreeMap.empty(using HMap.byteArrayOrdering)):
  
  /** Constructs the internal key by combining prefix and key bytes with a backslash separator */
  private def fullKey[P <: String & Singleton : ValueOf](key: KeyAt[M, P])
    (using kl: KeyLike[KeyAt[M, P]]): Array[Byte] =
    val prefixBytes = valueOf[P].getBytes(StandardCharsets.UTF_8)
    val keyBytes = kl.asBytes(key)
    val separator = Array(0x5C.toByte) // backslash
    prefixBytes ++ separator ++ keyBytes
  
  /**
   * Calculate the range bounds for a prefix in the TreeMap.
   * Returns (lowerBound, upperBound) where lowerBound is inclusive and upperBound is exclusive.
   * This allows efficient iteration over all entries with a given prefix.
   */
  private def prefixRange[P <: String & Singleton : ValueOf](): (Array[Byte], Array[Byte]) =
    val prefixBytes = valueOf[P].getBytes(StandardCharsets.UTF_8)
    val separatorByte = 0x5C.toByte // backslash
    val lowerBound = prefixBytes :+ separatorByte
    val upperBound = prefixBytes :+ (separatorByte + 1).toByte
    (lowerBound, upperBound)

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
   * Create a new HMap with multiple values removed for the given prefix.
   * 
   * More efficient than calling `removed` multiple times in a fold, as it builds
   * a new TreeMap in one pass.
   * 
   * @tparam P The prefix (must be in the schema)
   * @param keys The keys to remove within the prefix namespace
   * @return A new HMap with all specified entries removed
   * 
   * @example
   * {{{
   * val hmap = HMap.empty[Schema]
   *   .updated["users"](UserId("u1"), UserData(...))
   *   .updated["users"](UserId("u2"), UserData(...))
   *   .updated["users"](UserId("u3"), UserData(...))
   * 
   * val cleaned = hmap.removedAll["users"](List(UserId("u1"), UserId("u2")))
   * }}}
   */
  def removedAll[P <: String & Singleton : ValueOf](keys: IterableOnce[KeyAt[M, P]])
    (using c: Contains[M, P], kl: KeyLike[KeyAt[M, P]]): HMap[M] =
    val fullKeys = keys.iterator.map(key => fullKey[P](key))
    copy(m = m.removedAll(fullKeys))


  /**
   * Returns an iterator over (key, value) pairs for the specified prefix.
   * Only entries belonging to prefix P are included; key is the typed key (without prefix).
   * 
   * Uses TreeMap's range for efficient iteration - only visits entries with the prefix.
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
    val (lowerBound, upperBound) = prefixRange[P]()
    
    // Use TreeMap's range for efficient iteration (O(log n + k) where k = results)
    m.range(lowerBound, upperBound).iterator.map { case (k, v) =>
      // Extract key bytes (everything after prefix + separator)
      val keyBytes = k.drop(lowerBound.length)
      val logicalKey = kl.fromBytes(keyBytes)
      (logicalKey, v.asInstanceOf[ValueAt[M, P]])
    }
  }

  /**
   * Returns an iterator over (key, value) pairs for a key range within the specified prefix.
   * 
   * Only entries belonging to prefix P with keys in the range [from, until) are included.
   * The range is based on the byte representation of the keys (after conversion via KeyLike).
   * 
   * @tparam P The prefix (must be present in the schema)
   * @param from The start of the range (inclusive)
   * @param until The end of the range (exclusive)
   * @return Iterator of (KeyType, ValueType) pairs within the range
   * 
   * @example
   * {{{
   * type Schema = ("users", UserId, UserData) *: EmptyTuple
   * val hmap = HMap.empty[Schema]
   *   .updated["users"](UserId("user001"), UserData(...))
   *   .updated["users"](UserId("user005"), UserData(...))
   *   .updated["users"](UserId("user010"), UserData(...))
   * 
   * // Get users from "user003" to "user008"
   * hmap.range["users"](UserId("user003"), UserId("user008")).toList
   * // Returns: List((UserId("user005"), UserData(...)))
   * }}}
   */
  def range[P <: String & Singleton : ValueOf](from: KeyAt[M, P], until: KeyAt[M, P])
    (using c: Contains[M, P], kl: KeyLike[KeyAt[M, P]]): Iterator[(KeyAt[M, P], ValueAt[M, P])] = {
    val prefixBytes = valueOf[P].getBytes(StandardCharsets.UTF_8)
    val separatorByte = 0x5C.toByte // backslash
    val prefixWithSep = prefixBytes :+ separatorByte
    
    val fromKey = prefixWithSep ++ kl.asBytes(from)
    val untilKey = prefixWithSep ++ kl.asBytes(until)
    
    // TreeMap.range returns entries in [from, until) based on byte ordering
    m.range(fromKey, untilKey).iterator.map { case (k, v) =>
      // Extract key bytes (everything after prefix + separator)
      val keyBytes = k.drop(prefixWithSep.length)
      val logicalKey = kl.fromBytes(keyBytes)
      (logicalKey, v.asInstanceOf[ValueAt[M, P]])
    }
  }

  /**
   * Check if any entry in the specified prefix satisfies the predicate.
   * 
   * Uses the underlying TreeMap's range.exists for efficient short-circuit evaluation.
   * Stops as soon as it finds a matching entry without creating intermediate iterators.
   * 
   * @tparam P The prefix (must be present in the schema)
   * @param predicate Function to test each (key, value) pair
   * @return true if any entry satisfies the predicate, false otherwise
   * 
   * @example
   * {{{
   * type Schema = ("users", UserId, UserData) *: EmptyTuple
   * val hmap = HMap.empty[Schema]
   *   .updated["users"](UserId("alice"), UserData(age = 25))
   *   .updated["users"](UserId("bob"), UserData(age = 30))
   * 
   * // Check if any user is over 18
   * val hasAdults = hmap.exists["users"] { (_, user) => user.age >= 18 }
   * }}}
   */
  def exists[P <: String & Singleton : ValueOf](predicate: (KeyAt[M, P], ValueAt[M, P]) => Boolean)
    (using c: Contains[M, P], kl: KeyLike[KeyAt[M, P]]): Boolean = {
    val (lowerBound, upperBound) = prefixRange[P]()
    
    // Use TreeMap's range.exists directly for maximum efficiency
    m.range(lowerBound, upperBound).exists { case (k, v) =>
      val keyBytes = k.drop(lowerBound.length)
      val logicalKey = kl.fromBytes(keyBytes)
      predicate(logicalKey, v.asInstanceOf[ValueAt[M, P]])
    }
  }



/** 
 * Companion object for HMap providing factory methods, type-level functions, and implicit conversions.
 */
object HMap:
  
  /**
   * Byte array ordering using Java's unsigned byte comparison.
   * This ensures proper lexicographic ordering of byte arrays.
   */
  given byteArrayOrdering: Ordering[Array[Byte]] = 
    Ordering.comparatorToOrdering(java.util.Arrays.compareUnsigned(_, _))
  
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
   * Typeclass for converting typed keys to/from byte arrays for internal storage.
   * 
   * This enables HMap to work with newtypes and other custom key types while
   * storing them as byte arrays internally for efficient binary comparison and ordering.
   * 
   * ## Usage with ZIO Prelude Newtype[String]
   * 
   * For each newtype used as a key, provide a KeyLike instance:
   * 
   * ```scala
   * object UserId extends Newtype[String]
   * type UserId = UserId.Type
   * 
   * given HMap.KeyLike[UserId] = HMap.KeyLike.forNewtype(UserId)
   * ```
   * 
   * ## Usage with Composite Keys
   * 
   * For composite keys, implement KeyLike directly with proper encoding:
   * 
   * ```scala
   * given HMap.KeyLike[(UserId, Timestamp)] = new HMap.KeyLike[(UserId, Timestamp)]:
   *   def asBytes(key: (UserId, Timestamp)): Array[Byte] = 
   *     // Encode with length prefixes and proper ordering
   *     ???
   *   def fromBytes(bytes: Array[Byte]): (UserId, Timestamp) = 
   *     // Decode with length prefixes
   *     ???
   * ```
   */
  trait KeyLike[K]:
    /** Convert a typed key to bytes for storage */
    def asBytes(key: K): Array[Byte]
    
    /** Convert bytes back to a typed key */
    def fromBytes(bytes: Array[Byte]): K

  object KeyLike:
    /**
     * Helper method to create KeyLike instances for Newtype[String] types.
     * Encodes strings as UTF-8 bytes.
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
      def asBytes(key: A): Array[Byte] = 
        nt.unwrap(key).getBytes(StandardCharsets.UTF_8)
      def fromBytes(bytes: Array[Byte]): A = 
        nt.wrap(new String(bytes, StandardCharsets.UTF_8))

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
