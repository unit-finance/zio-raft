package zio.raft.client

import zio.*
import zio.stream.*
import zio.raft.protocol.*
import scodec.bits.ByteVector
import java.time.Instant
import zio.zmq.ZContext

/** Main RaftClient implementation using pure functional state machine.
  *
  * The client uses a unified stream that merges all events. Each ClientState knows how to handle events and transition
  * to new states.
  */
class RaftClient private (
  zmqTransport: ClientTransport,
  config: ClientConfig,
  actionQueue: Queue[ClientAction],
  serverRequestQueue: Queue[ServerRequest]
) {

  /** Start the client's main event loop. Must be called (typically forked) for the client to process messages.
    */
  def run(): UIO[Unit] = {
    val actionStream = ZStream
      .fromQueue(actionQueue)
      .map(RaftClient.StreamEvent.Action(_))

    val messageStream = zmqTransport.incomingMessages
      .map(RaftClient.StreamEvent.ServerMsg(_))

    val keepAliveStream = ZStream
      .tick(config.keepAliveInterval)
      .map(_ => RaftClient.StreamEvent.KeepAliveTick)

    val timeoutStream = ZStream
      .tick(100.millis)
      .map(_ => RaftClient.StreamEvent.TimeoutCheck)

    val unifiedStream = ZStream.mergeAllUnbounded(1024)(actionStream, messageStream, keepAliveStream, timeoutStream)

    // Start in Disconnected state
    unifiedStream
      .runFoldZIO(RaftClient.ClientState.Disconnected(config): RaftClient.ClientState) { (state, event) =>
        state.handle(event, zmqTransport, serverRequestQueue)
      }
      .orDie
      .unit
  }

  def connect(): UIO[Unit] =
    actionQueue.offer(ClientAction.Connect).unit

  def submitCommand(payload: ByteVector): UIO[ByteVector] =
    for {
      promise <- Promise.make[Nothing, ByteVector]
      action = ClientAction.SubmitCommand(payload, promise)
      _ <- actionQueue.offer(action)
      result <- promise.await
    } yield result

  /** Submit a read-only Query (leader-served, linearizable).
    */
  def query(payload: ByteVector): UIO[ByteVector] =
    for {
      promise <- Promise.make[Nothing, ByteVector]
      action = ClientAction.SubmitQuery(payload, promise)
      _ <- actionQueue.offer(action)
      result <- promise.await
    } yield result

  def disconnect(): UIO[Unit] =
    actionQueue.offer(ClientAction.Disconnect).unit

  /** Stream of server-initiated requests for user to handle.
    */
  def serverRequests: ZStream[Any, Nothing, ServerRequest] =
    ZStream.fromQueue(serverRequestQueue)
}

object RaftClient {

  def make(config: ClientConfig): ZIO[Scope & ZContext, Throwable, RaftClient] =
    for {
      validatedConfig <- ClientConfig.validated(config).mapError(new IllegalArgumentException(_))

      zmqTransport <- ClientTransport.make(validatedConfig)
      actionQueue <- Queue.unbounded[ClientAction]
      serverRequestQueue <- Queue.unbounded[ServerRequest]

      client = new RaftClient(zmqTransport, validatedConfig, actionQueue, serverRequestQueue)

      // Register finalizer to cleanly close session on scope exit
      _ <- ZIO.addFinalizer(
        zmqTransport.sendMessage(CloseSession(CloseReason.ClientShutdown)).ignore
      )

    } yield client

  def make(
    clusterMembers: Map[MemberId, String],
    capabilities: Map[String, String]
  ): ZIO[Scope & ZContext, Throwable, RaftClient] = {
    val config = ClientConfig.make(clusterMembers, capabilities)
    make(config)
  }

  /** Functional state machine: each state handles events and returns new states.
    */
  private sealed trait ClientState {
    def stateName: String
    def handle(
      event: StreamEvent,
      transport: ClientTransport,
      serverRequestQueue: Queue[ServerRequest]
    ): UIO[ClientState]

