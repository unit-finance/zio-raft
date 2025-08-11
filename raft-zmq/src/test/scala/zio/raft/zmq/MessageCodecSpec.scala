package zio.raft.zmq

import zio.raft.*
import zio.test.*

import scodec.bits.BitVector
import scodec.Codec

object MessageCodecSpec extends ZIOSpecDefault:

  case class TestCommand(value: String) extends Command:
    type Response = String

  object TestCommand:
    given codec: Codec[TestCommand] = scodec.codecs.utf8_32.as[TestCommand]

  override def spec = test("encode and decode append entries"):
    val appendEntries = AppendEntriesRequest(
      term = Term.zero,
      leaderId = MemberId("leader"),
      previousIndex = Index.zero,
      previousTerm = Term.zero,
      entries = List(CommandLogEntry(TestCommand("test"), Term.zero, Index.one)),
      leaderCommitIndex = Index.zero
    )

    val codec = RpcMessageCodec.codec[TestCommand]

    val encoded = codec.encode(appendEntries).require.toByteArray

    val decoded = codec.decode(BitVector(encoded)).require.value

    assertTrue(decoded == appendEntries)
