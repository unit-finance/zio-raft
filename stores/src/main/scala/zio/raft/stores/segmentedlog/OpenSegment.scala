package zio.raft.stores.segmentedlog

import java.nio.file.StandardOpenOption

import zio.nio.channels.AsynchronousFileChannel
import zio.nio.file.Path
import zio.raft.stores.segmentedlog.internal.*
import zio.raft.{Command, Index, LogEntry}
import zio.{Scope, UIO, ZIO}
import scodec.zio.*

import scodec.Codec
import zio.raft.Term
import scodec.bits.crc.crc32Builder
import zio.raft.stores.segmentedlog.BaseTransducer.Result

class OpenSegment[A <: Command: Codec](
    val path: Path,
    channel: AsynchronousFileChannel,
    positionRef: LocalLongRef[Long],
    val firstIndex: Index,
    val previousTerm: Term
) extends Segment:
  def size: UIO[Long] = positionRef.get

  def stream(startInclusive: Index, toInclusive: Index) =
    val stream = makeStream(channel).via(recordsOnly)

    if startInclusive.value > firstIndex.value then
      stream
        .drop((startInclusive.value - firstIndex.value).toInt)
        .takeWhile(_.index <= toInclusive)
        .via(decode)
    else stream.takeWhile(_.index <= toInclusive).via(decode)

  def getEntry(index: Index): ZIO[Any, Nothing, Option[LogEntry[A]]] =
    if index >= firstIndex then
      makeStream(channel)
        .via(recordsOnly)
        .drop((index.value - firstIndex.value).toInt)
        .via(decode)
        .runHead
        .orDie
    else ZIO.none

  def getLastTermIndex[A <: Command: Codec]: ZIO[Any, Nothing, (Term, Index)] =
    for {
      last <- makeStream(channel)
        .via(recordsOnly)
        .run(lastAndDecode)
        .orDie // TODO (eran): we keep calling makeStream over and over, should we store the stream?
    } yield last match
      case None    => (previousTerm, firstIndex.minusOne)
      case Some(e) => (e.term, e.index)

  def writeEntry(entry: LogEntry[A]): ZIO[Any, Nothing, Int] = writeEntries(List(entry))

  // Write entries write all of the entries, regardless if the segment size will be exceeded
  // segment size is just a recommendation at this moment.
  // TODO: we should only write entries that will fit in the segment and return the rest to be written in the next segment
  def writeEntries(entries: List[LogEntry[A]]): ZIO[Any, Nothing, Int] =
    for {
      bytes <- ZIO.attempt(entriesCodec.encode(entries).require.toByteVector).orDie
      position <- positionRef.get
      written <- channel.write(bytes, position).orDie
      _ <- channel.force(true).orDie
      _ <- positionRef.update(_ + written)
    } yield written

  def deleteFrom(minInclusive: Index): ZIO[Any, Nothing, Unit] =
    for
      // we need to find the offset of the entry to truncate the file
      offset <-
        if (minInclusive == firstIndex) ZIO.succeed(BaseTransducer.headerSize)
        else
          makeStream(channel)
            .via(recordsOnly)
            .filter(r => r.index == minInclusive)
            .runHead
            .someOrFail(new IllegalStateException("Index not found"))
            .orDie
            .map(_.offset)

      // we need to calculate the checksum of the records that will remain after
      maybeChecksum <- makeStream(channel)
        .takeWhile(r => r.offset < offset)
        .runFold(None)((maybeCrc, record) =>
          record match
            case Result.Record(offset, index, payload) => Some(maybeCrc.getOrElse(crc32Builder).updated(payload))
            case Result.Checksum(offset, matched)      => None
        )
        .orDie

      _ <- maybeChecksum match
        // offset happen to be after a checksum or header
        case None =>
          for
            _ <- channel.truncate(offset).orDie
            _ <- channel.force(true).orDie
            _ <- positionRef.set(offset)
          yield ()
        case Some(crc) =>
          // we need to rewrite the checksum for the remaining records
          // we first write the checksum and then truncate the file
          // TOOD: is this crash safe?
          val checksumBytes = (isEntryCodec.encode(false).require ++ crc.result).toByteVector
          for
            _ <- channel.write(checksumBytes, offset).orDie
            _ <- channel.truncate(offset + checksumBytes.length).orDie
            _ <- channel.force(true).orDie
            _ <- positionRef.set(offset + checksumBytes.length)
          yield ()
    yield ()

  def recoverFromCrash: ZIO[Any, Nothing, Unit] =
    for
      lastChecksum <- makeStream(
        channel,
        validateChecksum = true
      ) // TODO (eran): we don't need to validate checksum here, we can grab the last one and only validate it, this will be more efficient
        .collect:
          case c: BaseTransducer.Result.Checksum =>
            c // TODO (eran): Do we want to optimize this by allowing the transducer to skip body decoding?
        .runLast
        .orDie

      currentFileSize <- channel.size.orDie

      _ <- lastChecksum match
        case None if currentFileSize == BaseTransducer.headerSize =>
          ZIO.logInfo("SegementedLog: No checksum found - no recovery needed")
        case None =>
          ZIO.logWarning("SegementedLog: No valid checksum found - truncate to header")
          for
            _ <- channel.truncate(BaseTransducer.headerSize).orDie
            _ <- channel.force(true).orDie
            _ <- positionRef.set(BaseTransducer.headerSize)
          yield ()
        case Some(BaseTransducer.Result.Checksum(offset, true))
            if currentFileSize == offset + BaseTransducer.isEntrySize + BaseTransducer.checksumSize => // TODO (eran): we want to verify that nothing was written after the checksum, why do we need isEntrySize?
          ZIO.logInfo("SegementedLog: Checksum is valid - no recovery needed")
        case Some(BaseTransducer.Result.Checksum(offset, true)) =>
          ZIO.logWarning(
            "SegementedLog: Checksum is valid but batch is not committed - truncate to last valid checksum"
          )
          for
            _ <- channel.truncate(offset + BaseTransducer.isEntrySize + BaseTransducer.checksumSize).orDie
            _ <- channel.force(true).orDie
            _ <- positionRef.set(offset + BaseTransducer.isEntrySize + BaseTransducer.checksumSize)
          yield ()
        case Some(BaseTransducer.Result.Checksum(_, false)) =>
          for
            _ <- ZIO.logWarning("SegementedLog: Checksum is invalid - recovering from crash")
            lastGoodChecksumOffset <- makeStream(channel, validateChecksum = true)
              .collect:
                case BaseTransducer.Result.Checksum(offset, true) => offset
              .runLast
              .orDie

            offset <- lastGoodChecksumOffset match
              case None =>
                ZIO
                  .logInfo("SegementedLog: No valid checksum found - truncate to header")
                  .as(BaseTransducer.headerSize)
              case Some(checksumOffset) =>
                ZIO
                  .logInfo(s"SegementedLog: Truncate to last valid checksum at offset ${checksumOffset}")
                  .as(checksumOffset + BaseTransducer.isEntrySize + BaseTransducer.checksumSize)
            _ <- channel.truncate(offset).orDie
            _ <- channel.force(true).orDie
            _ <- positionRef.set(offset)
          yield ()
    yield ()

  def close() = channel.close.orDie