    protected def config: ClientConfig

    private val membersList = config.clusterMembers.toList

    /** Get the next member to try (round-robin through available members) */
    protected def nextMember(currentMemberId: MemberId): (MemberId, String) = {
      val currentIndex = membersList.indexWhere(_._1 == currentMemberId)
      val nextIndex = (currentIndex + 1) % membersList.size
      membersList(nextIndex)
    }
  }

  object ClientState {

    private[RaftClient] case class Disconnected(config: ClientConfig) extends ClientState {
      override def stateName: String = "Disconnected"

      override def handle(
        event: StreamEvent,
        transport: ClientTransport,
        serverRequestQueue: Queue[ServerRequest]
      ): UIO[ClientState] = {
        event match {
          case StreamEvent.Action(ClientAction.Connect) =>
            for {
              nonce <- Nonce.generate()
              now <- Clock.instant
              // Pick first member from the cluster
              firstMember = config.clusterMembers.head
              (memberId, address) = firstMember
              nextRequestId <- RequestIdRef.make
              _ <- transport.connect(address).orDie
              _ <- transport.sendMessage(CreateSession(config.capabilities, nonce)).orDie
              _ <- ZIO.logInfo(s"Connecting to $memberId at $address")
            } yield ConnectingNewSession(
              config = config,
              capabilities = config.capabilities,
              nonce = nonce,
              currentMemberId = memberId,
              createdAt = now,
              nextRequestId = nextRequestId,
              pendingRequests = PendingRequests.empty,
              pendingQueries = PendingQueries.empty
            )

          case StreamEvent.ServerMsg(message) =>
            ZIO.logWarning(s"Received message while disconnected: ${message.getClass.getSimpleName}").as(this)

          case StreamEvent.Action(ClientAction.SubmitCommand(_, promise)) =>
            promise.die(new IllegalStateException("Not connected")).ignore.as(this)

          case StreamEvent.Action(ClientAction.SubmitQuery(_, promise)) =>
            promise.die(new IllegalStateException("Not connected")).ignore.as(this)

          case StreamEvent.Action(ClientAction.Disconnect) =>
            ZIO.succeed(this)

          case StreamEvent.KeepAliveTick | StreamEvent.TimeoutCheck =>
            ZIO.succeed(this)
        }
      }
    }

