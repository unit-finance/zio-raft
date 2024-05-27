package zio.raft.zmq

import zio.test.*
import zio.raft.*
import scodec.bits.BitVector
import zio.Scope

object MessageCodecSpec extends ZIOSpecDefault:

  case class TestCommand(value: String) extends Command:
    type Response = String

  object TestCommand:
    val codec = scodec.codecs.utf8_32.as[TestCommand]

  override def spec = test("encode and decode append entries"):
    val appendEntries = AppendEntriesRequest(
      term = Term.zero,
      leaderId = MemberId("leader"),
      previousIndex = Index.zero,
      previousTerm = Term.zero,
      entries = List(LogEntry(TestCommand("test"), Term.zero, Index.one)),
      leaderCommitIndex = Index.zero
    )

    val codec = RpcMessageCodec.codec[TestCommand](TestCommand.codec)

    val encoded = codec.encode(appendEntries).require.toByteArray

    val decoded = codec.decode(BitVector(encoded)).require.value

    assertTrue(decoded == appendEntries)
