package zio.raft.client

import zio._
import zio.stream._
import zio.raft.protocol._
import scodec.bits.{BitVector, ByteVector}
import java.time.Instant
import zio.raft.client.RaftClient.ZmqClientTransport
import zio.zmq.{ZContext, ZSocket}
import zio.raft.protocol.Codecs.{clientMessageCodec, serverMessageCodec}
import zio.raft.protocol.{SessionClosed, ConnectionClosed}

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

  def make(addresses: List[String], capabilities: Map[String, String]): ZIO[Scope & ZContext, Throwable, RaftClient] = {
    val config = ClientConfig.make(addresses, capabilities)
    for {
      validatedConfig <- ClientConfig.validated(config).mapError(new IllegalArgumentException(_))

      zmqTransport <- createZmqTransport(validatedConfig)
      actionQueue <- Queue.unbounded[ClientAction]
      serverRequestQueue <- Queue.unbounded[ServerRequest]

      client = new RaftClient(zmqTransport, validatedConfig, actionQueue, serverRequestQueue)

      // Start in Disconnected state
      initialState = ClientState.Disconnected

      // Start main loop
      _ <- startMainLoop(zmqTransport, validatedConfig, actionQueue, serverRequestQueue, initialState).fork

    } yield client
  }

  private def createZmqTransport(config: ClientConfig) =     
    for {
      socket <- ZSocket.client
      _ <- socket.options.setImmediate(false)
      _ <- socket.options.setLinger(0)
      _ <- socket.options.setHeartbeat(1.seconds, 10.second, 30.second)
      timeoutConnectionClosed = serverMessageCodec.encode(SessionClosed(ConnectionClosed, None)).require.toByteArray
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
              address = config.clusterAddresses.head
              nextRequestId <- RequestIdRef.make
              _ <- transport.connect(address).orDie
              _ <- transport.sendMessage(CreateSession(config.capabilities, nonce)).orDie
              _ <- ZIO.logInfo("Connecting to cluster...")
            } yield ConnectingNewSession(
              capabilities = config.capabilities,
              nonce = nonce,
              addresses = config.clusterAddresses,
              currentAddressIndex = 0,
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
        addresses: List[String],
        currentAddressIndex: Int,
        createdAt: Instant,
        nextRequestId: RequestIdRef,
        pendingRequests: PendingRequests
    ) extends ClientState {
      override def stateName: String = "ConnectingNewSession"

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
                addresses = addresses,
                currentAddressIndex = currentAddressIndex
              )
            } else {
              ZIO.logWarning("Nonce mismatch, ignoring SessionCreated").as(this)
            }

          case StreamEvent.ServerMsg(SessionRejected(reason, responseNonce, leaderId)) =>
            if (nonce == responseNonce) {
              reason match {
                case NotLeader =>
                  val nextIndex = (currentAddressIndex + 1) % addresses.length
                  val nextAddr = addresses(nextIndex)
                  for {
                    _ <- ZIO.logInfo(s"Not leader, trying next: $nextAddr")
                    _ <- transport.disconnect().orDie
                    _ <- transport.connect(nextAddr).orDie
                    newNonce <- Nonce.generate()
                    _ <- transport.sendMessage(CreateSession(capabilities, newNonce)).orDie
                    now <- Clock.instant
                  } yield copy(nonce = newNonce, currentAddressIndex = nextIndex, createdAt = now)

                case SessionNotFound =>
                  ZIO.dieMessage("Session not found - cannot continue")

                case InvalidCapabilities =>
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
              elapsed = java.time.Duration.between(createdAt, now)
              nextState <-
                if (elapsed.compareTo(java.time.Duration.ofSeconds(config.connectionTimeout.toSeconds)) > 0) {
                  val nextIndex = (currentAddressIndex + 1) % addresses.length
                  val nextAddr = addresses(nextIndex)
                  for {
                    _ <- ZIO.logWarning(s"Connection timeout, trying next address: $nextAddr")
                    _ <- transport.disconnect().orDie
                    _ <- transport.connect(nextAddr).orDie
                    newNonce <- Nonce.generate()
                    _ <- transport.sendMessage(CreateSession(capabilities, newNonce)).orDie
                    now <- Clock.instant
                  } yield copy(nonce = newNonce, currentAddressIndex = nextIndex, createdAt = now)
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

          case StreamEvent.ServerMsg(RequestError(_, _)) =>
            ZIO.logWarning("Received RequestError while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(SessionClosed(_, _)) =>
            ZIO.logWarning("Received SessionClosed while connecting, ignoring").as(this)

          case StreamEvent.Action(ClientAction.Disconnect) =>
            for {
              _ <- transport.disconnect().orDie
            } yield Disconnected

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
        addresses: List[String],
        currentAddressIndex: Int,
        createdAt: Instant,
        serverRequestTracker: ServerRequestTracker,
        nextRequestId: RequestIdRef,
        pendingRequests: PendingRequests
    ) extends ClientState {
      override def stateName: String = s"ConnectingExistingSession($sessionId)"

      override def handle(
          event: StreamEvent,
          transport: ZmqClientTransport,
          config: ClientConfig,
          serverRequestQueue: Queue[ServerRequest]
      ): UIO[ClientState] = {
        event match {
          case StreamEvent.ServerMsg(SessionContinued(responseNonce)) =>
            if (nonce == responseNonce) {
              for {
                _ <- ZIO.logInfo(s"Session continued: $sessionId")
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
                addresses = addresses,
                currentAddressIndex = currentAddressIndex
              )
            } else {
              ZIO.logWarning("Nonce mismatch, ignoring SessionContinued").as(this)
            }

          case StreamEvent.ServerMsg(SessionRejected(reason, responseNonce, leaderId)) =>
            if (nonce == responseNonce) {
              reason match {
                case NotLeader =>
                  val nextIndex = (currentAddressIndex + 1) % addresses.length
                  val nextAddr = addresses(nextIndex)
                  for {
                    _ <- ZIO.logInfo(s"Not leader, trying next: $nextAddr")
                    _ <- transport.disconnect().orDie
                    _ <- transport.connect(nextAddr).orDie
                    newNonce <- Nonce.generate()
                    _ <- transport.sendMessage(ContinueSession(sessionId, newNonce)).orDie
                    now <- Clock.instant
                  } yield copy(nonce = newNonce, currentAddressIndex = nextIndex, createdAt = now)

                case SessionNotFound =>
                  ZIO.dieMessage("Session not found - cannot continue")

                case InvalidCapabilities =>
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
              elapsed = java.time.Duration.between(createdAt, now)
              nextState <-
                if (elapsed.compareTo(java.time.Duration.ofSeconds(config.connectionTimeout.toSeconds)) > 0) {
                  val nextIndex = (currentAddressIndex + 1) % addresses.length
                  val nextAddr = addresses(nextIndex)
                  for {
                    _ <- ZIO.logWarning(s"Connection timeout, trying next address: $nextAddr")
                    _ <- transport.disconnect().orDie
                    _ <- transport.connect(nextAddr).orDie
                    newNonce <- Nonce.generate()
                    _ <- transport.sendMessage(ContinueSession(sessionId, newNonce)).orDie
                    now <- Clock.instant
                  } yield copy(nonce = newNonce, currentAddressIndex = nextIndex, createdAt = now)
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

          case StreamEvent.ServerMsg(RequestError(_, _)) =>
            ZIO.logWarning("Received RequestError while connecting, ignoring").as(this)

          case StreamEvent.ServerMsg(SessionClosed(_, _)) =>
            ZIO.logWarning("Received SessionClosed while connecting, ignoring").as(this)

          case StreamEvent.Action(ClientAction.Disconnect) =>
            for {
              _ <- transport.disconnect().orDie
            } yield Disconnected

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
        addresses: List[String],
        currentAddressIndex: Int
    ) extends ClientState {
      override def stateName: String = s"Connected($sessionId)"

      override def handle(
          event: StreamEvent,
          transport: ZmqClientTransport,
          config: ClientConfig,
          serverRequestQueue: Queue[ServerRequest]
      ): UIO[ClientState] = {
        event match {
          case StreamEvent.Action(ClientAction.Disconnect) =>
            for {
              _ <- transport.sendMessage(CloseSession(ClientShutdown)).orDie
              _ <- transport.disconnect().orDie
              _ <- ZIO.logInfo("Disconnected")
            } yield Disconnected

          case StreamEvent.Action(ClientAction.SubmitCommand(payload, promise)) =>
            for {
              requestId <- nextRequestId.next
              now <- Clock.instant
              request = ClientRequest(requestId, payload, now)
              _ <- transport.sendMessage(request).orDie
              newPending = pendingRequests.add(requestId, payload, promise, now)
            } yield copy(pendingRequests = newPending)

          case StreamEvent.ServerMsg(ClientResponse(requestId, result)) =>
            pendingRequests.complete(requestId, result) match {
              case Some(newPending) =>
                ZIO.succeed(copy(pendingRequests = newPending))
              case None =>
                ZIO.logWarning(s"Response for unknown request: $requestId").as(this)
            }

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
                  _ <- ZIO.logDebug(s"Re-acknowledged already processed request: ${serverRequest.requestId}")
                } yield this

              case ServerRequestResult.OutOfOrder =>
                ZIO
                  .logWarning(
                    s"Dropping out-of-order server request: ${serverRequest.requestId}, expected: ${serverRequestTracker.lastAcknowledgedRequestId.next}"
                  )
                  .as(this)
            }

          case StreamEvent.ServerMsg(RequestError(NotLeaderRequest, leaderId)) =>
            for {
              _ <- ZIO.logInfo(s"Not leader, reconnecting")
              nonce <- Nonce.generate()
              nextIndex = (currentAddressIndex + 1) % addresses.length
              nextAddr = addresses(nextIndex)
              _ <- transport.disconnect().orDie
              _ <- transport.connect(nextAddr).orDie
              _ <- transport.sendMessage(ContinueSession(sessionId, nonce)).orDie
              now <- Clock.instant
            } yield ConnectingExistingSession(
              sessionId = sessionId,
              capabilities = capabilities,
              nonce = nonce,
              addresses = addresses,
              currentAddressIndex = nextIndex,
              createdAt = now,
              serverRequestTracker = serverRequestTracker,
              nextRequestId = nextRequestId,
              pendingRequests = pendingRequests
            )

          case StreamEvent.ServerMsg(RequestError(SessionTerminated, _)) =>
            ZIO.dieMessage(s"Session terminated by server for session $sessionId")

          case StreamEvent.ServerMsg(RequestError(reason, _)) =>
            ZIO.logWarning(s"Request error: $reason").as(this)

          case StreamEvent.ServerMsg(SessionClosed(reason, _)) =>
            for {
              _ <- ZIO.logInfo(s"Session closed: $reason, reconnecting")
              nonce <- Nonce.generate()
              currentAddr = addresses(currentAddressIndex)
              _ <- transport.disconnect().orDie
              _ <- transport.connect(currentAddr).orDie
              _ <- transport.sendMessage(ContinueSession(sessionId, nonce)).orDie
              now <- Clock.instant
            } yield ConnectingExistingSession(
              sessionId = sessionId,
              capabilities = capabilities,
              nonce = nonce,
              addresses = addresses,
              currentAddressIndex = currentAddressIndex,
              createdAt = now,
              serverRequestTracker = serverRequestTracker,
              nextRequestId = nextRequestId,
              pendingRequests = pendingRequests
            )

          case StreamEvent.KeepAliveTick =>
            for {
              now <- Clock.instant
              _ <- transport.sendMessage(KeepAlive(now)).orDie
            } yield this

          case StreamEvent.TimeoutCheck =>
            for {
              now <- Clock.instant
              newPending <- pendingRequests.resendExpired(transport, now, ClientConfig.REQUEST_TIMEOUT)
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

    def complete(requestId: RequestId, result: ByteVector): Option[PendingRequests] =
      requests.get(requestId).map { data =>
        data.promise.succeed(result).ignore
        copy(requests = requests.removed(requestId))
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
        val elapsed = java.time.Duration.between(data.lastSentAt, currentTime)
        if (elapsed.compareTo(java.time.Duration.ofSeconds(timeoutSeconds)) > 0) {
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
    def sendMessage(message: ClientMessage):ZIO[Any, Throwable, Unit]
    def incomingMessages: ZStream[Any, Throwable, ServerMessage]
  }

  private class ZmqClientTransportLive(socket: ZSocket, lastAddressRef: Ref[Option[String]]
  ) extends ZmqClientTransport {
    private val timeoutConnectionClosed = ???

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
          case None => ZIO.unit
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

  private class ZmqClientTransportStub extends ZmqClientTransport {
    override def connect(address: String): Task[Unit] =
      ZIO.logDebug(s"Stub: connecting to $address")

    override def disconnect(): Task[Unit] =
      ZIO.logDebug("Stub: disconnecting")

    override def sendMessage(message: ClientMessage): Task[Unit] =
      ZIO.logDebug(s"Stub: sending message: ${message.getClass.getSimpleName}")

    override def incomingMessages: ZStream[Any, Throwable, ServerMessage] =
      ZStream.empty
  }

  class TimeoutException(message: String) extends RuntimeException(message)

}
