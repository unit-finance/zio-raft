package scodec.zio

import zio.nio.channels.AsynchronousFileChannel
import zio.nio.Buffer
import _root_.scodec.bits.ByteVector
import scala.collection.mutable.ListBuffer
import zio.nio.ByteBuffer
import zio.ZIO
import java.io.IOException
import zio.IO
import zio.stream.ZSink

extension (channel: AsynchronousFileChannel)
  def write(bytes: ByteVector, position: Long): IO[IOException, Int] =
    val listBuilder = ListBuffer.newBuilder[ByteBuffer]
    bytes.foreachV(v => listBuilder.addOne(Buffer.byteFromJava(v.asByteBuffer)))

    ZIO
      .foldLeft(listBuilder.result)(position)((pos, buffer) =>
        for written <- go(buffer, position, 0)
        yield pos + written
      )
      .map(_ - position)
      .map(_.toInt)

  private def go(b: ByteBuffer, pos: Long, bytesWrittenTotal: Int): IO[IOException, Int] =
    channel.write(b, pos).flatMap { bytesWritten =>
      b.hasRemaining.flatMap:
        case true  => go(b, pos + bytesWritten.toLong, bytesWrittenTotal + bytesWritten)
        case false => ZIO.succeed(bytesWrittenTotal + bytesWritten)
    }

  def byteVectorSink(position: Long): ZSink[Any, IOException, ByteVector, ByteVector, Int] =
    ZSink
      .foldLeftZIO(position)((pos, bytes: ByteVector) =>
        for written <- write(bytes, pos)
        yield pos + written
      )
      .map(_ - position)
      .map(_.toInt)
