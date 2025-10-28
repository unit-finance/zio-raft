package zio.raft.sessionstatemachine

import zio.test.*
import scodec.Codec
import scodec.codecs.*
import zio.raft.sessionstatemachine.Codecs.given
import zio.raft.protocol.*
import java.time.Instant
import zio.raft.Command

object CodecsSpec extends ZIOSpecDefault:
  def spec =
    suiteAll("Session State Machine Codecs") {
      test("sessionMetadata round-trip") {
        val sm = SessionMetadata(Map("a" -> "b"), Instant.ofEpochMilli(1234L))
        val bits = summon[Codec[SessionMetadata]].encode(sm).require
        val decoded = summon[Codec[SessionMetadata]].decode(bits).require.value
        assertTrue(decoded == sm)
      }

      test("requestId round-trip") {
        val r = RequestId(42L)
        val bits = Codecs.requestIdCodec.encode(r).require
        val decoded = Codecs.requestIdCodec.decode(bits).require.value
        assertTrue(decoded == r)
      }

      test("pendingServerRequest round-trip") {
        given Codec[String] = utf8_32
        val psr = PendingServerRequest("payload", Instant.ofEpochMilli(999L))
        val bits = summon[Codec[PendingServerRequest[String]]].encode(psr).require
        val decoded = summon[Codec[PendingServerRequest[String]]].decode(bits).require.value
        assertTrue(decoded == psr)
      }

      suiteAll("sessionCommand discriminated union round-trips") {
        test("ClientRequest") {
          // define a concrete UC that extends zio.raft.Command and provide a codec for it
          case object DummyCmd extends Command:
            type Response = Unit
          given Codec[DummyCmd.type] = provide(DummyCmd)
          given Codec[Unit] = provide(())
          val cmd: SessionCommand[DummyCmd.type, Unit] = SessionCommand.ClientRequest[DummyCmd.type, Unit](
            createdAt = Instant.EPOCH,
            sessionId = SessionId.fromString("s-1"),
            requestId = RequestId(1L),
            lowestPendingRequestId = RequestId(0L),
            command = DummyCmd
          )
          val codec = summon[Codec[SessionCommand[DummyCmd.type, Unit]]]
          val bits = codec.encode(cmd).require
          val decoded = codec.decode(bits).require.value
          assertTrue(decoded == cmd)
        }

        test("ServerRequestAck") {
          // reuse Dummy UC type param for the union codec
          case object DummyCmd extends Command:
            type Response = Unit
          given Codec[DummyCmd.type] = provide(DummyCmd)
          given Codec[Unit] = provide(())
          val cmd = SessionCommand.ServerRequestAck[Unit](
            createdAt = Instant.EPOCH,
            sessionId = SessionId.fromString("s-2"),
            requestId = RequestId(2L)
          )
          val codec = summon[Codec[SessionCommand[DummyCmd.type, Unit]]]
          val bits = codec.encode(cmd).require
          val decoded = codec.decode(bits).require.value
          assertTrue(decoded == cmd)
        }

        test("CreateSession") {
          case object DummyCmd extends Command:
            type Response = Unit
          given Codec[DummyCmd.type] = provide(DummyCmd)
          given Codec[Unit] = provide(())
          val cmd = SessionCommand.CreateSession[Unit](
            createdAt = Instant.EPOCH,
            sessionId = SessionId.fromString("s-3"),
            capabilities = Map("k" -> "v")
          )
          val codec = summon[Codec[SessionCommand[DummyCmd.type, Unit]]]
          val bits = codec.encode(cmd).require
          val decoded = codec.decode(bits).require.value
          assertTrue(decoded == cmd)
        }

        test("SessionExpired") {
          case object DummyCmd extends Command:
            type Response = Unit
          given Codec[DummyCmd.type] = provide(DummyCmd)
          given Codec[Unit] = provide(())
          val cmd = SessionCommand.SessionExpired[Unit](
            createdAt = Instant.EPOCH,
            sessionId = SessionId.fromString("s-4")
          )
          val codec = summon[Codec[SessionCommand[DummyCmd.type, Unit]]]
          val bits = codec.encode(cmd).require
          val decoded = codec.decode(bits).require.value
          assertTrue(decoded == cmd)
        }

        test("GetRequestsForRetry") {
          case object DummyCmd extends Command:
            type Response = Unit
          given Codec[DummyCmd.type] = provide(DummyCmd)
          given Codec[Unit] = provide(())
          val cmd = SessionCommand.GetRequestsForRetry[Unit](
            createdAt = Instant.EPOCH,
            lastSentBefore = Instant.ofEpochMilli(500L)
          )
          val codec = summon[Codec[SessionCommand[DummyCmd.type, Unit]]]
          val bits = codec.encode(cmd).require
          val decoded = codec.decode(bits).require.value
          assertTrue(decoded == cmd)
        }
      }
    }
end CodecsSpec
