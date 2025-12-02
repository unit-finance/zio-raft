package zio

import zio.raft.{Command, HMap}
import zio.prelude.Newtype

package object kvstore:

  sealed trait KVCommand extends Command
  object KVCommand:
    final case class Set(key: String, value: String) extends KVCommand:
      type Response = KVResponse.SetDone.type
    final case class Watch(key: String) extends KVCommand:
      type Response = KVResponse.WatchDone.type
    case object PurgeUnwatchedKeys extends KVCommand:
      type Response = KVResponse.PurgeResult

  sealed trait KVResponse
  object KVResponse:
    case object SetDone extends KVResponse
    final case class GetResult(value: Option[String]) extends KVResponse
    case object WatchDone extends KVResponse
    final case class PurgeResult(keysRemoved: Int) extends KVResponse

  object KVKey extends Newtype[String]
  type KVKey = KVKey.Type
  given HMap.KeyLike[KVKey] = HMap.KeyLike.forNewtype(KVKey)

  type KVSchema =
    ("kv", KVKey, String) *:
      ("subsByKey", KVKey, Set[zio.raft.protocol.SessionId]) *:
      ("subsBySession", zio.raft.protocol.SessionId, Set[KVKey]) *:
      EmptyTuple
