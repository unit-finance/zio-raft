package zio.raft.client

import zio.*
import zio.raft.protocol.*
import scala.math.Ordering
import scodec.bits.ByteVector
import java.time.Instant

/** Manages pending requests with lastSentAt timestamps for retry.
  */
case class PendingRequests(
  requests: Map[RequestId, PendingRequests.PendingRequestData]
) {
  def contains(requestId: RequestId): Boolean = requests.contains(requestId)
  def lowestPendingRequestIdOr(default: RequestId): RequestId =
    if (requests.isEmpty) default else requests.keys.min(using Ordering.by[RequestId, Long](_.value))

  def add(
    requestId: RequestId,
    payload: ByteVector,
    promise: Promise[Nothing, ByteVector],
    sentAt: Instant
  ): PendingRequests =
    copy(requests = requests.updated(requestId, PendingRequests.PendingRequestData(payload, promise, sentAt, sentAt)))

  def complete(requestId: RequestId, result: ByteVector): ZIO[Any, Nothing, PendingRequests] =
    requests.get(requestId) match {
      case Some(data) =>
        data.promise.succeed(result).as(copy(requests = requests.removed(requestId)))
      case None =>
        ZIO.succeed(this)
    }

  /** Resend all pending requests (used after successful connection). Returns updated PendingRequests with new
    * lastSentAt timestamps.
    */
  def resendAll(transport: ClientTransport): UIO[PendingRequests] =
    ZIO.foldLeft(requests.toList)(this) { case (pending, (requestId, data)) =>
      for {
        now <- Clock.instant
        lowestPendingRequestId = pending.lowestPendingRequestIdOr(requestId)
        request = ClientRequest(requestId, lowestPendingRequestId, data.payload, now)
        _ <- transport.sendMessage(request).orDie
        _ <- ZIO.logDebug(s"Resending pending request: $requestId")
        updatedData = data.copy(lastSentAt = now)
      } yield PendingRequests(pending.requests.updated(requestId, updatedData))
    }

  /** Resend expired requests and update lastSentAt.
    */
  def resendExpired(transport: ClientTransport, currentTime: Instant, timeout: Duration): UIO[PendingRequests] = {
    val timeoutSeconds = timeout.toSeconds
    ZIO.foldLeft(requests.toList)(this) { case (pending, (requestId, data)) =>
      val elapsed = Duration.fromInterval(data.lastSentAt, currentTime)
      if (elapsed > timeout) {
        val lowestPendingRequestId = pending.lowestPendingRequestIdOr(requestId)
        val request = ClientRequest(requestId, lowestPendingRequestId, data.payload, currentTime)
        for {
          _ <- transport.sendMessage(request).orDie
          _ <- ZIO.logDebug(s"Resending timed out request: $requestId")
          updatedData = data.copy(lastSentAt = currentTime)
        } yield PendingRequests(pending.requests.updated(requestId, updatedData))
      } else {
        ZIO.succeed(pending)
      }
    }
  }

  def die(requestId: RequestId, error: Throwable): UIO[PendingRequests] =
    requests.get(requestId) match {
      case Some(data) => data.promise.die(error).ignore.as(copy(requests = requests.removed(requestId)))
      case None       => ZIO.succeed(this)
    }

  /** Die all pending requests with the given error. */
  def dieAll(error: Throwable): UIO[Unit] =
    ZIO.foreachDiscard(requests.values)(data => data.promise.die(error).ignore)
}

object PendingRequests {
  def empty: PendingRequests = PendingRequests(Map.empty)

  case class PendingRequestData(
    payload: ByteVector,
    promise: Promise[Nothing, ByteVector],
    createdAt: Instant,
    lastSentAt: Instant
  )
}
