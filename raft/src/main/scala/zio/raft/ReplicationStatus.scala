package zio.raft

import java.time.Instant

enum PeerReplicationStatus:
  case Paused
  case Replicating
  case Snapshot(lastResponse: Instant, index: Index)

class ReplicationStatus(val peerStatus: Map[MemberId, PeerReplicationStatus]):
  def pause(peer: MemberId): ReplicationStatus =
    new ReplicationStatus(
      peerStatus.updated(peer, PeerReplicationStatus.Paused)
    )

  def resume(peer: MemberId): ReplicationStatus =
    peerStatus.get(peer) match
      case Some(PeerReplicationStatus.Paused) =>
        new ReplicationStatus(
          peerStatus.updated(peer, PeerReplicationStatus.Replicating)
        )
      case _ => this

  def snapshot(peer: MemberId, now: Instant, index: Index): ReplicationStatus =
    new ReplicationStatus(
      peerStatus.updated(peer, PeerReplicationStatus.Snapshot(now, index))
    )

  def snapshotResponse(
      peer: MemberId,
      now: Instant,
      responseIndex: Index,
      done: Boolean
  ) =
    peerStatus.get(peer) match
      case Some(PeerReplicationStatus.Snapshot(_, snapshotIndex))
          if responseIndex == snapshotIndex =>
        if done then
          new ReplicationStatus(
            peerStatus.updated(peer, PeerReplicationStatus.Replicating)
          )
        else
          new ReplicationStatus(
            peerStatus.updated(
              peer,
              PeerReplicationStatus.Snapshot(now, snapshotIndex)
            )
          )
      case _ => this

  def snapshotFailure(peer: MemberId, now: Instant, responseIndex: Index) =
    peerStatus.get(peer) match
      case Some(PeerReplicationStatus.Snapshot(_, snapshotIndex))
          if responseIndex == snapshotIndex =>
        new ReplicationStatus(
          peerStatus.updated(peer, PeerReplicationStatus.Replicating)
        )
      case _ => this

  def isPaused(peer: MemberId): Boolean =
    peerStatus.get(peer) match
      case Some(PeerReplicationStatus.Paused)         => true
      case Some(PeerReplicationStatus.Snapshot(_, _)) => true
      case _                                          => false

  def isSnapshot(peer: MemberId): Boolean =
    peerStatus.get(peer) match
      case Some(PeerReplicationStatus.Snapshot(_, _)) => true
      case _                                          => false

object ReplicationStatus:
  def apply(peers: Peers) = new ReplicationStatus(
    peers.map(_ -> PeerReplicationStatus.Replicating).toMap
  )
