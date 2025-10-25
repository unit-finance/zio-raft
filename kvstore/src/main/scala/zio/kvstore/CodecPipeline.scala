package zio.kvstore

import scodec.Codec
import scodec.bits.BitVector
import scodec.Err
import zio.stream.{ZPipeline, ZStream}
import zio.{Chunk, ChunkBuilder, ZIO, Ref}

/**
 * Streaming codec decoder for ZStream.
 * 
 * Provides a ZPipeline that decodes a stream of bytes using a scodec Codec,
 * handling partial data and backpressure properly. Works on Chunks for efficiency.
 */
object CodecPipeline:
      
  /**
   * Create a stateful decoding pipeline that maintains buffer across chunks.
   * 
   * Properly handles:
   * - Values spanning multiple chunks (maintains buffer state)
   * - InsufficientBits errors (waits for more data)
   * - Other decode errors (fails the stream)
   * 
   * Assumes input is Stream[Chunk[Byte]] (already chunked).
   */
  def decoder[A](codec: Codec[A]): ZPipeline[Any, scodec.Err, Chunk[Byte], A] =
    ZPipeline.mapAccumZIO[Any, Err, Chunk[Byte], BitVector, Chunk[A]](BitVector.empty) { (buffer, chunk) =>
      // Recursive decoder that accumulates results using ChunkBuilder
      def decodeAll(currentBuffer: BitVector, builder: ChunkBuilder[A]): ZIO[Any, Err, (BitVector, Chunk[A])] =
        if currentBuffer.isEmpty then
          ZIO.succeed((currentBuffer, builder.result()))
        else
          codec.decode(currentBuffer) match
            case scodec.Attempt.Successful(result) =>
              // Successfully decoded - add to builder and continue with remainder
              builder += result.value
              decodeAll(result.remainder, builder)
            
            case scodec.Attempt.Failure(err) =>
              err match
                case Err.InsufficientBits(_, _, _) =>
                  // Not enough data - stop and keep buffer for next chunk
                  ZIO.succeed((currentBuffer, builder.result()))
                case other =>
                  // Actual decode error - fail the stream
                  ZIO.fail(err)
      
      val newBuffer = buffer ++ BitVector(chunk.toArray)
      decodeAll(newBuffer, ChunkBuilder.make[A]())
    } >>> ZPipeline.flattenChunks

