package zio.raft.client

import zio.*
import zio.raft.protocol.*
import scodec.bits.ByteVector
import java.time.Instant

/** Manages pending queries with lastSentAt timestamps for retry. */
case class PendingQueries(
  queries: Map[CorrelationId, PendingQueries.PendingQueryData]
) {
  def contains(correlationId: CorrelationId): Boolean = queries.contains(correlationId)

  def add(
    correlationId: CorrelationId,
    payload: ByteVector,
    promise: Promise[Nothing, ByteVector],
    sentAt: Instant
  ): PendingQueries =
    copy(queries = queries.updated(correlationId, PendingQueries.PendingQueryData(payload, promise, sentAt, sentAt)))

  def complete(correlationId: CorrelationId, result: ByteVector): UIO[PendingQueries] =
    queries.get(correlationId) match {
      case Some(data) => data.promise.succeed(result).as(copy(queries = queries.removed(correlationId)))
      case None       => ZIO.succeed(this)
    }

  /** Resend all pending queries (used after successful connection). */
  def resendAll(transport: ClientTransport): UIO[PendingQueries] =
    ZIO.foldLeft(queries.toList)(this) { case (pending, (correlationId, data)) =>
      for {
        now <- Clock.instant
        _ <- transport.sendMessage(Query(correlationId, data.payload, now)).orDie
        _ <- ZIO.logDebug(s"Resending pending query: ${CorrelationId.unwrap(correlationId)}")
        updatedData = data.copy(lastSentAt = now)
      } yield PendingQueries(pending.queries.updated(correlationId, updatedData))
    }

  /** Resend expired queries and update lastSentAt. */
  def resendExpired(transport: ClientTransport, currentTime: Instant, timeout: Duration): UIO[PendingQueries] =
    ZIO.foldLeft(queries.toList)(this) { case (pending, (correlationId, data)) =>
      val elapsed = Duration.fromInterval(data.lastSentAt, currentTime)
      if (elapsed > timeout) {
        for {
          _ <- transport.sendMessage(Query(correlationId, data.payload, currentTime)).orDie
          _ <- ZIO.logDebug(s"Resending timed out query: ${CorrelationId.unwrap(correlationId)}")
          updatedData = data.copy(lastSentAt = currentTime)
        } yield PendingQueries(pending.queries.updated(correlationId, updatedData))
      } else {
        ZIO.succeed(pending)
      }
    }

  /** Die all pending queries with the given error. */
  def dieAll(error: Throwable): UIO[Unit] =
    ZIO.foreachDiscard(queries.values)(data => data.promise.die(error).ignore)
}

object PendingQueries {
  def empty: PendingQueries = PendingQueries(Map.empty)

  case class PendingQueryData(
    payload: ByteVector,
    promise: Promise[Nothing, ByteVector],
    createdAt: Instant,
    lastSentAt: Instant
  )
}
