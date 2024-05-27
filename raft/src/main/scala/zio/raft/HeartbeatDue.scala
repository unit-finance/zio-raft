package zio.raft

import java.time.Instant

case class HeartbeatDue(map: Map[MemberId, Instant]):
  def set(peer: MemberId, timeout: Instant) =
    this.copy(map.updated(peer, timeout))

  def due(now: Instant, peer: MemberId) =
    map.get(peer) match
      case None          => true
      case Some(timeout) => now.isAfter(timeout)

object HeartbeatDue:
  def empty: HeartbeatDue = HeartbeatDue(Map.empty)
