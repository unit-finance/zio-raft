package zio.kvstore.node

import zio.*
import zio.stream.*
import zio.kvstore.*
import zio.kvstore.protocol.KVServerRequest
import zio.raft.Raft
import zio.raft.protocol.*
import zio.raft.sessionstatemachine.{SessionCommand, PendingServerRequest, SessionStateMachine}
import zio.raft.sessionstatemachine.given
import scodec.Codec
import scodec.bits.ByteVector
import zio.raft.server.RaftServer
import zio.raft.server.RaftServer.RaftAction
import java.time.Instant

/** Node wiring RaftServer, Raft core, and KV state machine. */
final case class Node(
  raftServer: RaftServer,
  raft: zio.raft.Raft[
    zio.raft.HMap[Tuple.Concat[zio.raft.sessionstatemachine.SessionSchema[KVResponse, KVServerRequest], KVSchema]],
    SessionCommand[KVCommand, KVServerRequest]
  ],
  stateMachine: KVStateMachine
):

  // Bring codecs for (de)serialization of commands/responses/server-requests
  import zio.kvstore.Codecs.given
  import zio.kvstore.protocol.KVServerRequest.given

  private def encodeServerRequestPayload(req: KVServerRequest): ByteVector =
    summon[Codec[KVServerRequest]].encode(req).require.bytes

  private def decodeCommand(bytes: ByteVector): KVCommand =
    summon[Codec[KVCommand]].decode(bytes.bits).require.value

  private def dispatchServerRequests(
    now: Instant,
    envelopes: List[zio.raft.sessionstatemachine.ServerRequestEnvelope[KVServerRequest]]
  ): UIO[Unit] =
    ZIO.foreachDiscard(envelopes) { env =>
      val payload = encodeServerRequestPayload(env.payload)
      raftServer.sendServerRequest(env.sessionId, ServerRequest(env.requestId, payload, now))
    }

  // Unified stream pattern (@stream-architecture-pattern): define actions, merge streams, handle in one fold
  private sealed trait NodeAction
  private object NodeAction:
    case class CreateSession(sessionId: SessionId, capabilities: Map[String, String]) extends NodeAction
    case class ClientRequest(
      sessionId: SessionId,
      requestId: RequestId,
      lowestPendingRequestId: RequestId,
      command: KVCommand
    ) extends NodeAction
    case class ServerRequestAck(sessionId: SessionId, requestId: RequestId) extends NodeAction
    case class ExpireSession(sessionId: SessionId) extends NodeAction
    case class RetryTick(now: Instant) extends NodeAction
    case class StateNotificationReceived(notification: zio.raft.StateNotification) extends NodeAction

  private def handleAction(action: NodeAction): UIO[Unit] =
    action match
      // Process raftActions → SessionCommand → raft → publish via RaftServer
      case NodeAction.CreateSession(sessionId, capabilities) =>
        for
          now <- Clock.instant
          cmd = SessionCommand.CreateSession[KVServerRequest](now, sessionId, capabilities)
          res <- raft.sendCommand(cmd).either
          _ <- res match
            case Right(envelopes) =>
              for
                // It's important to confirm session creation before sending server requests
                // As the session might be one of the recipients of the server requests
                _ <- raftServer.confirmSessionCreation(sessionId)
                _ <- dispatchServerRequests(now, envelopes)
              yield ()
            case Left(_) => ZIO.unit
        yield ()

      case NodeAction.ClientRequest(sessionId, requestId, lowestPendingRequestId, command) =>
        command match
          case set: KVCommand.Set =>
            for
              maybe <- applyCommand(sessionId, requestId, lowestPendingRequestId, set)
              _ <- maybe match
                case Some(_) =>
                  val bytes = summon[Codec[KVResponse.SetDone.type]].encode(KVResponse.SetDone).require.bytes
                  raftServer.sendClientResponse(sessionId, ClientResponse(requestId, bytes))
                case _ => ZIO.unit
            yield ()

          case get: KVCommand.Get =>
            for
              maybe <- applyCommand(sessionId, requestId, lowestPendingRequestId, get)
              _ <- maybe match
                case Some(result) =>
                  val bytes = summon[Codec[KVResponse.GetResult]].encode(result).require.bytes
                  raftServer.sendClientResponse(sessionId, ClientResponse(requestId, bytes))
                case _ => ZIO.unit
            yield ()

          case watch: KVCommand.Watch =>
            for
              maybe <- applyCommand(sessionId, requestId, lowestPendingRequestId, watch)
              _ <- maybe match
                case Some(_) =>
                  val bytes = summon[Codec[KVResponse.WatchDone.type]].encode(KVResponse.WatchDone).require.bytes
                  raftServer.sendClientResponse(sessionId, ClientResponse(requestId, bytes))
                case _ => ZIO.unit
            yield ()

      case NodeAction.ServerRequestAck(sessionId, requestId) =>
        for
          now <- Clock.instant
          cmd = SessionCommand.ServerRequestAck[KVServerRequest](now, sessionId, requestId)
          _ <- raft.sendCommand(cmd).either.unit
        yield ()

      case NodeAction.ExpireSession(sessionId) =>
        for
          now <- Clock.instant
          cmd = SessionCommand.SessionExpired[KVServerRequest](now, sessionId)
          res <- raft.sendCommand(cmd).either
          _ <- res match
            case Right(envelopes) => dispatchServerRequests(now, envelopes)
            case Left(_)          => ZIO.unit
        yield ()

      // Retry stream every 10s - dirty read + command to bump lastSentAt
      case NodeAction.RetryTick(now) =>
        val lastSentBefore = now.minusSeconds(10)
        for
          state <- raft.readStateDirty
          hasPending =
            SessionStateMachine.hasPendingRequests[KVResponse, KVServerRequest, KVSchema](state, lastSentBefore)
          _ <-
            if hasPending then
              val cmd = SessionCommand.GetRequestsForRetry[KVServerRequest](now, lastSentBefore)
              raft.sendCommand(cmd).either.flatMap {
                case Right(envelopes) => dispatchServerRequests(now, envelopes)
                case Left(_)          =>
                  // node is always running the job, regardless if leader or not
                  ZIO.unit
              }
            else ZIO.unit
        yield ()

      // State notifications mapping to server leadership signals
      case NodeAction.StateNotificationReceived(notification) =>
        notification match
          case zio.raft.StateNotification.SteppedUp =>
            raft.readState.either.flatMap {
              case Right(state) =>
                val sessionsSSM = state.iterator["metadata"].toList.collect {
                  case (sid: SessionId, md: zio.raft.sessionstatemachine.SessionMetadata) => (sid, md)
                }.toMap
                val sessionsServer: Map[zio.raft.protocol.SessionId, zio.raft.server.SessionMetadata] =
                  sessionsSSM.map { case (sid, md) =>
                    (sid, zio.raft.server.SessionMetadata(md.capabilities, md.createdAt))
                  }
                raftServer.stepUp(sessionsServer)
              case Left(_) => ZIO.unit
            }
          case zio.raft.StateNotification.SteppedDown(leaderId) =>
            raftServer.stepDown(leaderId.map(id => zio.raft.protocol.MemberId(id.value)))
          case zio.raft.StateNotification.LeaderChanged(leaderId) =>
            raftServer.leaderChanged(zio.raft.protocol.MemberId(leaderId.value))

  // Unified stream construction
  private val unifiedStream: ZStream[Any, Nothing, NodeAction] =
    ZStream.mergeAllUnbounded(16)(
      raftServer.raftActions.map {
        case RaftAction.CreateSession(sessionId, capabilities) =>
          NodeAction.CreateSession(sessionId, capabilities)
        case RaftAction.ClientRequest(sessionId, requestId, lowestPendingRequestId, payload) =>
          val command = decodeCommand(payload)
          NodeAction.ClientRequest(sessionId, requestId, lowestPendingRequestId, command)
        case RaftAction.ServerRequestAck(sessionId, requestId) =>
          NodeAction.ServerRequestAck(sessionId, requestId)
        case RaftAction.ExpireSession(sessionId) =>
          NodeAction.ExpireSession(sessionId)
      },
      ZStream.tick(10.seconds).mapZIO(_ => Clock.instant.map(NodeAction.RetryTick.apply)),
      raft.stateNotifications.map(NodeAction.StateNotificationReceived.apply)
    )

  def applyCommand(
    sessionId: SessionId,
    requestId: RequestId,
    lowestPendingRequestId: RequestId,
    command: KVCommand
  ): UIO[Option[command.Response]] =
    for
      now <- Clock.instant
      cmd = SessionCommand.ClientRequest[KVCommand, KVServerRequest](
        now,
        sessionId,
        requestId,
        lowestPendingRequestId,
        command
      )
      either <- raft.sendCommand(cmd).either
      result <- either match
        case Right(Right((resp, envelopes))) =>
          for
            _ <- ZIO.foreachDiscard(envelopes) { env =>
              val payload = encodeServerRequestPayload(env.payload)
              raftServer.sendServerRequest(env.sessionId, ServerRequest(env.requestId, payload, now))
            }
          yield Some(resp.asInstanceOf[command.Response])
        case Right(Left(zio.raft.sessionstatemachine.RequestError.ResponseEvicted)) =>
          raftServer.sendRequestError(sessionId, RequestError(requestId, RequestErrorReason.ResponseEvicted)).as(None)
        case Left(_: zio.raft.NotALeaderError) =>
          // Ignore not leader error, server will handle it eventually
          ZIO.none
    yield result

  // Run unified loop
  def run: UIO[Unit] =
    ZIO.logInfo("Node started") *>
      unifiedStream.mapZIO(handleAction).runDrain
end Node
