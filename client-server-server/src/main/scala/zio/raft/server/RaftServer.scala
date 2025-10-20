package zio.raft.server

import zio.*
import zio.stream.*
import zio.raft.protocol.*
import zio.zmq.RoutingId
import scodec.bits.ByteVector
import java.time.Instant
import zio.zmq.ZSocket
import zio.raft.protocol.Codecs.serverMessageCodec
import scodec.bits.BitVector
import zio.raft.protocol.Codecs.clientMessageCodec
import zio.zmq.ZContext
import zio.zmq.ignoreHostUnreachable

object RaftServer {

  /** Actions initiated by the server (internal or from Raft).
    */
  sealed trait ServerAction

  object ServerAction {
    case class SendResponse(sessionId: SessionId, response: ClientResponse) extends ServerAction
    case class SendServerRequest(sessionId: SessionId, request: ServerRequest) extends ServerAction
    case class SessionCreationConfirmed(sessionId: SessionId) extends ServerAction
    case class StepUp(sessions: Map[SessionId, SessionMetadata]) extends ServerAction
    case class StepDown(leaderId: Option[MemberId]) extends ServerAction
  }

  /** Actions to forward to Raft state machine.
    */
  sealed trait RaftAction

  object RaftAction {
    case class CreateSession(sessionId: SessionId, capabilities: Map[String, String]) extends RaftAction
    case class ClientRequest(sessionId: SessionId, requestId: RequestId, payload: ByteVector) extends RaftAction
    case class ServerRequestAck(sessionId: SessionId, requestId: RequestId) extends RaftAction
    case class ExpireSession(sessionId: SessionId) extends RaftAction
  }

  /** Transport abstraction for ZeroMQ SERVER socket.
    */
  trait ZmqServerTransport {
    def disconnect(routingId: RoutingId): Task[Unit]
    def sendMessage(routingId: RoutingId, message: ServerMessage): Task[Unit]
    def incomingMessages: ZStream[Any, Throwable, IncomingMessage]
  }

  case class IncomingMessage(
      routingId: RoutingId,
      message: ClientMessage
  )

  case class SessionConnection(
      routingId: Option[RoutingId],
      expiresAt: Instant
  )

  case class SessionMetadata(
      capabilities: Map[String, String],
      createdAt: Instant
  )

  case class PendingSession(
      routingId: RoutingId,
      nonce: Nonce,
      capabilities: Map[String, String],
      createdAt: Instant
  )

  /** Simplified RaftServer using pure functional state machine.
    *
    * Similar to RaftClient, the server uses a unified stream that merges all events. Each ServerState knows how to
    * handle events and transition to new states.
    */
  class RaftServer(
      transport: ZmqServerTransport,
      config: ServerConfig,
      actionQueue: Queue[ServerAction],
      raftActionsOut: Queue[RaftAction],
      stateRef: Ref[ServerState]
  ) {

    /** Stream of actions to forward to Raft state machine.
      */
    def raftActions: ZStream[Any, Nothing, RaftAction] =
      ZStream.fromQueue(raftActionsOut)

    /** Submit a response from Raft back to a client.
      */
    def sendClientResponse(sessionId: SessionId, response: ClientResponse): UIO[Unit] =
      actionQueue.offer(ServerAction.SendResponse(sessionId, response)).unit

    /** Send server-initiated request to a client.
      */
    def sendServerRequest(sessionId: SessionId, request: ServerRequest): UIO[Unit] =
      actionQueue.offer(ServerAction.SendServerRequest(sessionId, request)).unit

    /** Confirm that a session has been committed by Raft.
      */
    def confirmSessionCreation(sessionId: SessionId): UIO[Unit] =
      actionQueue.offer(ServerAction.SessionCreationConfirmed(sessionId)).unit

    /** Notify server that this node has become the leader.
      */
    def stepUp(sessions: Map[SessionId, SessionMetadata]): UIO[Unit] =
      actionQueue.offer(ServerAction.StepUp(sessions)).unit

    /** Notify server that this node has stepped down from leadership.
      */
    def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
      actionQueue.offer(ServerAction.StepDown(leaderId)).unit

  }