    /** Connecting to create a NEW session (no existing sessionId).
      */
    private case class ConnectingNewSession(
      config: ClientConfig,
      capabilities: Map[String, String],
      nonce: Nonce,
      currentMemberId: MemberId,
      createdAt: Instant,
      nextRequestId: RequestIdRef,
      pendingRequests: PendingRequests,
      pendingQueries: PendingQueries
    ) extends ClientState {
      override def stateName: String = "ConnectingNewSession"

      /** Connect to a specific member or use hint */
      private def connectToMember(
        targetMemberId: Option[MemberId],
        transport: ClientTransport,
        logPrefix: String
      ): UIO[ConnectingNewSession] = {
        val (memberId, address) = targetMemberId
          .flatMap(id => config.clusterMembers.get(id).map(addr => (id, addr)))
          .getOrElse(nextMember(currentMemberId))
        val currentAddr = config.clusterMembers.get(currentMemberId)

        for {
          _ <- ZIO.logInfo(s"$logPrefix at ${currentAddr.getOrElse("unknown")}, trying $memberId at $address")
          _ <- transport.disconnect().orDie
          _ <- transport.connect(address).orDie
          newNonce <- Nonce.generate()
          _ <- transport.sendMessage(CreateSession(capabilities, newNonce)).orDie
          _ <- ZIO.logInfo(s"Connecting New Session to $memberId at $address")
          now <- Clock.instant
        } yield copy(nonce = newNonce, currentMemberId = memberId, createdAt = now)
      }

      override def handle(
        event: StreamEvent,
        transport: ClientTransport,
        serverRequestQueue: Queue[ServerRequest]
      ): UIO[ClientState] = {
        event match {
          case StreamEvent.ServerMsg(SessionCreated(sessionId, responseNonce)) =>
            if (nonce == responseNonce) {
              for {
                _ <- ZIO.logInfo(s"Session created: $sessionId")
                now <- Clock.instant
                // Send all pending requests/queries
                updatedPending <- pendingRequests.resendAll(transport)
                updatedPendingQueries <- pendingQueries.resendAll(transport)
              } yield Connected(
                config = config,
                sessionId = sessionId,
                capabilities = capabilities,
                createdAt = now,
                serverRequestTracker = ServerRequestTracker(),
                nextRequestId = nextRequestId,
                pendingRequests = updatedPending,
                pendingQueries = updatedPendingQueries,
                currentMemberId = currentMemberId
              )
            } else {
              ZIO.logWarning("Nonce mismatch, ignoring SessionCreated").as(this)
            }

          case StreamEvent.ServerMsg(SessionRejected(reason, responseNonce, leaderId)) =>
            if (nonce == responseNonce) {
              reason match {
                case RejectionReason.NotLeader =>
                  connectToMember(leaderId, transport, s"Not leader at $currentMemberId")

                case RejectionReason.SessionExpired =>
                  pendingRequests.dieAll(new RuntimeException("Session expired")) *>
                    pendingQueries.dieAll(new RuntimeException("Session expired")) *>
                    ZIO.dieMessage("Session not found - cannot continue")

                case RejectionReason.InvalidCapabilities =>
                  pendingRequests.dieAll(new RuntimeException("Invalid capabilities")) *>
                    pendingQueries.dieAll(new RuntimeException("Invalid capabilities")) *>
                    ZIO.dieMessage(s"Invalid capabilities - cannot connect: ${capabilities}")

                case RejectionReason.Other =>
                  pendingRequests.dieAll(new RuntimeException("Other reason")) *>
                    pendingQueries.dieAll(new RuntimeException("Other reason")) *>
                    ZIO.dieMessage(s"Other reason - cannot connect: ${reason}")
              }
            } else {
              ZIO.logWarning("Nonce mismatch, ignoring SessionRejected").as(this)
            }

          case StreamEvent.Action(ClientAction.SubmitCommand(payload, promise)) =>
            // Queue request while connecting
            for {
              requestId <- nextRequestId.next
              now <- Clock.instant
              newPending = pendingRequests.add(requestId, payload, promise, now)
            } yield copy(pendingRequests = newPending)

          case StreamEvent.Action(ClientAction.SubmitQuery(payload, promise)) =>
            // Queue query while connecting (handled after session established)
            for {
              now <- Clock.instant
              correlationId <- Random.nextUUID.map(u => CorrelationId.fromString(u.toString))
              newPending = pendingQueries.add(correlationId, payload, promise, now)
            } yield copy(pendingQueries = newPending)

          case StreamEvent.TimeoutCheck =>
            for {
              now <- Clock.instant
              elapsed = Duration.fromInterval(createdAt, now)
              nextState <-
                if (elapsed > config.connectionTimeout) {
                  connectToMember(None, transport, s"Connection timeout at $currentMemberId")
                } else {
                  ZIO.succeed(this)
                }
            } yield nextState

          case StreamEvent.ServerMsg(SessionContinued(_)) =>
            ZIO.logWarning("Received SessionContinued while connecting new session, ignoring").as(this)

          case StreamEvent.ServerMsg(ClientResponse(_, _)) =>
            ZIO.logWarning("Received ClientResponse while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(QueryResponse(correlationId, result)) =>
            ZIO.logWarning("Received QueryResponse while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(_: RequestError) =>
            ZIO.logWarning("Received RequestError while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(KeepAliveResponse(_)) =>
            ZIO.succeed(this)

          case StreamEvent.ServerMsg(_: ServerRequest) =>
            ZIO.logWarning("Received ServerRequest while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(SessionClosed(_, _)) =>
            ZIO.logWarning("Received SessionClosed while connecting, ignoring").as(this)

          case StreamEvent.Action(ClientAction.Disconnect) =>
            for {
              _ <- transport.disconnect().orDie
            } yield Disconnected(config)

          case StreamEvent.Action(ClientAction.Connect) =>
            ZIO.logWarning("Received Connect while connecting, ignoring").as(this)

          case StreamEvent.KeepAliveTick =>
            ZIO.succeed(this)
        }
      }
    }

    /** Connecting to resume an EXISTING session (has sessionId).
      */
    private case class ConnectingExistingSession(
      config: ClientConfig,
      sessionId: SessionId,
      capabilities: Map[String, String],
      nonce: Nonce,
      currentMemberId: MemberId,
      createdAt: Instant,
      serverRequestTracker: ServerRequestTracker,
      nextRequestId: RequestIdRef,
      pendingRequests: PendingRequests,
      pendingQueries: PendingQueries
    ) extends ClientState {
      override def stateName: String = s"ConnectingExistingSession($sessionId)"

      /** Connect to a specific member or use hint */
      private def connectToMember(
        targetMemberId: Option[MemberId],
        transport: ClientTransport,
        logPrefix: String
      ): UIO[ConnectingExistingSession] = {
        val (memberId, address) = targetMemberId
          .flatMap(id => config.clusterMembers.get(id).map(addr => (id, addr)))
          .getOrElse(nextMember(currentMemberId))
        val currentAddr = config.clusterMembers.get(currentMemberId)

        for {
          _ <- ZIO.logInfo(s"$logPrefix at ${currentAddr.getOrElse("unknown")}, trying $memberId at $address")
          _ <- transport.disconnect().orDie
          _ <- transport.connect(address).orDie
          newNonce <- Nonce.generate()
          _ <- transport.sendMessage(ContinueSession(sessionId, newNonce)).orDie
          _ <- ZIO.logInfo(s"Connecting Existing Session $sessionId to $memberId at $address")
          now <- Clock.instant
        } yield copy(nonce = newNonce, currentMemberId = memberId, createdAt = now)
      }

      override def handle(
        event: StreamEvent,
        transport: ClientTransport,
        serverRequestQueue: Queue[ServerRequest]
      ): UIO[ClientState] = {
        event match {
          case StreamEvent.ServerMsg(SessionContinued(responseNonce)) =>
            if (nonce == responseNonce) {
              val currentAddr = config.clusterMembers.get(currentMemberId)
              for {
                _ <- ZIO.logInfo(
                  s"Session continued: $sessionId at $currentMemberId (${currentAddr.getOrElse("unknown")})"
                )
                now <- Clock.instant
                // Send all pending requests/queries
                updatedPending <- pendingRequests.resendAll(transport)
                updatedPendingQueries <- pendingQueries.resendAll(transport)
              } yield Connected(
                config = config,
                sessionId = sessionId,
                capabilities = capabilities,
                createdAt = now,
                serverRequestTracker = serverRequestTracker,
                nextRequestId = nextRequestId,
                pendingRequests = updatedPending,
                pendingQueries = updatedPendingQueries,
                currentMemberId = currentMemberId
              )
            } else {
              ZIO.logWarning("Nonce mismatch, ignoring SessionContinued").as(this)
            }

          case StreamEvent.ServerMsg(SessionRejected(reason, responseNonce, leaderId)) =>
            if (nonce == responseNonce) {
              reason match {
                case RejectionReason.NotLeader =>
                  connectToMember(leaderId, transport, s"Not leader at $currentMemberId")

                case RejectionReason.SessionExpired =>
                  pendingRequests.dieAll(new RuntimeException("Session expired")) *>
                    pendingQueries.dieAll(new RuntimeException("Session expired")) *>
                    ZIO.logWarning("Session not found - cannot continue") *>
                    ZIO.dieMessage("Session not found - cannot continue")

                case RejectionReason.InvalidCapabilities =>
                  pendingRequests.dieAll(new RuntimeException("Invalid capabilities")) *>
                    pendingQueries.dieAll(new RuntimeException("Invalid capabilities")) *>
                    ZIO.dieMessage(s"Invalid capabilities - cannot connect: ${capabilities}")

                case RejectionReason.Other =>
                  pendingRequests.dieAll(new RuntimeException("Other reason")) *>
                    pendingQueries.dieAll(new RuntimeException("Other reason")) *>
                    ZIO.dieMessage(s"Other reason - cannot connect: ${reason}")
              }
            } else {
              ZIO.logWarning("Nonce mismatch, ignoring SessionRejected").as(this)
            }

          case StreamEvent.Action(ClientAction.SubmitCommand(payload, promise)) =>
            // Queue request while connecting
            for {
              requestId <- nextRequestId.next
              now <- Clock.instant
              newPending = pendingRequests.add(requestId, payload, promise, now)
            } yield copy(pendingRequests = newPending)

          case StreamEvent.Action(ClientAction.SubmitQuery(payload, promise)) =>
            // Queue query while connecting (handled after session established)
            for {
              now <- Clock.instant
              correlationId <- Random.nextUUID.map(u => CorrelationId.fromString(u.toString))
              newPending = pendingQueries.add(correlationId, payload, promise, now)
            } yield copy(pendingQueries = newPending)

          case StreamEvent.TimeoutCheck =>
            for {
              now <- Clock.instant
              elapsed = Duration.fromInterval(createdAt, now)
              nextState <-
                if (elapsed > config.connectionTimeout) {
                  connectToMember(None, transport, s"Connection timeout at $currentMemberId")
                } else {
                  ZIO.succeed(this)
                }
            } yield nextState

          case StreamEvent.ServerMsg(SessionCreated(_, _)) =>
            ZIO.logWarning("Received SessionCreated while connecting existing session, ignoring").as(this)

          case StreamEvent.ServerMsg(ClientResponse(_, _)) =>
            ZIO.logWarning("Received ClientResponse while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(QueryResponse(correlationId, result)) =>
            ZIO.logWarning("Received QueryResponse while connecting existing session, ignoring").as(this)

          case StreamEvent.ServerMsg(KeepAliveResponse(_)) =>
            ZIO.succeed(this)

          case StreamEvent.ServerMsg(_: RequestError) =>
            ZIO.logWarning("Received RequestError while connecting existing session, ignoring").as(this)

          case StreamEvent.ServerMsg(_: ServerRequest) =>
            ZIO.logWarning("Received ServerRequest while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(SessionClosed(_, _)) =>
            ZIO.logWarning("Received SessionClosed while connecting, ignoring").as(this)

          case StreamEvent.Action(ClientAction.Disconnect) =>
            for {
              _ <- transport.disconnect().orDie
            } yield Disconnected(config)

          case StreamEvent.Action(ClientAction.Connect) =>
            ZIO.logWarning("Received Connect while connecting, ignoring").as(this)

          case StreamEvent.KeepAliveTick =>
            ZIO.succeed(this)
        }
      }
    }

    private case class Connected(
      config: ClientConfig,
      sessionId: SessionId,
      capabilities: Map[String, String],
      createdAt: Instant,
      serverRequestTracker: ServerRequestTracker,
      nextRequestId: RequestIdRef,
      pendingRequests: PendingRequests,
      pendingQueries: PendingQueries,
      currentMemberId: MemberId
    ) extends ClientState {
      override def stateName: String = s"Connected($sessionId)"

      /** Helper method to reconnect to a specific member with an existing session. Uses leader hint if available,
        * otherwise falls back to next member.
        */
      private def reconnectTo(
        targetMemberId: Option[MemberId],
        transport: ClientTransport,
        logMessage: String
      ): UIO[ConnectingExistingSession] = {
        val (memberId, address) = targetMemberId
          .flatMap(id => config.clusterMembers.get(id).map(addr => (id, addr)))
          .getOrElse(nextMember(currentMemberId))

        for {
          _ <- ZIO.logInfo(logMessage)
          nonce <- Nonce.generate()
          _ <- transport.disconnect().orDie
          _ <- transport.connect(address).orDie
          _ <- transport.sendMessage(ContinueSession(sessionId, nonce)).orDie
          _ <- ZIO.logInfo(s"Connecting Existing Session $sessionId to $memberId at $address")
          now <- Clock.instant
        } yield ConnectingExistingSession(
          config = config,
          sessionId = sessionId,
          capabilities = capabilities,
          nonce = nonce,
          currentMemberId = memberId,
          createdAt = now,
          serverRequestTracker = serverRequestTracker,
          nextRequestId = nextRequestId,
          pendingRequests = pendingRequests,
          pendingQueries = pendingQueries
        )
      }

      override def handle(
        event: StreamEvent,
        transport: ClientTransport,
        serverRequestQueue: Queue[ServerRequest]
      ): UIO[ClientState] = {
        event match {
          case StreamEvent.Action(ClientAction.Disconnect) =>
            for {
              _ <- transport.sendMessage(CloseSession(CloseReason.ClientShutdown)).orDie
              _ <- transport.disconnect().orDie
              _ <- ZIO.logInfo("Disconnected")
            } yield Disconnected(config)

          case StreamEvent.Action(ClientAction.Connect) =>
            ZIO.logWarning("Received Connect while connected, ignoring").as(this)

          case StreamEvent.Action(ClientAction.SubmitCommand(payload, promise)) =>
            for {
              requestId <- nextRequestId.next
              now <- Clock.instant
              lowestPendingRequestId = pendingRequests.lowestPendingRequestIdOr(requestId)
              request = ClientRequest(requestId, lowestPendingRequestId, payload, now)
              _ <- transport.sendMessage(request).orDie
              newPending = pendingRequests.add(requestId, payload, promise, now)
            } yield copy(pendingRequests = newPending)
          case StreamEvent.Action(ClientAction.SubmitQuery(payload, promise)) =>
            for {
              now <- Clock.instant
              correlationId <- Random.nextUUID.map(u => CorrelationId.fromString(u.toString))
              newPending = pendingQueries.add(correlationId, payload, promise, now)
              _ <- transport.sendMessage(Query(correlationId, payload, now)).orDie
            } yield copy(pendingQueries = newPending)

          case StreamEvent.ServerMsg(ClientResponse(requestId, result)) =>
            pendingRequests.complete(requestId, result).map(newPending => copy(pendingRequests = newPending))
          case StreamEvent.ServerMsg(QueryResponse(correlationId, result)) =>
            pendingQueries.complete(correlationId, result).map(newPending => copy(pendingQueries = newPending))

          case StreamEvent.ServerMsg(RequestError(requestId, RequestErrorReason.ResponseEvicted)) =>
            if (pendingRequests.contains(requestId))
              ZIO.logError(s"RequestError: ResponseEvicted for request $requestId, terminating client") *>
                pendingRequests.dieAll(new RuntimeException("ResponseEvicted")) *>
                pendingQueries.dieAll(new RuntimeException("ResponseEvicted")) *>
                ZIO.dieMessage("ResponseEvicted")
            else
              ZIO.logWarning(s"RequestError for non-pending request $requestId, ignoring").as(this)

          case StreamEvent.ServerMsg(KeepAliveResponse(_)) =>
            ZIO.succeed(this)

          case StreamEvent.ServerMsg(serverRequest: ServerRequest) =>
            serverRequestTracker.shouldProcess(serverRequest.requestId) match {
              case ServerRequestResult.Process =>
                for {
                  _ <- serverRequestQueue.offer(serverRequest)
                  newTracker = serverRequestTracker.acknowledge(serverRequest.requestId)
                  _ <- transport.sendMessage(ServerRequestAck(serverRequest.requestId)).orDie
                  _ <- ZIO.logDebug(s"Enqueued and acknowledged server request: ${serverRequest.requestId}")
                } yield copy(serverRequestTracker = newTracker)

              case ServerRequestResult.OldRequest =>
                for {
                  _ <- transport.sendMessage(ServerRequestAck(serverRequest.requestId)).orDie
                  _ <- ZIO.logWarning(s"Re-acknowledged already processed request: ${serverRequest.requestId}")
                } yield this

              case ServerRequestResult.OutOfOrder =>
                ZIO
                  .logWarning(
                    s"Dropping out-of-order server request: ${serverRequest.requestId}, expected: ${serverRequestTracker.lastAcknowledgedRequestId.next}"
                  )
                  .as(this)
            }

          case StreamEvent.ServerMsg(SessionClosed(SessionCloseReason.Shutdown, _)) =>
            ZIO.logInfo("Server shutdown, session closed").as(Disconnected(config))

          case StreamEvent.ServerMsg(SessionClosed(SessionCloseReason.NotLeaderAnymore, leaderId)) =>
            val currentAddr = config.clusterMembers.get(currentMemberId)
            reconnectTo(
              leaderId,
              transport,
              s"Session closed: not leader anymore at $currentMemberId (${currentAddr.getOrElse("unknown")})"
            )

          case StreamEvent.ServerMsg(SessionClosed(SessionCloseReason.SessionError, _)) =>
            val currentAddr = config.clusterMembers.get(currentMemberId)
            reconnectTo(
              Some(currentMemberId),
              transport,
              s"Session closed: session error, reconnecting to same member: $currentMemberId (${currentAddr.getOrElse("unknown")})"
            )

          case StreamEvent.ServerMsg(SessionClosed(SessionCloseReason.ConnectionClosed, _)) =>
            val currentAddr = config.clusterMembers.get(currentMemberId)
            reconnectTo(
              Some(currentMemberId),
              transport,
              s"Session closed: connection closed, reconnecting to same member: $currentMemberId (${currentAddr.getOrElse("unknown")})"
            )

          case StreamEvent.ServerMsg(SessionClosed(SessionCloseReason.SessionExpired, _)) =>
            pendingRequests.dieAll(new RuntimeException("Session expired")) *>
              pendingQueries.dieAll(new RuntimeException("Session expired")) *>
              ZIO.logWarning("Session closed due to timeout, terminating client") *>
              ZIO.dieMessage("Session timed out: shutting down client.")

          case StreamEvent.KeepAliveTick =>
            for {
              now <- Clock.instant
              _ <- transport.sendMessage(KeepAlive(now)).orDie
            } yield this

          case StreamEvent.TimeoutCheck =>
            for {
              now <- Clock.instant
              newPendingReqs <- pendingRequests.resendExpired(transport, now, config.requestTimeout)
              newPendingQs <- pendingQueries.resendExpired(transport, now, config.requestTimeout)
            } yield copy(pendingRequests = newPendingReqs, pendingQueries = newPendingQs)

          case StreamEvent.ServerMsg(SessionCreated(_, _)) =>
            ZIO.logWarning("Received SessionCreated while connected, ignoring").as(this)

          case StreamEvent.ServerMsg(SessionContinued(_)) =>
            ZIO.logWarning("Received SessionContinued while connected, ignoring").as(this)

          case StreamEvent.ServerMsg(SessionRejected(_, _, _)) =>
            ZIO.logWarning("Received SessionRejected while connected, ignoring").as(this)
        }
      }
    }
  }

  private sealed trait StreamEvent

  private object StreamEvent {
    case class Action(action: ClientAction) extends StreamEvent
    case class ServerMsg(message: ServerMessage) extends StreamEvent
    case object KeepAliveTick extends StreamEvent
    case object TimeoutCheck extends StreamEvent
  }
}
