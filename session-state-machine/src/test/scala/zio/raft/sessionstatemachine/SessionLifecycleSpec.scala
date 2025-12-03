package zio.raft.sessionstatemachine

import zio.test.*
import zio.raft.{Command, HMap, Index}
import zio.raft.protocol.SessionId
import java.time.Instant

object SessionLifecycleSpec extends ZIOSpecDefault:

  sealed trait TestCommand extends Command
  object TestCommand:
    case object Noop extends TestCommand:
      type Response = Unit

  type TestResponse = Unit
  type TestSchema = EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema[TestResponse, String, Nothing], TestSchema]

  // Minimal codecs
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

  class TestStateMachine extends SessionStateMachine[TestCommand, TestResponse, String, Nothing, TestSchema, Nothing]
      with ScodecSerialization[TestResponse, String, Nothing, TestSchema]:

    val codecs = summon[HMap.TypeclassMap[CombinedSchema, scodec.Codec]]

    protected def applyCommand(
      createdAt: Instant,
      sessionId: SessionId,
      cmd: TestCommand
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, cmd.Response & TestResponse] =
      StateWriter.succeed(().asInstanceOf[cmd.Response & TestResponse])

    protected def createSession(
      createdAt: Instant,
      sid: SessionId,
      caps: Map[String, String]
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, Unit] =
      // Emit a server request to another session (admin) upon session creation
      StateWriter.log(ServerRequestForSession[String](SessionId("admin"), s"created:${SessionId.unwrap(sid)}"))
        .as(())

    protected def handleSessionExpired(
      createdAt: Instant,
      sid: SessionId,
      capabilities: Map[String, String]
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, Unit] =
      // Emit a server request to another session (admin) upon session expiration
      StateWriter.log(ServerRequestForSession[String](SessionId("admin"), s"expired:${SessionId.unwrap(sid)}"))
        .as(())

    protected def applyInternalCommand(
      createdAt: Instant,
      command: Nothing
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, Nothing] =
      throw new UnsupportedOperationException("IC = Nothing, internal commands disabled")

    override def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
      false

  def spec = suite("Session lifecycle with cross-session server requests (T017)")(
    test("CreateSession emits admin request and SessionExpired cleans all session data") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()
      val sid = SessionId("s1")

      // Create session
      val create =
        SessionCommand.CreateSession[String, Nothing](now, sid, Map("k" -> "v"))
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing, Nothing]]
      val (state1, _) = sm.apply(create).run(state0)

      // Verify admin request exists
      val h1 = state1.asInstanceOf[HMap[CombinedSchema]]
      val hasAdminCreated = h1.iterator["serverRequests"].exists { case ((sess, _), pending) =>
        sess == SessionId("admin") && pending.payload == s"created:${SessionId.unwrap(sid)}"
      }
      assertTrue(hasAdminCreated)

      // Expire session
      val expire =
        SessionCommand.SessionExpired[String](now, sid)
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing, Nothing]]
      val (state2, _) = sm.apply(expire).run(state1)

      // Verify session data cleaned
      val h2 = state2.asInstanceOf[HMap[CombinedSchema]]
      val noMetadata = h2.get["metadata"](sid).isEmpty
      val noCache = !h2.iterator["cache"].exists { case ((sess, _), _) => sess == sid }
      val noServerRequests = !h2.iterator["serverRequests"].exists { case ((sess, _), _) => sess == sid }
      val noLastId = h2.get["lastServerRequestId"](sid).isEmpty

      assertTrue(noMetadata && noCache && noServerRequests && noLastId)
    }
    // TODO: Add InternalCommand tests with a separate TestStateMachine that uses IC parameter
  )
end SessionLifecycleSpec
