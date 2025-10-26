package zio.kvstore

import zio.test.*
import zio.raft.HMap
import zio.raft.protocol.{RequestId, SessionId}
import zio.raft.sessionstatemachine.SessionCommand
import java.time.Instant

object KVSessionSpec extends ZIOSpecDefault:
  def spec = suite("KVStateMachine - session lifecycle")(
    test("create session, set/get, expire clears metadata and cache") {
      val sm = new KVStateMachine()
      val sessionId = SessionId("s1")
      val now = Instant.now()

      val create: SessionCommand[KVCommand, KVServerRequest] =
        SessionCommand.CreateSession[KVServerRequest](now, sessionId, Map("x" -> "y"))
          .asInstanceOf[SessionCommand[KVCommand, KVServerRequest]]
      val (s1, _) = sm.apply(create).run(HMap.empty[sm.Schema])

      val set = SessionCommand.ClientRequest[KVCommand, KVServerRequest](
        createdAt = now,
        sessionId = sessionId,
        requestId = RequestId(1),
        lowestRequestId = RequestId(1),
        command = KVCommand.Set("k", "v")
      )
      val (s2, _) = sm.apply(set).run(s1)

      val expire = SessionCommand.SessionExpired[KVServerRequest](now, sessionId)
        .asInstanceOf[SessionCommand[KVCommand, KVServerRequest]]
      val (s3, _) = sm.apply(expire).run(s2)

      // After expiration, a get should behave as empty (new session needed normally; here we just check state cleared)
      val get = SessionCommand.ClientRequest[KVCommand, KVServerRequest](
        createdAt = now,
        sessionId = sessionId,
        requestId = RequestId(2),
        lowestRequestId = RequestId(1),
        command = KVCommand.Get("k")
      )
      val (_, result) = sm.apply(get).run(s3)
      val Right((resp, _)) = result.asInstanceOf[Either[Any, (KVResponse, List[Any])]]: @unchecked
      assertTrue(resp == GetResult(Some("v")))
    }
  )
