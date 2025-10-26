package zio.raft

import zio.test.ZIOSpecDefault
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.assertTrue

object InsertSortListSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("InsertSortList Spec")(
    test("sorted insertion in different positions") {
      val empty = InsertSortList.empty[Int]

      val withOne = empty.withSortedInsert(5)
      val withTwo = withOne.withSortedInsert(3)
      val withThree = withTwo.withSortedInsert(7)
      val withFour = withThree.withSortedInsert(6)

      assertTrue(
        withOne.toList == List(5),
        withTwo.toList == List(3, 5),
        withThree.toList == List(3, 5, 7),
        withFour.toList == List(3, 5, 6, 7),
        withFour.last == 7
      )
    },
    test("order preservation with multiple insertions") {
      val unsortedInts = List(8, 3, 1, 6, 4, 7, 5, 2)
      val sortedList = unsortedInts.foldLeft(InsertSortList.empty[Int]) { (acc, item) =>
        acc.withSortedInsert(item)
      }

      val unsortedStrings = List("zebra", "apple", "banana", "cherry")
      val sortedStringList = unsortedStrings.foldLeft(InsertSortList.empty[String]) { (acc, item) =>
        acc.withSortedInsert(item)
      }

      assertTrue(
        sortedList.toList == List(1, 2, 3, 4, 5, 6, 7, 8),
        sortedStringList.toList == List("apple", "banana", "cherry", "zebra"),
        !sortedList.isEmpty,
        !sortedStringList.isEmpty
      )
    },
    test("edge cases and duplicates") {
      val list = InsertSortList.empty[Int]
        .withSortedInsert(5)
        .withSortedInsert(5) // duplicate
        .withSortedInsert(3)
        .withSortedInsert(5) // another duplicate
        .withSortedInsert(7)

      val singleElement = InsertSortList.empty[String].withSortedInsert("hello")

      assertTrue(
        list.toList == List(3, 5, 5, 5, 7),
        list.last == 7,
        list.size == 5,
        singleElement.toList == List("hello"),
        singleElement.last == "hello",
        !singleElement.isEmpty
      )
    },
    test("additional methods - span, ++, and iterator") {
      val list1 = InsertSortList.empty[Int]
        .withSortedInsert(1)
        .withSortedInsert(3)
        .withSortedInsert(5)
        .withSortedInsert(7)

      val list2 = InsertSortList.empty[Int]
        .withSortedInsert(2)
        .withSortedInsert(4)

      val (lessThanFive, fiveAndAbove) = list1.span(_ < 5)
      val concatenated = list1 ++ list2
      val iteratorList = list1.iterator.toList

      assertTrue(
        lessThanFive.toList == List(1, 3),
        fiveAndAbove.toList == List(5, 7),
        concatenated.toList == List(1, 3, 5, 7, 2, 4), // Note: ++ doesn't maintain sort order
        iteratorList == List(1, 3, 5, 7),
        list1.iterator.hasNext,
        InsertSortList.empty[Int].iterator.isEmpty
      )
    }
  )
end InsertSortListSpec