object OpenSegment:
  def createNewSegment[A <: Command: Codec](
      logDirectory: String,
      fileName: String,
      firstIndex: Index,
      previousTerm: Term
  ): ZIO[Any, Nothing, ZIO[Scope, Nothing, OpenSegment[A]]] =
    val fullPath = Path(logDirectory, fileName)

    for {
      _ <- createFileWithHeader(logDirectory, fullPath)
    } yield openSegment[A](fullPath, firstIndex, previousTerm)

  // atomically create a new segment with an empty log entry with the previous index and term

  private def createFileWithHeader(
      logDirectory: String,
      fullPath: Path
  ) =
    for
      _ <- ZIO
        .scoped(
          AsynchronousFileChannel
            .open(
              fullPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE,
              StandardOpenOption.READ,
              StandardOpenOption.SYNC
            )
            .flatMap { channel =>
              val header = fileHeaderCodec.encode(()).require
              channel.write(header.bytes, 0)
            }
        )
        .orDie

      // Force the directory, to make sure the file is created
      _ <- ZIO
        .scoped(AsynchronousFileChannel.open(Path(logDirectory), StandardOpenOption.READ).flatMap(_.force(true)))
        .orDie
    yield ()

  def openSegment[A <: Command: Codec](
      logDirectory: String,
      fileName: String,
      firstIndex: Index,
      previousTerm: Term
  ): ZIO[Scope, Nothing, OpenSegment[A]] =
    val fullPath = Path(logDirectory, fileName)
    openSegment(fullPath, firstIndex, previousTerm)

  def openSegment[A <: Command: Codec](
      fullPath: Path,
      firstIndex: Index,
      previousTerm: Term
  ): ZIO[Scope, Nothing, OpenSegment[A]] =
    for
      // TODO: should we verify the header?
      channel <- AsynchronousFileChannel
        .open(
          fullPath,
          StandardOpenOption.WRITE,
          StandardOpenOption.READ
        )
        .orDie

      position <- channel.size.orDie
      positionRef <- LocalLongRef.make(position)

      segment = new OpenSegment[A](fullPath, channel, positionRef, firstIndex, previousTerm)
    yield segment
