package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.{Command, HMap, Index}
import zio.raft.protocol.{SessionId, RequestId}
import java.time.Instant

object ResponseCachingSpec extends ZIOSpecDefault:

  sealed trait TestCommand extends Command
  object TestCommand:
    case class Increment(by: Int) extends TestCommand:
      type Response = Int

  type TestResponse = Int

  import zio.prelude.Newtype
  object CounterKey extends Newtype[String]
  type CounterKey = CounterKey.Type
  given HMap.KeyLike[CounterKey] = HMap.KeyLike.forNewtype(CounterKey)

  type TestSchema = ("counter", CounterKey, Int) *: EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema[TestResponse, String], TestSchema]

  val counterKey = CounterKey("value")

  // Minimal codecs for ScodecSerialization
  import scodec.codecs.*
  given scodec.Codec[Any] = scodec.Codec[String].upcast[Any]
  given scodec.Codec[Int] = int32
  import zio.raft.sessionstatemachine.Codecs.{sessionMetadataCodec, requestIdCodec, pendingServerRequestCodec}
  given scodec.Codec[PendingServerRequest[?]] =
    summon[scodec.Codec[PendingServerRequest[String]]].asInstanceOf[scodec.Codec[PendingServerRequest[?]]]

  class TestStateMachine extends SessionStateMachine[TestCommand, TestResponse, String, TestSchema]
      with ScodecSerialization[TestResponse, String, TestSchema]:

    val codecs = summon[HMap.TypeclassMap[CombinedSchema, scodec.Codec]]
    var callCount = 0

    protected def applyCommand(
      createdAt: Instant,
      sessionId: SessionId,
      cmd: TestCommand
    ): StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], cmd.Response & TestResponse] =
      callCount += 1
      cmd match
        case TestCommand.Increment(by) =>
          for
            state <- StateWriter.get[HMap[CombinedSchema]]
            current = state.get["counter"](counterKey).getOrElse(0)
            newValue = current + by
            _ <- StateWriter.set(state.updated["counter"](counterKey, newValue))
          yield newValue.asInstanceOf[cmd.Response & TestResponse]

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

  def spec = suite("Response caching (PC-2)")(
    test("First request caches, second returns cached without calling applyCommand") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()
      val sessionId = SessionId("s1")

      val createCmd =
        SessionCommand.CreateSession[String](now, sessionId, Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String]]
      val (state1, _) = sm.apply(createCmd).run(state0)

      val cmd1: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(1), RequestId(1), TestCommand.Increment(10))
      val (state2, result1) = sm.apply(cmd1).run(state1)
      val Right((response1, _)) = (result1.asInstanceOf[Either[RequestError, (Int, List[Any])]]): @unchecked

      assertTrue(sm.callCount == 1) && assertTrue(response1 == 10)

      val cmd2: SessionCommand[TestCommand, String] =
        SessionCommand.ClientRequest(now, sessionId, RequestId(1), RequestId(1), TestCommand.Increment(999))
      val (_, result2) = sm.apply(cmd2).run(state2)
      val Right((response2, _)) = (result2.asInstanceOf[Either[RequestError, (Int, List[Any])]]): @unchecked

      assertTrue(
        sm.callCount == 1 &&
          response2 == 10
      )
    }
  )
end ResponseCachingSpec
