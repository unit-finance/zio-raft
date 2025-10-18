package zio.raft.server

import zio._
import zio.stream._
import zio.raft.protocol._
import zio.zmq.RoutingId
import scodec.bits.ByteVector
import java.time.Instant

object RaftServer {
  
  /**
   * Actions initiated by the server (internal or from Raft).
   */
  sealed trait ServerAction
  
  object ServerAction {
    case class SendResponse(sessionId: SessionId, response: ClientResponse) extends ServerAction
    case class SendServerRequest(sessionId: SessionId, request: ServerRequest) extends ServerAction
    case class SessionCreationConfirmed(sessionId: SessionId) extends ServerAction
    case class StepUp(sessions: Map[SessionId, SessionMetadata]) extends ServerAction
    case class StepDown(leaderId: Option[MemberId]) extends ServerAction
  }
  
  /**
   * Actions to forward to Raft state machine.
   */
  sealed trait RaftAction
  
  object RaftAction {
    case class CreateSession(sessionId: SessionId, capabilities: Map[String, String]) extends RaftAction
    case class ClientRequest(sessionId: SessionId, requestId: RequestId, payload: ByteVector) extends RaftAction
    case class ServerRequestAck(sessionId: SessionId, requestId: RequestId) extends RaftAction
    case class ExpireSession(sessionId: SessionId) extends RaftAction
  }
  
  /**
   * Transport abstraction for ZeroMQ SERVER socket.
   */
  trait ZmqServerTransport {
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
  
  
  /**
   * Simplified RaftServer using pure functional state machine.
   * 
   * Similar to RaftClient, the server uses a unified stream that merges all events.
   * Each ServerState knows how to handle events and transition to new states.
   */
  class RaftServer(
    transport: ZmqServerTransport,
    config: ServerConfig,
    actionQueue: Queue[ServerAction],
    raftActionsOut: Queue[RaftAction],
    stateRef: Ref[ServerState]
  ) {
    
    /**
     * Stream of actions to forward to Raft state machine.
     */
    def raftActions: ZStream[Any, Nothing, RaftAction] =
      ZStream.fromQueue(raftActionsOut)
    
    /**
     * Submit a response from Raft back to a client.
     */
    def sendClientResponse(sessionId: SessionId, response: ClientResponse): UIO[Unit] =
      actionQueue.offer(ServerAction.SendResponse(sessionId, response)).unit
    
    /**
     * Send server-initiated request to a client.
     */
    def sendServerRequest(sessionId: SessionId, request: ServerRequest): UIO[Unit] =
      actionQueue.offer(ServerAction.SendServerRequest(sessionId, request)).unit
    
    /**
     * Confirm that a session has been committed by Raft.
     */
    def confirmSessionCreation(sessionId: SessionId): UIO[Unit] =
      actionQueue.offer(ServerAction.SessionCreationConfirmed(sessionId)).unit
    
    /**
     * Notify server that this node has become the leader.
     */
    def stepUp(sessions: Map[SessionId, SessionMetadata]): UIO[Unit] =
      actionQueue.offer(ServerAction.StepUp(sessions)).unit
    
    /**
     * Notify server that this node has stepped down from leadership.
     */
    def stepDown(leaderId: Option[MemberId]): UIO[Unit] =
      actionQueue.offer(ServerAction.StepDown(leaderId)).unit
    
  }
  
