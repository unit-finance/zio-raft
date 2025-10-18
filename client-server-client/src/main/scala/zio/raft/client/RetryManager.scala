package zio.raft.client

import zio._
import zio.raft.protocol._
import scodec.bits.ByteVector
import java.time.Instant

/**
 * Simple retry manager for tracking request timeouts.
 * Used as part of ClientState for functional state management.
 */
case class RetryManager(
  config: ClientConfig,
  pendingRequests: Map[RequestId, PendingRequestInfo] = Map.empty
) {
  
  def addPendingRequest(
    requestId: RequestId,
    sentAt: Instant
  ): RetryManager = {
    val info = PendingRequestInfo(sentAt, sentAt)
    copy(pendingRequests = pendingRequests.updated(requestId, info))
  }
  
  def removePendingRequest(requestId: RequestId): RetryManager = {
    copy(pendingRequests = pendingRequests.removed(requestId))
  }
  
  def updateLastSent(requestId: RequestId, sentAt: Instant): RetryManager = {
    pendingRequests.get(requestId) match {
      case Some(info) =>
        copy(pendingRequests = pendingRequests.updated(requestId, info.copy(lastSentAt = sentAt)))
      case None =>
        this
    }
  }
  
  def getTimedOutRequests(currentTime: Instant): List[RequestId] = {
    pendingRequests.filter { case (_, info) =>
      val timeSinceLastSent = java.time.Duration.between(info.lastSentAt, currentTime)
      timeSinceLastSent.compareTo(java.time.Duration.ofSeconds(ClientConfig.REQUEST_TIMEOUT.toSeconds)) > 0
    }.keys.toList
  }
}

object RetryManager {
  def empty(config: ClientConfig): RetryManager = 
    RetryManager(config, Map.empty)
}

/**
 * Pending request tracking information.
 */
case class PendingRequestInfo(
  createdAt: Instant,
  lastSentAt: Instant
)
