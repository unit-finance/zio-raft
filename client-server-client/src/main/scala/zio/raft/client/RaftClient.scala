package zio.raft.client

import zio.*
import zio.stream.*
import zio.raft.protocol.*
import scodec.bits.{BitVector, ByteVector}
import java.time.Instant
import zio.raft.client.RaftClient.ZmqClientTransport
import zio.zmq.{ZContext, ZSocket}
import zio.raft.protocol.Codecs.{clientMessageCodec, serverMessageCodec}

/** Main RaftClient implementation using pure functional state machine.
  *
  * The client uses a unified stream that merges all events. Each ClientState knows how to handle events and transition
  * to new states.
  */
class RaftClient private (
    zmqTransport: ZmqClientTransport,
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

    val unifiedStream = actionStream
      .merge(messageStream)
      .merge(keepAliveStream)
      .merge(timeoutStream)

    // Start in Disconnected state
    unifiedStream
      .runFoldZIO(RaftClient.ClientState.Disconnected: RaftClient.ClientState) { (state, event) =>
        state.handle(event, zmqTransport, config, serverRequestQueue)
      }
      .orDie
      .unit
  }

  def connect(): UIO[Unit] =
    actionQueue.offer(ClientAction.Connect).unit

  def submitCommand(payload: ByteVector): Task[ByteVector] =
    for {
      promise <- Promise.make[Throwable, ByteVector]
      action = ClientAction.SubmitCommand(payload, promise)
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

      zmqTransport <- createZmqTransport(validatedConfig)
      actionQueue <- Queue.unbounded[ClientAction]
      serverRequestQueue <- Queue.unbounded[ServerRequest]

      client = new RaftClient(zmqTransport, validatedConfig, actionQueue, serverRequestQueue)

      // Register finalizer to cleanly close session on scope exit
      _ <- ZIO.addFinalizer(
        zmqTransport.sendMessage(CloseSession(CloseReason.ClientShutdown)).orDie
      )

    } yield client

  def make(
      clusterMembers: Map[MemberId, String],
      capabilities: Map[String, String]
  ): ZIO[Scope & ZContext, Throwable, RaftClient] = {
    val config = ClientConfig.make(clusterMembers, capabilities)
    make(config)
  }

  private def createZmqTransport(config: ClientConfig) =
    for {
      socket <- ZSocket.client
      _ <- socket.options.setLinger(0)
      _ <- socket.options.setHeartbeat(1.seconds, 10.second, 30.second)
      timeoutConnectionClosed = serverMessageCodec
        .encode(SessionClosed(SessionCloseReason.ConnectionClosed, None))
        .require
        .toByteArray
      _ <- socket.options.setHiccupMessage(timeoutConnectionClosed)
      _ <- socket.options.setHighWatermark(200000, 200000)
      lastAddressRef <- Ref.make(Option.empty[String])
      transport = new ZmqClientTransportLive(socket, lastAddressRef)
    } yield transport

  /** Main loop accepts initial state as parameter.
    */
  private def startMainLoop(
      transport: ZmqClientTransport,
      config: ClientConfig,
      actionQueue: Queue[ClientAction],
      serverRequestQueue: Queue[ServerRequest],
      initialState: ClientState
  ): Task[Unit] = {

    val actionStream = ZStream
      .fromQueue(actionQueue)
      .map(StreamEvent.Action(_))

    val messageStream = transport.incomingMessages
      .map(StreamEvent.ServerMsg(_))

    val keepAliveStream = ZStream
      .tick(config.keepAliveInterval)
      .map(_ => StreamEvent.KeepAliveTick)

    // Timeout check every 100ms
    val timeoutStream = ZStream
      .tick(100.millis)
      .map(_ => StreamEvent.TimeoutCheck)

    val unifiedStream = actionStream
      .merge(messageStream)
      .merge(keepAliveStream)
      .merge(timeoutStream)

    // Pure functional state machine: state handles events
    unifiedStream
      .runFoldZIO(initialState) { (state, event) =>
        // State handles everything - just pass dependencies
        state.handle(event, transport, config, serverRequestQueue)
      }
      .unit
  }

  /** Functional state machine: each state handles events and returns new states.
    */
  private sealed trait ClientState {
    def stateName: String
    def handle(
        event: StreamEvent,
        transport: ZmqClientTransport,
        config: ClientConfig,
        serverRequestQueue: Queue[ServerRequest]
    ): UIO[ClientState]
  }

  object ClientState {

    private[RaftClient] case object Disconnected extends ClientState {
      override def stateName: String = "Disconnected"

      override def handle(
          event: StreamEvent,
          transport: ZmqClientTransport,
          config: ClientConfig,
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
              capabilities = config.capabilities,
              nonce = nonce,
              clusterMembers = config.clusterMembers,
              currentMemberId = memberId,
              createdAt = now,
              nextRequestId = nextRequestId,
              pendingRequests = PendingRequests.empty
            )

          case StreamEvent.ServerMsg(message) =>
            ZIO.logWarning(s"Received message while disconnected: ${message.getClass.getSimpleName}").as(this)

          case StreamEvent.Action(ClientAction.SubmitCommand(_, promise)) =>
            promise.fail(new IllegalStateException("Not connected")).ignore.as(this)

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
        capabilities: Map[String, String],
        nonce: Nonce,
        clusterMembers: Map[MemberId, String],
        currentMemberId: MemberId,
        createdAt: Instant,
        nextRequestId: RequestIdRef,
        pendingRequests: PendingRequests
    ) extends ClientState {
      override def stateName: String = "ConnectingNewSession"

      /** Get the next member to try (round-robin through available members) */
      private def nextMember: (MemberId, String) = {
        val membersList = clusterMembers.toList
        val currentIndex = membersList.indexWhere(_._1 == currentMemberId)
        val nextIndex = (currentIndex + 1) % membersList.size
        membersList(nextIndex)
      }

      /** Connect to a specific member or use hint */
      private def connectToMember(
          targetMemberId: Option[MemberId],
          transport: ZmqClientTransport,
          logPrefix: String
      ): UIO[ConnectingNewSession] = {
        val (memberId, address) = targetMemberId
          .flatMap(id => clusterMembers.get(id).map(addr => (id, addr)))
          .getOrElse(nextMember)
        val currentAddr = clusterMembers.get(currentMemberId)

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
          transport: ZmqClientTransport,
          config: ClientConfig,
          serverRequestQueue: Queue[ServerRequest]
      ): UIO[ClientState] = {
        event match {
          case StreamEvent.ServerMsg(SessionCreated(sessionId, responseNonce)) =>
            if (nonce == responseNonce) {
              for {
                _ <- ZIO.logInfo(s"Session created: $sessionId")
                now <- Clock.instant
                // Send all pending requests
                updatedPending <- pendingRequests.resendAll(transport)
              } yield Connected(
                sessionId = sessionId,
                capabilities = capabilities,
                createdAt = now,
                serverRequestTracker = ServerRequestTracker(),
                nextRequestId = nextRequestId,
                pendingRequests = updatedPending,
                clusterMembers = clusterMembers,
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

                case RejectionReason.SessionNotFound =>
                  ZIO.dieMessage("Session not found - cannot continue")

                case RejectionReason.InvalidCapabilities =>
                  ZIO.dieMessage(s"Invalid capabilities - cannot connect: ${capabilities}")
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

          case StreamEvent.ServerMsg(KeepAliveResponse(_)) =>
            ZIO.succeed(this)

          case StreamEvent.ServerMsg(_: ServerRequest) =>
            ZIO.logWarning("Received ServerRequest while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(SessionClosed(_, _)) =>
            ZIO.logWarning("Received SessionClosed while connecting, ignoring").as(this)

          case StreamEvent.Action(ClientAction.Disconnect) =>
            for {
              _ <- transport.disconnect().orDie
            } yield Disconnected

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
        sessionId: SessionId,
        capabilities: Map[String, String],
        nonce: Nonce,
        clusterMembers: Map[MemberId, String],
        currentMemberId: MemberId,
        createdAt: Instant,
        serverRequestTracker: ServerRequestTracker,
        nextRequestId: RequestIdRef,
        pendingRequests: PendingRequests
    ) extends ClientState {
      override def stateName: String = s"ConnectingExistingSession($sessionId)"

      /** Get the next member to try (round-robin through available members) */
      private def nextMember: (MemberId, String) = {
        val membersList = clusterMembers.toList
        val currentIndex = membersList.indexWhere(_._1 == currentMemberId)
        val nextIndex = (currentIndex + 1) % membersList.size
        membersList(nextIndex)
      }

      /** Connect to a specific member or use hint */
      private def connectToMember(
          targetMemberId: Option[MemberId],
          transport: ZmqClientTransport,
          logPrefix: String
      ): UIO[ConnectingExistingSession] = {
        val (memberId, address) = targetMemberId
          .flatMap(id => clusterMembers.get(id).map(addr => (id, addr)))
          .getOrElse(nextMember)
        val currentAddr = clusterMembers.get(currentMemberId)

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
          transport: ZmqClientTransport,
          config: ClientConfig,
          serverRequestQueue: Queue[ServerRequest]
      ): UIO[ClientState] = {
        event match {
          case StreamEvent.ServerMsg(SessionContinued(responseNonce)) =>
            if (nonce == responseNonce) {
              val currentAddr = clusterMembers.get(currentMemberId)
              for {
                _ <- ZIO.logInfo(
                  s"Session continued: $sessionId at $currentMemberId (${currentAddr.getOrElse("unknown")})"
                )
                now <- Clock.instant
                // Send all pending requests
                updatedPending <- pendingRequests.resendAll(transport)
              } yield Connected(
                sessionId = sessionId,
                capabilities = capabilities,
                createdAt = now,
                serverRequestTracker = serverRequestTracker,
                nextRequestId = nextRequestId,
                pendingRequests = updatedPending,
                clusterMembers = clusterMembers,
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

                case RejectionReason.SessionNotFound =>
                  ZIO.logWarning("Session not found - cannot continue") *> ZIO.dieMessage(
                    "Session not found - cannot continue"
                  )

                case RejectionReason.InvalidCapabilities =>
                  ZIO.dieMessage(s"Invalid capabilities - cannot connect: ${capabilities}")
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

          case StreamEvent.ServerMsg(KeepAliveResponse(_)) =>
            ZIO.succeed(this)

          case StreamEvent.ServerMsg(_: ServerRequest) =>
            ZIO.logWarning("Received ServerRequest while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(SessionClosed(_, _)) =>
            ZIO.logWarning("Received SessionClosed while connecting, ignoring").as(this)

          case StreamEvent.Action(ClientAction.Disconnect) =>
            for {
              _ <- transport.disconnect().orDie
            } yield Disconnected

          case StreamEvent.Action(ClientAction.Connect) =>
            ZIO.logWarning("Received Connect while connecting, ignoring").as(this)

          case StreamEvent.KeepAliveTick =>
            ZIO.succeed(this)
        }
      }
    }

    private case class Connected(
        sessionId: SessionId,
        capabilities: Map[String, String],
        createdAt: Instant,
        serverRequestTracker: ServerRequestTracker,
        nextRequestId: RequestIdRef,
        pendingRequests: PendingRequests,
        clusterMembers: Map[MemberId, String],
        currentMemberId: MemberId
    ) extends ClientState {
      override def stateName: String = s"Connected($sessionId)"

      /** Get the next member to try (round-robin through available members) */
      private def nextMember: (MemberId, String) = {
        val membersList = clusterMembers.toList
        val currentIndex = membersList.indexWhere(_._1 == currentMemberId)
        val nextIndex = (currentIndex + 1) % membersList.size
        membersList(nextIndex)
      }

      /** Helper method to reconnect to a specific member with an existing session. Uses leader hint if available,
        * otherwise falls back to next member.
        */
      private def reconnectTo(
          targetMemberId: Option[MemberId],
          transport: ZmqClientTransport,
          logMessage: String
      ): UIO[ConnectingExistingSession] = {
        val (memberId, address) = targetMemberId
          .flatMap(id => clusterMembers.get(id).map(addr => (id, addr)))
          .getOrElse(nextMember)

        for {
          _ <- ZIO.logInfo(logMessage)
          nonce <- Nonce.generate()
          _ <- transport.disconnect().orDie
          _ <- transport.connect(address).orDie
          _ <- transport.sendMessage(ContinueSession(sessionId, nonce)).orDie
          _ <- ZIO.logInfo(s"Connecting Existing Session $sessionId to $memberId at $address")
          now <- Clock.instant
        } yield ConnectingExistingSession(
          sessionId = sessionId,
          capabilities = capabilities,
          nonce = nonce,
          clusterMembers = clusterMembers,
          currentMemberId = memberId,
          createdAt = now,
          serverRequestTracker = serverRequestTracker,
          nextRequestId = nextRequestId,
          pendingRequests = pendingRequests
        )
      }

      override def handle(
          event: StreamEvent,
          transport: ZmqClientTransport,
          config: ClientConfig,
          serverRequestQueue: Queue[ServerRequest]
      ): UIO[ClientState] = {
        event match {
          case StreamEvent.Action(ClientAction.Disconnect) =>
            for {
              _ <- transport.sendMessage(CloseSession(CloseReason.ClientShutdown)).orDie
              _ <- transport.disconnect().orDie
              _ <- ZIO.logInfo("Disconnected")
            } yield Disconnected

          case StreamEvent.Action(ClientAction.Connect) =>
            ZIO.logWarning("Received Connect while connected, ignoring").as(this)

          case StreamEvent.Action(ClientAction.SubmitCommand(payload, promise)) =>
            for {
              requestId <- nextRequestId.next
              now <- Clock.instant
              request = ClientRequest(requestId, payload, now)
              _ <- transport.sendMessage(request).orDie
              newPending = pendingRequests.add(requestId, payload, promise, now)
            } yield copy(pendingRequests = newPending)

          case StreamEvent.ServerMsg(ClientResponse(requestId, result)) =>
            pendingRequests.complete(requestId, result).map(newPending => copy(pendingRequests = newPending))

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
            ZIO.logInfo("Server shutdown, session closed").as(Disconnected)

          case StreamEvent.ServerMsg(SessionClosed(SessionCloseReason.NotLeaderAnymore, leaderId)) =>
            val currentAddr = clusterMembers.get(currentMemberId)
            reconnectTo(
              leaderId,
              transport,
              s"Session closed: not leader anymore at $currentMemberId (${currentAddr.getOrElse("unknown")})"
            )

          case StreamEvent.ServerMsg(SessionClosed(SessionCloseReason.SessionError, _)) =>
            val currentAddr = clusterMembers.get(currentMemberId)
            reconnectTo(
              Some(currentMemberId),
              transport,
              s"Session closed: session error, reconnecting to same member: $currentMemberId (${currentAddr.getOrElse("unknown")})"
            )

          case StreamEvent.ServerMsg(SessionClosed(SessionCloseReason.ConnectionClosed, _)) =>
            val currentAddr = clusterMembers.get(currentMemberId)
            reconnectTo(
              Some(currentMemberId),
              transport,
              s"Session closed: connection closed, reconnecting to same member: $currentMemberId (${currentAddr.getOrElse("unknown")})"
            )

          case StreamEvent.ServerMsg(SessionClosed(SessionCloseReason.SessionTimeout, _)) =>
            ZIO.logWarning("Session closed due to timeout, terminating client") *> ZIO.dieMessage(
              "Session timed out: shutting down client."
            )

          case StreamEvent.KeepAliveTick =>
            for {
              now <- Clock.instant
              _ <- transport.sendMessage(KeepAlive(now)).orDie
            } yield this

          case StreamEvent.TimeoutCheck =>
            for {
              now <- Clock.instant
              newPending <- pendingRequests.resendExpired(transport, now, config.requestTimeout)
            } yield copy(pendingRequests = newPending)

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

  /** Manages pending requests with lastSentAt timestamps for retry.
    */
  case class PendingRequests(
      requests: Map[RequestId, PendingRequestData]
  ) {
    def add(
        requestId: RequestId,
        payload: ByteVector,
        promise: Promise[Throwable, ByteVector],
        sentAt: Instant
    ): PendingRequests =
      copy(requests = requests.updated(requestId, PendingRequestData(payload, promise, sentAt, sentAt)))

    def complete(requestId: RequestId, result: ByteVector): ZIO[Any, Nothing, PendingRequests] =
      requests.get(requestId) match {
        case Some(data) =>
          data.promise.succeed(result).as(copy(requests = requests.removed(requestId)))
        case None =>
          ZIO.succeed(this)
      }

    /** Resend all pending requests (used after successful connection). Returns updated PendingRequests with new
      * lastSentAt timestamps.
      */
    def resendAll(transport: ZmqClientTransport): UIO[PendingRequests] =
      ZIO.foldLeft(requests.toList)(this) { case (pending, (requestId, data)) =>
        for {
          now <- Clock.instant
          request = ClientRequest(requestId, data.payload, now)
          _ <- transport.sendMessage(request).orDie
          _ <- ZIO.logDebug(s"Resending pending request: $requestId")
          updatedData = data.copy(lastSentAt = now)
        } yield PendingRequests(pending.requests.updated(requestId, updatedData))
      }

    /** Resend expired requests and update lastSentAt.
      */
    def resendExpired(transport: ZmqClientTransport, currentTime: Instant, timeout: Duration): UIO[PendingRequests] = {
      val timeoutSeconds = timeout.toSeconds
      ZIO.foldLeft(requests.toList)(this) { case (pending, (requestId, data)) =>
        val elapsed = Duration.fromInterval(data.lastSentAt, currentTime)
        if (elapsed > timeout) {
          val request = ClientRequest(requestId, data.payload, currentTime)
          for {
            _ <- transport.sendMessage(request).orDie
            _ <- ZIO.logDebug(s"Resending timed out request: $requestId")
            updatedData = data.copy(lastSentAt = currentTime)
          } yield PendingRequests(pending.requests.updated(requestId, updatedData))
        } else {
          ZIO.succeed(pending)
        }
      }
    }
  }

  case class PendingRequestData(
      payload: ByteVector,
      promise: Promise[Throwable, ByteVector],
      createdAt: Instant,
      lastSentAt: Instant
  )

  object PendingRequests {
    def empty: PendingRequests = PendingRequests(Map.empty)
  }

  private sealed trait StreamEvent

  private object StreamEvent {
    case class Action(action: ClientAction) extends StreamEvent
    case class ServerMsg(message: ServerMessage) extends StreamEvent
    case object KeepAliveTick extends StreamEvent
    case object TimeoutCheck extends StreamEvent
  }

  trait ZmqClientTransport {
    def connect(address: String): ZIO[Any, Throwable, Unit]
    def disconnect(): ZIO[Any, Throwable, Unit]
    def sendMessage(message: ClientMessage): ZIO[Any, Throwable, Unit]
    def incomingMessages: ZStream[Any, Throwable, ServerMessage]
  }

  private class ZmqClientTransportLive(socket: ZSocket, lastAddressRef: Ref[Option[String]])
      extends ZmqClientTransport {
    override def connect(address: String): ZIO[Any, Throwable, Unit] =
      for {
        _ <- lastAddressRef.set(Some(address))
        _ <- socket.connect(address)
      } yield ()

    override def disconnect(): ZIO[Any, Throwable, Unit] =
      for {
        lastAddress <- lastAddressRef.get
        _ <- lastAddress match {
          case Some(address) => socket.disconnect(address)
          case None          => ZIO.unit
        }
        _ <- lastAddressRef.set(None)
      } yield ()

    override def sendMessage(message: ClientMessage): ZIO[Any, Throwable, Unit] =
      for {
        bytes <- ZIO.attempt(clientMessageCodec.encode(message).require.toByteArray)
        _ <- socket.sendImmediately(bytes)
      } yield ()

    override def incomingMessages: ZStream[Any, Throwable, ServerMessage] =
      socket.stream.mapZIO { msg =>
        ZIO.attempt(serverMessageCodec.decode(BitVector(msg.data())).require.value)
      }
  }
}
