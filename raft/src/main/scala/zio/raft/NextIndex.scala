package zio.raft

case class NextIndex(nextIndex: Index, map: Map[MemberId, Index]):
  def get(peer: MemberId) = map.get(peer).getOrElse(nextIndex)
  def set(peer: MemberId, index: Index) =
    this.copy(map = map.updated(peer, index))
  def setMaxOf(peer: MemberId, a:Index, b: Index) =
    this.set(peer, Index.max(a, b))    
object NextIndex:
  def apply(nextIndex: Index): NextIndex = 
    NextIndex(nextIndex, Map.empty)
