package zio.kvstore

import zio.test.*
import zio.test.Assertion.*
import zio.stream.ZStream
import zio.{Chunk, ZIO}
import scodec.Codec
import scodec.codecs.{int32, utf8_32, variableSizeBytes}
import scodec.bits.ByteVector

/**
 * Test for CodecPipeline streaming decoder/encoder.
 */
object CodecPipelineSpec extends ZIOSpecDefault:
  
  case class TestData(id: Int, name: String)
  
  // Codec with length prefix for streaming
  val testCodec: Codec[TestData] = 
    ((int32 :: utf8_32).as[TestData])
  
  val lengthPrefixedCodec: Codec[TestData] =
    variableSizeBytes(int32, testCodec)
  
  def spec = suite("CodecPipeline")(
    
    test("chunkDecoder decodes multiple values from stream") {
      val data = List(
        TestData(1, "Alice"),
        TestData(2, "Bob"),
        TestData(3, "Charlie")
      )
      
      // Encode data to bytes
      val encoded = data.flatMap { d =>
        lengthPrefixedCodec.encode(d).require.toByteArray.toList
      }
      
      // Create stream and decode
      val byteStream = ZStream.fromIterable(encoded)
      val decoded = byteStream.chunks
        .via(CodecPipeline.decoder(lengthPrefixedCodec))
        .runCollect
      
      decoded.map { result =>
        assertTrue(result.toList == data)
      }
    },
    
    test("chunkDecoder handles partial data across chunks") {
      val data = TestData(42, "Test")
      val allBytes = lengthPrefixedCodec.encode(data).require.toByteArray
      
      // Split into multiple chunks to simulate partial data
      val chunk1 = allBytes.take(5)
      val chunk2 = allBytes.drop(5)
      
      val byteStream = ZStream(chunk1, chunk2).flatMap(ZStream.fromIterable(_))
      val decoded = byteStream.chunks
        .via(CodecPipeline.decoder(lengthPrefixedCodec))
        .runCollect
      
      decoded.map { result =>
        assertTrue(
          result.size == 1 &&
          result.head == data
        )
      }
    },
    
    
    test("round-trip: encode then decode preserves data") {
      val original = List(
        TestData(10, "First"),
        TestData(20, "Second"),
        TestData(30, "Third")
      )
      
      val roundTrip = ZStream.fromIterable(original)
        .mapZIO { value =>
          ZIO.attempt(Chunk.fromArray(lengthPrefixedCodec.encode(value).require.toByteArray))
        }
        .flattenChunks
        .chunks
        .via(CodecPipeline.decoder(lengthPrefixedCodec))
        .runCollect
      
      roundTrip.map { result =>
        assertTrue(result.toList == original)
      }
    }
  )
