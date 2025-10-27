package zio.kvstore

import zio.test.*

object WatchSpec extends ZIOSpecDefault:
  def spec =
    suiteAll("WatchSpec") {
      test("Watch returns current value and updates") {
        assertTrue(false)
      }
      
      test("Duplicate watch is idempotent") {
        assertTrue(false)
      }
    }
