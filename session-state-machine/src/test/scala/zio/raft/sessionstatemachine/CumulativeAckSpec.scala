package zio.raft.sessionstatemachine

import zio.test.*
import zio.raft.{Command, HMap, Index}
import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

object CumulativeAckSpec extends ZIOSpecDefault:

  sealed trait TestCommand extends Command
  object TestCommand:
    case object Noop extends TestCommand:
      type Response = Unit

  type TestResponse = Unit

  type TestSchema = EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema[TestResponse, String, Nothing], TestSchema]

  // Minimal codecs for ScodecSerialization
  import scodec.codecs.*
  given scodec.Codec[Unit] = provide(())
  // Codec for Either[Nothing, TestResponse] to satisfy cache value type
  given scodec.Codec[Either[Nothing, TestResponse]] =
    summon[scodec.Codec[TestResponse]].exmap[Either[Nothing, TestResponse]](
      r => scodec.Attempt.successful(Right(r)),
      (e: Either[Nothing, TestResponse]) =>
        e match
          case Right(r) => scodec.Attempt.successful(r)
          case Left(_)  => scodec.Attempt.failure(scodec.Err("Left (Nothing) is not encodable/decodable"))
    )
  import zio.raft.sessionstatemachine.Codecs.{sessionMetadataCodec, requestIdCodec, pendingServerRequestCodec}
  given scodec.Codec[PendingServerRequest[?]] =
    summon[scodec.Codec[PendingServerRequest[String]]].asInstanceOf[scodec.Codec[PendingServerRequest[?]]]

  class TestStateMachine extends SessionStateMachine[TestCommand, TestResponse, String, Nothing, TestSchema]
      with ScodecSerialization[TestResponse, String, Nothing, TestSchema]:

    val codecs = summon[HMap.TypeclassMap[CombinedSchema, scodec.Codec]]

    protected def applyCommand(
      createdAt: Instant,
      sessionId: SessionId,
      cmd: TestCommand      
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, cmd.Response & TestResponse] =
      StateWriter.succeed(().asInstanceOf[cmd.Response & TestResponse])

    protected def handleSessionCreated(
      createdAt: Instant,
      sid: SessionId,
      caps: Map[String, String]      
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, Unit] =
      StateWriter.succeed(())

    protected def handleSessionExpired(
      createdAt: Instant,
      sid: SessionId,
      capabilities: Map[String, String]      
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, Unit] =
      StateWriter.succeed(())

    override def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
      false

  def spec = suite("Cumulative Ack (PC-3)")(
    test("Ack N removes all pending requests with id <= N (inclusive)") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()
      val sessionId = SessionId("s1")

      val createCmd =
        SessionCommand.CreateSession[String](now, sessionId, Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing]]
      val (state1, _) = sm.apply(createCmd).run(state0)

      // Seed pending server requests manually (IDs 1..5)
      val pending1 = PendingServerRequest(payload = "a", lastSentAt = now)
      val pending2 = PendingServerRequest(payload = "b", lastSentAt = now)
      val pending3 = PendingServerRequest(payload = "c", lastSentAt = now)
      val pending4 = PendingServerRequest(payload = "d", lastSentAt = now)
      val pending5 = PendingServerRequest(payload = "e", lastSentAt = now)

      val s1 = state1.asInstanceOf[HMap[CombinedSchema]]
      val s2 = s1
        .updated["serverRequests"]((sessionId, RequestId(1)), pending1)
        .updated["serverRequests"]((sessionId, RequestId(2)), pending2)
        .updated["serverRequests"]((sessionId, RequestId(3)), pending3)
        .updated["serverRequests"]((sessionId, RequestId(4)), pending4)
        .updated["serverRequests"]((sessionId, RequestId(5)), pending5)
        .updated["lastServerRequestId"](sessionId, RequestId(5))
      val state2 = s2.asInstanceOf[HMap[sm.Schema]]

      // Acknowledge up to RequestId(1) should remove first batch
      val ack1: SessionCommand[TestCommand, String, Nothing] =
        SessionCommand.ServerRequestAck[String](now, sessionId, RequestId(1))
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing]]
      val (state3, _) = sm.apply(ack1).run(state2)

      val pendingAfterAck1 = state3.asInstanceOf[HMap[CombinedSchema]].iterator["serverRequests"].toList
      assertTrue(pendingAfterAck1.forall { case ((sid, rid), _) =>
        sid == sessionId && rid.isGreaterThan(RequestId(1))
      }) &&
      assertTrue(pendingAfterAck1.size == 4)
    }
  )
end CumulativeAckSpec
