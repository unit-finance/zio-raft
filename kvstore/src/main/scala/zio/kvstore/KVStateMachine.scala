package zio.kvstore

import zio.raft.{Index, HMap}
import zio.raft.sessionstatemachine.{SessionStateMachine, ScodecSerialization, ServerRequestForSession, StateWriter}
import zio.raft.sessionstatemachine.asResponseType
import zio.raft.protocol.SessionId
import zio.kvstore.protocol.KVServerRequest
import scodec.Codec
import java.time.Instant
import zio.kvstore.Codecs.given
import zio.raft.sessionstatemachine.given
import zio.raft.sessionstatemachine.Codecs.{sessionMetadataCodec, requestIdCodec, pendingServerRequestCodec}

class KVStateMachine extends SessionStateMachine[KVCommand, KVResponse, zio.kvstore.protocol.KVServerRequest, KVSchema]
    with ScodecSerialization[KVResponse, zio.kvstore.protocol.KVServerRequest, KVSchema]:

  // Local alias to aid match-type reduction bug in scala 3.3
  type Schema = Tuple.Concat[zio.raft.sessionstatemachine.SessionSchema[KVResponse, KVServerRequest], KVSchema]
  private type SW[A] = StateWriter[HMap[Schema], ServerRequestForSession[zio.kvstore.protocol.KVServerRequest], A]

  // Helpers
  private def putValue(key: KVKey, value: String): SW[Unit] =
    StateWriter.update[HMap[Schema], HMap[Schema]](s => s.updated["kv"](key, value))

  private def getValue(key: KVKey): SW[Option[String]] =
    StateWriter.get[HMap[Schema]].map { s =>
      s.get["kv"](key)
    }

  private def subscribersFor(key: KVKey): SW[Set[zio.raft.protocol.SessionId]] =
    StateWriter.get[HMap[Schema]].map { s =>
      s.get["subsByKey"](key).getOrElse(Set.empty)
    }

  private def addSubscription(sessionId: zio.raft.protocol.SessionId, key: KVKey): SW[Unit] =
    StateWriter.update[HMap[Schema], HMap[Schema]] { s =>
      val subs = s.get["subsByKey"](key).getOrElse(Set.empty)
      val s1 = s.updated["subsByKey"](key, subs + sessionId)
      val keys = s1.get["subsBySession"](sessionId).getOrElse(Set.empty)
      s1.updated["subsBySession"](sessionId, keys + key)
    }

  private def removeAllSubscriptions(sessionId: zio.raft.protocol.SessionId): SW[Unit] =
    StateWriter.update[HMap[Schema], HMap[Schema]] { s =>
      val keys = s.get["subsBySession"](sessionId).getOrElse(Set.empty)
      val cleared = keys.foldLeft(s) { (acc, k) =>
        val set = acc.get["subsByKey"](k).getOrElse(Set.empty) - sessionId
        acc.updated["subsByKey"](k, set)
      }
      cleared.removed["subsBySession"](sessionId)
    }

  // Typeclass map for Schema → Codec
  val codecs = summon[HMap.TypeclassMap[Schema, Codec]]

  protected def applyCommand(
    createdAt: Instant,
    sessionId: SessionId,
    command: KVCommand
  ): SW[command.Response & KVResponse] =
    command match
      case set @ KVCommand.Set(key, value) =>
        val k = KVKey(key)
        for
          _ <- putValue(k, value)
          sessions <- subscribersFor(k)
          _ <- StateWriter.foreach(sessions) { sid =>
            StateWriter.log(
              ServerRequestForSession[
                KVServerRequest
              ](
                sid,
                KVServerRequest.Notification(key, value)
              )
            )
          }
        yield KVResponse.SetDone.asResponseType(command, set)

      case watch @ KVCommand.Watch(key) =>
        val k = KVKey(key)
        for
          _ <- addSubscription(sessionId, k)
          current <- getValue(k)
          _ <- current match
            case Some(v) =>
              StateWriter.log(
                ServerRequestForSession[
                  KVServerRequest
                ](
                  sessionId,
                  KVServerRequest.Notification(key, v)
                )
              )
            case None => StateWriter.unit
        yield KVResponse.WatchDone.asResponseType(command, watch)

  protected def handleSessionCreated(
    createdAt: Instant,
    sessionId: SessionId,
    capabilities: Map[String, String]
  ): SW[Unit] =
    StateWriter.unit

  protected def handleSessionExpired(
    createdAt: Instant,
    sessionId: SessionId,
    capabilities: Map[String, String]
  ): SW[Unit] =
    removeAllSubscriptions(sessionId)

  override def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
    (commitIndex.value - lastSnapshotIndex.value) >= 100

end KVStateMachine
