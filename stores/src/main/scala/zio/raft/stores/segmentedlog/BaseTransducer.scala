package zio.raft.stores.segmentedlog

import scala.annotation.tailrec

import zio.raft.stores.segmentedlog.BaseTransducer.*
import zio.raft.stores.segmentedlog.internal.*
import zio.stream.ZPipeline
import zio.{Chunk, ChunkBuilder, Ref, ZIO}

import scodec.Attempt.{Failure, Successful}
import scodec.Err
import scodec.bits.BitVector
import scodec.bits.crc.crc32Builder
import scodec.bits.crc.CrcBuilder
import scodec.codecs.{bits}
import zio.raft.Index

class BaseTransducer(ref: Ref[BaseTransducer.State], validateChecksum: Boolean): 
  def apply(chunk: Option[Chunk[Byte]]) =
    ref.get.flatMap { state =>
      chunk match
        case None => ZIO.succeed(Chunk.empty) // TODO: do we allow remainder to be non-empty?
        case Some(newBytes) =>
          val newBits = BitVector(newBytes)
          process(ChunkBuilder.make(), state.withNewBits(newBits)).map(_.result())
    }

  @tailrec private def process(
      results: ChunkBuilder[BaseTransducer.Result],
      state: BaseTransducer.State
  ): ZIO[Any, Throwable, ChunkBuilder[BaseTransducer.Result]] =
    state match
      case ReadFileHeader(index, bits) =>
        fileHeaderCodec.decode(bits) match
          case Successful(decodeResult) =>
            process(results, ReadRecordType(headerSize, index, decodeResult.remainder, ChunkBuilder.make(), crc32Builder))
          case Failure(e: Err.InsufficientBits) =>
            ref.set(ReadFileHeader(index, bits)).as(results)
          case Failure(comp: Err.Composite) if comp.errs.exists(_.isInstanceOf[Err.InsufficientBits]) =>
            ref.set(ReadFileHeader(index, bits)).as(results)
          case f: Failure =>
            ZIO.fail(new Throwable(s"Error decoding segement file header: ${f.cause.messageWithContext}"))
      case ReadRecordType(offset, index, bits, chunkBuilder, crcBuilder) =>
        isEntryCodec.decode(bits) match
          case Successful(decodeResult) =>
            if (decodeResult.value)
              process(results, ReadSize(offset, index, decodeResult.remainder, chunkBuilder, crcBuilder))
            else process(results, ReadChecksum(offset, index, decodeResult.remainder, chunkBuilder, crcBuilder))
          case Failure(e: Err.InsufficientBits) =>
            ref.set(ReadRecordType(offset, index,bits, chunkBuilder, crcBuilder)).as(results)
          case Failure(comp: Err.Composite) if comp.errs.exists(_.isInstanceOf[Err.InsufficientBits]) =>
            ref.set(ReadRecordType(offset,index, bits, chunkBuilder, crcBuilder)).as(results)
          case f: Failure =>
            ZIO.fail(new Throwable(s"Error decoding segment record type: ${f.cause.messageWithContext}"))
      case ReadSize(offset, index, bits, chunkBuilder, crcBuilder) =>
        entrySizeCodec.decode(bits) match
          case Successful(decodeResult) =>
            process(
              results,
              ReadBody(offset, index, decodeResult.remainder, decodeResult.value, chunkBuilder, crcBuilder)
            )
          case Failure(e: Err.InsufficientBits) =>
            ref.set(ReadSize(offset, index, bits, chunkBuilder, crcBuilder)).as(results)
          case Failure(comp: Err.Composite) if comp.errs.exists(_.isInstanceOf[Err.InsufficientBits]) =>
            ref.set(ReadSize(offset, index, bits, chunkBuilder, crcBuilder)).as(results)
          case f: Failure => ZIO.fail(new Throwable(s"Error decoding segment entry: ${f.cause.messageWithContext}"))
      case ReadBody(offset, index, bitsVector, size, chunkBuilder, crcBuilder) =>
        bits(size * 8).decode(bitsVector) match
          case Successful(result) =>
            // We discard the remainder because we know we've read exactly the right number of bits
            process(
              results,
              ReadRecordType(
                offset + isEntrySize + sizeSize + size,
                index.plusOne, 
                result.remainder,
                chunkBuilder.addOne(BaseTransducer.Result.Record(offset, index, result.value)),
                if validateChecksum then crcBuilder.updated(result.value) else crcBuilder
              )
            )
          case Failure(e: Err.InsufficientBits) =>
            ref.set(ReadBody(offset, index, bitsVector, size, chunkBuilder, crcBuilder)).as(results)
          case Failure(comp: Err.Composite) if comp.errs.exists(_.isInstanceOf[Err.InsufficientBits]) =>
            ref.set(ReadBody(offset, index, bitsVector, size, chunkBuilder, crcBuilder)).as(results)
          case f: Failure =>
            ZIO.fail(new Throwable(s"Error occurred: ${f.cause.messageWithContext}"))
      case ReadChecksum(offset, index, bitsVector, chunkBuilder, crcBuilder) =>
        bits(checksumSize * 8).decode(bitsVector) match
          case Successful(decodeResult) =>
            if validateChecksum then
              val actual = crcBuilder.result
              if actual == decodeResult.value then
                process(
                  results.addAll(chunkBuilder.addOne(BaseTransducer.Result.Checksum(offset, true)).result()),
                  ReadRecordType(offset + isEntrySize + checksumSize, index, decodeResult.remainder, ChunkBuilder.make(), crc32Builder)
                )
              else
                process(
                  results.addAll(chunkBuilder.addOne(BaseTransducer.Result.Checksum(offset, false)).result()),
                  ReadRecordType(offset + isEntrySize + checksumSize, index, decodeResult.remainder, ChunkBuilder.make(), crc32Builder)
                )
            else
              process(
                results.addAll(chunkBuilder.addOne(BaseTransducer.Result.Checksum(offset, true)).result()),
                ReadRecordType(offset + isEntrySize + checksumSize, index, decodeResult.remainder, ChunkBuilder.make(), crc32Builder)
              )
          case Failure(e: Err.InsufficientBits) =>
            ref.set(ReadChecksum(offset, index, bitsVector, chunkBuilder, crcBuilder)).as(results)
          case Failure(comp: Err.Composite) if comp.errs.exists(_.isInstanceOf[Err.InsufficientBits]) =>
            ref.set(ReadChecksum(offset, index, bitsVector, chunkBuilder, crcBuilder)).as(results)
          case f: Failure =>
            ZIO.fail(new Throwable(s"Error decoding checksum: ${f.cause.messageWithContext}"))
