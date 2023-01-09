package zio.raft

import java.time.Instant

case class RPCDue(map: Map[MemberId, Instant]):
  def set(peer: MemberId, timeout: Instant) =
    this.copy(map.updated(peer, timeout))

  def ack(peer: MemberId) =
    this.copy(map.updated(peer, Instant.MAX))

  def now(peer: MemberId) =
    this.copy(map.updated(peer, Instant.MIN))

  def due(now: Instant, peer: MemberId) =
    map.get(peer) match
      case None          => true
      case Some(timeout) => now.isAfter(timeout)

object RPCDue:
  def makeNever(peers: Peers) = RPCDue(peers.map(p => (p, Instant.MAX)).toMap)
  def makeNow(peers: Peers) = RPCDue(peers.map(p => (p, Instant.MIN)).toMap)
