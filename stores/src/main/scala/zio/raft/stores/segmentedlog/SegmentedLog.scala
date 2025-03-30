package zio.raft.stores.segmentedlog

import zio.raft.stores.segmentedlog.internal.*
import zio.raft.{Command, Index, LogEntry, LogStore, Term}
import zio.stream.ZStream
import zio.{UIO, ZIO}
import zio.raft.stores.segmentedlog.SegmentMetadataDatabase.{SegmentMetadata, SegmentStatus}
import scodec.Codec
import zio.Scope
import zio.lmdb.Environment

class SegmentedLog[A <: Command: Codec](
    logDirectory: String,
    maxLogFileSize: Long,
    currentSegment: CurrentSegment[A],
    lastIndexRef: LocalLongRef[Index],
    lastTermRef: LocalLongRef[Term],
    segmentMetadataDatabase: SegmentMetadataDatabase
) extends LogStore[A]:

  override def lastIndex: UIO[Index] = lastIndexRef.get

  override def lastTerm: UIO[Term] = lastTermRef.get

  private def previousTermIndexOfEntireLog =
    for firstSegment <- segmentMetadataDatabase.firstSegment
    yield (firstSegment.previousTerm, firstSegment.firstIndex.minusOne)

  override def logTerm(index: Index) =
    if index.isZero then ZIO.some(Term.zero)
    else
      for
        lastIndexValue <- this.lastIndex
        term <-
          if lastIndexValue == index then lastTermRef.get.asSome
          else
            getLog(index).flatMap:
              case Some(entry) => ZIO.some(entry.term)
              case None =>
                for (previousTerm, previousIndex) <- previousTermIndexOfEntireLog
                yield if index == previousIndex then Some(previousTerm) else None
      yield term

  private def getLog(index: Index): UIO[Option[LogEntry[A]]] =
    for
      current <- currentSegment.get
      segment <-
        if index >= current.firstIndex then ZIO.some[Segment[A]](current)
        else listSegments.map(_.find(_.isInSegment(index)))
      entry <- segment match
        case None          => ZIO.none
        case Some(segment) => segment.getEntry(index)
    yield entry

  override def getLogs(from: Index, toInclusive: Index): UIO[Option[List[LogEntry[A]]]] =
    for
      entries <- stream(from, toInclusive).runCollect
      result <- entries.headOption match
        case Some(entry) if entry.index == from => ZIO.some(entries.toList)
        case _                                  => ZIO.none
    yield result

  override def stream(fromInclusive: Index, toInclusive: Index): ZStream[Any, Nothing, LogEntry[A]] =
    // very good chance that everything we need is in the current segment, let's optimize for that
    ZStream
      .fromZIO(currentSegment.get)
      .flatMap(current =>
        if fromInclusive >= current.firstIndex then current.stream(fromInclusive, toInclusive).orDie
        else
          ZStream
            .fromZIO(listSegments)
            .flattenIterables
            .filter(_.isInStream(fromInclusive, toInclusive))
            .flatMap(_.stream(fromInclusive, toInclusive).orDie)
      )

  override def storeLog(entry: LogEntry[A]) =
    for {
      logFile <- currentSegment.get
      _ <- logFile.writeEntry(entry)

      // TODO: check that the index matches

      _ <- lastIndexRef.set(entry.index)
      _ <- lastTermRef.set(entry.term)

      // We allow a file to exceed the maximum size, but immediately after that we create a new file
      logFileSize <- logFile.size
      _ <- ZIO.when(logFileSize > maxLogFileSize)(createNextSegment())
    } yield ()

  override def storeLogs(entries: List[LogEntry[A]]): UIO[Unit] =
    for {
      logFile <- currentSegment.get
      _ <- logFile.writeEntries(entries)

      // TODO: check that the index matches

      last = entries.last
      _ <- lastIndexRef.set(last.index)
      _ <- lastTermRef.set(last.term)

      // We allow a file to exceed the maximum size, but immediately after that we create a new file
      logFileSize <- logFile.size
      _ <- ZIO.when(logFileSize > maxLogFileSize)(createNextSegment())
    } yield ()

  override def discardEntireLog(previousIndex: Index, previousTerm: Term): UIO[Unit] =
    for
      // We have to close the current segment otherwise we won't be able to delete it
      _ <- currentSegment.close()

      segments <- listSegments

      // We first truncate the database, before we delete all files
      // in case of a crash after the database is truncated we would start from empty db
      // without previousIndex and previousTerm
      // TODO: if we would add the new segment as part of the same transaction we discard all, it would not loose the previousIndex and previousTerm
      _ <- segmentMetadataDatabase.discardAll

      // After the database is empty, we can delete the files
      _ <- ZIO.foreachDiscard(segments)(_.delete)

      firstSegment <- SegmentedLog
        .createNewSegment[A](logDirectory, segmentMetadataDatabase, previousIndex.plusOne, previousTerm)

      _ <- currentSegment.switch(firstSegment)
      _ <- lastIndexRef.set(previousIndex)
      _ <- lastTermRef.set(previousTerm)
    yield ()

  // we might delete less entries than requested, as we can only delete entire segments
  // this is fine because discardLogUpTo is only used for log compaction
  // TODO: store in lmdb the new first index (and previous term), if entry before that is requested we can fail
  override def discardLogUpTo(index: Index): UIO[Unit] =
    for
      segments <- listSegments

      segmentsToDelete = segments.filter(_.canBeDeleted(index))

      // We delete the segments in order, so we don't end up with a gap in the log (in case of crash)
      _ <- ZIO.foreachDiscard(segmentsToDelete)(segment =>
        for
          // We first delete the metadata, so in case of a crash we won't end up with metadata but no file
          _ <- segmentMetadataDatabase.delete(segment.firstIndex)
          _ <- segment.delete
        yield ()
      )
    yield ()

  override def deleteFrom(minInclusive: Index): UIO[Unit] =
    val previousIndex = minInclusive.minusOne
    for
      previousTerm <- logTerm(previousIndex).someOrFail(new Exception("No term found for previous index")).orDie

      // we have to close the current segment otherwise we won't be able to delete it
      _ <- currentSegment.close()

      // We have to delete segments that are above minInclusive, and one segment that we have to truncate)
      segmentsWeCanDeleteEntirely <- listSegments.map(_.filter(_.firstIndex > minInclusive))

      // We delete the segments in reverse order, so we don't end up with a gap in the log (in case of crash)
      _ <- ZIO.foreachDiscard(segmentsWeCanDeleteEntirely.reverse)(segment =>
        for
          // We first delete the metadata, so in case of a crash we won't end up with metadata but no file
          _ <- segmentMetadataDatabase.delete(segment.firstIndex)
          _ <- segment.delete
        yield ()
      )

      // We know for sure that we have at least one segment, because in case of firstIndex == minInclusive we are not deleting the entire segment (see the condition above)
      // in case of firstIndex == minInclusive we would just truncate the file
      lastSegment <- segmentMetadataDatabase.getAll.map(_.last)
      _ <- currentSegment.switch(
        OpenSegment.openSegment[A](
          logDirectory,
          lastSegment.fileName,
          lastSegment.firstIndex,
          lastSegment.previousTerm
        )
      )

      // In last, we have to delete the entries from the current segment
      current <- currentSegment.get
      _ <- current.deleteFrom(minInclusive)

      _ <- lastIndexRef.set(previousIndex)
      _ <- lastTermRef.set(previousTerm)
    yield ()

  private def createNextSegment(): ZIO[Any, Nothing, Unit] =
    for {
      firstIndex <- lastIndex.map(_.plusOne)
      previousTerm <- lastTerm
      newSegment <- SegmentedLog.createNewSegment[A](logDirectory, segmentMetadataDatabase, firstIndex, previousTerm)
      oldSegment <- currentSegment.get
      oldSegmentSize <- oldSegment.size
      _ <- ZIO.logInfo(
        s"Creating new segment, old segment ${oldSegment.firstIndex}, old file size: $oldSegmentSize, new file ${firstIndex}"
      )
      _ <- currentSegment.switch(newSegment)
    } yield ()

  // TODO: this should be a stream or in memory
  private def listSegments =
    for
      metadata <- segmentMetadataDatabase.getAll
      segments <- ZIO.foreach(metadata):
        case s @ SegmentMetadata(_, firstIndex, _, _, SegmentStatus.Closed(lastIndex, _)) =>
          ReadOnlySegment.open[A](logDirectory, s.fileName, firstIndex, Some(lastIndex))
        case s @ SegmentMetadata(_, firstIndex, _, _, SegmentStatus.Open) =>
          ReadOnlySegment.open[A](logDirectory, s.fileName, firstIndex, None)
    yield segments
