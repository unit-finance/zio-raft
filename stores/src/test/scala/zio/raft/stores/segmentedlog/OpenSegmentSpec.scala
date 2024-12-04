package zio.raft.segmentedlog

import zio.test.*
import zio.nio.file.Files
import zio.ZIO
import zio.Scope
import zio.raft.Command
import zio.raft.stores.segmentedlog.OpenSegment
import zio.raft.Term
import zio.raft.Index
import zio.raft.LogEntry

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

end OpenSegmentSpec
