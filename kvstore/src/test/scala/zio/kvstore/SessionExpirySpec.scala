package zio.kvstore

import zio.test.*

object SessionExpirySpec extends ZIOSpecDefault:
  def spec =
    suiteAll("SessionExpirySpec")(
      test("Session expiry removes subscriptions") {
        assertTrue(false)
      }
    )