end SegmentedLog

object SegmentedLog:

  private def createNewSegment[A <: Command: Codec](
      logDirectory: String,
      database: SegmentMetadataDatabase,
      firstIndex: Index,
      previousTerm: Term
  ): ZIO[Any, Nothing, ZIO[Scope, Nothing, OpenSegment[A]]] =
    for
      id <- zio.Random.nextLong.map(_.abs)
      now <- zio.Clock.instant
      metadata = SegmentMetadata(id, firstIndex, now, previousTerm, SegmentStatus.Open)
      newSegment <- OpenSegment.createNewSegment[A](logDirectory, metadata.fileName, firstIndex, previousTerm)
      _ <- database.addNew(id, firstIndex, now, previousTerm)
    yield newSegment

  // Using 100mb is the default, this might be too big. However, a smaller size will cause more files to be created
  // because we are saving 6 months back, this can be more files than the OS can handle.
  // We should probably not save 6 months back, and archive tasks using other methods to allow restore
  def make[A <: Command: Codec](
      logDirectory: String,
      maxLogFileSize: Long = 1024 * 1024 * 100 /*100 MB*/
  ): ZIO[Environment & Scope, Nothing, SegmentedLog[A]] =
    for {
      database <- SegmentMetadataDatabase.make
      segments <- database.getAll

      openSegment <-
        if segments.isEmpty then createNewSegment(logDirectory, database, Index.one, Term.zero)
        else
          val lastSegment = segments.last
          ZIO.succeed(
            OpenSegment.openSegment[A](
              logDirectory,
              lastSegment.fileName,
              lastSegment.firstIndex,
              lastSegment.previousTerm
            )
          )

      currentFile <- CurrentSegment.make[A](openSegment)

      // Recover the open segment from crash
      _ <- currentFile.get.flatMap(_.recoverFromCrash)

      (lastTerm, lastIndex) <- currentFile.get.flatMap(_.getLastTermIndex)
      lastIndexRef <- LocalLongRef.make(lastIndex.value).map(_.dimap[Index](Index(_), _.value))
      lastTermRef <- LocalLongRef.make(lastTerm.value).map(_.dimap[Term](Term(_), _.value))
    } yield new SegmentedLog[A](logDirectory, maxLogFileSize, currentFile, lastIndexRef, lastTermRef, database)
