package zio.raft.client

import zio.*
import zio.test.*
import zio.raft.protocol.*
import zio.raft.protocol.Codecs.*
import zio.zmq.*
import scodec.bits.ByteVector

/** Unit tests for RaftClient using real ZeroMQ SERVER socket.
  *
  * Tests RaftClient (CLIENT socket) by acting as a server (SERVER socket). This validates actual ZeroMQ integration,
  * message serialization, and all client flows.
  */
object RaftClientSpec extends ZIOSpec[TestEnvironment & ZContext] {
  override def bootstrap: ZLayer[Any, Any, TestEnvironment & ZContext] =
    testEnvironment ++ ZContext.live

  def findOpenPort: Int = {
    val tmpSocket = new java.net.ServerSocket()
    try {
      tmpSocket.setReuseAddress(true)
      tmpSocket.bind(new java.net.InetSocketAddress(null.asInstanceOf[java.net.InetAddress], 0), 1)
      tmpSocket.getLocalPort
    } finally {
      if (tmpSocket != null) {
        tmpSocket.close()
      }
    }
  }

  def sendServerMessage(socket: ZSocket, routingId: RoutingId, message: ServerMessage): Task[Unit] = {
    for {
      bytes <- ZIO.attempt(serverMessageCodec.encode(message).require.toByteArray)
      _ <- socket.send(routingId, bytes)
    } yield ()
  }

  /** Receive next message, expecting specific type. Throws if wrong type. Does NOT skip keep-alives.
    */
  def expectMessage[A <: ClientMessage](
    socket: ZSocket
  )(implicit ct: scala.reflect.ClassTag[A]): Task[(RoutingId, A)] = {
    socket.receive.flatMap { case msg =>
      ZIO
        .fromEither(
          clientMessageCodec.decode(scodec.bits.BitVector(msg.data)).toEither.map(_.value)
        )
        .mapError(err => new RuntimeException(s"Decode error: $err"))
        .flatMap {
          case a: A => ZIO.succeed((msg.routingId, a))
          case other =>
            ZIO.fail(
              new RuntimeException(s"Expected ${ct.runtimeClass.getSimpleName}, got ${other.getClass.getSimpleName}")
            )
        }
    }
  }

  /** Wait for message of specific type, automatically responding to and skipping keep-alives.
    */
  def waitForMessage[A <: ClientMessage](
    socket: ZSocket
  )(implicit ct: scala.reflect.ClassTag[A]): Task[(RoutingId, A)] =
    for {
      msg <- socket.receive
      decoded <- ZIO
        .fromEither(clientMessageCodec.decode(scodec.bits.BitVector(msg.data)).toEither.map(_.value))
        .mapError(err => new RuntimeException(s"Decode error: $err"))

      result <- decoded match {
        case a: A => ZIO.succeed((msg.routingId, a))
        case ka: KeepAlive =>
          sendServerMessage(socket, msg.routingId, KeepAliveResponse(ka.timestamp)) *> waitForMessage[A](socket)
        case other =>
          waitForMessage[A](socket)
      }
    } yield result

  /** Do a request-response round-trip to verify connection is working. Returns true if successful.
    */
  def verifyConnection(client: RaftClient, mockServer: ZSocket): Task[Boolean] = {
    val payload = ByteVector.fromValidHex("aabbccdd")
    val expectedResult = ByteVector.fromValidHex("11223344")

    for {
      // Submit command from client
      responseFiber <- client.submitCommand(payload).fork

      // Receive request on server
      (routingId, clientReq) <- waitForMessage[ClientRequest](mockServer)

      // Send response
      _ <- sendServerMessage(mockServer, routingId, ClientResponse(clientReq.requestId, expectedResult))

      // Verify client receives response
      response <- responseFiber.join
    } yield response == expectedResult
  }

  override def spec: Spec[ZContext & TestEnvironment & Scope, Any] = suiteAll("RaftClient with Real ZMQ") {

    // ==========================================================================
    // Session Creation Tests
    // ==========================================================================

    suiteAll("Session Creation") {
      test("should send CreateSession when connect() is called") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test-worker" -> "v1.0")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (_, createMsg) <- expectMessage[CreateSession](mockServer)
        } yield assertTrue(createMsg.capabilities == Map("test-worker" -> "v1.0"))
      }