  def make(bindAddress: String): ZIO[Scope & ZContext, Throwable, RaftServer] = {
    val config = ServerConfig.make(bindAddress)
    for {
      validatedConfig <- ZIO.fromEither(ServerConfig.validated(config).left.map(new IllegalArgumentException(_)))

      transport <- createTransport(validatedConfig)
      actionQueue <- Queue.unbounded[ServerAction]
      raftActionsOut <- Queue.unbounded[RaftAction]

      // Start main loop in Follower state (not leader initially)
      initialState = ServerState.Follower(None)
      stateRef <- Ref.make[ServerState](initialState)

      server = new RaftServer(transport, validatedConfig, actionQueue, raftActionsOut, stateRef)

      _ <- startMainLoop(transport, validatedConfig, actionQueue, raftActionsOut, stateRef).forkScoped

      // Register finalizer to cleanly shutdown all sessions on scope exit
      _ <- ZIO.addFinalizer(
        for {
          state <- stateRef.get
          _ <- state match {
            case leader: ServerState.Leader =>
              leader.sessions.shutdown(transport)
            case _ =>
              ZIO.unit
          }
        } yield ()
      )

    } yield server
  }

  private def createTransport(config: ServerConfig): ZIO[ZContext & Scope, Throwable, ZmqServerTransport] =
    for {
      socket <- ZSocket.server
      _ <- socket.options.setDisconnectMessage(
        clientMessageCodec.encode(ConnectionClosed).require.toByteArray
      )
      _ <- socket.options.setLinger(10000) // Allow ten seconds to process messages from peer before terminating
      _ <- socket.options.setHighWatermark(200000, 200000)
      _ <- socket.bind(config.bindAddress)
      transport = new ZmqServerTransportLive(socket)
    } yield transport

  /** Main loop processes unified stream of events.
    */
  private def startMainLoop(
      transport: ZmqServerTransport,
      config: ServerConfig,
      actionQueue: Queue[ServerAction],
      raftActionsOut: Queue[RaftAction],
      stateRef: Ref[ServerState]
  ): Task[Unit] = {

    val actionStream = ZStream
      .fromQueue(actionQueue)
      .map(StreamEvent.Action(_))

    val messageStream = transport.incomingMessages
      .map(msg => StreamEvent.IncomingClientMessage(msg.routingId, msg.message))

    val cleanupStream = ZStream
      .tick(config.cleanupInterval)
      .map(_ => StreamEvent.CleanupTick)

    val unifiedStream = actionStream
      .merge(messageStream)
      .merge(cleanupStream)

    // Pure functional state machine with Ref tracking
    for {
      initialState <- stateRef.get
      _ <- unifiedStream
        .runFoldZIO(initialState) { (state, event) =>
          for {
            newState <- state.handle(event, transport, config, raftActionsOut)
            _ <- stateRef.set(newState)
          } yield newState
        }
    } yield ()
  }

  /** Functional state machine: each state handles events and returns new states.
    */
  sealed trait ServerState {
    def stateName: String
    def handle(
        event: StreamEvent,
        transport: ZmqServerTransport,
        config: ServerConfig,
        raftActionsOut: Queue[RaftAction]
    ): UIO[ServerState]
  }

  private object ServerState {

    /** Follower state - not leader, rejects all session operations.
      */
    case class Follower(leaderId: Option[MemberId]) extends ServerState {
      override def stateName: String = "Follower"

      override def handle(
          event: StreamEvent,
          transport: ZmqServerTransport,
          config: ServerConfig,
          raftActionsOut: Queue[RaftAction]
      ): UIO[ServerState] = {
        event match {
          case StreamEvent.IncomingClientMessage(routingId, message) =>
            handleClientMessage(routingId, message, transport)

          case StreamEvent.Action(ServerAction.StepUp(sessions)) =>
            for {
              _ <- ZIO.logInfo(s"Became leader with ${sessions.size} existing sessions")
              now <- Clock.instant
            } yield Leader(sessions = Sessions.fromMetadata(sessions, config, now))

          case StreamEvent.Action(ServerAction.StepDown(_)) =>
            ZIO.succeed(this)

          case StreamEvent.Action(ServerAction.SendResponse(_, _)) =>
            ZIO.logWarning("Cannot send response - not leader").as(this)

          case StreamEvent.Action(ServerAction.SendServerRequest(_, _)) =>
            ZIO.logWarning("Cannot send server request - not leader").as(this)

          case StreamEvent.Action(ServerAction.SessionCreationConfirmed(_)) =>
            ZIO.logWarning("Received SessionCreationConfirmed while not leader - ignoring").as(this)

          case StreamEvent.CleanupTick =>
            ZIO.succeed(this)
        }
      }

      private def handleClientMessage(
          routingId: RoutingId,
          message: ClientMessage,
          transport: ZmqServerTransport
      ): UIO[ServerState] = {
        message match {
          case CreateSession(_, nonce) =>
            for {
              _ <- transport.sendMessage(routingId, SessionRejected(RejectionReason.NotLeader, nonce, leaderId)).orDie
              _ <- transport.disconnect(routingId).orDie
            } yield this

          case ContinueSession(_, nonce) =>
            for {
              _ <- transport.sendMessage(routingId, SessionRejected(RejectionReason.NotLeader, nonce, leaderId)).orDie
              _ <- transport.disconnect(routingId).orDie
            } yield this

          case KeepAlive(timestamp) =>
            for {
              _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie
              _ <- transport.disconnect(routingId).orDie
            } yield this

          case _: ClientRequest =>
            for {
              _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie
              _ <- transport.disconnect(routingId).orDie
            } yield this

          case ServerRequestAck(_) =>
            ZIO.succeed(this)

          case CloseSession(_) =>
            ZIO.succeed(this)

          case ConnectionClosed =>
            // Client detected OS/TCP level disconnection, just log it
            ZIO.logDebug(s"Client at $routingId reported connection closed").as(this)
        }
      }
    }