object BaseTransducer:
  val headerSize = fileHeaderCodec.sizeBound.exact.get / 8
  val sizeSize = entrySizeCodec.sizeBound.exact.get / 8
  val isEntrySize = isEntryCodec.sizeBound.exact.get / 8
  val checksumSize = 4  

  sealed trait Result:
    val offset: Long
  object Result:
    case class Record(offset: Long, index: Index, payload: BitVector) extends Result
    case class Checksum(offset: Long, matched: Boolean) extends Result

  sealed trait State:
    val remainder: BitVector
    val offset: Long
    val index: Index

    def withNewBits(newBits: BitVector): State =
      this match
        case ReadFileHeader(index, remainder) => ReadFileHeader(index, remainder ++ newBits)
        case ReadRecordType(offset, index,remainder, chunkBuilder, crcBuilder) =>
          ReadRecordType(offset, index,remainder ++ newBits, chunkBuilder, crcBuilder)
        case ReadSize(offset, index,remainder, chunkBuilder, crcBuilder) =>
          ReadSize(offset, index,remainder ++ newBits, chunkBuilder, crcBuilder)
        case ReadBody(offset, index,remainder, size, chunkBuilder, crcBuilder) =>
          ReadBody(offset, index,remainder ++ newBits, size, chunkBuilder, crcBuilder)
        case ReadChecksum(offset, index,remainder, chunkBuilder, crcBuilder) =>
          ReadChecksum(offset, index,remainder ++ newBits, chunkBuilder, crcBuilder)

  case class ReadFileHeader(index: Index, remainder: BitVector) extends State:
    val offset: Long = 0L
  case class ReadRecordType(
      offset: Long,
      index: Index,
      remainder: BitVector,
      chunkBuilder: ChunkBuilder[Result],
      crcBuilder: CrcBuilder[BitVector]
  ) extends State
  case class ReadSize(
      offset: Long,
      index: Index,
      remainder: BitVector,
      chunkBuilder: ChunkBuilder[Result],
      crcBuilder: CrcBuilder[BitVector]
  ) extends State
  case class ReadBody(
      offset: Long,
      index: Index,
      remainder: BitVector,
      size: Int,
      chunkBuilder: ChunkBuilder[Result],
      crcBuilder: CrcBuilder[BitVector]
  ) extends State
  case class ReadChecksum(
      offset: Long,
      index: Index,
      remainder: BitVector,
      chunkBuilder: ChunkBuilder[Result],
      crcBuilder: CrcBuilder[BitVector]
  ) extends State

  def make[A](firstIndex: Index, validateChecksum: Boolean) =
    ZPipeline.fromPush(
      Ref
        .make[State](ReadFileHeader(firstIndex, BitVector.empty))
        .map(ref => new BaseTransducer(ref, validateChecksum).apply)
    )
