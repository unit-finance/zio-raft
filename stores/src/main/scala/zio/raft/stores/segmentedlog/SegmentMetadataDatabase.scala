package zio.raft.stores.segmentedlog

import zio.lmdb.Database
import zio.raft.Index
import java.time.Instant
import scodec.Codec
import scodec.codecs.{discriminated, int8, int64}
import zio.raft.stores.codecs.indexCodec
import zio.lmdb.Environment
import zio.ZIO
import zio.raft.stores.segmentedlog.SegmentMetadataDatabase.{SegmentStatus, SegmentMetadata, segmentMetadataCodec}
import scodec.bits.BitVector
import zio.raft.Term
import zio.raft.stores.codecs.termCodec
import org.lmdbjava.DbiFlags

class SegmentMetadataDatabase(environment: Environment, database: Database):
  def addNew(id: Long, firstIndex: Index, createdAt: Instant, previousTerm: Term) =
    val status = SegmentStatus.Open
    val metadata = SegmentMetadata(id, firstIndex, createdAt, previousTerm, status)

    for
      bytes <- ZIO.attempt(segmentMetadataCodec.encode(metadata).require.toByteArray).orDie
      key <- ZIO.attempt(indexCodec.encode(firstIndex).require.toByteArray).orDie
      _ <- environment.transact(database.put(key, bytes)).orDie
    yield ()

  def close(firstIndex: Index, lastIndex: Index, closedAt: Instant) =
    val program = for
      key <- ZIO.attempt(indexCodec.encode(firstIndex).require.toByteArray).orDie
      existingValueBytes <- database.get(key).someOrFail(new Exception("Segment metadata not found")).orDie
      existingMetadata <- ZIO.attempt(segmentMetadataCodec.decodeValue(BitVector(existingValueBytes)).require).orDie
      closedMetadata = existingMetadata.copy(status = SegmentStatus.Closed(lastIndex, closedAt))
      bytes <- ZIO.attempt(segmentMetadataCodec.encode(closedMetadata).require.toByteArray).orDie
      _ <- database.put(key, bytes).orDie
    yield ()

    environment.transact(program)

  def getAll =
    environment
      .transact(
        database.stream
          .mapZIO((_, value) => ZIO.attempt(segmentMetadataCodec.decodeValue(BitVector(value)).require).orDie)
          .runCollect
      )
      .orDie

  def firstSegment: ZIO[Any, Nothing, SegmentMetadata] =
    environment
      .transact(database.stream.runHead)
      .flatMap:
        case Some((_, value)) =>
          ZIO.attempt(segmentMetadataCodec.decodeValue(BitVector(value)).require).orDie
        case None => ZIO.fail(new Exception("No segment metadata found"))
      .orDie

  def delete(firstIndex: Index) =
    for
      key <- ZIO.attempt(indexCodec.encode(firstIndex).require.toByteArray).orDie
      _ <- environment.transact(database.delete(key)).orDie
    yield ()

  def discardAll = environment.transact(database.truncate).orDie

end SegmentMetadataDatabase

object SegmentMetadataDatabase:
  case class SegmentMetadata(
      id: Long,
      firstIndex: Index,
      createdAt: Instant,
      previousTerm: Term,
      status: SegmentStatus
  ):
    def previousIndex: Index = firstIndex.minusOne

    def fileName =
      val indexString = "%020d".format(firstIndex.value)
      val idString = "%016x".format(id)
      s"$indexString-$idString.wal"

  enum SegmentStatus:
    case Open
    case Closed(lastIndex: Index, closedAt: Instant)

  lazy val instantCodec = int64.xmap[Instant](Instant.ofEpochMilli(_), _.toEpochMilli())

  lazy val segmentStatusCodec: scodec.Codec[SegmentStatus] =
    discriminated[SegmentStatus]
      .by(int8)
      .singleton(0, SegmentStatus.Open)
      .typecase(1, (indexCodec :: instantCodec).as[SegmentStatus.Closed])

  lazy val segmentMetadataCodec: scodec.Codec[SegmentMetadata] =
    (int64 :: indexCodec :: instantCodec :: termCodec :: segmentStatusCodec).as[SegmentMetadata]

  def make =
    for
      environment <- ZIO.service[Environment]
      database <- environment.openDatabase("segment-metadata", DbiFlags.MDB_CREATE).orDie
    yield SegmentMetadataDatabase(environment, database)

end SegmentMetadataDatabase
