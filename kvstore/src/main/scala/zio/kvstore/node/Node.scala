package zio.kvstore.node

import zio.*
import zio.stream.*
import zio.kvstore.*
import zio.kvstore.protocol.KVServerRequest
import zio.kvstore.protocol.{KVClientRequest, KVQuery}
import zio.kvstore.protocol.KVClientResponse.given
import zio.raft.Raft
import zio.raft.protocol.*
import zio.raft.sessionstatemachine.{SessionCommand, SessionStateMachine}
import zio.raft.sessionstatemachine.Codecs.given
import zio.kvstore.KVServer.KVServerAction
import java.time.Instant
import zio.kvstore.given
import zio.kvstore.Codecs.given scodec.Codec[zio.kvstore.KVCommand]
import zio.kvstore.node.Node.NodeAction
import zio.kvstore.node.Node.NodeAction.*
import zio.raft.stores.LmdbStable
import zio.raft.stores.segmentedlog.SegmentedLog
import zio.raft.stores.FileSnapshotStore
import zio.raft.zmq.ZmqRpc
import zio.raft.NotALeaderError
import zio.raft.sessionstatemachine.ServerRequestEnvelope

/** Node wiring KVServer, Raft core, and KV state machine. */
final case class Node(
  kvServer: KVServer,
  raft: zio.raft.Raft[
    zio.raft.HMap[Tuple.Concat[
      zio.raft.sessionstatemachine.SessionSchema[KVResponse, KVServerRequest, Nothing],
      KVSchema
    ]],
    SessionCommand[KVCommand, KVServerRequest, Nothing]
  ]
):

  private def dispatchServerRequests(
    now: Instant,
    envelopes: List[zio.raft.sessionstatemachine.ServerRequestEnvelope[KVServerRequest]]
  ): UIO[Unit] =
    ZIO.foreachDiscard(envelopes) { env =>
      kvServer.sendServerRequest(now, env.sessionId, env.requestId, env.payload)
    }

  private def handleAction(action: NodeAction): UIO[Unit] =
    action match
      // Process KVServer actions → SessionCommand → raft → publish via KVServer
      case NodeAction.FromServer(KVServerAction.CreateSession(sessionId, capabilities)) =>
        for
          now <- Clock.instant
          cmd = SessionCommand.CreateSession[KVServerRequest](now, sessionId, capabilities)

          cont = (r: Either[NotALeaderError, List[ServerRequestEnvelope[KVServerRequest]]]) =>
            r match
              case Right(envelopes) =>
                for
                  // It's important to confirm session creation before sending server requests
                  // As the session might be one of the recipients of the server requests
                  _ <- kvServer.confirmSessionCreation(sessionId)
                  _ <- dispatchServerRequests(now, envelopes)
                yield ()
              case Left(_) => ZIO.unit

          _ <- raft.sendCommand(cmd, cont)
        yield ()

      case NodeAction.FromServer(KVServerAction.ClientRequest(
          sessionId,
          requestId,
          lowestPendingRequestId,
          clientReq
        )) =>
        clientReq match
          case KVClientRequest.Set(k, v) =>
            val cmd = KVCommand.Set(k, v)
            val cont = (maybe: Option[cmd.Response]) =>
              maybe match
                case Some(_) => kvServer.reply(sessionId, requestId, ())
                case _       => ZIO.unit
            applyCommand(sessionId, requestId, lowestPendingRequestId, cmd, cont)
          case KVClientRequest.Watch(k) =>
            val cmd = KVCommand.Watch(k)
            val cont = (maybe: Option[cmd.Response]) =>
              maybe match
                case Some(_) => kvServer.reply(sessionId, requestId, ())
                case _       => ZIO.unit
            applyCommand(sessionId, requestId, lowestPendingRequestId, cmd, cont)

      case NodeAction.FromServer(KVServerAction.Query(sessionId, correlationId, query)) =>
        query match
          case KVQuery.Get(k) =>
            raft.readState.either.flatMap {
              case Right(state) =>
                val value = state.get["kv"](KVKey(k))
                kvServer.replyQuery(sessionId, correlationId, value)
              case Left(_) => ZIO.unit
            }
          case _ => ZIO.unit

      case NodeAction.FromServer(KVServerAction.ServerRequestAck(sessionId, requestId)) =>
        for
          now <- Clock.instant
          cmd = SessionCommand.ServerRequestAck[KVServerRequest](now, sessionId, requestId)
          _ <- raft.sendCommand(cmd, _ => ZIO.unit).ignore
        yield ()

      case NodeAction.FromServer(KVServerAction.ExpireSession(sessionId)) =>
        for
          now <- Clock.instant
          cmd = SessionCommand.SessionExpired[KVServerRequest](now, sessionId)
          cont = (r: Either[NotALeaderError, List[ServerRequestEnvelope[KVServerRequest]]]) =>
            r match
              case Right(envelopes) => dispatchServerRequests(now, envelopes)
              case Left(_)          => ZIO.unit
          _ <- raft.sendCommand(cmd, cont)
        yield ()

      // Retry stream every 10s - dirty read + command to bump lastSentAt
      case NodeAction.RetryTick(now) =>
        val lastSentBefore = now.minusSeconds(10)
        for
          state <- raft.readStateDirty
          hasPending =
            SessionStateMachine.hasPendingRequests[KVResponse, KVServerRequest, Nothing, KVSchema](
              state,
              lastSentBefore
            )
          _ <-
            if hasPending then
              val cmd = SessionCommand.GetRequestsForRetry[KVServerRequest](now, lastSentBefore)
              val cont = (r: Either[NotALeaderError, List[ServerRequestEnvelope[KVServerRequest]]]) =>
                r match
                  case Right(envelopes) => dispatchServerRequests(now, envelopes)
                  case Left(_)          => ZIO.unit
              raft.sendCommand(cmd, cont)
            else ZIO.unit
        yield ()

      // State notifications mapping to server leadership signals
      case NodeAction.FromRaft(action) =>
        action match
          case zio.raft.RaftAction.SteppedUp =>
            ZIO.logInfo("Node stepped up") *>
              raft.readState.either.flatMap {
                case Right(state) =>
                  val sessions =
                    SessionStateMachine.getSessions[KVResponse, KVServerRequest, Nothing, KVSchema](state).map {
                      case (sessionId: SessionId, metadata) =>
                        (
                          sessionId,
                          zio.raft.sessionstatemachine.SessionMetadata(metadata.capabilities, metadata.createdAt)
                        )
                    }
                  kvServer.stepUp(sessions)
                case Left(_) => ZIO.unit
              }
          case zio.raft.RaftAction.SteppedDown(leaderId) =>
            kvServer.stepDown(leaderId)
          case zio.raft.RaftAction.LeaderChanged(leaderId) =>
            kvServer.leaderChanged(leaderId)
          case zio.raft.RaftAction.CommandContinuation(continuation) =>
            continuation

      case NodeAction.FromServer(_) => ZIO.unit

  // Unified stream construction
  private val unifiedStream: ZStream[Any, Nothing, NodeAction] =
    ZStream.mergeAllUnbounded(16)(
      kvServer.stream.map(NodeAction.FromServer.apply),
      // TODO: filter this if we are not the leader
      ZStream.tick(10.seconds).mapZIO(_ => Clock.instant.map(NodeAction.RetryTick.apply)),
      raft.raftActions.map(NodeAction.FromRaft.apply)
    )

  def applyCommand(
    sessionId: SessionId,
    requestId: RequestId,
    lowestPendingRequestId: RequestId,
    command: KVCommand,
    cont: Option[command.Response] => ZIO[Any, Nothing, Unit]
  ): UIO[Unit] =
    def applyCommandCont(
      now: Instant,
      cmd: SessionCommand.ClientRequest[KVCommand, KVServerRequest, Nothing]
    )(r: Either[NotALeaderError, cmd.Response]): ZIO[Any, Nothing, Unit] =
      r match
        case Right(envelopes, Right(resp)) =>
          for
            _ <- cont(Some(resp.asInstanceOf[command.Response]))
            _ <- ZIO.foreachDiscard(envelopes) { env =>
              kvServer.sendServerRequest(now, env.sessionId, env.requestId, env.payload)
            }
          yield ()
        case Right(envelopes, Left(zio.raft.sessionstatemachine.RequestError.ResponseEvicted)) =>
          for
            _ <- cont(None)
            _ <- ZIO.foreachDiscard(envelopes) { env =>
              kvServer.sendServerRequest(now, env.sessionId, env.requestId, env.payload)
            }
            _ <- kvServer.requestError(sessionId, requestId, zio.raft.sessionstatemachine.RequestError.ResponseEvicted)
          yield ()
        case Left(_: zio.raft.NotALeaderError) =>
          // Ignore not leader error, server will handle it eventually
          cont(None)

    for
      now <- Clock.instant
      cmd = SessionCommand.ClientRequest[KVCommand, KVServerRequest, Nothing](
        now,
        sessionId,
        requestId,
        lowestPendingRequestId,
        command
      )
      _ <- raft.sendCommand(cmd, applyCommandCont(now, cmd))
    yield ()

  // Run unified loop
  def run: UIO[Unit] =
    ZIO.logInfo("Node started") *>
      unifiedStream.mapZIO(handleAction).runDrain
