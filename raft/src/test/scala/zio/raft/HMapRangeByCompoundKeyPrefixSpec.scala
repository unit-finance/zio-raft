package zio.raft

import zio.test.*
import zio.prelude.Newtype

object HMapRangeByCompoundKeyPrefixSpec extends ZIOSpecDefault:

  // Use newtypes and the shared KeyLike.forNewTypeTuple which encodes:
  // [4 bytes BE length of A][A UTF-8][optional: 4 bytes BE length of B][B UTF-8]
  // and omits B entirely when it's the empty string.
  object First extends Newtype[String]
  type First = First.Type
  object Second extends Newtype[String]
  type Second = Second.Type

  given HMap.KeyLike[(First, Second)] = HMap.KeyLike.forNewTypeTuple(First, Second)

  type Schema = ("users", (First, Second), Int) *: EmptyTuple

  def spec = suiteAll("HMap.rangeByCompoundKeyPrefix") {

    test("computePrefixUpperBound works for compound key prefix") {
      val prefix = (First("r1"), Second(""))
      val key = (First("r1"), Second("a"))
      val hmap = HMap.empty[Schema]
      val keyBytes = summon[HMap.KeyLike[(First, Second)]].asBytes(key)
      val upper = hmap.computePrefixUpperBound(summon[HMap.KeyLike[(First, Second)]].asBytes(prefix))


      assertTrue(HMap.byteArrayOrdering.compare(upper, keyBytes) > 0)
    }

    test("returns all entries that share the same first component only") {
      val hmap =
        HMap.empty[Schema]
          .updated["users"]((First("r1"), Second("a")), 1)
          .updated["users"]((First("r1"), Second("b")), 2)
          .updated["users"]((First("r1"), Second("c")), 3)
          .updated["users"]((First("r2"), Second("x")), 10)
          .updated["users"]((First("r3"), Second("y")), 20)

      val results = hmap.rangeByCompoundKeyPrefix["users"]((First("r1"), Second(""))).toList
      val keys = results.map(_._1)
      val values = results.map(_._2)

      assertTrue(results.length == 3) &&
      assertTrue(keys.toSet == Set((First("r1"), Second("a")), (First("r1"), Second("b")), (First("r1"), Second("c")))) &&
      assertTrue(values.toSet == Set(1, 2, 3))
    }

    test("includes empty-second-component key and excludes other first components") {
      val hmap =
        HMap.empty[Schema]
          .updated["users"]((First("ns"), Second("")), 0)
          .updated["users"]((First("ns"), Second("k1")), 1)
          .updated["users"]((First("ns"), Second("k2")), 2)
          .updated["users"]((First("ns2"), Second("")), 100)
          .updated["users"]((First("ns2"), Second("k3")), 101)

      val nsResults = hmap.rangeByCompoundKeyPrefix["users"]((First("ns"), Second(""))).toList
      val nsKeys = nsResults.map(_._1).toSet
      val nsValues = nsResults.map(_._2).toSet

      val ns2Results = hmap.rangeByCompoundKeyPrefix["users"]((First("ns2"), Second(""))).toList
      val ns2Keys = ns2Results.map(_._1).toSet
      val ns2Values = ns2Results.map(_._2).toSet

      assertTrue(nsKeys == Set((First("ns"), Second("")), (First("ns"), Second("k1")), (First("ns"), Second("k2")))) &&
      assertTrue(nsValues == Set(0, 1, 2)) &&
      assertTrue(ns2Keys == Set((First("ns2"), Second("")), (First("ns2"), Second("k3")))) &&
      assertTrue(ns2Values == Set(100, 101))
    }

    test("works with unicode in first component and multiple seconds") {
      val first = "régiön-𝟙" // unicode characters
      val hmap =
        HMap.empty[Schema]
          .updated["users"]((First(first), Second("α")), 5)
          .updated["users"]((First(first), Second("β")), 6)
          .updated["users"]((First(first), Second("γ")), 7)
          .updated["users"]((First("other"), Second("δ")), 8)

      val results = hmap.rangeByCompoundKeyPrefix["users"]((First(first), Second(""))).toList

      assertTrue(results.length == 3) &&
      assertTrue(results.map(_._1).toSet == Set((First(first), Second("α")), (First(first), Second("β")), (First(first), Second("γ")))) &&
      assertTrue(results.map(_._2).toSet == Set(5, 6, 7))
    }
  }
end HMapRangeByCompoundKeyPrefixSpec
