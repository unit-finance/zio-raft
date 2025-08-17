package zio.raft.stores.segmentedlog

import zio.nio.channels.AsynchronousFileChannel
import zio.nio.file.Path
import zio.raft.{Index}
import zio.ZIO

import scodec.Attempt
import zio.raft.stores.segmentedlog.BaseTransducer.Result
import zio.raft.Command
import scodec.Codec
import zio.stream.ZPipeline
import zio.stream.ZSink
import zio.stream.ZStream
import zio.raft.LogEntry
import zio.raft.stores.segmentedlog.internal.logEntryCodec

trait Segment[A <: Command: Codec]:
  val path: Path
  val firstIndex: Index

  def getEntry(index: Index): ZIO[Any, Nothing, Option[LogEntry]]

  def makeStream(channel: AsynchronousFileChannel, validateChecksum: Boolean = false): ZStream[Any, Throwable, Result] =
    channel
      .stream(0)
      .via(BaseTransducer.make(firstIndex, false))

  def recordsOnly: ZPipeline[Any, Nothing, Result, Result.Record] =
    ZPipeline.collect[BaseTransducer.Result, BaseTransducer.Result.Record]:
      case r: BaseTransducer.Result.Record => r

  def decode: ZPipeline[Any, Throwable, Result.Record, LogEntry] =
    ZPipeline.mapZIO[Any, Throwable, BaseTransducer.Result.Record, LogEntry](record =>
      ZIO
        .attemptBlocking(logEntryCodec[A].decodeValue(record.payload))
        .flatMap {
          case Attempt.Successful(value) => ZIO.succeed(value)
          case Attempt.Failure(f)        => ZIO.fail(new Throwable(s"Error occurred: ${f.messageWithContext}"))
        }
    )

  def lastAndDecode: ZSink[Any, Throwable, Result.Record, Result.Record, Option[LogEntry]] =
    ZSink
      .last[BaseTransducer.Result.Record]
      .mapZIO:
        case Some(record) =>
          ZIO
            .attemptBlocking(logEntryCodec[A].decodeValue(record.payload))
            .flatMap:
              case Attempt.Successful(value) => ZIO.some(value)
              case Attempt.Failure(f)        => ZIO.fail(new Throwable(s"Error occurred: ${f.messageWithContext}"))
        case None => ZIO.none