      test("should transition to Connected after receiving SessionCreated") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Verify connected by doing request-response round-trip
          connected <- verifyConnection(client, mockServer)
        } yield assertTrue(connected)
      }

      test("should retry on SessionRejected(NotLeader)") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg1) <- expectMessage[CreateSession](mockServer)

          _ <- sendServerMessage(
            mockServer,
            routingId,
            SessionRejected(
              RejectionReason.NotLeader,
              createMsg1.nonce,
              Some(MemberId.fromString("node1"))
            )
          )

          (_, createMsg2) <- expectMessage[CreateSession](mockServer)
        } yield assertTrue(createMsg2.capabilities == Map("test" -> "v1"))

      }
    }

    // ==================================================================
    // Query Submission Tests
    // ==================================================================

    suiteAll("Query Submission") {
      test("should send Query and complete promise when QueryResponse received") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Issue a query from the client
          payload = ByteVector.fromValidHex("c0ffee")
          queryFiber <- client.query(payload).fork

          // Wait for Query message
          (_, queryMsg) <- waitForMessage[Query](mockServer)

          // Respond with matching correlationId
          result = ByteVector.fromValidHex("beaded")
          _ <- sendServerMessage(mockServer, routingId, QueryResponse(queryMsg.correlationId, result))

          response <- queryFiber.join
        } yield assertTrue(response == result)
      }
    }

    // ==========================================================================
    // Request Submission Tests
    // ==========================================================================

    suiteAll("Request Submission") {
      test("should queue request submitted during Connecting and send after Connected") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped

          // Connect (moves to Connecting state)
          _ <- client.connect()

          // Submit command WHILE in Connecting state (after connect(), before SessionCreated)
          payload = ByteVector.fromValidHex("aabbccdd")
          responseFiber <- client.submitCommand(payload).fork

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Should now receive the queued request
          (_, clientReq) <- waitForMessage[ClientRequest](mockServer)

          // Respond
          result = ByteVector.fromValidHex("11223344")
          _ <- sendServerMessage(mockServer, routingId, ClientResponse(clientReq.requestId, result))

          response <- responseFiber.join
        } yield assertTrue(clientReq.payload == payload) &&
          assertTrue(response == result)
      }

      test("should send ClientRequest and receive ClientResponse") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          payload = ByteVector.fromValidHex("deadbeef")
          responseFiber <- client.submitCommand(payload).fork

          // Wait for ClientRequest (auto-responding to any keep-alives)
          (_, clientReq) <- waitForMessage[ClientRequest](mockServer)

          result = ByteVector.fromValidHex("facade00")
          _ <- sendServerMessage(mockServer, routingId, ClientResponse(clientReq.requestId, result))

          response <- responseFiber.join
        } yield assertTrue(response == result) &&
          assertTrue(clientReq.payload == payload) &&
          assertTrue(clientReq.requestId == RequestId.fromLong(1L))
      }

      test("includes lowestPendingRequestId = min(pending) on ClientRequest") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Submit two commands quickly so the second is sent while first is pending
          _ <- client.submitCommand(ByteVector.fromValidHex("01")).fork
          _ <- client.submitCommand(ByteVector.fromValidHex("02")).fork

          // Receive two ClientRequest messages
          (_, req1) <- waitForMessage[ClientRequest](mockServer)
          (_, req2) <- waitForMessage[ClientRequest](mockServer)

        } yield assertTrue(req2.lowestPendingRequestId == req1.requestId)
      }

      test("should queue multiple requests during Connecting and send all after Connected") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped

          // Connect (moves to Connecting state)
          _ <- client.connect()

          // Submit 3 commands WHILE in Connecting state (after connect(), before SessionCreated)
          req1Fiber <- client.submitCommand(ByteVector.fromValidHex("aa")).fork
          req2Fiber <- client.submitCommand(ByteVector.fromValidHex("bb")).fork
          req3Fiber <- client.submitCommand(ByteVector.fromValidHex("cc")).fork

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Should receive all 3 queued requests
          (_, clientReq1) <- waitForMessage[ClientRequest](mockServer)
          (_, clientReq2) <- waitForMessage[ClientRequest](mockServer)
          (_, clientReq3) <- waitForMessage[ClientRequest](mockServer)

          // Respond to all 3
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ClientResponse(clientReq1.requestId, ByteVector.fromValidHex("01"))
          )
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ClientResponse(clientReq2.requestId, ByteVector.fromValidHex("02"))
          )
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ClientResponse(clientReq3.requestId, ByteVector.fromValidHex("03"))
          )

          resp1 <- req1Fiber.join
          resp2 <- req2Fiber.join
          resp3 <- req3Fiber.join
        } yield assertTrue(resp1.nonEmpty && resp2.nonEmpty && resp3.nonEmpty)
      }

      test("should retry request after timeout when server doesn't respond") {
        val port = findOpenPort
        val customConfig = ClientConfig
          .make(
            Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            Map("test" -> "v1")
          )
          .copy(requestTimeout = 1.second)

        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(customConfig)
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Submit command
          payload = ByteVector.fromValidHex("deadbeef")
          responseFiber <- client.submitCommand(payload).fork

          // Server receives first attempt but doesn't respond
          (_, clientReq1) <- waitForMessage[ClientRequest](mockServer)

          // Wait for request timeout (now 1s in this test)
          // Client should retry the request after timeout
          retryAttempt <- waitForMessage[ClientRequest](mockServer).timeout(3.seconds)

          // Verify we got the retry
          (_, clientReq2) = retryAttempt.get

          // Now respond to the retry
          result = ByteVector.fromValidHex("facade00")
          _ <- sendServerMessage(mockServer, routingId, ClientResponse(clientReq2.requestId, result))

          response <- responseFiber.join
        } yield assertTrue(retryAttempt.isDefined) && // Retry was sent
          assertTrue(clientReq1.requestId == clientReq2.requestId) && // Same request ID (idempotent retry)
          assertTrue(response == result)
      }

      test("should resend pending request after reconnection following ConnectionClosed") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          // 1. Establish session
          (routingId1, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId1, SessionCreated(sessionId, createMsg.nonce))

          // 2. Send one command
          payload = ByteVector.fromValidHex("deadbeef00")
          responseFiber <- client.submitCommand(payload).fork

          // Server receives request
          (_, clientReq1) <- waitForMessage[ClientRequest](mockServer)

          // 3. Don't respond - 4. Send ConnectionClosed instead
          _ <- sendServerMessage(
            mockServer,
            routingId1,
            SessionClosed(SessionCloseReason.ConnectionClosed, None)
          )

          // 5. Client should reconnect with ContinueSession
          (routingId2, continueMsg) <- expectMessage[ContinueSession](mockServer)

          _ <- sendServerMessage(mockServer, routingId2, SessionContinued(continueMsg.nonce))

          // 6. Client should resend the pending request
          (_, clientReq2) <- waitForMessage[ClientRequest](mockServer)

          // Now respond
          result = ByteVector.fromValidHex("facade00")
          _ <- sendServerMessage(mockServer, routingId2, ClientResponse(clientReq2.requestId, result))

          response <- responseFiber.join
        } yield assertTrue(continueMsg.sessionId == sessionId) &&
          assertTrue(clientReq1.requestId == clientReq2.requestId) && // Same request ID
          assertTrue(clientReq1.payload == clientReq2.payload) && // Same payload
          assertTrue(response == result)
      }

      test("should handle multiple concurrent requests") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          req1Fiber <- client.submitCommand(ByteVector.fromValidHex("cafe0001")).fork
          req2Fiber <- client.submitCommand(ByteVector.fromValidHex("babe0002")).fork

          // Wait for both requests (auto-responding to keep-alives)
          (_, req1) <- waitForMessage[ClientRequest](mockServer)
          (_, req2) <- waitForMessage[ClientRequest](mockServer)

          _ <- sendServerMessage(
            mockServer,
            routingId,
            ClientResponse(req1.requestId, ByteVector.fromValidHex("01"))
          )
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ClientResponse(req2.requestId, ByteVector.fromValidHex("02"))
          )

          resp1 <- req1Fiber.join
          resp2 <- req2Fiber.join
        } yield assertTrue(resp1.nonEmpty && resp2.nonEmpty)
      }
    }

    // ==========================================================================
    // RequestError Handling Tests
    // ==========================================================================

    suiteAll("RequestError Handling") {
      test("dies when RequestError is for a pending request") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          runFiber <- client.run().sandbox.forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)
          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Submit command; capture the requestId then send RequestError
          _ <- client.submitCommand(ByteVector.fromValidHex("aa")).fork
          (_, req) <- waitForMessage[ClientRequest](mockServer)
          _ <- sendServerMessage(mockServer, routingId, RequestError(req.requestId, RequestErrorReason.ResponseEvicted))

          exit <- runFiber.join.exit
        } yield assertTrue(exit.is(_.failure).isDie)
      }

      test("ignores RequestError when request is not pending") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          runFiber <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)
          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Send RequestError for non-pending id
          _ <- sendServerMessage(
            mockServer,
            routingId,
            RequestError(RequestId.fromLong(999L), RequestErrorReason.ResponseEvicted)
          )

          // Give the client a bit of time; it should not die
          _ <- ZIO.sleep(200.millis)
          _ <- runFiber.interrupt
        } yield assertCompletes
      }
    }
    // ==========================================================================
    // Keep-Alive Tests
    // ==========================================================================

    suiteAll("Keep-Alive Protocol") {
      test("should send periodic keep-alive messages") {
        val port = findOpenPort
        val clientConfig = ClientConfig(
          clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
          capabilities = Map("test" -> "v1"),
          keepAliveInterval = 1.second // Make keep-alive interval short for fast test
        )
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(clientConfig)
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          (_, keepAlive) <- expectMessage[KeepAlive](mockServer)
          _ <- sendServerMessage(mockServer, routingId, KeepAliveResponse(keepAlive.timestamp))
        } yield assertCompletes
      }
    }

    // ==========================================================================
    // Server Request Tests
    // ==========================================================================

    suiteAll("Server-Initiated Requests") {
      test("should process consecutive request IDs (1, 2, 3...)") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("worker" -> "v1.0")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          result1 <- expectMessage[CreateSession](mockServer)
          (routingId, createMsg) = result1

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Start collecting server requests
          serverReqFiber <- client.serverRequests.take(3).runCollect.fork

          // Send 3 consecutive ServerRequests (IDs 1, 2, 3)
          timestamp <- Clock.instant
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ServerRequest(RequestId.fromLong(1L), ByteVector.fromValidHex("aa"), timestamp)
          )
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ServerRequest(RequestId.fromLong(2L), ByteVector.fromValidHex("bb"), timestamp)
          )
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ServerRequest(RequestId.fromLong(3L), ByteVector.fromValidHex("cc"), timestamp)
          )

          // Receive 3 acks
          (_, ack1) <- waitForMessage[ServerRequestAck](mockServer)
          (_, ack2) <- waitForMessage[ServerRequestAck](mockServer)
          (_, ack3) <- waitForMessage[ServerRequestAck](mockServer)

          // Verify all 3 delivered to stream
          serverReqs <- serverReqFiber.join
        } yield assertTrue(serverReqs.size == 3) &&
          assertTrue(ack1.requestId == RequestId.fromLong(1L)) &&
          assertTrue(ack2.requestId == RequestId.fromLong(2L)) &&
          assertTrue(ack3.requestId == RequestId.fromLong(3L))
      }

      test("should re-acknowledge duplicate/old requests") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("worker" -> "v1.0")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          result1 <- expectMessage[CreateSession](mockServer)
          (routingId, createMsg) = result1

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Start collecting - should only get 1 request
          serverReqFiber <- client.serverRequests.take(1).runCollect.fork

          // Send request ID 1
          timestamp <- Clock.instant
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ServerRequest(RequestId.fromLong(1L), ByteVector.fromValidHex("aabbcc"), timestamp)
          )

          // Receive first ack
          (_, ack1) <- waitForMessage[ServerRequestAck](mockServer)

          // Send request ID 1 again (duplicate)
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ServerRequest(RequestId.fromLong(1L), ByteVector.fromValidHex("aabbcc"), timestamp)
          )

          // Should receive ack again
          (_, ack2) <- waitForMessage[ServerRequestAck](mockServer)

          // Verify only 1 request delivered to stream (duplicate skipped)
          serverReqs <- serverReqFiber.join
        } yield assertTrue(serverReqs.size == 1) &&
          assertTrue(ack1.requestId == RequestId.fromLong(1L)) &&
          assertTrue(ack2.requestId == RequestId.fromLong(1L)) // Re-acknowledged

      }

      test("should drop out-of-order requests (non-consecutive IDs)") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("worker" -> "v1.0")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          result1 <- expectMessage[CreateSession](mockServer)
          (routingId, createMsg) = result1

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Start collecting - should only get request 1
          serverReqFiber <- client.serverRequests.take(1).runCollect.fork

          // Send request ID 1 (OK)
          timestamp <- Clock.instant
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ServerRequest(RequestId.fromLong(1L), ByteVector.fromValidHex("111111"), timestamp)
          )

          // Receive ack for request 1
          (_, ack1) <- waitForMessage[ServerRequestAck](mockServer)

          // Send request ID 3 (skipping 2 - out of order!)
          _ <- sendServerMessage(
            mockServer,
            routingId,
            ServerRequest(RequestId.fromLong(3L), ByteVector.fromValidHex("333333"), timestamp)
          )

          // Should NOT receive ack for request 3 (dropped)
          // Verify only request 1 was delivered to stream
          serverReqs <- serverReqFiber.join
        } yield assertTrue(serverReqs.size == 1) &&
          assertTrue(serverReqs.head.requestId == RequestId.fromLong(1L)) &&
          assertTrue(ack1.requestId == RequestId.fromLong(1L))

      }

      test("should receive ServerRequest and send ServerRequestAck") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("worker" -> "v1.0")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Must send request ID 1 (first request)
          requestId = RequestId.fromLong(1L)
          payload = ByteVector.fromValidHex("feedface")
          timestamp <- Clock.instant
          _ <- sendServerMessage(mockServer, routingId, ServerRequest(requestId, payload, timestamp))

          (_, ack) <- waitForMessage[ServerRequestAck](mockServer)
        } yield assertTrue(ack.requestId == RequestId.fromLong(1L))

      }

      test("should deliver ServerRequest to serverRequests stream") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("worker" -> "v1.0")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          serverReqFiber <- client.serverRequests.take(1).runCollect.fork

          // Must send request ID 1 (first request)
          requestId = RequestId.fromLong(1L)
          payload = ByteVector.fromValidHex("cafebabe")
          timestamp <- Clock.instant
          _ <- sendServerMessage(mockServer, routingId, ServerRequest(requestId, payload, timestamp))

          serverReqs <- serverReqFiber.join
        } yield assertTrue(serverReqs.size == 1) &&
          assertTrue(serverReqs.head.requestId == requestId) &&
          assertTrue(serverReqs.head.payload == payload)
      }
    }

    // ==========================================================================
    // Reconnection Tests
    // ==========================================================================

    suiteAll("Reconnection Scenarios") {

      test("should fail when SessionRejected(SessionExpired) after SessionClosed") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          runFiber <- client.run().sandbox.forkScoped
          _ <- client.connect()

          (routingId, createMsg1) <- expectMessage[CreateSession](mockServer)

          sessionId1 <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId1, createMsg1.nonce))

          // let's force the client to disconnect
          _ <- sendServerMessage(
            mockServer,
            routingId,
            SessionClosed(
              SessionCloseReason.ConnectionClosed,
              None
            )
          )
          _ <- mockServer.disconnectPeer(routingId)

          // Client tries to continue
          (routingId, continueMsg) <- expectMessage[ContinueSession](mockServer)

          _ <- sendServerMessage(
            mockServer,
            routingId,
            SessionRejected(
              RejectionReason.SessionExpired,
              continueMsg.nonce,
              None
            )
          )

          result <- runFiber.join.exit
        } yield assertTrue(result.is(_.failure).isDie)
      }

      test("should reconnect after TCP connection drop (ConnectionClosed)") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          // Simulate TCP drop - server sends ConnectionClosed and disconnects
          _ <- sendServerMessage(
            mockServer,
            routingId,
            SessionClosed(SessionCloseReason.ConnectionClosed, None)
          )
          _ <- mockServer.disconnectPeer(routingId)

          // Client should reconnect with ContinueSession
          (newRoutingId, continueMsg) <- expectMessage[ContinueSession](mockServer)

          // Server accepts continuation
          _ <- sendServerMessage(mockServer, newRoutingId, SessionContinued(continueMsg.nonce))

          // Verify reconnection works with request-response
          reconnected <- verifyConnection(client, mockServer)
        } yield assertTrue(continueMsg.sessionId == sessionId) &&
          assertTrue(newRoutingId != routingId) && // New connection = new routing ID
          assertTrue(reconnected)

      }

      test("should reconnect after server restart (session still exists)") {
        val port = findOpenPort
        for {
          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          sessionId <- SessionId.generate()

          // Simulate server restart - disconnect peer abruptly by stopping the server
          _ <- ZIO.scoped {
            for {
              mockServer <- ZSocket.server
              _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

              (routingId1, createMsg) <- expectMessage[CreateSession](mockServer)

              _ <- sendServerMessage(mockServer, routingId1, SessionCreated(sessionId, createMsg.nonce))

              _ <- verifyConnection(client, mockServer)

              _ <- mockServer.disconnect(s"tcp://0.0.0.0:$port")
            } yield ()
          }

          _ <- ZIO.sleep(100.millis)
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          // Client detects disconnect and reconnects with ContinueSession
          (routingId2, continueMsg) <- expectMessage[ContinueSession](mockServer)

          // Server still has session (Raft persistence), accepts continuation
          _ <- sendServerMessage(mockServer, routingId2, SessionContinued(continueMsg.nonce))

          // Verify reconnection works
          reconnected <- verifyConnection(client, mockServer).debug("Verified reconnection")
        } yield assertTrue(continueMsg.sessionId == sessionId) &&
          assertTrue(reconnected)
      }

      test("should redirect to new leader when current leader loses leadership (multi-server)") {
        val node1Port = findOpenPort
        val node2Port = findOpenPort
        for {
          // Start node1 (current leader)
          node1 <- ZSocket.server
          _ <- node1.options.setLinger(0)
          _ <- node1.bind(s"tcp://0.0.0.0:$node1Port")

          // Client knows about both nodes
          client <- RaftClient.make(
            clusterMembers = Map(
              MemberId.fromString("node1") -> s"tcp://127.0.0.1:$node1Port",
              MemberId.fromString("node2") -> s"tcp://127.0.0.1:$node2Port"
            ),
            capabilities = Map("worker" -> "v1.0")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          // Client connects to node1 first
          (routingId1, createMsg) <- expectMessage[CreateSession](node1)

          // Node1 accepts and creates session
          sessionId <- SessionId.generate()
          _ <- sendServerMessage(node1, routingId1, SessionCreated(sessionId, createMsg.nonce))

          // Verify connection works with node1
          connected1 <- verifyConnection(client, node1)

          // Start node2 (new leader)
          node2 <- ZSocket.server
          _ <- node2.options.setLinger(0)
          _ <- node2.bind(s"tcp://0.0.0.0:$node2Port")

          // Node1 loses leadership - sends NotLeaderAnymore
          _ <- sendServerMessage(
            node1,
            routingId1,
            SessionClosed(
              SessionCloseReason.NotLeaderAnymore,
              Some(MemberId.fromString("node2"))
            )
          )

          // Client should reconnect to node2 with ContinueSession
          (routingId2, continueMsg) <- expectMessage[ContinueSession](node2)

          // Node2 accepts continuation
          _ <- sendServerMessage(node2, routingId2, SessionContinued(continueMsg.nonce))

          // Verify connection works with node2
          connected2 <- verifyConnection(client, node2).debug("Verified connection with node2")
        } yield assertTrue(connected1) &&
          assertTrue(continueMsg.sessionId == sessionId) &&
          assertTrue(connected2)
      }

      test("should send CloseSession on disconnect()") {
        val port = findOpenPort
        for {
          mockServer <- ZSocket.server
          _ <- mockServer.bind(s"tcp://0.0.0.0:$port")

          client <- RaftClient.make(
            clusterMembers = Map(MemberId.fromString("node1") -> s"tcp://127.0.0.1:$port"),
            capabilities = Map("test" -> "v1")
          )
          _ <- client.run().forkScoped
          _ <- client.connect()

          (routingId, createMsg) <- expectMessage[CreateSession](mockServer)

          sessionId <- SessionId.generate()
          _ <- sendServerMessage(mockServer, routingId, SessionCreated(sessionId, createMsg.nonce))

          _ <- verifyConnection(client, mockServer)

          _ <- client.disconnect()

          (_, closeMsg) <- waitForMessage[CloseSession](mockServer)
        } yield assertTrue(closeMsg.reason == CloseReason.ClientShutdown)
      }
    }
  } @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
