package zio.kvstore

import zio.test.*

object DeliverySpec extends ZIOSpecDefault:
  def spec =
    suiteAll("DeliverySpec")(
      test("All updates delivered, no coalescing") {
        assertTrue(false)
      }
    )
