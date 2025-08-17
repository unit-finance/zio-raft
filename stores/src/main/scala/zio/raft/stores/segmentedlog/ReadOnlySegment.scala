package zio.raft.stores.segmentedlog

import zio.ZIO
import zio.nio.file.{Files, Path}
import zio.raft.{Command, Index}

import scodec.Codec
import zio.stream.ZStream
import zio.nio.channels.AsynchronousFileChannel
import java.nio.file.StandardOpenOption
import zio.raft.LogEntry

class ReadOnlySegment[A <: Command: Codec](
    val path: Path,
    val firstIndex: Index,
    val lastIndexExclusive: Option[Index]
) extends Segment:
  def stream(startInclusive: Index, toInclusive: Index) =
    val stream =
      ZStream
        .scoped(AsynchronousFileChannel.open(path, StandardOpenOption.READ))
        .flatMap(channel => makeStream(channel))
        .via(recordsOnly)

    if (startInclusive.value > firstIndex.value)
      stream.drop((startInclusive.value - firstIndex.value).toInt).takeWhile(_.index <= toInclusive).via(decode)
    else stream.takeWhile(_.index <= toInclusive).via(decode)

  // Checks if the file is in the stream, i.e. contains entries greater than fromInclusive and lower than toInclusive
  def isInStream(fromInclusive: Index, toInclusive: Index) = lastIndexExclusive match
    case None => toInclusive >= firstIndex
    case Some(lastIndexExclusive) =>
      fromInclusive.value < lastIndexExclusive.value && toInclusive >= firstIndex

  def getEntry(index: Index): ZIO[Any, Nothing, Option[LogEntry]] =
    if (isInSegment(index))
      ZStream
        .scoped(AsynchronousFileChannel.open(path, StandardOpenOption.READ))
        .flatMap(channel => makeStream(channel))
        .via(recordsOnly)
        .drop((index.value - firstIndex.value).toInt)
        .via(decode)
        .runHead
        .someOrFail(new IllegalStateException("Index not found"))
        .orDie
        .asSome
    else ZIO.none

  // Checks if the index is in the segment, i.e is the index between firstIndex and lastIndex
  def isInSegment(index: Index) =
    if (index >= firstIndex)
      lastIndexExclusive match
        case None => true
        case Some(lastIndexExclusive) =>
          index < lastIndexExclusive
    else false

  // Checks if the file can be deleted, i.e. all the entries in the file are less than deleteUntilInclusive
  def canBeDeleted(deleteUntilInclusive: Index) = lastIndexExclusive match
    case None => false // We never delete the current file
    case Some(lastIndexExclusive) =>
      lastIndexExclusive.minusOne <= deleteUntilInclusive

  def delete = Files.delete(path).orDie

  override def toString(): String = s"Segment($firstIndex)"
object ReadOnlySegment:
  def open[A <: Command: Codec](logDirectory: String, fileName: String, firstIndex: Index, lastIndex: Option[Index]) =
    val path = Path(logDirectory, fileName)
    ZIO.succeed(ReadOnlySegment[A](path, firstIndex, lastIndexExclusive = lastIndex.map(_.plusOne)))
