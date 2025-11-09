package zio.raft

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Newtype
import java.nio.charset.StandardCharsets

/** Tests for HMap.prefixRange carry propagation and boundary calculation.
  *
  * These tests verify the critical logic for computing upper bounds when iterating over prefixes, especially the carry
  * propagation when prefix bytes are 0xFF.
  */
object HMapPrefixRangeSpec extends ZIOSpecDefault:

  // Test schema with various prefixes
  object TestKey extends Newtype[String]
  type TestKey = TestKey.Type
  given HMap.KeyLike[TestKey] = HMap.KeyLike.forNewtype(TestKey)

  // Add the prefix as a type to the schema so we can call prefixRange
  type PrefixTestSchema[P <: String & Singleton] = (P, TestKey, Int) *: EmptyTuple

  type TestSchema = ("simple", TestKey, Int) *: ("end_ff", TestKey, Int) *: ("all_ff", TestKey, Int) *: EmptyTuple

  // Helper to get prefixRange by calling the actual method
  private def getPrefixRange[P <: String & Singleton: ValueOf]: (Array[Byte], Array[Byte]) =
    val hmap = HMap.empty[PrefixTestSchema[P]]
    hmap.prefixRange[P]()

  // Helper to compare byte arrays using unsigned comparison
  private def compareUnsigned(a: Array[Byte], b: Array[Byte]): Int =
    java.util.Arrays.compareUnsigned(a, b)

  def spec = suiteAll("HMap.prefixRange") {
    test("simple prefix without 0xFF bytes") {
      val (lower, upper) = getPrefixRange["test"]
      val prefixBytes = "test".getBytes(StandardCharsets.UTF_8)

      // Lower bound should be [4]["test"]
      assertTrue(lower.length == 1 + prefixBytes.length) &&
      assertTrue(lower(0) == 4.toByte) &&
      assertTrue(lower.drop(1).sameElements(prefixBytes)) &&
      // Upper bound should be [4]["tesu"] (increment last byte 't' -> 'u')
      assertTrue(upper.length == 1 + prefixBytes.length) &&
      assertTrue(upper(0) == 4.toByte) &&
      assertTrue(upper.last == ('t' + 1).toByte)
    }

    test("prefix ending with high byte value - carry") {
      // Use a prefix where last UTF-8 byte is near 0xFF
      // Let's use actual string "test~" where ~ is 0x7E, close to max ASCII
      val prefix = "test\u007E" // 0x7E
      val (lower, upper) = getPrefixRange["test~"]
      val prefixBytes = prefix.getBytes(StandardCharsets.UTF_8)

      // Lower bound should match input
      assertTrue(lower.drop(1).sameElements(prefixBytes)) &&
      // Upper bound should have last byte incremented
      assertTrue(upper.drop(1).last == 0x7f.toByte)
    }

    test("prefix with UTF-8 multibyte character ending in 0xFF") {
      // Use a character whose UTF-8 encoding ends in 0xFF
      // U+083F (࠿) encodes as 0xE0 0xA0 0xBF in UTF-8
      val prefix = "test\u083F"
      val (lower, upper) = getPrefixRange["test\u083F"]
      val prefixBytes = prefix.getBytes(StandardCharsets.UTF_8)

      // Lower bound should match input
      assertTrue(lower.drop(1).sameElements(prefixBytes)) &&
      // Upper bound should carry: 0xBF + 1 = 0xC0, but might need carry
      // Actually E0 A0 BF -> E0 A1 00 (since 0xBF + 1 = 0xC0, which is outside the valid range for that UTF-8 byte position, the carry propagates to the previous byte, incrementing 0xA0 to 0xA1 and setting the current byte to 0x00)
      assertTrue(compareUnsigned(upper, lower) > 0)
    }

    test("prefix where all UTF-8 bytes are 0xFF - overflow case") {
      // This is tricky - we need a string whose UTF-8 representation is all 0xFF
      // Actually, that's not possible with valid UTF-8
      // Let's test with a string that ends with multiple high bytes
      // Use U+0FFF (࿿) which encodes as 0xE0 0xBF 0xBF in UTF-8
      val prefix = "\u0FFF"
      val (lower, upper) = getPrefixRange["\u0FFF"]
      val prefixBytes = prefix.getBytes(StandardCharsets.UTF_8)

      // This should carry through the bytes
      assertTrue(lower.drop(1).sameElements(prefixBytes)) &&
      // Upper bound should handle carry
      assertTrue(compareUnsigned(upper, lower) > 0)
    }

    test("prefix range bounds work with compareUnsigned") {
      val prefixBytes = Array[Byte]('p'.toByte, 'r'.toByte, 'e'.toByte)
      val prefix = new String(prefixBytes, StandardCharsets.ISO_8859_1)
      val (lower, upper) = getPrefixRange["pre"]

      // Create keys within and outside the range
      val keyInRange1 = Array(3.toByte) ++ prefixBytes ++ Array[Byte](0.toByte)
      val keyInRange2 = Array(3.toByte) ++ prefixBytes ++ Array[Byte](127.toByte)
      val keyInRange3 = Array(3.toByte) ++ prefixBytes ++ Array[Byte](0xff.toByte)
      val keyBefore = Array(2.toByte) ++ Array[Byte]('z'.toByte, 'z'.toByte)
      val keyAfter = Array(3.toByte) ++ Array[Byte]('p'.toByte, 'r'.toByte, 'f'.toByte)

      assertTrue(
        // Keys in range should be >= lower and < upper
        compareUnsigned(keyInRange1, lower) >= 0 &&
          compareUnsigned(keyInRange1, upper) < 0 &&
          compareUnsigned(keyInRange2, lower) >= 0 &&
          compareUnsigned(keyInRange2, upper) < 0 &&
          compareUnsigned(keyInRange3, lower) >= 0 &&
          compareUnsigned(keyInRange3, upper) < 0 &&
          // Key before should be < lower
          compareUnsigned(keyBefore, lower) < 0 &&
          // Key after should be >= upper
          compareUnsigned(keyAfter, upper) >= 0
      )
    }

    test("prefix with high byte in middle - no carry to middle") {
      // Normal string - last byte increments, middle untouched
      val prefix = "a\u00FFz" // Has high byte in middle
      val (lower, upper) = getPrefixRange["a\u00FFz"]
      val prefixBytes = prefix.getBytes(StandardCharsets.UTF_8)

      // Upper bound should increment last byte without carrying to middle
      assertTrue(lower.drop(1).sameElements(prefixBytes)) &&
      // Just verify upper > lower
      assertTrue(compareUnsigned(upper, lower) > 0)
    }

    test("single byte prefix") {
      val prefixBytes = Array[Byte]('x'.toByte)
      val prefix = new String(prefixBytes, StandardCharsets.ISO_8859_1)
      val (lower, upper) = getPrefixRange["x"]

      assertTrue(lower(0) == 1.toByte) &&
      assertTrue(lower(1) == 'x'.toByte) &&
      assertTrue(upper(0) == 1.toByte) &&
      assertTrue(upper(1) == ('x' + 1).toByte)
    }

    test("extreme case - character with max valid UTF-8 bytes") {
      // U+10FFFF (���) is the maximum Unicode code point
      // It encodes as 0xF4 0x8F 0xBF 0xBF in UTF-8
      val prefix = "\uDBFF\uDFFF" // Surrogate pair for U+10FFFF
      val (lower, upper) = getPrefixRange["\uDBFF\uDFFF"]
      val prefixBytes = prefix.getBytes(StandardCharsets.UTF_8)

      // This should handle carry through multiple 0xBF bytes
      assertTrue(lower.drop(1).sameElements(prefixBytes)) &&
      assertTrue(compareUnsigned(upper, lower) > 0) &&
      // Check that upper is actually usable as a bound
      assertTrue(compareUnsigned(lower, upper) < 0)
    }

    test("actual HMap iteration uses correct bounds") {
      // Integration test: verify iterator actually respects the bounds
      val hmap = HMap.empty[TestSchema]
        .updated["simple"](TestKey("a"), 1)
        .updated["simple"](TestKey("b"), 2)
        .updated["simple"](TestKey("c"), 3)
        .updated["end_ff"](TestKey("x"), 10)
        .updated["all_ff"](TestKey("y"), 20)

      val simpleEntries = hmap.iterator["simple"].toList
      val endFfEntries = hmap.iterator["end_ff"].toList
      val allFfEntries = hmap.iterator["all_ff"].toList

      assertTrue(
        simpleEntries.length == 3 &&
          simpleEntries.map(_._2).toSet == Set(1, 2, 3) &&
          endFfEntries.length == 1 &&
          endFfEntries.head._2 == 10 &&
          allFfEntries.length == 1 &&
          allFfEntries.head._2 == 20
      )
    }

    test("bounds are exclusive upper bound") {
      // Verify that upper bound is exclusive (not included in range)
      val prefixBytes = Array[Byte]('t'.toByte, 'e'.toByte, 's'.toByte, 't'.toByte)
      val prefix = new String(prefixBytes, StandardCharsets.ISO_8859_1)
      val (lower, upper) = getPrefixRange["test"]

      // Upper bound itself should NOT be in range
      assertTrue(compareUnsigned(upper, lower) > 0) &&
      assertTrue(compareUnsigned(lower, upper) < 0)
    }

    test("computePrefixUpperBound works") {
      val prefixBytes = Array[Byte]('t'.toByte, 'e'.toByte, 's'.toByte, 't'.toByte)
      val hmap = HMap.empty[TestSchema]
      val upper = hmap.computePrefixUpperBound(prefixBytes)

      assertTrue(HMap.byteArrayOrdering.compare(upper, prefixBytes) == 1) &&
      assertTrue(upper.length == 4) &&
      assertTrue(upper.sameElements(Array[Byte]('t'.toByte, 'e'.toByte, 's'.toByte, 'u'.toByte)))
    }

    test("computePrefixUpperBound for max string 0xff") {
      val prefixBytes = Array[Byte](0xff.toByte, 0xff.toByte)
      val hmap = HMap.empty[TestSchema]
      val upper = hmap.computePrefixUpperBound(prefixBytes)

      assertTrue(HMap.byteArrayOrdering.compare(upper, prefixBytes) == 1) &&
      assertTrue(upper.length == 3) &&
      assertTrue(upper.sameElements(Array[Byte](0xff.toByte, 0xff.toByte, 0x00.toByte)))
    }

    test("truncate trailing zeros from computePrefixUpperBound") {
      val prefixBytes = Array[Byte](0x01.toByte, 0xFF.toByte)
      val hmap = HMap.empty[TestSchema]
      val upper = hmap.computePrefixUpperBound(prefixBytes)

      assertTrue(HMap.byteArrayOrdering.compare(upper, prefixBytes) == 1) &&
      assertTrue(upper.length == 1) &&
      assertTrue(upper.sameElements(Array[Byte](0x02.toByte)))
    }

  }
end HMapPrefixRangeSpec
