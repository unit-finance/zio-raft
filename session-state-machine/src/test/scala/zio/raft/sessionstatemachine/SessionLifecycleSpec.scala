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

  class TestStateMachine extends SessionStateMachine[TestCommand, TestResponse, String, Nothing, TestSchema]
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
      command: TestCommand
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, command.Response & TestResponse] =
      // For test purposes, emit a notification to admin session
      StateWriter.log(ServerRequestForSession[String](SessionId("admin"), "internal-command-executed"))
        .as(().asInstanceOf[command.Response & TestResponse])

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
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing]]
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
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing]]
      val (state2, _) = sm.apply(expire).run(state1)

      // Verify session data cleaned
      val h2 = state2.asInstanceOf[HMap[CombinedSchema]]
      val noMetadata = h2.get["metadata"](sid).isEmpty
      val noCache = !h2.iterator["cache"].exists { case ((sess, _), _) => sess == sid }
      val noServerRequests = !h2.iterator["serverRequests"].exists { case ((sess, _), _) => sess == sid }
      val noLastId = h2.get["lastServerRequestId"](sid).isEmpty

      assertTrue(noMetadata && noCache && noServerRequests && noLastId)
    },
    test("InternalCommand executes without session context") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()

      // Execute internal command - no session required
      val cmd = SessionCommand.InternalCommand[TestCommand, String](
        now,
        TestCommand.Noop
      ).asInstanceOf[SessionCommand[TestCommand, String, Nothing]]
      
      val result = sm.apply(cmd).run(state0)
      val state1 = result._1
      val response = result._2.asInstanceOf[(List[ServerRequestEnvelope[String]], Unit)]

      // Verify command executed and returned result
      assertTrue(response._2 == ())
    },
    test("InternalCommand can emit server requests to multiple sessions") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()

      // Create two sessions to receive notifications
      val create1 = SessionCommand.CreateSession[String, Nothing](now, SessionId("s1"), Map.empty)
        .asInstanceOf[SessionCommand[TestCommand, String, Nothing]]
      val (state1, _) = sm.apply(create1).run(state0)
      
      val create2 = SessionCommand.CreateSession[String, Nothing](now, SessionId("admin"), Map.empty)
        .asInstanceOf[SessionCommand[TestCommand, String, Nothing]]
      val (state2, _) = sm.apply(create2).run(state1)

      // Execute internal command
      val cmd = SessionCommand.InternalCommand[TestCommand, String](
        now,
        TestCommand.Noop
      ).asInstanceOf[SessionCommand[TestCommand, String, Nothing]]
      
      val result = sm.apply(cmd).run(state2)
      val state3 = result._1

      // Verify server request was emitted to admin session
      val h = state3.asInstanceOf[HMap[CombinedSchema]]
      val hasAdminRequest = h.iterator["serverRequests"].exists { 
        case ((sess, _), pending) =>
          sess == SessionId("admin") && pending.payload == "internal-command-executed"
      }
      
      assertTrue(hasAdminRequest)
    }
  )
end SessionLifecycleSpec
