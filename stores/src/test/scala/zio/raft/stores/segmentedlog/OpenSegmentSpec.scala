package zio.raft.segmentedlog

import zio.test.*
import zio.nio.file.Files
import zio.nio.file.Path
import zio.ZIO
import zio.Scope
import zio.raft.Command
import zio.raft.stores.segmentedlog.OpenSegment
import zio.raft.Term
import zio.raft.Index
import zio.raft.LogEntry
import zio.raft.stores.segmentedlog.BaseTransducer
import zio.nio.channels.AsynchronousFileChannel
import java.nio.file.StandardOpenOption

object OpenSegmentSpec extends ZIOSpecDefault:

  case class TestCommand(value: String) extends Command:
    type Response = String

  object TestCommand:
    given codec: scodec.Codec[TestCommand] = scodec.codecs.utf8_32.as[TestCommand]
    def generator = Gen.string.map(TestCommand(_))


  def multipleBatchesGen = 
    
    def loop(entries: List[LogEntry[TestCommand]], acc: List[List[LogEntry[TestCommand]]]): Gen[Any, List[List[LogEntry[TestCommand]]]] = 
        entries match
            case Nil => Gen.const(acc.reverse)
            case entries => 
                for
                    batchSize <- Gen.int(1, entries.size)
                    (batch, rest) = entries.splitAt(batchSize)
                    result <- loop(rest, batch :: acc)
                yield result
    
    Gen.listOfBounded(1, 1000)(TestCommand.generator)
        .map(_.zipWithIndex)
        .map(_.map((command, index) => LogEntry[TestCommand](command, Term.one, Index(index.toLong + 1))))
        .flatMap(loop(_, Nil))

  def writeBatches(segment: OpenSegment[TestCommand], batches: List[List[LogEntry[TestCommand]]]) =     
    ZIO.foreachDiscard(batches)(entries => segment.writeEntries(entries))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteAll("recover from crash"):
      test("can recover from crash when no crash"):
        check(multipleBatchesGen)(batches =>
          for
            tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
            segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
            _ <- ZIO.scoped(segment.flatMap(segment => writeBatches(segment, batches)))
            fileSizeBeforeRecover <- ZIO.scoped(segment.flatMap(_.size))
            _ <- ZIO.scoped(segment.flatMap(_.recoverFromCrash))
            fileSizeAfterRecover <- ZIO.scoped(segment.flatMap(_.size))
                
          yield assertTrue(fileSizeBeforeRecover == fileSizeAfterRecover) // no recovery needed, so file size should be the same
        )

      test("can recover when file has only header"):
        for
          tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
          segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          fileSizeBeforeRecover <- ZIO.scoped(segment.flatMap(_.size))
          _ <- ZIO.scoped(segment.flatMap(_.close()))
          
          recoveredSegment <- OpenSegment.openSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
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
          _ <- ZIO.scoped(segment.flatMap(_.writeEntries(entries)))
          _ <- ZIO.scoped(segment.flatMap(_.close()))

          // Get current file size and truncate to remove the checksum
          currentSize <- Files.size(Path(tempDirectory.toString(), "0.log"))
          _ <- ZIO.scoped(
            AsynchronousFileChannel
              .open(Path(tempDirectory.toString(), "0.log"), StandardOpenOption.WRITE)
              .flatMap(channel => channel.truncate(currentSize - BaseTransducer.checksumSize))
          )

          // Open the existing segment and recover from crash
          recoveredSegment <- OpenSegment.openSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
          _ <- recoveredSegment.recoverFromCrash
          
          // assert file only has header
          fileSizeAfterRecover <- recoveredSegment.size
        yield assertTrue(fileSizeAfterRecover == BaseTransducer.headerSize)

      // test("can recover when valid last checksum but data is written after it"):
      //   for
      //     tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
      //     segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
      //     entries1 = List(LogEntry[TestCommand](TestCommand("batch1"), Term.one, Index.one))
      //     entries2 = List(LogEntry[TestCommand](TestCommand("batch2"), Term.one, Index.two))
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(entries1)))
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(entries2)))
      //     fileSizeBeforeRecover <- ZIO.scoped(segment.flatMap(_.size))
      //     // Write some invalid data after the checksum
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(List(LogEntry[TestCommand](TestCommand("invalid"), Term.one, Index(3))))))
      //     _ <- ZIO.scoped(segment.flatMap(_.recoverFromCrash))
      //     fileSizeAfterRecover <- ZIO.scoped(segment.flatMap(_.size))
      //     recoveredEntries <- ZIO.scoped(segment.flatMap(_.getEntries(Index.one, Index.two)))
      //   yield assertTrue(
      //     fileSizeAfterRecover == fileSizeBeforeRecover,
      //     recoveredEntries.size == 2,
      //     recoveredEntries.head.command.value == "batch1",
      //     recoveredEntries.last.command.value == "batch2"
      //   )

      // test("can recover when invalid last checksum with a previous valid checksum available"):
      //   for
      //     tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
      //     segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
      //     entries1 = List(LogEntry[TestCommand](TestCommand("batch1"), Term.one, Index.one))
      //     entries2 = List(LogEntry[TestCommand](TestCommand("batch2"), Term.one, Index.two))
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(entries1)))
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(entries2)))
      //     fileSizeBeforeRecover <- ZIO.scoped(segment.flatMap(_.size))
      //     // Corrupt the last checksum by writing invalid data
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(List(LogEntry[TestCommand](TestCommand("corrupt"), Term.one, Index(3))))))
      //     _ <- ZIO.scoped(segment.flatMap(_.recoverFromCrash))
      //     fileSizeAfterRecover <- ZIO.scoped(segment.flatMap(_.size))
      //     recoveredEntries <- ZIO.scoped(segment.flatMap(_.getEntries(Index.one, Index.two)))
      //   yield assertTrue(
      //     fileSizeAfterRecover == fileSizeBeforeRecover - BaseTransducer.isEntrySize - BaseTransducer.checksumSize,
      //     recoveredEntries.size == 1,
      //     recoveredEntries.head.command.value == "batch1"
      //   )

      // test("can recover when invalid last checksum with no previous checksum available"):
      //   for
      //     tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
      //     segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
      //     entries = List(LogEntry[TestCommand](TestCommand("test"), Term.one, Index.one))
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(entries)))
      //     fileSizeBeforeRecover <- ZIO.scoped(segment.flatMap(_.size))
      //     // Corrupt the checksum by writing invalid data
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(List(LogEntry[TestCommand](TestCommand("corrupt"), Term.one, Index.two)))))
      //     _ <- ZIO.scoped(segment.flatMap(_.recoverFromCrash))
      //     fileSizeAfterRecover <- ZIO.scoped(segment.flatMap(_.size))
      //     recoveredEntries <- ZIO.scoped(segment.flatMap(_.getEntries(Index.one, Index.one)))
      //   yield assertTrue(
      //     fileSizeAfterRecover == fileSizeBeforeRecover - BaseTransducer.isEntrySize - BaseTransducer.checksumSize,
      //     recoveredEntries.isEmpty
      //   )

      // test("can recover when last checksum can't be decoded because of data corruption"):
      //   for
      //     tempDirectory <- Files.createTempDirectoryScoped(None, Seq.empty)
      //     segment <- OpenSegment.createNewSegment[TestCommand](tempDirectory.toString(), "0.log", Index.one, Term.zero)
      //     entries = List(LogEntry[TestCommand](TestCommand("test"), Term.one, Index.one))
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(entries)))
      //     fileSizeBeforeRecover <- ZIO.scoped(segment.flatMap(_.size))
      //     // Write partial checksum data to corrupt it
      //     _ <- ZIO.scoped(segment.flatMap(_.writeEntries(List(LogEntry[TestCommand](TestCommand("partial"), Term.one, Index.two)))))
      //     _ <- ZIO.scoped(segment.flatMap(_.recoverFromCrash))
      //     fileSizeAfterRecover <- ZIO.scoped(segment.flatMap(_.size))
      //     recoveredEntries <- ZIO.scoped(segment.flatMap(_.getEntries(Index.one, Index.one)))
      //   yield assertTrue(
      //     fileSizeAfterRecover == fileSizeBeforeRecover - BaseTransducer.isEntrySize - BaseTransducer.checksumSize,
      //     recoveredEntries.isEmpty
      //   )

end OpenSegmentSpec
