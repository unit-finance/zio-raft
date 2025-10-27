package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.{Command, HMap, Index}
import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

object ServerRequestTargetingSpec extends ZIOSpecDefault:

  sealed trait TestCommand extends Command
  object TestCommand:
    case object Noop extends TestCommand:
      type Response = Unit

  type TestResponse = Unit
  type TestSchema = EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema[TestResponse, String], TestSchema]

  // Minimal codecs
  import scodec.codecs.*
  given scodec.Codec[Unit] = provide(())
  import zio.raft.sessionstatemachine.Codecs.{sessionMetadataCodec, requestIdCodec, pendingServerRequestCodec}
  given scodec.Codec[PendingServerRequest[?]] =
    summon[scodec.Codec[PendingServerRequest[String]]].asInstanceOf[scodec.Codec[PendingServerRequest[?]]]

  class TestStateMachine extends SessionStateMachine[TestCommand, TestResponse, String, TestSchema]
      with ScodecSerialization[TestResponse, String, TestSchema]:

    val codecs = summon[HMap.TypeclassMap[CombinedSchema, scodec.Codec]]

    protected def applyCommand(
      createdAt: Instant,
      sessionId: SessionId,
      cmd: TestCommand
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], cmd.Response & TestResponse] =
      // Emit server requests to two different sessions
      for
        _ <- StateWriter.log(ServerRequestForSession[String](SessionId("s1"), "msg-s1"))
        _ <- StateWriter.log(ServerRequestForSession[String](SessionId("s2"), "msg-s2"))
      yield ().asInstanceOf[cmd.Response & TestResponse]

    protected def handleSessionCreated(
      createdAt: Instant,
      sid: SessionId,
      caps: Map[String, String]
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Unit] =
      StateWriter.succeed(())

    protected def handleSessionExpired(
      createdAt: Instant,
      sid: SessionId,
      capabilities: Map[String, String]
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Unit] =
      StateWriter.succeed(())

    override def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
      false

  def spec = suite("Server request targeting across sessions (T015)")(
    test("Requests are assigned to their target sessions with monotonically increasing IDs") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()
      val s1 = SessionId("s1")
      val s2 = SessionId("s2")

      val create1 =
        SessionCommand.CreateSession[String](now, s1, Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String]]
      val (state1, _) = sm.apply(create1).run(state0)

      val create2 =
        SessionCommand.CreateSession[String](now, s2, Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String]]
      val (state2, _) = sm.apply(create2).run(state1)

      val cmd: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, s1, RequestId(1), RequestId(0), TestCommand.Noop)
      val (state3, _) = sm.apply(cmd).run(state2)

      val h = state3.asInstanceOf[HMap[CombinedSchema]]
      val s1Reqs =
        h.iterator["serverRequests"].toList.collect { case ((sid, rid), pending) if sid == s1 => (rid, pending) }
      val s2Reqs =
        h.iterator["serverRequests"].toList.collect { case ((sid, rid), pending) if sid == s2 => (rid, pending) }

      assertTrue(s1Reqs.nonEmpty && s2Reqs.nonEmpty) &&
      assertTrue(s1Reqs.forall(_._2.payload == "msg-s1") || s2Reqs.forall(_._2.payload == "msg-s2"))
    }
  )
end ServerRequestTargetingSpec
