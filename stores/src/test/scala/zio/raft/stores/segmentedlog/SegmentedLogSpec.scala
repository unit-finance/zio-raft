package zio.raft.stores.segmentedlog

import zio.test.*
import zio.nio.file.Files
import zio.raft.LogEntry
import zio.raft.Term
import zio.raft.Index
import zio.raft.Command

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
        logEntry = LogEntry.command(command, Term.one, Index.one)
        _ <- log.storeLog(logEntry)
        entries <- log.getLogs(Index.one, Index.one)
      yield assertTrue(entries.head.head == logEntry && entries.size == 1)

    test("can write and read multiple entries"):
      check(Gen.listOfBounded(1, 1000)(TestCommand.generator)) { commands =>
        (for
          logDirectory <- tempDirectory
          log <- SegmentedLog.make[TestCommand](logDirectory.toString(), maxLogFileSize = 1024)
          entries = commands.zipWithIndex.map((command, index) =>
            LogEntry.command(command, Term.one, Index(index.toLong + 1))
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
          logEntry = LogEntry.command(command, Term.one, Index.one)
          _ <- log.storeLog(logEntry)
          term <- log.logTerm(Index.zero)
        yield assertTrue(term.get == Term.zero)

      test("log-term for last index"):
        for
          logDirectory <- tempDirectory
          log <- SegmentedLog.make[TestCommand](logDirectory.toString())
          command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
          logEntry = LogEntry.command(command, Term.one, Index.one)
          _ <- log.storeLog(logEntry)
          term <- log.logTerm(Index.one)
        yield assertTrue(term.get == Term.one)

      test("log term for index in the middle"):
        for
          logDirectory <- tempDirectory
          log <- SegmentedLog.make[TestCommand](logDirectory.toString())
          command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
          logEntry = LogEntry.command(command, Term.one, Index.one)
          _ <- log.storeLog(logEntry)
          command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
          logEntry = LogEntry.command(command, Term(10L), Index(2L))
          _ <- log.storeLog(logEntry)
          command <- TestCommand.generator.runHead.someOrFail(new Exception("No command"))
          logEntry = LogEntry.command(command, Term(30L), Index(3L))
          _ <- log.storeLog(logEntry)

          term <- log.logTerm(Index(2))
        yield assertTrue(term.get == Term(10))

    test("handles segment rollover correctly"):
      for
        logDirectory <- tempDirectory
        // Set a small max size to force segment rollover
        log <- SegmentedLog.make[TestCommand](logDirectory.toString(), maxLogFileSize = 100)

        // Create entries that will exceed the max size
        commands <- Gen.listOfBounded(5, 10)(TestCommand.generator).runHead.someOrFail(new Exception("No commands"))
        entries = commands.zipWithIndex.map((command, index) =>
          LogEntry.command(command, Term.one, Index(index.toLong + 1))
        )

        // Store entries which should trigger segment rollover
        _ <- log.storeLogs(entries)

        // Verify we can read all entries back
        result <- log.stream(Index.one, Index(entries.size.toLong)).runCollect

        // Verify last index and term are correct
        lastIndex <- log.lastIndex
        lastTerm <- log.lastTerm

        // Verify we can read entries from different parts of the log
        firstHalf <- log.getLogs(Index.one, Index(entries.size.toLong / 2))
        secondHalf <- log.getLogs(Index(entries.size.toLong / 2 + 1), Index(entries.size.toLong))
      yield assertTrue(
        entries == result.toList, // All entries are accessible
        lastIndex == Index(entries.size.toLong), // Last index is correct
        lastTerm == Term.one, // Last term is correct (should be constant)
        firstHalf.isDefined && secondHalf.isDefined, // Can read from different parts
        firstHalf.get.size + secondHalf.get.size == entries.size // All entries are accessible in parts
      )

    suiteAll("recovery tests"):
      test("recovers from basic crash"):
        for
          logDirectory <- tempDirectory
          log <- SegmentedLog.make[TestCommand](logDirectory.toString(), maxLogFileSize = 100)
          commands <- Gen.listOfBounded(5, 10)(TestCommand.generator).runHead.someOrFail(new Exception("No commands"))
          entries = commands.zipWithIndex.map((command, index) =>
            LogEntry.command(command, Term.one, Index(index.toLong + 1))
          )
          _ <- log.storeLogs(entries)
          recoveredLog <- SegmentedLog.make[TestCommand](logDirectory.toString(), maxLogFileSize = 100)
          recoveredLastIndex <- recoveredLog.lastIndex
          recoveredLastTerm <- recoveredLog.lastTerm
          recoveredEntries <- recoveredLog.stream(Index.one, Index(entries.size.toLong)).runCollect

          // Verify we can read entries from different parts of the recovered log
          recoveredFirstHalf <- recoveredLog.getLogs(Index.one, Index(entries.size.toLong / 2))
          recoveredSecondHalf <- recoveredLog.getLogs(Index(entries.size.toLong / 2 + 1), Index(entries.size.toLong))
        yield assertTrue(
          recoveredLastIndex == Index(entries.size.toLong),
          recoveredLastTerm == Term.one,
          entries == recoveredEntries.toList,
          recoveredFirstHalf.isDefined && recoveredSecondHalf.isDefined, // Can read from different parts
          recoveredFirstHalf.get.size + recoveredSecondHalf.get.size == entries.size // All entries are accessible in parts
        )

      test("recovers with different segment sizes"):
        for
          logDirectory <- tempDirectory
          // Create log with small segment size
          log <- SegmentedLog.make[TestCommand](logDirectory.toString(), maxLogFileSize = 100)
          commands <- Gen.listOfBounded(5, 10)(TestCommand.generator).runHead.someOrFail(new Exception("No commands"))
          entries = commands.zipWithIndex.map((command, index) =>
            LogEntry.command(command, Term.one, Index(index.toLong + 1))
          )
          _ <- log.storeLogs(entries)
          // Recover with larger segment size
          recoveredLog <- SegmentedLog.make[TestCommand](logDirectory.toString(), maxLogFileSize = 1000)
          recoveredLastIndex <- recoveredLog.lastIndex
          recoveredLastTerm <- recoveredLog.lastTerm
          recoveredEntries <- recoveredLog.stream(Index.one, Index(entries.size.toLong)).runCollect
        yield assertTrue(
          recoveredLastIndex == Index(entries.size.toLong),
          recoveredLastTerm == Term.one,
          entries == recoveredEntries.toList
        )

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