  def make(bindAddress: String): ZIO[Scope, Throwable, RaftServer] = {
    val config = ServerConfig.make(bindAddress)
    for {
      validatedConfig <- ZIO.fromEither(ServerConfig.validated(config).left.map(new IllegalArgumentException(_)))
      
      transport <- createTransport(validatedConfig)
      actionQueue <- Queue.unbounded[ServerAction]
      raftActionsOut <- Queue.unbounded[RaftAction]
      
      // Start main loop in Follower state (not leader initially)
      initialState = ServerState.Follower
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
  
  private def createTransport(config: ServerConfig): UIO[ZmqServerTransport] =
    ZIO.succeed(new ZmqServerTransportStub())
  
  /**
   * Main loop processes unified stream of events.
   */
  private def startMainLoop(
    transport: ZmqServerTransport,
    config: ServerConfig,
    actionQueue: Queue[ServerAction],
    raftActionsOut: Queue[RaftAction],
    stateRef: Ref[ServerState]
  ): Task[Unit] = {
    
    val actionStream = ZStream.fromQueue(actionQueue)
      .map(StreamEvent.Action(_))
    
    val messageStream = transport.incomingMessages
      .map(msg => StreamEvent.IncomingClientMessage(msg.routingId, msg.message))
    
    val cleanupStream = ZStream.tick(config.cleanupInterval)
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
  
  /**
   * Functional state machine: each state handles events and returns new states.
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
    
    /**
     * Follower state - not leader, rejects all session operations.
     */
    case object Follower extends ServerState {
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
            transport.sendMessage(routingId, SessionRejected(RejectionReason.NotLeader, nonce, None)).orDie.as(this)
          
          case ContinueSession(_, nonce) =>
            transport.sendMessage(routingId, SessionRejected(RejectionReason.NotLeader, nonce, None)).orDie.as(this)
          
          case KeepAlive(timestamp) =>
            transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie.as(this)
          
          case _: ClientRequest =>
            transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie.as(this)
          
          case ServerRequestAck(_) =>
            ZIO.succeed(this)
          
          case CloseSession(_) =>
            ZIO.succeed(this)
        }
      }
    }
    
    /**
     * Leader state - manages sessions and forwards requests to Raft.
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
            } yield Follower
          
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
                for {
                  now <- Clock.instant
                  _ <- transport.sendMessage(routingId, SessionContinued(nonce)).orDie
                  newSessions = sessions.reconnect(sessionId, routingId, now, config)
                } yield copy(sessions = newSessions)
              case None =>
                transport.sendMessage(routingId, SessionRejected(RejectionReason.SessionNotFound, nonce, None)).orDie.as(this)
            }
          
          case KeepAlive(timestamp) =>
            sessions.findSessionByRouting(routingId) match {
              case Some(sessionId) if !sessions.isPending(sessionId) =>
                for {
                  now <- Clock.instant
                  _ <- transport.sendMessage(routingId, KeepAliveResponse(timestamp)).orDie
                  newSessions = sessions.updateExpiry(sessionId, now, config)
                } yield copy(sessions = newSessions)
              case Some(_) =>
                // Session is pending, ignore keep-alive
                ZIO.succeed(this)
              case None =>
                transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie.as(this)
            }
          
          case clientRequest: ClientRequest =>
            sessions.findSessionByRouting(routingId) match {
              case Some(sessionId) if !sessions.isPending(sessionId) =>
                for {
                  _ <- raftActionsOut.offer(RaftAction.ClientRequest(sessionId, clientRequest.requestId, clientRequest.payload))
                } yield this
              case Some(_) =>
                // Session is pending, reject request
                transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie.as(this)
              case None =>
                transport.sendMessage(routingId, SessionClosed(SessionCloseReason.SessionError, None)).orDie.as(this)
            }
          
          case ServerRequestAck(requestId) =>
            sessions.findSessionByRouting(routingId) match {
              case Some(sessionId) if !sessions.isPending(sessionId) =>
                for {
                  _ <- raftActionsOut.offer(RaftAction.ServerRequestAck(sessionId, requestId))
                } yield this
              case Some(_) =>
                // Session is pending, ignore ack
                ZIO.succeed(this)
              case None =>
                ZIO.succeed(this)
            }
          
          case CloseSession(reason) =>
            sessions.findSessionByRouting(routingId) match {
              case Some(sessionId) =>
                for {
                  _ <- ZIO.logInfo(s"Client closed session $sessionId: $reason")
                  newSessions = sessions.disconnect(sessionId, routingId)
                } yield copy(sessions = newSessions)
              case None =>
                ZIO.succeed(this)
            }
        }
      }
    }
  }
  
  /**
   * Immutable sessions management.
   */
  case class Sessions(
    metadata: Map[SessionId, SessionMetadata],
    connections: Map[SessionId, SessionConnection],
    routingToSession: Map[RoutingId, SessionId],
    pendingSessions: Map[SessionId, PendingSession]
  ) {
    def addPending(sessionId: SessionId, routingId: RoutingId, nonce: Nonce, capabilities: Map[String, String], now: Instant): Sessions = {
      copy(
        pendingSessions = pendingSessions.updated(sessionId, PendingSession(routingId, nonce, capabilities, now))
      )
    }
    
    def confirmSession(sessionId: SessionId, now: Instant, config: ServerConfig): Option[(RoutingId, Nonce, Sessions)] = {
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
      routingToSession.get(routingId).orElse {
        // Also check pending sessions
        pendingSessions.collectFirst {
          case (sid, pending) if pending.routingId == routingId => sid
        }
      }
    
    def isPending(sessionId: SessionId): Boolean =
      pendingSessions.contains(sessionId)
    
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
  
  /**
   * Unified stream events.
   */
  sealed trait StreamEvent
  
  object StreamEvent {
    case class Action(action: ServerAction) extends StreamEvent
    case class IncomingClientMessage(routingId: RoutingId, message: ClientMessage) extends StreamEvent
    case object CleanupTick extends StreamEvent
  }
  
  private class ZmqServerTransportStub extends ZmqServerTransport {
    override def sendMessage(routingId: RoutingId, message: ServerMessage): Task[Unit] =
      ZIO.logDebug(s"Stub: sending to $routingId: ${message.getClass.getSimpleName}")
    
    override def incomingMessages: ZStream[Any, Throwable, IncomingMessage] =
      ZStream.empty
  }
}
