package zio.raft.stores.segmentedlog

import zio.nio.file.Files
import zio.nio.file.Path
import zio.ZIO
import zio.Scope
import zio.raft.Command
import zio.raft.Term
import zio.raft.Index
import zio.raft.LogEntry
import zio.nio.channels.AsynchronousFileChannel
import java.nio.file.StandardOpenOption
import zio.Chunk
import zio.test.ZIOSpecDefault
import zio.test.Gen
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.check
import zio.test.assertTrue

object OpenSegmentSpec extends ZIOSpecDefault:

  case class TestCommand(value: String) extends Command:
    type Response = String

  object TestCommand:
    given codec: scodec.Codec[TestCommand] = scodec.codecs.utf8_32.as[TestCommand]
    def generator = Gen.string.map(TestCommand(_))

  def multipleBatchesGen =

    def loop(
        entries: List[LogEntry[TestCommand]],
        acc: List[List[LogEntry[TestCommand]]]
    ): Gen[Any, List[List[LogEntry[TestCommand]]]] =
      entries match
        case Nil => Gen.const(acc.reverse)
        case entries =>
          for
            batchSize <- Gen.int(1, entries.size)
            (batch, rest) = entries.splitAt(batchSize)
            result <- loop(rest, batch :: acc)
          yield result

    Gen
      .listOfBounded(1, 1000)(TestCommand.generator)
      .map(_.zipWithIndex)
      .map(_.map((command, index) => LogEntry[TestCommand](command, Term.one, Index(index.toLong + 1))))
      .flatMap(loop(_, Nil))

  def writeBatches(segment: OpenSegment[TestCommand], batches: List[List[LogEntry[TestCommand]]]) =
    ZIO.foreachDiscard(batches)(entries => segment.writeEntries(entries))

  /** Get entries from the segment.
    */
  def getEntries(
      segment: OpenSegment[TestCommand],
      from: Index,
      to: Index
  ): ZIO[Scope, Throwable, List[LogEntry[TestCommand]]] =
    segment.stream(from, to).runCollect.map(_.toList)

  /** Truncate the file by removing the specified number of bytes from the end.
    */
  def truncateFileBySize(directory: Path, fileName: String, size: Long): ZIO[Scope, Throwable, Unit] =
    for
      currentSize <- Files.size(Path(directory.toString(), fileName))
      _ <- ZIO.scoped(
        AsynchronousFileChannel
          .open(Path(directory.toString(), fileName), StandardOpenOption.WRITE)
          .flatMap(channel => channel.truncate(currentSize - size))
      )
    yield ()

  /** Write bytes at a specific position from the end of the file.
    */
  def writeLastBytes(
      directory: Path,
      fileName: String,
      bytesFromEnd: Long,
      data: Array[Byte]
  ): ZIO[Scope, Throwable, Unit] =
    for
      currentSize <- Files.size(Path(directory.toString(), fileName))
      _ <- ZIO.scoped(
        AsynchronousFileChannel
          .open(Path(directory.toString(), fileName), StandardOpenOption.WRITE)
          .flatMap(channel =>
            for
              buffer <- zio.nio.Buffer.byte(Chunk.fromArray(data))
              _ <- channel.write(buffer, currentSize - bytesFromEnd)
            yield ()
          )
      )
    yield ()

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteAll("recover from crash"):
      test("can recover from crash when no crash"):
        check(multipleBatchesGen)(batches =>
          for
            tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
            segment <- OpenSegment
              .createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
            _ <- segment.flatMap(writeBatches(_, batches))
            fileSizeBeforeRecover <- segment.flatMap(_.size)
            _ <- segment.flatMap(_.recoverFromCrash)
            fileSizeAfterRecover <- segment.flatMap(_.size)
          yield assertTrue(
            fileSizeBeforeRecover == fileSizeAfterRecover
          ) // no recovery needed, so file size should be the same
        )

      test("can recover when file has only header"):
        for
          tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
          segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          fileSizeBeforeRecover <- segment.flatMap(_.size)
          _ <- segment.flatMap(_.close())

          recoveredSegment <- OpenSegment
            .openSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          _ <- recoveredSegment.recoverFromCrash
          fileSizeAfterRecover <- recoveredSegment.size
        yield assertTrue(fileSizeBeforeRecover == fileSizeAfterRecover)

      test("can recover when file has data but no checksum"):
        for
          // Create a new segment
          tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
          segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)

          // Add entries to the segment - this also adds a checksum
          entries = List(LogEntry[TestCommand](TestCommand("test"), Term.one, Index.one))
          _ <- segment.flatMap(_.writeEntries(entries))
          _ <- segment.flatMap(_.close())

          // Get current file size and truncate to remove the checksum
          _ <- truncateFileBySize(tempDirectory, "0.log", BaseTransducer.checksumSize)

          // Open the existing segment and recover from crash
          recoveredSegment <- OpenSegment
            .openSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          _ <- recoveredSegment.recoverFromCrash

          // assert file only has header
          fileSizeAfterRecover <- recoveredSegment.size
        yield assertTrue(fileSizeAfterRecover == BaseTransducer.headerSize)

      test("can recover when valid last checksum but data is written after it"):
        for
          tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
          segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)

          entries1 = List(LogEntry[TestCommand](TestCommand("batch1"), Term.one, Index.one))
          entries2 = List(LogEntry[TestCommand](TestCommand("batch2"), Term.one, Index.one.plusOne))
          _ <- segment.flatMap(_.writeEntries(entries1))
          entries2Size <- segment.flatMap(_.writeEntries(entries2))

          fileSizeBeforeRecover <- segment.flatMap(_.size)
          _ <- segment.flatMap(_.close())

          // Remove the last checksum so we have a valid checksum with some data after it
          _ <- truncateFileBySize(tempDirectory, "0.log", BaseTransducer.checksumSize)

          // Recover from crash
          recoveredSegment <- OpenSegment
            .openSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          _ <- recoveredSegment.recoverFromCrash
          fileSizeAfterRecover <- recoveredSegment.size
          recoveredEntries <- getEntries(recoveredSegment, Index.one, Index.one.plusOne)
        yield assertTrue(
          fileSizeAfterRecover == fileSizeBeforeRecover - entries2Size,
          recoveredEntries.size == 1,
          recoveredEntries.headOption.exists(_.command.value == "batch1")
        )

      test("can recover when invalid last checksum with a previous valid checksum available"):
        for
          // Create a new segment
          tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
          segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)

          // Add two batches of entries - this adds checksums after each batch
          _ <- segment.flatMap(_.writeEntries(List(LogEntry[TestCommand](TestCommand("batch1"), Term.one, Index.one))))
          entries2Size <- segment.flatMap(
            _.writeEntries(List(LogEntry[TestCommand](TestCommand("batch2"), Term.one, Index.one.plusOne)))
          )
          fileSizeBeforeRecover <- segment.flatMap(_.size)
          _ <- segment.flatMap(_.close())

          // Corrupt the last checksum
          _ <- writeLastBytes(tempDirectory, "0.log", 5, Array[Byte](0xff.toByte, 0xff.toByte))

          // Open the existing segment and recover from crash
          recoveredSegment <- OpenSegment
            .openSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          _ <- recoveredSegment.recoverFromCrash
          fileSizeAfterRecover <- recoveredSegment.size
          recoveredEntries <- getEntries(recoveredSegment, Index.one, Index.one.plusOne)
        yield assertTrue(
          fileSizeAfterRecover == fileSizeBeforeRecover - entries2Size,
          recoveredEntries.size == 1,
          recoveredEntries.headOption.exists(_.command.value == "batch1")
        )

      test("can recover when invalid last checksum with no previous checksum available"):
        for
          tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
          segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          entries = List(LogEntry[TestCommand](TestCommand("test"), Term.one, Index.one))
          _ <- segment.flatMap(_.writeEntries(entries))
          fileSizeBeforeRecover <- segment.flatMap(_.size)
          _ <- segment.flatMap(_.close())
          
          // Corrupt the checksum
          _ <- writeLastBytes(tempDirectory, "0.log", 5, Array[Byte](0xFF.toByte, 0xFF.toByte))
          
          // Open the existing segment and recover from crash
          recoveredSegment <- OpenSegment.openSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          _ <- recoveredSegment.recoverFromCrash
          fileSizeAfterRecover <- recoveredSegment.size
          recoveredEntries <- recoveredSegment.getEntry(Index.one)
        yield assertTrue(
          fileSizeAfterRecover == BaseTransducer.headerSize,
          recoveredEntries.isEmpty
        )

      test("can recover when last checksum can't be decoded because of data corruption"):
        for
          tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
          segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          _ <- segment.flatMap(_.writeEntries(List(LogEntry[TestCommand](TestCommand("batch1"), Term.one, Index.one))))
          entries2Size <- segment.flatMap(
            _.writeEntries(List(LogEntry[TestCommand](TestCommand("batch2"), Term.one, Index.one.plusOne)))
          )

          fileSizeBeforeRecover <- segment.flatMap(_.size)
          _ <- segment.flatMap(_.close())
          
          // Truncate half of the checksum to make it undecodable
          _ <- truncateFileBySize(tempDirectory, "0.log", BaseTransducer.checksumSize / 2)
          
          // Open the existing segment and recover from crash
          recoveredSegment <- OpenSegment.openSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          _ <- recoveredSegment.recoverFromCrash
          fileSizeAfterRecover <- recoveredSegment.size
          recoveredEntries <- getEntries(recoveredSegment, Index.one, Index.one)
        yield assertTrue(
          fileSizeAfterRecover == fileSizeBeforeRecover - entries2Size,
          recoveredEntries.size == 1,
          recoveredEntries.headOption.exists(_.command.value == "batch1")
        )
end OpenSegmentSpec
