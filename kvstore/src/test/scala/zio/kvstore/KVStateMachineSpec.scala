package zio.kvstore

import zio.test.*
import zio.raft.HMap
import zio.raft.protocol.{RequestId, SessionId}
import zio.raft.sessionstatemachine.SessionCommand
import java.time.Instant
import zio.kvstore.protocol.KVServerRequest

object KVStateMachineSpec extends ZIOSpecDefault:

  def spec = suite("KVStateMachine - idempotency")(
    test("duplicate Set with same requestId ignores new payload (cached)") {
      val sm = new KVStateMachine()
      val sessionId = SessionId("s1")
      val now = Instant.now()

      val create: SessionCommand[KVCommand, KVServerRequest] =
        SessionCommand.CreateSession[KVServerRequest](now, sessionId, Map.empty)
          .asInstanceOf[SessionCommand[KVCommand, KVServerRequest]]
      val (s1, _) = sm.apply(create).run(HMap.empty[sm.Schema])

      val set1 = SessionCommand.ClientRequest[KVCommand, KVServerRequest](
        createdAt = now,
        sessionId = sessionId,
        requestId = RequestId(1),
        lowestPendingRequestId = RequestId(1),
        command = KVCommand.Set("a", "v1")
      )
      val (s2, _) = sm.apply(set1).run(s1)

      // Same requestId but different payload – should return cached response of first, not apply second payload
      val set2 = SessionCommand.ClientRequest[KVCommand, KVServerRequest](
        createdAt = now,
        sessionId = sessionId,
        requestId = RequestId(1),
        lowestPendingRequestId = RequestId(1),
        command = KVCommand.Set("a", "v2")
      )
      val (s3, _) = sm.apply(set2).run(s2)

      val get = SessionCommand.ClientRequest[KVCommand, KVServerRequest](
        createdAt = now,
        sessionId = sessionId,
        requestId = RequestId(2),
        lowestPendingRequestId = RequestId(1),
        command = KVCommand.Get("a")
      )
      val (_, result) = sm.apply(get).run(s3)

      val Right((resp, _)) = result.asInstanceOf[Either[Any, (KVResponse, List[Any])]]: @unchecked
      assertTrue(resp == KVResponse.GetResult(Some("v1")))
    }
  )
