package zio.raft.stores

import zio.lmdb.Environment
import zio.lmdb.Database
import zio.nio.file.Path
import zio.raft.SnapshotStore
import zio.raft.Index
import zio.UIO
import zio.raft.Term
import zio.raft.Snapshot
import zio.Chunk
import zio.Ref
import zio.raft.stores.FileSnapshotStore.{SnapshotStatus}
import zio.lmdb.TransactionScope
import zio.ZIO
import zio.stream.Stream
import scodec.Codec
import scodec.codecs.{discriminated, uint8, int64}
import scodec.bits.BitVector
import zio.nio.file.Files
import zio.nio.channels.AsynchronousFileChannel
import java.nio.file.StandardOpenOption
import zio.nio.Buffer
import zio.stream.ZStream

class FileSnapshotStore(
  environment: Environment,
  database: Database,
  directory: Path,
  latest: Ref[Option[(Term, Index, Long)]]
) extends SnapshotStore:

  private def saveValue(
    term: Term,
    index: Index,
    status: FileSnapshotStore.SnapshotStatus
  ): ZIO[TransactionScope, Nothing, Unit] =
    for
      key <- ZIO.fromTry(FileSnapshotStore.keyCodec.encode((index, term)).toTry).orDie
      value <- ZIO.fromTry(FileSnapshotStore.enumCodec.encode(status).toTry).orDie
      _ <- database.put(key.toByteArray, value.toByteArray).orDie
    yield ()

  private def getValue(term: Term, index: Index): ZIO[TransactionScope, Nothing, Option[SnapshotStatus]] =
    for
      key <- ZIO.fromTry(FileSnapshotStore.keyCodec.encode((index, term)).toTry).orDie
      bytes <- database.get(key.toByteArray).orDie
      status <- bytes match
        case None        => ZIO.succeed(None)
        case Some(value) => ZIO.fromTry(FileSnapshotStore.enumCodec.decodeValue(BitVector(value)).toTry).orDie.asSome
    yield status

  private def deleteValue(term: Term, index: Index): ZIO[TransactionScope, Nothing, Unit] =
    for
      key <- ZIO.fromTry(FileSnapshotStore.keyCodec.encode((index, term)).toTry).orDie
      _ <- database.delete(key.toByteArray).orDie
    yield ()

  private def generateFileName(term: Term, index: Index): Path =
    directory / s"${term.value}-${index.value}"

  private def createPartialFile(term: Term, index: Index): UIO[Unit] =
    val fileName = generateFileName(term, index)
    for
      _ <- Files.deleteIfExists(fileName).orDie
      _ <- Files.createFile(fileName).orDie
      _ <- ZIO
        .scoped(
          AsynchronousFileChannel
            .open(fileName, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            .flatMap(_.force(true))
        )
        .orDie
      _ <- forceDirectory
    yield ()

  // force the directory to be flushed, important for new files
  private def forceDirectory =
    ZIO.scoped(AsynchronousFileChannel.open(directory, StandardOpenOption.READ).flatMap(_.force(true))).orDie

  private def deleteFile(term: Term, index: Index): UIO[Unit] =
    val fileName = generateFileName(term, index)
    Files.deleteIfExists(fileName).unit.orDie

  private def writeToPartial(term: Term, index: Index, offset: Long, data: Chunk[Byte]): UIO[Unit] =
    val fileName = generateFileName(term, index)
    for
      byteBuffer <- Buffer.byte(data)
      _ <- ZIO
        .scoped(
          AsynchronousFileChannel
            .open(fileName, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
            .flatMap(_.write(byteBuffer, offset))
        )
        .orDie
    yield ()

  private def writeFile(term: Term, index: Index, stream: Stream[Nothing, Byte]): UIO[Long] =
    val fileName = generateFileName(term, index)
    val program =
      for
        channel <- AsynchronousFileChannel.open(
          fileName,
          StandardOpenOption.WRITE,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
        _ <- stream.run(channel.sink(0L))
        _ <- channel.force(true)
        size <- channel.size
      yield size

    ZIO.scoped(program).zipLeft(forceDirectory).orDie

  private def snapshotStream(term: Term, index: Index): Stream[Nothing, Byte] =
    val fileName = generateFileName(term, index)
    ZStream.scoped(AsynchronousFileChannel.open(fileName, StandardOpenOption.READ)).flatMap(_.stream(0L)).orDie

  private def updateLatest(term: Term, index: Index, size: Long): UIO[Unit] =
    latest.update:
      case None                                             => Some((term, index, size))
      case Some(_, currentIndex, _) if currentIndex < index => Some((term, index, size))
      case x                                                => x

  private def deleteOldSnapshots =
    latest.get.flatMap:
      case None => ZIO.unit
      case Some(lastestTerm, latestIndex, _) =>
        for
          oldSnapshots <- environment
            .transactReadOnly(
              database.stream
                .mapZIO((key, value) =>
                  for
                    tuple <- ZIO.fromTry(FileSnapshotStore.keyCodec.decodeValue(BitVector(key)).toTry).orDie
                    (index, term) = tuple
                    status <- ZIO.fromTry(FileSnapshotStore.enumCodec.decodeValue(BitVector(value)).toTry).orDie
                  yield (term, index, status)
                )
                .collect {
                  case (term, index, SnapshotStatus.Complete(size)) if !(term == lastestTerm && index == latestIndex) =>
                    (term, index)
                }
                .runCollect
            )
            .orDie

          _ <- ZIO.foreachDiscard(oldSnapshots) { case (term, index) =>
            environment.transact(deleteValue(term, index)) *> deleteFile(term, index)
          }
        yield ()

  override def createNewPartialSnapshot(previousTerm: Term, previousIndex: Index): UIO[Unit] =
    for
      // Delete the previous snapshot if exist
      _ <- environment.transact(deleteValue(previousTerm, previousIndex))
      _ <- createPartialFile(previousTerm, previousIndex)
      _ <- environment.transact(
        saveValue(previousTerm, previousIndex, status = FileSnapshotStore.SnapshotStatus.Partial(0L))
      )
    yield ()

  override def deletePartial(previousTerm: Term, previousIndex: Index): UIO[Unit] =
    for
      _ <- deleteFile(previousTerm, previousIndex)
      _ <- environment.transact(deleteValue(previousTerm, previousIndex))
    yield ()

  override def writePartial(previousTerm: Term, previousIndex: Index, offset: Long, data: Chunk[Byte]): UIO[Boolean] =
    for
      value <- environment.transactReadOnly(getValue(previousTerm, previousIndex))
      result <- value match
        case Some(SnapshotStatus.Partial(currentOffset)) if currentOffset == offset =>
          for
            _ <- writeToPartial(previousTerm, previousIndex, offset, data)
            _ <- environment.transact(
              saveValue(previousTerm, previousIndex, status = SnapshotStatus.Partial(offset + data.size))
            )
          yield true
        case _ => ZIO.succeed(false)
    yield result

  override def latestSnapshotIndex: UIO[Index] = latest.get.map:
    case None              => Index(0)
    case Some(_, index, _) => index

  override def latestSnapshotIndexAndSize: UIO[(Index, Long)] = latest.get.map:
    case None                 => (Index(0), 0L)
    case Some(_, index, size) => (index, size)

  override def latestSnapshot: UIO[Option[Snapshot]] = latest.get.map:
    case None                   => None
    case Some((term, index, _)) => Some(Snapshot(index, term, snapshotStream(term, index)))

  override def completePartial(previousTerm: Term, previousIndex: Index): UIO[Snapshot] =
    for
      // TODO: how can we be sure we didn't miss any chunks?
      value <- environment.transactReadOnly(getValue(previousTerm, previousIndex))
      snapshot <- value match
        case Some(SnapshotStatus.Partial(offset)) =>
          for
            _ <- environment.transact(saveValue(previousTerm, previousIndex, status = SnapshotStatus.Complete(offset)))
            _ <- updateLatest(previousTerm, previousIndex, offset)
            _ <- deleteOldSnapshots
          yield Snapshot(previousIndex, previousTerm, snapshotStream(previousTerm, previousIndex))

        case _ => ZIO.die(new Throwable(s"Partial Snapshot not found for term: $previousTerm, index: $previousIndex"))
    yield snapshot

  override def createNewSnapshot(snapshot: Snapshot): UIO[Unit] =
    for
      size <- writeFile(snapshot.previousTerm, snapshot.previousIndex, snapshot.stream)
      _ <- environment.transact(
        saveValue(snapshot.previousTerm, snapshot.previousIndex, status = SnapshotStatus.Complete(size))
      )
      _ <- updateLatest(snapshot.previousTerm, snapshot.previousIndex, size)
      _ <- deleteOldSnapshots
    yield ()

object FileSnapshotStore:
  def make(directory: Path): ZIO[Environment, Nothing, FileSnapshotStore] =
    for
      environment <- ZIO.service[Environment]
      database <- Database.open("snapshots").orDie
      latest <- environment
        .transact(
          database.stream
            .mapZIO((key, value) =>
              for
                tuple <- ZIO.fromTry(keyCodec.decodeValue(BitVector(key)).toTry).orDie
                (index, term) = tuple
                status <- ZIO.fromTry(enumCodec.decodeValue(BitVector(value)).toTry).orDie
              yield (term, index, status)
            )
            .collect { case (term, index, SnapshotStatus.Complete(size)) =>
              (term, index, size)
            }
            .runLast
        )
        .orDie
      ref <- Ref.make(latest)
    // TODO: we can end up with partial snapshots, we need to clean them up
    // TODO: we can end up with snapshots files without entries in the database, we need to clean them up
    yield FileSnapshotStore(environment, database, directory, ref)

  enum SnapshotStatus:
    case Partial(offset: Long)
    case Complete(size: Long)

  // Index must be first, so we would be able to find the latest snapshot. Index codec is big endian, which works for lmdb ordering
  val keyCodec: Codec[(Index, Term)] =
    (codecs.indexCodec :: codecs.termCodec).as[(Index, Term)]

  val enumCodec: Codec[SnapshotStatus] =
    discriminated[SnapshotStatus]
      .by(uint8)
      .typecase(0, int64.as[SnapshotStatus.Partial])
      .typecase(1, int64.as[SnapshotStatus.Complete])
