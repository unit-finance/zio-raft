package zio.raft.stores.segmentedlog

import zio.raft.{Command, Index, Term}
import zio.{Scope, ScopedRef, ZIO}

import scodec.Codec
import scodec.codecs.{constant, int64, uint16, uint32, int32, bool, discriminated, uint8, listOfN}
import zio.raft.LogEntry
import zio.raft.LogEntry.CommandLogEntry
import zio.raft.LogEntry.NoopLogEntry

object internal:
  val entrySizeCodec = int32

  val signature: Codec[Unit] = constant(uint32.encode(0xdeadbeef).require)

  private def termCodec = int64.xmap(Term(_), _.value)
  private def indexCodec = int64.xmap(Index(_), _.value)

  // TODO (Eran): improve this codec? maybe move it to where LogEntry is defined?
  def logEntryCodec[A <: Command](using commandCodec: Codec[A]) = discriminated[LogEntry]
    .by(uint8)
    .typecase(0, commandLogEntryCodec(commandCodec))
    .typecase(1, noopLogEntryCodec)

  def logEntriesCodec[A <: Command: Codec](using commandCodec: Codec[A]) =
    new ChecksummedList[LogEntry](logEntryCodec[A](using commandCodec))

  private def commandLogEntryCodec[A <: Command](commandCodec: Codec[A]) =
    (commandCodec :: termCodec :: indexCodec).as[CommandLogEntry[A]]

  private def noopLogEntryCodec = (termCodec :: indexCodec).as[NoopLogEntry]

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
