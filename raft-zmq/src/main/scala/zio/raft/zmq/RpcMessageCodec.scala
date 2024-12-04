package zio.raft.zmq

import zio.Chunk
import zio.raft.*
import zio.raft.AppendEntriesResult.Success

import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs.{bool, bytes, discriminated, int32, int64, listOfN, optional, uint8, utf8_32, variableSizeBytes}

object RpcMessageCodec:
  def codec[A <: Command](using commandCodec: Codec[A]) : Codec[RPCMessage[A]] = discriminated[RPCMessage[A]]
    .by(uint8)
    .typecase(0, heartbeatCodec[A])
    .typecase(1, heartbeatResponseCodec[A])
    .typecase(2, appendEntriesCodec(commandCodec))
    .typecase(3, appendEntriesResultCodec[A])
    .typecase(4, requestVoteRequestCodec[A])
    .typecase(5, requestVoteResultCodec[A])
    .typecase(6, installSnapshotCodec[A])
    .typecase(7, installSnapshotResultCodec[A])

  private def termCodec = int64.xmap(Term(_), _.value)
  private def indexCodec = int64.xmap(Index(_), _.value)
  private def memberIdCodec = utf8_32.xmap(MemberId(_), _.value)

  // We can define better conversion between Chunk and ByteVector
  private def chunkCodec =
    variableSizeBytes(int32, bytes).xmap(bv => Chunk.fromIterable(bv.toIterable), c => ByteVector(c.toIterable))

  private def logEntryCodec[A <: Command](commandCodec: Codec[A]) =
    (commandCodec :: termCodec :: indexCodec).as[LogEntry[A]]

  private def entriesCodec[A <: Command](commandCodec: Codec[A]): Codec[List[LogEntry[A]]] =
    listOfN(int32, logEntryCodec(commandCodec))

  private def heartbeatCodec[A <: Command] = (termCodec :: memberIdCodec :: indexCodec).as[HeartbeatRequest[A]]

  private def heartbeatResponseCodec[A <: Command] = (memberIdCodec :: termCodec).as[HeartbeatResponse[A]]

  private def appendEntriesCodec[A <: Command](commandCodec: Codec[A]) =
    (termCodec :: memberIdCodec :: indexCodec :: termCodec :: entriesCodec(commandCodec) :: indexCodec)
      .as[AppendEntriesRequest[A]]

  private def appendEntriesResultCodec[A <: Command] = discriminated[AppendEntriesResult[A]]
    .by(uint8)
    .typecase(0, (memberIdCodec :: termCodec :: indexCodec).as[AppendEntriesResult.Success[A]])
    .typecase(
      1,
      (memberIdCodec :: termCodec :: indexCodec :: optional(bool(8), termCodec :: indexCodec))
        .as[AppendEntriesResult.Failure[A]]
    )

  private def requestVoteRequestCodec[A <: Command] =
    (termCodec :: memberIdCodec :: indexCodec :: termCodec).as[RequestVoteRequest[A]]

  private def requestVoteResultCodec[A <: Command] = discriminated[RequestVoteResult[A]]
    .by(uint8)
    .typecase(0, (memberIdCodec :: termCodec).as[RequestVoteResult.Rejected[A]])
    .typecase(1, (memberIdCodec :: termCodec).as[RequestVoteResult.Granted[A]])

  private def installSnapshotCodec[A <: Command] =
    (termCodec :: memberIdCodec :: indexCodec :: termCodec :: int64 :: bool(8) :: chunkCodec)
      .as[InstallSnapshotRequest[A]]

  private def installSnapshotResultCodec[A <: Command] = discriminated[InstallSnapshotResult[A]]
    .by(uint8)
    .typecase(0, (memberIdCodec :: termCodec :: indexCodec :: bool(8)).as[InstallSnapshotResult.Success[A]])
    .typecase(1, (memberIdCodec :: termCodec :: indexCodec).as[InstallSnapshotResult.Failure[A]])
