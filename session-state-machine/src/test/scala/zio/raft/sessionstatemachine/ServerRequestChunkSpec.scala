package zio.raft.sessionstatemachine

import zio.test.*
import zio.test.Assertion.*
import zio.raft.{Command, HMap, Index}
import zio.raft.protocol.{SessionId, RequestId}
import zio.Chunk
import java.time.Instant

object ServerRequestChunkSpec extends ZIOSpecDefault:

  sealed trait TestCommand extends Command
  object TestCommand:
    case class Emit(count: Int) extends TestCommand:
      type Response = Int

  type TestResponse = Int
  type TestSchema = EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema[TestResponse, String, Nothing], TestSchema]

  // Minimal codecs
  import scodec.codecs.*
  given scodec.Codec[Int] = int32
  // Codec for Either[Nothing, TestResponse] to satisfy cache value type
  given scodec.Codec[Either[Nothing, TestResponse]] =
    summon[scodec.Codec[TestResponse]].as[Right[Nothing, TestResponse]].upcast[Either[Nothing, TestResponse]]
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
      cmd match
        case TestCommand.Emit(count) =>
          // Log a Chunk of server requests in order 1..count to current session
          val requests =
            Chunk.fromIterable(1 to count).map(i => ServerRequestForSession[String](SessionId("s1"), s"p$i"))
          if requests.isEmpty then
            StateWriter.succeed(count.asInstanceOf[cmd.Response & TestResponse])
          else
            val init: StateWriter[HMap[CombinedSchema], ServerRequestForSession[String], Nothing, Unit] =
              StateWriter.get[HMap[CombinedSchema]].as(())
            val all = requests.foldLeft(init) { (acc, r) => acc.flatMap(_ => StateWriter.log(r)) }
            all.as(count.asInstanceOf[cmd.Response & TestResponse])

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

  def spec = suite("Server request chunk handling (T016)")(
    test("Chunk order and size preserved through pipeline") {
      val sm = new TestStateMachine()
      val state0 = HMap.empty[sm.Schema]
      val now = Instant.now()
      val s1 = SessionId("s1")

      val create =
        SessionCommand.CreateSession[String](now, s1, Map.empty)
          .asInstanceOf[SessionCommand[TestCommand, String, Nothing]]
      val (state1, _) = sm.apply(create).run(state0)

      val cmd: SessionCommand[TestCommand, String, Nothing] =
        SessionCommand.ClientRequest(now, s1, RequestId(1), RequestId(0), TestCommand.Emit(5))
      val (state2, result) = sm.apply(cmd).run(state1)
      val Right((resp, envelopes)) =
        (result.asInstanceOf[Either[RequestError[Nothing], (Int, List[ServerRequestEnvelope[String]])]]): @unchecked

      val h = state2.asInstanceOf[HMap[CombinedSchema]]
      val s1Reqs = h.iterator["serverRequests"].toList.collect {
        case ((sid, rid), pending) if sid == s1 => (rid, pending.payload)
      }.sortBy(_._1.value)

      assertTrue(resp == 5) &&
      assertTrue(envelopes.length == 5) &&
      assertTrue(s1Reqs.map(_._2) == List("p1", "p2", "p3", "p4", "p5"))
    }
  )
end ServerRequestChunkSpec
