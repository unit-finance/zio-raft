// package zio.raft.segmentedlog

// import zio.test.*
// import java.nio.file.Files
// import zio.ZIO
// import scodec.codecs.list

// object ReadOnlySegmentSpec extends DefaultMutableRunnableSpec {
//   testM("can read an entry") {
//     checkM(Generators.writeCommand) { (entry: WriteCommand) =>
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         entryBytes = variableSizeEntryCodec.encode(entry).require.toByteArray
//         _       <- blocking.effectBlocking(Files.write(logDirectory.resolve("0.log"), fileHeader ++ entryBytes)).orDie
//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), "0.log", None)
//         entries <- segment.stream(Index(0)).runCollect

//       } yield assertTrue(entries.head == entry && entries.length == 1)
//     }
//   }

//   testM("multiple entries") {
//     checkM(Generators.commands) { entries: List[WriteCommand] =>
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         entriesBytes = list(variableSizeEntryCodec).encode(entries).require.toByteArray
//         _       <- blocking.effectBlocking(Files.write(logDirectory.resolve("0.log"), fileHeader ++ entriesBytes)).orDie
//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), "0.log", None)

//         readEntries <- segment.stream(Index(0)).runCollect

//       } yield assertTrue(entries == readEntries.toList)
//     }
//   }

//   testM("stream from index") {
//     checkM(Generators.commands, Gen.int(0, 100)) { (entries, drop) =>
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         entriesBytes = list(variableSizeEntryCodec).encode(entries).require.toByteArray
//         _       <- blocking.effectBlocking(Files.write(logDirectory.resolve("0.log"), fileHeader ++ entriesBytes)).orDie
//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), "0.log", None)

//         fromIndex = drop % entries.length
//         readEntries <- segment.stream(Index(fromIndex)).runCollect

//       } yield assertTrue(entries.drop(fromIndex) == readEntries.toList)
//     }
//   }

//   testM("stream from index lower") {
//     for {
//       logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//       blocking     <- ZIO.service[Blocking.Service]
//       entries      <- Generators.commands.runHead.someOrFail(new Exception("No commands"))
//       fileName = s"${entries.length + 10}.log"
//       fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//       entriesBytes = list(variableSizeEntryCodec).encode(entries).require.toByteArray
//       _           <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader ++ entriesBytes)).orDie
//       segment     <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, None)
//       readEntries <- segment.stream(Index(0)).runCollect

//     } yield assertTrue(entries == readEntries.toList)
//   }

//   testM("stream from higher index") {
//     for {
//       logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//       blocking     <- ZIO.service[Blocking.Service]
//       entries      <- Generators.commands.runHead.someOrFail(new Exception("No commands"))
//       fileName = s"0.log"
//       fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//       entriesBytes = list(variableSizeEntryCodec).encode(entries).require.toByteArray
//       _           <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader ++ entriesBytes)).orDie
//       segment     <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, None)
//       readEntries <- segment.stream(Index(entries.length + 1)).runCollect

//     } yield assertTrue(readEntries.toList == Nil)
//   }

//   testM("stream from second index") {
//     for {
//       logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//       blocking     <- ZIO.service[Blocking.Service]
//       entries      <- Generators.commands.runHead.someOrFail(new Exception("No commands"))
//       fileName = s"1000.log"
//       fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//       entriesBytes = list(variableSizeEntryCodec).encode(entries).require.toByteArray
//       _           <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader ++ entriesBytes)).orDie
//       segment     <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, None)
//       readEntries <- segment.stream(Index(1001)).runCollect

//     } yield assertTrue(entries.drop(1) == readEntries.toList)
//   }

//   testM("stream reversed") {
//     checkM(Generators.commands) { entries: List[WriteCommand] =>
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"1000.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         entriesBytes = list(variableSizeEntryCodec).encode(entries).require.toByteArray
//         _           <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader ++ entriesBytes)).orDie
//         segment     <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, None)
//         readEntries <- segment.streamReversed.runCollect

//       } yield assertTrue(entries.reverse == readEntries.toList)
//     }
//   }

//   suite("isInSegment") {
//     testM("equal first index") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"0.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, None)

//       } yield assertTrue(segment.isInSegment(Index(0)))
//     }

//     testM("equal last index exclusive") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"0.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("10.log"))

//       } yield assertTrue(!segment.isInSegment(Index(10)))
//     }

//     testM("one less than last index exclusive") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"0.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("10.log"))

//       } yield assertTrue(segment.isInSegment(Index(9)))
//     }

//     testM("between first and last") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"0.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("10.log"))

//       } yield assertTrue(segment.isInSegment(Index(5)))
//     }
//   }

//   suite("isInStream") {
//     testM("equal first index") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"1000.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(segment.isInStream(Index(1000)))
//     }

//     testM("equal last index exclusive") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"1000.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(!segment.isInStream(Index(2000)))
//     }

//     testM("one less than last index exclusive") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"0.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("10.log"))

//       } yield assertTrue(segment.isInStream(Index(9)))
//     }

//     testM("between first and last") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"1000.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(segment.isInStream(Index(1500)))
//     }

//     testM("sinceIndex before the firstIndex") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"1000.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(segment.isInStream(Index(5)))
//     }

//     testM("sinceIndex after the lastIndex") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"1000.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(!segment.isInStream(Index(5000)))
//     }

//     testM("open file, always in stream") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileName = s"1000.log"
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         _ <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie

//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, None)

//       } yield assertTrue(segment.isInStream(Index(5)))
//     }
//   }

//   suite("canBeDeleted") {
//     testM("equal first index - can't be deleted") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         fileName = "1000.log"
//         _       <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie
//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(!segment.canBeDeleted(Index(1000)))
//     }

//     testM("equal last index exclusive - can be deleted") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         fileName = "1000.log"
//         _       <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie
//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(segment.canBeDeleted(Index(2000)))
//     }

//     testM("watermark in the segment - can't be deleted") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         fileName = "1000.log"
//         _       <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie
//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(!segment.canBeDeleted(Index(1500)))
//     }

//     testM("watermark before the segment - can't deleted") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         fileName = "1000.log"
//         _       <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie
//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(!segment.canBeDeleted(Index(500)))
//     }

//     testM("watermark after the segment - can be deleted") {
//       for {
//         logDirectory <- ZIO.effect(Files.createTempDirectory("logs"))
//         blocking     <- ZIO.service[Blocking.Service]
//         fileHeader = fileHeaderCodec.encode(()).require.toByteArray
//         fileName = "1000.log"
//         _       <- blocking.effectBlocking(Files.write(logDirectory.resolve(fileName), fileHeader)).orDie
//         segment <- ReadOnlySegment.open(blocking, logDirectory.toString(), fileName, Some("2000.log"))

//       } yield assertTrue(segment.canBeDeleted(Index(2500)))
//     }
//   }

// }
