package zio.raft.stores.segmentedlog

import zio.raft.{Command, Index, Term}
import zio.{Scope, ScopedRef, ZIO}

import scodec.Codec
import scodec.codecs.{constant, int64, uint16, uint32, int32, bool, discriminated, uint8}
import zio.raft.LogEntry
import zio.raft.LogEntry.CommandLogEntry
import zio.raft.LogEntry.NoopLogEntry

object internal:
  val entrySizeCodec = int32

  val signature: Codec[Unit] = constant(uint32.encode(0xdeadbeef).require)

  def Version(v: Int): Codec[Unit] = constant(uint8.encode(v).require)

  private def termCodec = int64.xmap(Term(_), _.value)
  private def indexCodec = int64.xmap(Index(_), _.value)

  def logEntryCodec[A <: Command](using commandCodec: Codec[A]) = discriminated[LogEntry[A]]
    .by(uint8)
    .typecase(0, versionedCommandLogEntryCodec(commandCodec))
    .typecase(1, versionedNoopLogEntryCodec)

  def logEntriesCodec[A <: Command: Codec](using commandCodec: Codec[A]) =
    new ChecksummedList[LogEntry[A]](logEntryCodec[A](using commandCodec))

  private def versionedCommandLogEntryCodec[A <: Command](commandCodec: Codec[A]) =
    Codec(
      Version(1) ~> commandLogEntryCodecV1(commandCodec),
      uint8.flatMap { case 1 =>
        commandLogEntryCodecV1(commandCodec)
      }
    )

  private def commandLogEntryCodecV1[A <: Command](commandCodec: Codec[A]) =
    (commandCodec :: termCodec :: indexCodec).as[CommandLogEntry[A]]

  private def versionedNoopLogEntryCodec =
    Codec(
      Version(1) ~> noopLogEntryCodecV1,
      uint8.flatMap { case 1 =>
        noopLogEntryCodecV1
      }
    )

  private def noopLogEntryCodecV1 = (termCodec :: indexCodec).as[NoopLogEntry]

  val fileVersion = constant(uint16.encode(1).require)
  val fileHeaderCodec = (signature :: fileVersion).unit(((), ()))

  def isEntryCodec: Codec[Boolean] = bool(8)

  val segmentSuffix = ".log"

  class CurrentSegment[A <: Command](
      ref: ScopedRef[Option[OpenSegment[A]]]
  ):
    def switch(newFile: ZIO[Scope, Nothing, OpenSegment[A]]) =
      ref.set(newFile.asSome).flatMap(_ => get)

    def close() = ref.set(ZIO.scoped(ZIO.none))

    def get = ref.get.someOrFail(new IllegalStateException("No segment open")).orDie

  object CurrentSegment:
    def make[A <: Command](file: ZIO[Scope, Nothing, OpenSegment[A]]) =
      for {
        ref <- ScopedRef.fromAcquire(file.asSome)
      } yield new CurrentSegment(ref)
