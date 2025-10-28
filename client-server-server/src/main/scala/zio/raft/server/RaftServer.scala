package zio.raft.server

import zio.*
import zio.stream.*
import zio.raft.protocol.*
import zio.zmq.RoutingId
import scodec.bits.ByteVector
import zio.zmq.ZContext

final class RaftServer(
  transport: ServerTransport,
  config: ServerConfig,
  actionQueue: Queue[RaftServer.ServerAction],
  raftActionsOut: Queue[RaftServer.RaftAction],
  stateRef: Ref[RaftServer.ServerState]
):

  /** Stream of actions to forward to Raft state machine.
    */
  def raftActions: ZStream[Any, Nothing, RaftServer.RaftAction] =
    ZStream.fromQueue(raftActionsOut)

  /** Submit a response from Raft back to a client.
    */
  def sendClientResponse(sessionId: SessionId, response: ClientResponse): UIO[Unit] =
    actionQueue.offer(RaftServer.ServerAction.SendResponse(sessionId, response)).unit

  /** Submit a QueryResponse back to a client (read-only query path).
    */
  def sendQueryResponse(sessionId: SessionId, response: QueryResponse): UIO[Unit] =
    actionQueue.offer(RaftServer.ServerAction.SendQueryResponse(sessionId, response)).unit

  /** Send server-initiated request to a client.
    */
  def sendServerRequest(sessionId: SessionId, request: ServerRequest): UIO[Unit] =
    actionQueue.offer(RaftServer.ServerAction.SendServerRequest(sessionId, request)).unit

  /** Send request error to a client. */
  def sendRequestError(sessionId: SessionId, error: RequestError): UIO[Unit] =
    actionQueue.offer(RaftServer.ServerAction.SendRequestError(sessionId, error)).unit

  /** Confirm that a session has been committed by Raft.
    */
  def confirmSessionCreation(sessionId: SessionId): UIO[Unit] =
    actionQueue.offer(RaftServer.ServerAction.SessionCreationConfirmed(sessionId)).unit

  /** Notify server that this node has become the leader.
    */
  def stepUp(sessions: Map[SessionId, SessionMetadata]): UIO[Unit] =
    actionQueue.offer(RaftServer.ServerAction.StepUp(sessions)).unit

  /** Notify server that this node has stepped down from leadership.
    */
  def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
    actionQueue.offer(RaftServer.ServerAction.StepDown(leaderId)).unit

  /** Notify server that this node has changed leader.
    * @param leaderId
    *   The new leader ID
    */
  def leaderChanged(leaderId: MemberId): UIO[Unit] =
    actionQueue.offer(RaftServer.ServerAction.LeaderChanged(leaderId)).unit

