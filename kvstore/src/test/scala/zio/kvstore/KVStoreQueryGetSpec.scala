package zio.kvstore

import zio.*
import zio.test.*

object KVStoreQueryGetSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment & TestEnvironment & Scope, Any] =
    suiteAll("kvstore GET via Query") {

      test("placeholder integration") {
        assertTrue(true)
      }
    }
}


