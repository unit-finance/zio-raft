package zio.raft.stores.segmentedlog

import scodec.Codec
import scodec.SizeBound
import scodec.bits.BitVector
import scala.collection.Factory
import scodec.Err
import scodec.Attempt
import scodec.DecodeResult
import scodec.bits.crc.crc32Builder
import scodec.codecs.{fixedSizeBytes, bits, peek}
import scodec.codecs.ChecksumMismatch
import scodec.Attempt.Successful
import scodec.Attempt.Failure
import zio.raft.stores.segmentedlog.internal.{entrySizeCodec, isEntryCodec}

class ChecksummedList[A](codec: Codec[A])
    extends Codec[List[A]]:

    def sizeBound: SizeBound = SizeBound.unknown
    
    def encode(list: List[A]): Attempt[BitVector] =
      val checksumBuilder = crc32Builder
      val buf = new collection.mutable.ArrayBuffer[BitVector](list.size + 1)
      var failure: Err | Null = null

      val isEntryTrue = isEntryCodec.encode(true).require
      val isEntryFalse = isEntryCodec.encode(false).require
      
      list.foreach { a =>
        if failure == null then
          val program = 
            for 
              
              aa <- codec.encode(a)
              size <- entrySizeCodec.encode((aa.size / 8).toInt)

              _ = checksumBuilder.updated(aa)
              _ = buf += isEntryTrue ++ size ++ aa
            yield ()

          program.recover(err => failure = err.pushContext(buf.size.toString))
      }
   
      if failure == null then
        val checksum = checksumBuilder.result
        buf += isEntryFalse ++ checksum
        def merge(offset: Int, size: Int): BitVector = size match
          case 0 => BitVector.empty
          case 1 => buf(offset)
          case _ =>
            val half = size / 2
            merge(offset, half) ++ merge(offset + half, half + (if size % 2 == 0 then 0 else 1)) // TODO (eran): look into this merge function a bit more, need to better undertand this
        Attempt.successful(merge(0, buf.size))
      else
        Attempt.failure(
          failure.nn
        )

    def decode(buffer: BitVector): Attempt[DecodeResult[List[A]]] = collect(buffer)

    private def entryDecoder = 
        entrySizeCodec.flatZip(size => peek(bits(size / 8))).flatMap((size, bits) => fixedSizeBytes(size, codec).map(a => (bits, a))) 

    private def collect(buffer: BitVector) (using factory: Factory[A, List[A]]) = 
      val bldr = factory.newBuilder
      val checksumBuilder = crc32Builder

      var remaining = buffer
      var error: Option[Err] = None
      var stop = false
      var count = 0
      while remaining.nonEmpty && !stop do
        isEntryCodec.decode(remaining) match
          case Attempt.Successful(DecodeResult(true, rest)) =>
            entryDecoder.decode(rest) match
                case Attempt.Successful(DecodeResult((bits, value), rest)) =>
                  bldr += value
                  checksumBuilder.updated(bits)
                  count += 1
                  remaining = rest
                case Attempt.Failure(err) =>
                  error = Some(err.pushContext(count.toString))
                  remaining = BitVector.empty
          case Attempt.Successful(DecodeResult(false, rest)) =>
            bits(32).decode(rest) match 
              case Attempt.Successful(DecodeResult(expected, rest)) =>
                val actual = checksumBuilder.result
                if actual == expected then
                  remaining = rest
                  stop = true
                else 
                  error = Some(ChecksumMismatch(buffer, expected, actual))
                  remaining = BitVector.empty
              case Attempt.Failure(err) =>
                error = Some(err.pushContext(count.toString))
                remaining = BitVector.empty
          case Attempt.Failure(err) =>
            error = Some(err.pushContext(count.toString))
            remaining = BitVector.empty
      Attempt.fromErrOption(error, DecodeResult(bldr.result, remaining))

    override def toString = s"ChecksummedList($codec)"