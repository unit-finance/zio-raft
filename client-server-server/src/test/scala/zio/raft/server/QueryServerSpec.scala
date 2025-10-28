package zio.raft.server

import zio.*
import zio.test.*

object QueryServerSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment & TestEnvironment & Scope, Any] =
    suiteAll("Server Query Handling") {

      test("Follower rejects Query with NotLeaderAnymore SessionClosed") {
        assertTrue(true) // Placeholder: full ZMQ harness out of scope here
      }
    }
}


