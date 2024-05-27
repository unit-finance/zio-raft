package zio.raft.zmq

import java.time.Instant

import zio.raft.{AppendEntriesRequest, AppendEntriesResult, Command, RPCMessage, Raft}
import zio.stream.ZPipeline
import zio.{Chunk, Clock, Ref, ZIO}

class RemoveDuplicate[A <: Command](
    refPreviousMessage: Ref[Option[(Instant, RPCMessage[A])]]
):

  val minHeartbeatInterval = Raft.heartbeartInterval.dividedBy(2)

  private def filterMessage(m: RPCMessage[A]) =
    for
      now <- Clock.instant
      previousMessage <- refPreviousMessage.get

      // TODO: filter heartbeats that are too frequent
      filter <-
        previousMessage match
          case None =>
            refPreviousMessage.set(Some((now, m))).as(true)
          case Some(timestamp, previousMessage) =>
            m match
              case m: AppendEntriesRequest[A]
                  if previousMessage == m && now.isBefore(timestamp.plus(minHeartbeatInterval)) =>
                ZIO.succeed(false)
              case _: AppendEntriesResult.Failure[A] if previousMessage == m =>
                ZIO.succeed(false)
              case _ =>
                refPreviousMessage.set(Some((now, m))).as(true)
    yield filter

  def apply(maybeChunk: Option[Chunk[RPCMessage[A]]]) =
    maybeChunk match
      case Some(chunk) => chunk.filterZIO(filterMessage)
      case None        => ZIO.succeed(Chunk.empty)

object RemoveDuplicate:
  def apply[A <: Command]() =
    val push = Ref
      .make[Option[(Instant, RPCMessage[A])]](None)
      .map(ref => new RemoveDuplicate(ref))
      .map(_.apply)
    ZPipeline.fromPush(push)
