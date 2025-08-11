package zio.raft.stores.segmentedlog

import zio.raft.{Command, Index, CommandLogEntry, Term}
import zio.{Scope, ScopedRef, ZIO}

import scodec.Codec
import scodec.codecs.{constant, int64, uint16, uint32, int32, bool}

object internal:
  val entrySizeCodec = int32

  val signature: Codec[Unit] = constant(uint32.encode(0xdeadbeef).require)

  private def termCodec = int64.xmap(Term(_), _.value)
  private def indexCodec = int64.xmap(Index(_), _.value)

  def entryCodec[A <: Command](using codec: Codec[A]): Codec[CommandLogEntry[A]] =
    (codec :: termCodec :: indexCodec).as[CommandLogEntry[A]]
  def entriesCodec[A <: Command: Codec]: ChecksummedList[CommandLogEntry[A]] =
    new ChecksummedList[CommandLogEntry[A]](entryCodec)

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
