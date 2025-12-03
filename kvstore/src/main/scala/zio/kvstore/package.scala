package zio

import zio.raft.{Command, HMap}
import zio.prelude.Newtype
import zio.kvstore.protocol.KVServerRequest

package object kvstore:

  sealed trait KVCommand extends Command
  object KVCommand:
    final case class Set(key: String, value: String) extends KVCommand:
      type Response = KVResponse.SetDone.type
    final case class Watch(key: String) extends KVCommand:
      type Response = KVResponse.WatchDone.type

  sealed trait KVResponse
  object KVResponse:
    case object SetDone extends KVResponse
    final case class GetResult(value: Option[String]) extends KVResponse
    case object WatchDone extends KVResponse

  object KVKey extends Newtype[String]
  type KVKey = KVKey.Type
  given HMap.KeyLike[KVKey] = HMap.KeyLike.forNewtype(KVKey)

  type KVSchema =
    ("kv", KVKey, String) *:
      ("subsByKey", KVKey, Set[zio.raft.protocol.SessionId]) *:
      ("subsBySession", zio.raft.protocol.SessionId, Set[KVKey]) *:
      EmptyTuple

  // Explicitly expand CombinedSchema to help Scala 3 reduce match types for HMap.KeyAt/ValueAt
  // Tuple.Concat can be used in later scala versions (>= 3.5)
  type CombinedSchema =
    ("metadata", zio.raft.protocol.SessionId, zio.raft.sessionstatemachine.SessionMetadata) *:
      ("cache", (zio.raft.protocol.SessionId, zio.raft.protocol.RequestId), Either[Nothing, KVResponse]) *:
      (
        "serverRequests",
        (zio.raft.protocol.SessionId, zio.raft.protocol.RequestId),
        zio.raft.sessionstatemachine.PendingServerRequest[KVServerRequest]
      ) *:
      ("lastServerRequestId", zio.raft.protocol.SessionId, zio.raft.protocol.RequestId) *:
      ("highestLowestPendingRequestIdSeen", zio.raft.protocol.SessionId, zio.raft.protocol.RequestId) *:
      ("kv", KVKey, String) *:
      ("subsByKey", KVKey, Set[zio.raft.protocol.SessionId]) *:
      ("subsBySession", zio.raft.protocol.SessionId, Set[KVKey]) *:
      EmptyTuple