object RaftServer:

  /** Actions initiated by the server (internal or from Raft).
    */
  sealed trait ServerAction

  object ServerAction:
    case class SendResponse(sessionId: SessionId, response: ClientResponse) extends ServerAction
    case class SendQueryResponse(sessionId: SessionId, response: QueryResponse) extends ServerAction
    case class SendServerRequest(sessionId: SessionId, request: ServerRequest) extends ServerAction
    case class SendRequestError(sessionId: SessionId, error: RequestError) extends ServerAction
    case class SessionCreationConfirmed(sessionId: SessionId) extends ServerAction
    case class StepUp(sessions: Map[SessionId, SessionMetadata]) extends ServerAction
    case class StepDown(leaderId: Option[MemberId]) extends ServerAction
    case class LeaderChanged(leaderId: MemberId) extends ServerAction

  /** Actions to forward to Raft state machine.
    */
  sealed trait RaftAction

  object RaftAction:
    case class CreateSession(sessionId: SessionId, capabilities: Map[String, String]) extends RaftAction
    case class ClientRequest(
      sessionId: SessionId,
      requestId: RequestId,
      lowestPendingRequestId: RequestId,
      payload: ByteVector
    ) extends RaftAction
    case class ServerRequestAck(sessionId: SessionId, requestId: RequestId) extends RaftAction
    case class ExpireSession(sessionId: SessionId) extends RaftAction
    /** Read-only Query forwarded to the application/handler layer. */
    case class Query(sessionId: SessionId, correlationId: CorrelationId, payload: ByteVector) extends RaftAction

  def make(bindAddress: String): ZIO[Scope & ZContext, Throwable, RaftServer] =
    val config = ServerConfig.make(bindAddress)
    for
      validatedConfig <- ZIO.fromEither(ServerConfig.validated(config).left.map(new IllegalArgumentException(_)))

      transport <- ServerTransport.make(validatedConfig)
      actionQueue <- Queue.unbounded[ServerAction]
      raftActionsOut <- Queue.unbounded[RaftAction]

      // Start main loop in Follower state (not leader initially)
      initialState = ServerState.Follower(None)
      stateRef <- Ref.make[ServerState](initialState)

      server = new RaftServer(transport, validatedConfig, actionQueue, raftActionsOut, stateRef)

      _ <- startMainLoop(transport, validatedConfig, actionQueue, raftActionsOut, stateRef).forkScoped

      // Register finalizer to cleanly shutdown all sessions on scope exit
      _ <- ZIO.addFinalizer(
        for
          state <- stateRef.get
          _ <- state match
            case leader: ServerState.Leader =>
              leader.sessions.shutdown(transport)
            case _ =>
              ZIO.unit
        yield ()
      )
    yield server

  /** Main loop processes unified stream of events.
    */
  private def startMainLoop(
    transport: ServerTransport,
    config: ServerConfig,
    actionQueue: Queue[ServerAction],
    raftActionsOut: Queue[RaftAction],
    stateRef: Ref[ServerState]
  ): Task[Unit] =

    val actionStream = ZStream
      .fromQueue(actionQueue)
      .map(StreamEvent.Action(_))

    val messageStream = transport.incomingMessages
      .map(msg => StreamEvent.IncomingClientMessage(msg.routingId, msg.message))

    val cleanupStream = ZStream
      .tick(config.cleanupInterval)
      .map(_ => StreamEvent.CleanupTick)

    val unifiedStream = ZStream.mergeAllUnbounded(1024)(actionStream, messageStream, cleanupStream)

    // Pure functional state machine with Ref tracking
    for
      initialState <- stateRef.get
      _ <- unifiedStream
        .runFoldZIO(initialState) { (state, event) =>
          for
            newState <- state.handle(event, transport, config, raftActionsOut)
            _ <- stateRef.set(newState)
          yield newState
        }
    yield ()

  /** Functional state machine: each state handles events and returns new states.
    */
  sealed trait ServerState:
    def stateName: String
    def handle(
      event: StreamEvent,
      transport: ServerTransport,
      config: ServerConfig,
      raftActionsOut: Queue[RaftAction]
    ): UIO[ServerState]

  private object ServerState:

    /** Follower state - not leader, rejects all session operations.
      */
    case class Follower(leaderId: Option[MemberId]) extends ServerState:
      override def stateName: String = "Follower"

      override def handle(
        event: StreamEvent,
        transport: ServerTransport,
        config: ServerConfig,
        raftActionsOut: Queue[RaftAction]
      ): UIO[ServerState] =
        event match
          case StreamEvent.IncomingClientMessage(routingId, message) =>
            handleClientMessage(routingId, message, transport)

          case StreamEvent.Action(ServerAction.StepUp(sessions)) =>
            for
              _ <- ZIO.logInfo(s"Became leader with ${sessions.size} existing sessions")
              now <- Clock.instant
            yield Leader(sessions = Sessions.fromMetadata(sessions, config, now))

          case StreamEvent.Action(ServerAction.SendRequestError(_, _)) =>
            ZIO.succeed(this)

          case StreamEvent.Action(ServerAction.StepDown(_)) =>
            ZIO.succeed(this)

          case StreamEvent.Action(ServerAction.LeaderChanged(leaderId)) =>
            ZIO.succeed(this.copy(leaderId = Some(leaderId)))

          case StreamEvent.Action(ServerAction.SendResponse(_, _)) =>
            ZIO.logWarning("Cannot send response - not leader").as(this)

          case StreamEvent.Action(ServerAction.SendQueryResponse(_, _)) =>
            ZIO.logWarning("Cannot send query response - not leader").as(this)

          case StreamEvent.Action(ServerAction.SendServerRequest(_, _)) =>
            ZIO.logWarning("Cannot send server request - not leader").as(this)

          case StreamEvent.Action(ServerAction.SessionCreationConfirmed(_)) =>
            ZIO.logWarning("Received SessionCreationConfirmed while not leader - ignoring").as(this)

          case StreamEvent.CleanupTick =>
            ZIO.succeed(this)

      private def handleClientMessage(
        routingId: RoutingId,
        message: ClientMessage,
        transport: ServerTransport
      ): UIO[ServerState] =
        message match
          case CreateSession(_, nonce) =>
            for
              _ <- transport.sendMessage(routingId, SessionRejected(RejectionReason.NotLeader, nonce, leaderId)).orDie
              _ <- transport.disconnect(routingId).orDie
            yield this

          case ContinueSession(_, nonce) =>
            for
              _ <- transport.sendMessage(routingId, SessionRejected(RejectionReason.NotLeader, nonce, leaderId)).orDie
              _ <- transport.disconnect(routingId).orDie
            yield this

          case KeepAlive(timestamp) =>
            for
              _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.NotLeaderAnymore, leaderId)).orDie
              _ <- transport.disconnect(routingId).orDie
            yield this

          case _: ClientRequest =>
            for
              _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.NotLeaderAnymore, leaderId)).orDie
              _ <- transport.disconnect(routingId).orDie
            yield this

          case _: Query =>
            for
              _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.NotLeaderAnymore, leaderId)).orDie
              _ <- transport.disconnect(routingId).orDie
            yield this

          case ServerRequestAck(_) =>
            ZIO.succeed(this)

          case CloseSession(_) =>
            ZIO.succeed(this)

          case ConnectionClosed =>
            // Client detected OS/TCP level disconnection, just log it
            ZIO.logDebug(s"Client at $routingId reported connection closed").as(this)
    end Follower

    /** Leader state - manages sessions and forwards requests to Raft.
      */
    case class Leader(
      sessions: Sessions
    ) extends ServerState:
      override def stateName: String = "Leader"

      override def handle(
        event: StreamEvent,
        transport: ServerTransport,
        config: ServerConfig,
        raftActionsOut: Queue[RaftAction]
      ): UIO[ServerState] =
        event match
          case StreamEvent.IncomingClientMessage(routingId, message) =>
            handleClientMessage(routingId, message, transport, raftActionsOut, config)

          case StreamEvent.Action(ServerAction.StepUp(_)) =>
            ZIO.succeed(this)

          case StreamEvent.Action(ServerAction.StepDown(leaderId)) =>
            for
              _ <- ZIO.logInfo("Lost leadership, closing all sessions")
              _ <- sessions.stepDown(transport, leaderId)
            yield Follower(leaderId)

          case StreamEvent.Action(ServerAction.LeaderChanged(leaderId)) =>
            ZIO.logWarning(s"We received LeaderChanged event while in Leader state, this should not happen").as(this)

          case StreamEvent.Action(ServerAction.SendResponse(sessionId, response)) =>
            sessions.getRoutingId(sessionId) match
              case Some(routingId) =>
                transport.sendMessage(routingId, response).orDie.as(this)
              case None =>
                ZIO.logWarning(s"Cannot send response - session $sessionId not found").as(this)

          case StreamEvent.Action(ServerAction.SendQueryResponse(sessionId, response)) =>
            sessions.getRoutingId(sessionId) match
              case Some(routingId) =>
                transport.sendMessage(routingId, response).orDie.as(this)
              case None =>
                ZIO.logWarning(s"Cannot send query response - session $sessionId not found").as(this)

          case StreamEvent.Action(ServerAction.SendServerRequest(sessionId, request)) =>
            sessions.getRoutingId(sessionId) match
              case Some(routingId) =>
                transport.sendMessage(routingId, request).orDie.as(this)
              case None =>
                ZIO.logWarning(s"Cannot send server request - session $sessionId not found").as(this)

          case StreamEvent.Action(ServerAction.SendRequestError(sessionId, error)) =>
            sessions.getRoutingId(sessionId) match
              case Some(routingId) =>
                transport.sendMessage(routingId, error).orDie.as(this)
              case None =>
                ZIO.logWarning(s"Cannot send request error - session $sessionId not found").as(this)

          case StreamEvent.Action(ServerAction.SessionCreationConfirmed(sessionId)) =>
            for
              now <- Clock.instant
              result <- sessions.confirmSession(sessionId, now, config) match
                case Some((routingId, nonce, newSessions)) =>
                  for
                    _ <- transport.sendMessage(routingId, SessionCreated(sessionId, nonce)).orDie
                    _ <- ZIO.logInfo(s"Session $sessionId confirmed and created")
                  yield copy(sessions = newSessions)
                case None =>
                  ZIO.logWarning(s"Session $sessionId confirmation failed - not found in pending").as(this)
            yield result

          case StreamEvent.CleanupTick =>
            for
              now <- Clock.instant
              (expiredIds, newSessions) = sessions.removeExpired(now)
              _ <- ZIO.foreachDiscard(expiredIds) { sessionId =>
                for
                  _ <- raftActionsOut.offer(RaftAction.ExpireSession(sessionId))
                  routingIdOpt = sessions.getRoutingId(sessionId)
                  _ <- routingIdOpt match
                    case Some(routingId) =>
                      transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionExpired, None)).orDie
                    case None => ZIO.unit
                yield ()
              }
            yield copy(sessions = newSessions)

      private def handleClientMessage(
        routingId: RoutingId,
        message: ClientMessage,
        transport: ServerTransport,
        raftActionsOut: Queue[RaftAction],
        config: ServerConfig
      ): UIO[ServerState] =
        message match
          case CreateSession(capabilities, nonce) =>
            for
              sessionId <- SessionId.generate()
              now <- Clock.instant
              _ <- raftActionsOut.offer(RaftAction.CreateSession(sessionId, capabilities))
              newSessions = sessions.addPending(sessionId, routingId, nonce, capabilities, now)
            yield copy(sessions = newSessions)

          case ContinueSession(sessionId, nonce) =>
            sessions.getMetadata(sessionId) match
              case Some(metadata) =>
                // Get old routing ID if it exists
                val oldRoutingIdOpt = sessions.getRoutingId(sessionId)
                for
                  // Disconnect old routing ID if it exists and is different from new one
                  _ <- oldRoutingIdOpt match
                    case Some(oldRoutingId) if oldRoutingId != routingId =>
                      for
                        _ <- ZIO.logInfo(s"Disconnecting old routing for session $sessionId before reconnecting")
                        _ <- transport.disconnect(oldRoutingId).orDie
                      yield ()
                    case _ => ZIO.unit
                  now <- Clock.instant
                  _ <- transport.sendMessage(routingId, SessionContinued(nonce)).orDie
                  newSessions = sessions.reconnect(sessionId, routingId, now, config)
                yield copy(sessions = newSessions)
              case None =>
                for
                  _ <- transport
                    .sendMessage(routingId, SessionRejected(RejectionReason.SessionExpired, nonce, None))
                    .orDie
                  _ <- transport.disconnect(routingId).orDie
                yield this

          case KeepAlive(timestamp) =>
            sessions.findSessionByRouting(routingId) match
              case Some(sessionId) =>
                for
                  now <- Clock.instant
                  _ <- transport.sendMessage(routingId, KeepAliveResponse(timestamp)).orDie
                  newSessions = sessions.updateExpiry(sessionId, now, config)
                yield copy(sessions = newSessions)
              case None =>
                for
                  _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie
                  _ <- transport.disconnect(routingId).orDie
                yield this

          case clientRequest: ClientRequest =>
            sessions.findSessionByRouting(routingId) match
              case Some(sessionId) =>
                for
                  _ <- raftActionsOut.offer(
                    RaftAction.ClientRequest(
                      sessionId,
                      clientRequest.requestId,
                      clientRequest.lowestPendingRequestId,
                      clientRequest.payload
                    )
                  )
                yield this
              case None =>
                for
                  _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie
                  _ <- transport.disconnect(routingId).orDie
                yield this

          case query: Query =>
            sessions.findSessionByRouting(routingId) match
              case Some(sessionId) =>
                // Forward Query to application layer via RaftAction.Query (read-only, no state machine involvement)
                raftActionsOut.offer(RaftAction.Query(sessionId, query.correlationId, query.payload)).as(this)
              case None =>
                for
                  _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie
                  _ <- transport.disconnect(routingId).orDie
                yield this

          case ServerRequestAck(requestId) =>
            sessions.findSessionByRouting(routingId) match
              case Some(sessionId) =>
                for
                  _ <- raftActionsOut.offer(RaftAction.ServerRequestAck(sessionId, requestId))
                yield this
              case None =>
                for
                  _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie
                  _ <- transport.disconnect(routingId).orDie
                yield this

          case CloseSession(reason) =>
            sessions.findSessionByRouting(routingId) match
              case Some(sessionId) =>
                for
                  _ <- ZIO.logInfo(s"Client closed session $sessionId: $reason")
                  newSessions = sessions.removeSession(sessionId, routingId)
                  _ <- raftActionsOut.offer(RaftAction.ExpireSession(sessionId))
                yield copy(sessions = newSessions)
              case None =>
                ZIO.succeed(this)

          case ConnectionClosed =>
            sessions.findSessionByRouting(routingId) match
              case Some(sessionId) =>
                for
                  _ <- ZIO.logInfo(s"Client connection closed: $sessionId")
                  newSessions = sessions.disconnect(sessionId, routingId)
                yield copy(sessions = newSessions)
              case None =>
                ZIO.succeed(this)
    end Leader
  end ServerState

  /** Unified stream events.
    */
  sealed trait StreamEvent

  object StreamEvent:
    case class Action(action: ServerAction) extends StreamEvent
    case class IncomingClientMessage(routingId: RoutingId, message: ClientMessage) extends StreamEvent
    case object CleanupTick extends StreamEvent

end RaftServer
