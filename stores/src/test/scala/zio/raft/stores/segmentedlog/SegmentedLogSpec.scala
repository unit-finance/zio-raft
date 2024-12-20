package zio.raft.stores.segmentedlog

import zio.test.*
import zio.nio.file.Files
import zio.Scope
import zio.test.Gen
import zio.raft.LogEntry
import zio.raft.Term
import zio.raft.Index
import zio.raft.Command
import zio.ZIO

object SegmentedLogSpec extends ZIOSpecDefault:
  val tempDirectory = Files.createTempDirectory(Some("logs"), Iterable.empty)

  case class TestCommand(value: String) extends Command:
    type Response = String

  object TestCommand:
    given codec: scodec.Codec[TestCommand] = scodec.codecs.utf8_32.as[TestCommand]
    def generator = Gen.string.map(TestCommand(_))

  override def spec = suiteAll("segmented log spec") {
    test("can write and read entry"):
      for
        logDirectory <- tempDirectory
        log <- SegmentedLog.make[TestCommand](logDirectory.toString())
        command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
        logEntry = LogEntry[TestCommand](command, Term.one, Index.one)
        _ <- log.storeLog(logEntry)
        entries <- log.getLogs(Index.one, Index.one)
      yield assertTrue(entries.head.head == logEntry && entries.size == 1)

    test("can write and read multiple entries"):
      check(Gen.listOfBounded(1, 1000)(TestCommand.generator)) { commands =>
        (for          
          logDirectory <- tempDirectory
          log <- SegmentedLog.make[TestCommand](logDirectory.toString(), maxLogFileSize = 1024)
          entries = commands.zipWithIndex.map((command, index) =>
            LogEntry[TestCommand](command, Term.one, Index(index.toLong + 1))
          )
          _ <- log.storeLogs(entries)

          result <- log.stream(Index.one, Index(commands.size.toLong + 1)).runCollect
        yield assertTrue(entries == result.toList)).provideSomeLayer(zio.lmdb.Environment.test)
      }

    suiteAll("log-term tests"):
      test("log-term for index 0"):
        for
          logDirectory <- tempDirectory
          log <- SegmentedLog.make[TestCommand](logDirectory.toString())
          command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
          logEntry = LogEntry[TestCommand](command, Term.one, Index.one)
          _ <- log.storeLog(logEntry)
          term <- log.logTerm(Index.zero)
        yield assertTrue(term.get == Term.zero)

      test("log-term for last index"):
        for
          logDirectory <- tempDirectory
          log <- SegmentedLog.make[TestCommand](logDirectory.toString())
          command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
          logEntry = LogEntry[TestCommand](command, Term.one, Index.one)
          _ <- log.storeLog(logEntry)
          term <- log.logTerm(Index.one)
        yield assertTrue(term.get == Term.one)

      test("log term for index in the middle"):
        for
          logDirectory <- tempDirectory
          log <- SegmentedLog.make[TestCommand](logDirectory.toString())
          command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
          logEntry = LogEntry[TestCommand](command, Term.one, Index.one)
          _ <- log.storeLog(logEntry)
          command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
          logEntry = LogEntry[TestCommand](command, Term(10L), Index(2L))
          _ <- log.storeLog(logEntry)
          command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
          logEntry = LogEntry[TestCommand](command, Term(30L), Index(3L))
          _ <- log.storeLog(logEntry)

          term <- log.logTerm(Index(2))
        yield assertTrue(term.get == Term(10))
    // tests:
    // 6. get log first, last, before first, after last
    // 7. get logs from after the last index
    // 8. get logs from before the first index
    // 9. get logs from range
    // 10. stream before first, after last, in the middle
    // 11. stream from multiple segments
    // 11. store log causing a segment rollover
    // 12. store logs causing a segment rollover and then read them back
    // 13. deleteFrom - currentSegment
    // 14. deleteFrom - previousSegment
    // 15. deleteFrom - multiple segments
    // 16. discardEntireLog ...
    // 17. discardLogUpTo ...
    // 18. recover from corruption (all cases)
  }.provideSomeLayer(zio.lmdb.Environment.test)
