package zio.raft

import zio.test.*
import zio.test.Assertion.*
import java.nio.charset.StandardCharsets

object HMapRangeByCompoundKeyPrefixSpec extends ZIOSpecDefault:

  // Compound key encoding:
  // bytes = lengthOfFirstComponent (1 byte) ++ firstComponentUtf8 ++ [lengthOfSecondComponent (1 byte) ++ secondComponentUtf8]
  // (The second component is omitted if empty.)
  // This ensures that all keys that share the same first component are in a contiguous
  // lexicographic range [firstComponentLength ++ firstComponentUtf8, ...), which is what
  // rangeByCompoundKeyPrefix relies on by computing the upper bound via +1 on the last byte.
  given HMap.KeyLike[(String, String)] with
    def asBytes(key: (String, String)): Array[Byte] =
      val (first, second) = key
      val firstBytes = first.getBytes(StandardCharsets.UTF_8)
      if second.isEmpty then
        Array(firstBytes.length.toByte) ++ firstBytes
      else
        val secondBytes = second.getBytes(StandardCharsets.UTF_8)
        Array(firstBytes.length.toByte) ++ firstBytes ++ Array(secondBytes.length.toByte) ++ secondBytes

    def fromBytes(bytes: Array[Byte]): (String, String) =
      val len1 = bytes(0) & 0xff
      val first = new String(bytes.slice(1, 1 + len1), StandardCharsets.UTF_8)
      if bytes.length == 1 + len1 then (first, "")
      else
        val len2Pos = 1 + len1
        val len2 = bytes(len2Pos) & 0xff
        val second = new String(bytes.slice(len2Pos + 1, len2Pos + 1 + len2), StandardCharsets.UTF_8)
        (first, second)

  type Schema = ("users", (String, String), Int) *: EmptyTuple

  def spec = suiteAll("HMap.rangeByCompoundKeyPrefix") {

    test("computePrefixUpperBound works for compound key prefix") {
      val prefix = ("r1", "")
      val key = ("r1", "a")
      val hmap = HMap.empty[Schema]
      val keyBytes = summon[HMap.KeyLike[(String, String)]].asBytes(key)
      val upper = hmap.computePrefixUpperBound(summon[HMap.KeyLike[(String, String)]].asBytes(prefix))


      assertTrue(HMap.byteArrayOrdering.compare(upper, keyBytes) > 0)
    }

    test("returns all entries that share the same first component only") {
      val hmap =
        HMap.empty[Schema]
          .updated["users"](("r1", "a"), 1)
          .updated["users"](("r1", "b"), 2)
          .updated["users"](("r1", "c"), 3)
          .updated["users"](("r2", "x"), 10)
          .updated["users"](("r3", "y"), 20)

      val results = hmap.rangeByCompoundKeyPrefix["users"](("r1", "")).toList
      val keys = results.map(_._1)
      val values = results.map(_._2)

      assertTrue(results.length == 3) &&
      assertTrue(keys.toSet == Set(("r1", "a"), ("r1", "b"), ("r1", "c"))) &&
      assertTrue(values.toSet == Set(1, 2, 3))
    }

    test("includes empty-second-component key and excludes other first components") {
      val hmap =
        HMap.empty[Schema]
          .updated["users"](("ns", ""), 0)
          .updated["users"](("ns", "k1"), 1)
          .updated["users"](("ns", "k2"), 2)
          .updated["users"](("ns2", ""), 100)
          .updated["users"](("ns2", "k3"), 101)

      val nsResults = hmap.rangeByCompoundKeyPrefix["users"](("ns", "")).toList
      val nsKeys = nsResults.map(_._1).toSet
      val nsValues = nsResults.map(_._2).toSet

      val ns2Results = hmap.rangeByCompoundKeyPrefix["users"](("ns2", "")).toList
      val ns2Keys = ns2Results.map(_._1).toSet
      val ns2Values = ns2Results.map(_._2).toSet

      assertTrue(nsKeys == Set(("ns", ""), ("ns", "k1"), ("ns", "k2"))) &&
      assertTrue(nsValues == Set(0, 1, 2)) &&
      assertTrue(ns2Keys == Set(("ns2", ""), ("ns2", "k3"))) &&
      assertTrue(ns2Values == Set(100, 101))
    }

    test("works with unicode in first component and multiple seconds") {
      val first = "régiön-𝟙" // unicode characters
      val hmap =
        HMap.empty[Schema]
          .updated["users"]((first, "α"), 5)
          .updated["users"]((first, "β"), 6)
          .updated["users"]((first, "γ"), 7)
          .updated["users"](("other", "δ"), 8)

      val results = hmap.rangeByCompoundKeyPrefix["users"]((first, "")).toList

      assertTrue(results.length == 3) &&
      assertTrue(results.map(_._1).toSet == Set((first, "α"), (first, "β"), (first, "γ"))) &&
      assertTrue(results.map(_._2).toSet == Set(5, 6, 7))
    }
  }
end HMapRangeByCompoundKeyPrefixSpec