    /** Leader state - manages sessions and forwards requests to Raft.
      */
    case class Leader(
        sessions: Sessions
    ) extends ServerState {
      override def stateName: String = "Leader"

      override def handle(
          event: StreamEvent,
          transport: ZmqServerTransport,
          config: ServerConfig,
          raftActionsOut: Queue[RaftAction]
      ): UIO[ServerState] = {
        event match {
          case StreamEvent.IncomingClientMessage(routingId, message) =>
            handleClientMessage(routingId, message, transport, raftActionsOut, config)

          case StreamEvent.Action(ServerAction.StepUp(_)) =>
            ZIO.succeed(this)

          case StreamEvent.Action(ServerAction.StepDown(leaderId)) =>
            for {
              _ <- ZIO.logInfo("Lost leadership, closing all sessions")
              _ <- sessions.stepDown(transport, leaderId)
            } yield Follower(leaderId)

          case StreamEvent.Action(ServerAction.SendResponse(sessionId, response)) =>
            sessions.getRoutingId(sessionId) match {
              case Some(routingId) =>
                transport.sendMessage(routingId, response).orDie.as(this)
              case None =>
                ZIO.logWarning(s"Cannot send response - session $sessionId not found").as(this)
            }

          case StreamEvent.Action(ServerAction.SendServerRequest(sessionId, request)) =>
            sessions.getRoutingId(sessionId) match {
              case Some(routingId) =>
                transport.sendMessage(routingId, request).orDie.as(this)
              case None =>
                ZIO.logWarning(s"Cannot send server request - session $sessionId not found").as(this)
            }

          case StreamEvent.Action(ServerAction.SessionCreationConfirmed(sessionId)) =>
            for {
              now <- Clock.instant
              result <- sessions.confirmSession(sessionId, now, config) match {
                case Some((routingId, nonce, newSessions)) =>
                  for {
                    _ <- transport.sendMessage(routingId, SessionCreated(sessionId, nonce)).orDie
                    _ <- ZIO.logInfo(s"Session $sessionId confirmed and created")
                  } yield copy(sessions = newSessions)
                case None =>
                  ZIO.logWarning(s"Session $sessionId confirmation failed - not found in pending").as(this)
              }
            } yield result

          case StreamEvent.CleanupTick =>
            for {
              now <- Clock.instant
              (expiredIds, newSessions) = sessions.removeExpired(now)
              _ <- ZIO.foreachDiscard(expiredIds) { sessionId =>
                for {
                  _ <- raftActionsOut.offer(RaftAction.ExpireSession(sessionId))
                  routingIdOpt = sessions.getRoutingId(sessionId)
                  _ <- routingIdOpt match {
                    case Some(routingId) =>
                      transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionTimeout, None)).orDie
                    case None => ZIO.unit
                  }
                } yield ()
              }
            } yield copy(sessions = newSessions)
        }
      }

