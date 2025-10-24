package zio.raft.client

import zio.*
import zio.raft.protocol.*
import scodec.bits.ByteVector
import java.time.Instant

/** Manages pending requests with lastSentAt timestamps for retry.
  */
case class PendingRequests(
  requests: Map[RequestId, PendingRequests.PendingRequestData]
) {
  def add(
    requestId: RequestId,
    payload: ByteVector,
    promise: Promise[Throwable, ByteVector],
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
        request = ClientRequest(requestId, data.payload, now)
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
        val request = ClientRequest(requestId, data.payload, currentTime)
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
}

object PendingRequests {
  def empty: PendingRequests = PendingRequests(Map.empty)

  case class PendingRequestData(
    payload: ByteVector,
    promise: Promise[Throwable, ByteVector],
    createdAt: Instant,
    lastSentAt: Instant
  )
}