end Node

object Node:

  def make(
    serverAddress: String,
    nodeAddress: String,
    logDirectory: String,
    snapshotDirectory: String,
    memberId: zio.raft.MemberId,
    peers: Map[zio.raft.MemberId, String]
  ) =
    for
      stable <- LmdbStable.make.debug("LmdbStable.make")

      logStore <-
        SegmentedLog.make[SessionCommand[KVCommand, KVServerRequest, Nothing]](logDirectory).debug("SegmentedLog.make")
      snapshotStore <- FileSnapshotStore.make(zio.nio.file.Path(snapshotDirectory)).debug("FileSnapshotStore.make")
      rpc <- ZmqRpc.make[SessionCommand[KVCommand, KVServerRequest, Nothing]](
        nodeAddress,
        peers
      ).debug("ZmqRpc.make")

      raft <- Raft.make(
        memberId = memberId,
        peers = peers.keySet,
        stable = stable,
        logStore = logStore,
        snapshotStore = snapshotStore,
        rpc = rpc,
        stateMachine = new KVStateMachine()
      )
      _ <- raft.run.forkScoped
      _ <- raft.bootstrap.when(memberId == zio.raft.MemberId("node-1"))
      kvServer <- zio.kvstore.KVServer.make(serverAddress).debug("KVServer.make")
      node = Node(kvServer, raft)
    yield node

  sealed trait NodeAction
  object NodeAction:
    case class FromServer(action: KVServerAction) extends NodeAction
    case class RetryTick(now: Instant) extends NodeAction
    case class FromRaft(action: zio.raft.RaftAction) extends NodeAction