      private def handleClientMessage(
          routingId: RoutingId,
          message: ClientMessage,
          transport: ZmqServerTransport,
          raftActionsOut: Queue[RaftAction],
          config: ServerConfig
      ): UIO[ServerState] = {
        message match {
          case CreateSession(capabilities, nonce) =>
            for {
              sessionId <- SessionId.generate()
              now <- Clock.instant
              _ <- raftActionsOut.offer(RaftAction.CreateSession(sessionId, capabilities))
              newSessions = sessions.addPending(sessionId, routingId, nonce, capabilities, now)
            } yield copy(sessions = newSessions)

          case ContinueSession(sessionId, nonce) =>
            sessions.getMetadata(sessionId) match {
              case Some(metadata) =>
                // Get old routing ID if it exists
                val oldRoutingIdOpt = sessions.getRoutingId(sessionId)
                for {
                  // Disconnect old routing ID if it exists and is different from new one
                  _ <- oldRoutingIdOpt match {
                    case Some(oldRoutingId) if oldRoutingId != routingId =>
                      for {
                        _ <- ZIO.logInfo(s"Disconnecting old routing for session $sessionId before reconnecting")
                        _ <- transport.disconnect(oldRoutingId).orDie
                      } yield ()
                    case _ => ZIO.unit
                  }
                  now <- Clock.instant
                  _ <- transport.sendMessage(routingId, SessionContinued(nonce)).orDie
                  newSessions = sessions.reconnect(sessionId, routingId, now, config)
                } yield copy(sessions = newSessions)
              case None =>
                for {
                  _ <- transport
                    .sendMessage(routingId, SessionRejected(RejectionReason.SessionNotFound, nonce, None))
                    .orDie
                  _ <- transport.disconnect(routingId).orDie
                } yield this
            }

          case KeepAlive(timestamp) =>
            sessions.findSessionByRouting(routingId) match {
              case Some(sessionId) =>
                for {
                  now <- Clock.instant
                  _ <- transport.sendMessage(routingId, KeepAliveResponse(timestamp)).orDie
                  newSessions = sessions.updateExpiry(sessionId, now, config)
                } yield copy(sessions = newSessions)
              case None =>
                for {
                  _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie
                  _ <- transport.disconnect(routingId).orDie
                } yield this
            }

          case clientRequest: ClientRequest =>
            sessions.findSessionByRouting(routingId) match {
              case Some(sessionId) =>
                for {
                  _ <- raftActionsOut.offer(
                    RaftAction.ClientRequest(sessionId, clientRequest.requestId, clientRequest.payload)
                  )
                } yield this
              case None =>
                for {
                  _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie
                  _ <- transport.disconnect(routingId).orDie
                } yield this
            }

          case ServerRequestAck(requestId) =>
            sessions.findSessionByRouting(routingId) match {
              case Some(sessionId) =>
                for {
                  _ <- raftActionsOut.offer(RaftAction.ServerRequestAck(sessionId, requestId))
                } yield this
              case None =>
                for {
                  _ <- transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie
                  _ <- transport.disconnect(routingId).orDie
                } yield this
            }

          case CloseSession(reason) =>
            sessions.findSessionByRouting(routingId) match {
              case Some(sessionId) =>
                for {
                  _ <- ZIO.logInfo(s"Client closed session $sessionId: $reason")
                  newSessions = sessions.removeSession(sessionId, routingId)
                  _ <- raftActionsOut.offer(RaftAction.ExpireSession(sessionId))
                } yield copy(sessions = newSessions)
              case None =>
                ZIO.succeed(this)
            }

          case ConnectionClosed =>
            sessions.findSessionByRouting(routingId) match {
              case Some(sessionId) =>
                for {
                  _ <- ZIO.logInfo(s"Client connection closed: $sessionId")
                  newSessions = sessions.disconnect(sessionId, routingId)
                } yield copy(sessions = newSessions)
              case None =>
                ZIO.succeed(this)
            }
        }
      }
    }
  }

  /** Immutable sessions management.
    */
  case class Sessions(
      metadata: Map[SessionId, SessionMetadata],
      connections: Map[SessionId, SessionConnection],
      routingToSession: Map[RoutingId, SessionId],
      pendingSessions: Map[SessionId, PendingSession]
  ) {
    def addPending(
        sessionId: SessionId,
        routingId: RoutingId,
        nonce: Nonce,
        capabilities: Map[String, String],
        now: Instant
    ): Sessions = {
      copy(
        pendingSessions = pendingSessions.updated(sessionId, PendingSession(routingId, nonce, capabilities, now))
      )
    }

    def confirmSession(
        sessionId: SessionId,
        now: Instant,
        config: ServerConfig
    ): Option[(RoutingId, Nonce, Sessions)] = {
      pendingSessions.get(sessionId).map { pending =>
        val expiresAt = now.plus(config.sessionTimeout)
        val newSessions = copy(
          metadata = metadata.updated(sessionId, SessionMetadata(pending.capabilities, pending.createdAt)),
          connections = connections.updated(sessionId, SessionConnection(Some(pending.routingId), expiresAt)),
          routingToSession = routingToSession.updated(pending.routingId, sessionId),
          pendingSessions = pendingSessions.removed(sessionId)
        )
        (pending.routingId, pending.nonce, newSessions)
      }
    }

    def reconnect(sessionId: SessionId, routingId: RoutingId, now: Instant, config: ServerConfig): Sessions = {
      connections.get(sessionId) match {
        case Some(conn) =>
          val expiresAt = now.plus(config.sessionTimeout)
          // Remove old routing mapping if it exists
          val cleanedRouting = conn.routingId.map(old => routingToSession.removed(old)).getOrElse(routingToSession)
          copy(
            connections = connections.updated(sessionId, conn.copy(routingId = Some(routingId), expiresAt = expiresAt)),
            routingToSession = cleanedRouting.updated(routingId, sessionId)
          )
        case None => this
      }
    }

    def disconnect(sessionId: SessionId, routingId: RoutingId): Sessions =
      copy(
        connections = connections.updatedWith(sessionId)(_.map(_.copy(routingId = None))),
        routingToSession = routingToSession.removed(routingId)
      )

    def removeSession(sessionId: SessionId, routingId: RoutingId): Sessions =
      copy(
        metadata = metadata.removed(sessionId),
        connections = connections.removed(sessionId),
        routingToSession = routingToSession.removed(routingId),
        pendingSessions = pendingSessions.removed(sessionId)
      )

    def updateExpiry(sessionId: SessionId, now: Instant, config: ServerConfig): Sessions = {
      connections.get(sessionId) match {
        case Some(conn) =>
          val expiresAt = now.plus(config.sessionTimeout)
          copy(connections = connections.updated(sessionId, conn.copy(expiresAt = expiresAt)))
        case None => this
      }
    }

    def removeExpired(now: Instant): (List[SessionId], Sessions) = {
      val expired = connections.filter { case (_, conn) => conn.expiresAt.isBefore(now) }.keys.toList
      val newMetadata = metadata -- expired
      val newConnections = connections -- expired
      val newRouting = routingToSession.filter { case (_, sid) => !expired.contains(sid) }
      val newPendingSessions = pendingSessions -- expired
      (expired, Sessions(newMetadata, newConnections, newRouting, newPendingSessions))
    }

    def getRoutingId(sessionId: SessionId): Option[RoutingId] =
      connections.get(sessionId).flatMap(_.routingId)

    def getMetadata(sessionId: SessionId): Option[SessionMetadata] =
      metadata.get(sessionId)

    def findSessionByRouting(routingId: RoutingId): Option[SessionId] =
      routingToSession.get(routingId)

    def shutdown(transport: ZmqServerTransport): UIO[Unit] = {
      ZIO.foreachDiscard(connections.values.flatMap(_.routingId)) { routingId =>
        transport.sendMessage(routingId, SessionClosed(SessionCloseReason.Shutdown, None)).orDie
      }
    }

    def stepDown(transport: ZmqServerTransport, leaderId: Option[MemberId]): UIO[Unit] = {
      ZIO.foreachDiscard(connections.values.flatMap(_.routingId)) { routingId =>
        transport.sendMessage(routingId, SessionClosed(SessionCloseReason.NotLeaderAnymore, leaderId)).orDie
      }
    }
  }

  object Sessions {
    def empty: Sessions = Sessions(Map.empty, Map.empty, Map.empty, Map.empty)

    def fromMetadata(metadata: Map[SessionId, SessionMetadata], config: ServerConfig, now: Instant): Sessions = {
      val expiresAt = now.plusSeconds(config.sessionTimeout.toSeconds)
      val connections = metadata.map { case (sid, _) =>
        sid -> SessionConnection(None, expiresAt)
      }
      Sessions(metadata, connections, Map.empty, Map.empty)
    }
  }

  /** Unified stream events.
    */
  sealed trait StreamEvent

  object StreamEvent {
    case class Action(action: ServerAction) extends StreamEvent
    case class IncomingClientMessage(routingId: RoutingId, message: ClientMessage) extends StreamEvent
    case object CleanupTick extends StreamEvent
  }

  private class ZmqServerTransportLive(socket: ZSocket) extends ZmqServerTransport {
    override def disconnect(routingId: RoutingId): Task[Unit] =
      for {
        _ <- socket.disconnectPeer(routingId)
      } yield ()

    override def sendMessage(routingId: RoutingId, message: ServerMessage): Task[Unit] =
      for {
        bytes <- ZIO.attempt(serverMessageCodec.encode(message).require.toByteArray)
        _ <- socket.sendImmediately(routingId, bytes).unit.ignoreHostUnreachable
      } yield ()

    override def incomingMessages: ZStream[Any, Throwable, IncomingMessage] =
      socket.stream.mapZIO { msg =>
        ZIO.attempt(
          IncomingMessage(RoutingId(msg.getRoutingId), clientMessageCodec.decode(BitVector(msg.data())).require.value)
        )
      }
  }
}
