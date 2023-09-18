package zio.raft

case class MatchIndex(map: Map[MemberId, Index]):
  def get(peer: MemberId) = map.get(peer).getOrElse(Index.zero)
  def set(peer: MemberId, index: Index) =
    this.copy(map.updated(peer, index))

  def indices = map.values.toList
object MatchIndex:
  def apply(peers: Peers) = new MatchIndex(peers.map(_ -> Index.zero).toMap)  
