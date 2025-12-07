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

  sealed trait TestInternalCommand extends Command
  object TestInternalCommand:
    case object NotifyAllSessions extends TestInternalCommand:
      type Response = Int // Returns number of sessions notified

  type TestResponse = Unit | Int
  type TestSchema = EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema[TestResponse, String, Nothing], TestSchema]

  // Minimal codecs
  import scodec.codecs.*
  given scodec.Codec[Unit] = provide(())
  given scodec.Codec[Int] = int32
  given scodec.Codec[TestResponse] = 
    discriminated[TestResponse].by(uint8)
      .typecase(0, summon[scodec.Codec[Unit]])
      .typecase(1, summon[scodec.Codec[Int]])
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

  class TestStateMachine extends SessionStateMachine[TestCommand, TestResponse, String, Nothing, TestSchema, TestInternalCommand]
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
      command: TestInternalCommand
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, command.Response & TestResponse] =
      command match
        case TestInternalCommand.NotifyAllSessions =>
          for
            state <- StateWriter.get[HMap[CombinedSchema]]
            // Get all sessions that have either metadata OR server requests (includes admin)
            metadataSessions = state.iterator["metadata"].map { case (sid, _) => sid }.toSet
            serverRequestSessions = state.iterator["serverRequests"].map { case ((sid, _), _) => sid }.toSet
            allSessions = (metadataSessions ++ serverRequestSessions).toList
            // Emit server request to each session
            _ <- StateWriter.foreach(allSessions) { sid =>
              StateWriter.log(ServerRequestForSession[String](sid, "internal-notification"))
            }
          yield allSessions.size.asInstanceOf[command.Response & TestResponse]

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
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing, TestInternalCommand]]
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
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing, TestInternalCommand]]
      val (state2, _) = sm.apply(expire).run(state1)

      // Verify session data cleaned
      val h2 = state2.asInstanceOf[HMap[CombinedSchema]]
      val noMetadata = h2.get["metadata"](sid).isEmpty
      val noCache = !h2.iterator["cache"].exists { case ((sess, _), _) => sess == sid }
      val noServerRequests = !h2.iterator["serverRequests"].exists { case ((sess, _), _) => sess == sid }
      val noLastId = h2.get["lastServerRequestId"](sid).isEmpty

      assertTrue(noMetadata && noCache && noServerRequests && noLastId)
    },
    test("InternalCommand notifies all active sessions without session context") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()

      // Create multiple sessions
      val create1 =
        SessionCommand.CreateSession[String, Nothing](now, SessionId("s1"), Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing, TestInternalCommand]]
      val (state1, _) = sm.apply(create1).run(state0)

      val create2 =
        SessionCommand.CreateSession[String, Nothing](now, SessionId("s2"), Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing, TestInternalCommand]]
      val (state2, _) = sm.apply(create2).run(state1)

      val create3 =
        SessionCommand.CreateSession[String, Nothing](now, SessionId("s3"), Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing, TestInternalCommand]]
      val (state3, _) = sm.apply(create3).run(state2)

      // Execute internal command to notify all sessions
      val internalCmd =
        SessionCommand.InternalCommand[TestInternalCommand, String](now, TestInternalCommand.NotifyAllSessions)
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing, TestInternalCommand]]
      val (state4, response) = sm.apply(internalCmd).run(state3)
      val (envelopes, numNotified) = response.asInstanceOf[(List[ServerRequestEnvelope[String]], Int)]

      // Verify response: should have notified 3 sessions (s1, s2, s3) + admin from session creations
      assertTrue(numNotified == 4) // s1, s2, s3, admin

      // Verify server requests were emitted to all sessions
      val h4 = state4.asInstanceOf[HMap[CombinedSchema]]
      val s1Notified = h4.iterator["serverRequests"].exists {
        case ((sess, _), pending) => sess == SessionId("s1") && pending.payload == "internal-notification"
      }
      val s2Notified = h4.iterator["serverRequests"].exists {
        case ((sess, _), pending) => sess == SessionId("s2") && pending.payload == "internal-notification"
      }
      val s3Notified = h4.iterator["serverRequests"].exists {
        case ((sess, _), pending) => sess == SessionId("s3") && pending.payload == "internal-notification"
      }
      val adminNotified = h4.iterator["serverRequests"].exists {
        case ((sess, _), pending) => sess == SessionId("admin") && pending.payload == "internal-notification"
      }

      assertTrue(s1Notified && s2Notified && s3Notified && adminNotified)
    }
  )
end SessionLifecycleSpec
