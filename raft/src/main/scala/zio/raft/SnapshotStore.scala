package zio.raft

import zio.stream.{Stream, ZStream}
import zio.{Chunk, Ref, UIO, ZIO}

case class Snapshot(
  previousIndex: Index,
  previousTerm: Term,
  stream: Stream[Nothing, Byte]
)

trait SnapshotStore:
  def createNewPartialSnapshot(
    previousTerm: Term,
    previousIndex: Index
  ): UIO[Unit]

  // TODO: return false if the offset doesn't match the current offset
  def writePartial(
    previousTerm: Term,
    previousIndex: Index,
    offset: Long,
    data: zio.Chunk[Byte]
  ): UIO[Boolean]

  // save snapshot file, last index and last term and discard any existing snapshot
  def completePartial(previousTerm: Term, previousIndex: Index): UIO[Snapshot]

  // delete the partial snapshot
  def deletePartial(previousTerm: Term, previousIndex: Index): UIO[Unit]

  def createNewSnapshot(snapshot: Snapshot): UIO[Unit]

  def latestSnapshotIndex: UIO[Index]

  def latestSnapshotIndexAndSize: UIO[(Index, Long)]

  def latestSnapshot: UIO[Option[Snapshot]]

object SnapshotStore:
  def makeInMemory =
    for
      latest <- Ref.make(None: Option[Snapshot])
      partial <- Ref.make(Map.empty[(Term, Index), Chunk[Byte]])
    yield InMemorySnapshotStore(latest, partial)

  class InMemorySnapshotStore(
    latest: Ref[Option[Snapshot]],
    partial: Ref[Map[(Term, Index), Chunk[Byte]]]
  ) extends SnapshotStore:
    override def createNewPartialSnapshot(
      previousTerm: Term,
      previousIndex: Index
    ): UIO[Unit] =
      partial.set(Map((previousTerm, previousIndex) -> Chunk.empty))

    override def writePartial(
      previousTerm: Term,
      previousIndex: Index,
      offset: Long,
      data: zio.Chunk[Byte]
    ): UIO[Boolean] =
      for
        snapshot <- partial.get
          .map(map => map.get((previousTerm, previousIndex)))
          .someOrFail(new Exception("no partial snapshot"))
          .orDie
        _ <- partial.update(
          _ + ((previousTerm, previousIndex) -> (snapshot ++ data))
        )
      yield true

    override def completePartial(
      previousTerm: Term,
      previousIndex: Index
    ) =
      for
        data <- partial.get
          .map(map => map.get((previousTerm, previousIndex)))
          .someOrFail(new Exception("no partial snapshot"))
          .orDie
        snapshot = Snapshot(previousIndex, previousTerm, ZStream.fromChunk(data))
        _ <- latest.set(Some(snapshot))
        _ <- partial.update(_ - ((previousTerm, previousIndex)))
      yield snapshot

    override def deletePartial(
      previousTerm: Term,
      previousIndex: Index
    ): UIO[Unit] =
      partial.update(_ - ((previousTerm, previousIndex)))

    override def createNewSnapshot(snapshot: Snapshot): UIO[Unit] =
      for
        // copy the stream
        chunk <- snapshot.stream.runCollect
        _ <- latest.set(Some(snapshot.copy(stream = ZStream.fromChunk(chunk))))
      yield ()

    override def latestSnapshotIndex: UIO[Index] =
      latest.get.map(_.map(_.previousIndex).getOrElse(Index.zero))
    override def latestSnapshotIndexAndSize: UIO[(Index, Long)] =
      for
        snapshot <- latest.get
        result <- snapshot match
          case None => ZIO.succeedNow((Index.zero, 0L))
          case Some(snapshot) =>
            snapshot.stream.runCollect
              .map(_.size.toLong)
              .map(size => (snapshot.previousIndex, size))
      yield result

    override def latestSnapshot = latest.get
