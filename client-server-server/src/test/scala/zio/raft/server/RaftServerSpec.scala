package zio.raft.server

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.raft.protocol.*
import zio.raft.protocol.Codecs.*
import zio.raft.server.RaftServer.*
import zio.zmq.*
import scodec.bits.ByteVector

/**
 * Unit tests for RaftServer using real ZeroMQ CLIENT socket.
 * 
 * Tests RaftServer (SERVER socket) by acting as a client (CLIENT socket).
 * This validates actual ZeroMQ integration, message serialization, and all server flows.
 */
object RaftServerSpec extends ZIOSpecDefault {

  override def spec = suite("RaftServer with Real ZMQ")(
    followerStateSuite,
    leaderSessionCreationSuite,
    leaderSessionContinuationSuite,
    leaderKeepAliveSuite,
    leaderClientRequestSuite,
    sessionClosureSuite,
    connectionClosedSuite
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  // ==========================================================================
  // Test Helpers
  // ==========================================================================

  /**
   * Send a client message to the server
   */
  def sendClientMessage(socket: ZSocket, message: ClientMessage): Task[Unit] = {
    for {
      bytes <- ZIO.attempt(clientMessageCodec.encode(message).require.toByteArray)
      _ <- socket.send(bytes)
    } yield ()
  }

  /**
   * Receive a server message from the server
   */
  def receiveServerMessage(socket: ZSocket): Task[ServerMessage] = {
    for {
      chunk <- socket.receive
      bytes = ByteVector(chunk.toArray)
      message <- ZIO.fromEither(serverMessageCodec.decode(bytes.bits).toEither.map(_.value))
        .mapError(err => new RuntimeException(s"Failed to decode: $err"))
    } yield message
  }

  /**
   * Wait for specific message type with timeout
   */
  def waitForMessage[A <: ServerMessage](socket: ZSocket, timeout: Duration = 3.seconds)(
    implicit tt: scala.reflect.TypeTest[ServerMessage, A], ct: scala.reflect.ClassTag[A]): Task[A] = {
    receiveServerMessage(socket).timeout(timeout).flatMap {
      case Some(msg: A) => ZIO.succeed(msg)
      case Some(other) => ZIO.fail(new RuntimeException(s"Expected ${ct.runtimeClass.getSimpleName}, got ${other.getClass.getSimpleName}"))
      case None => ZIO.fail(new RuntimeException(s"Timeout waiting for ${ct.runtimeClass.getSimpleName}"))
    }
  }

  val testPort = 25555
  val serverAddress = s"tcp://127.0.0.1:$testPort"

  // ==========================================================================
  // Follower State Tests
  // ==========================================================================

  def followerStateSuite = suiteAll("Follower State"){
    test("should reject CreateSession when not leader") {
      ZIO.scoped {
        for {
            // Start server as follower
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis) // Let server start
            
            // Connect as client
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            // Send CreateSession
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, CreateSession(Map("test" -> "v1"), nonce))
            
            // Expect SessionRejected with NotLeader
            rejection <- waitForMessage[SessionRejected](client)
          } yield {
            assertTrue(rejection.reason == RejectionReason.NotLeader) &&
            assertTrue(rejection.nonce == nonce)
        }
      }.provide(ZContext.live)
    }

    test("should reject ContinueSession when not leader") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            sessionId <- SessionId.generate()
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, ContinueSession(sessionId, nonce))
            
            rejection <- waitForMessage[SessionRejected](client)
        } yield {
          assertTrue(rejection.reason == RejectionReason.NotLeader)
        }
      }.provide(ZContext.live)
    }

    test("should reject ClientRequest when not leader") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            timestamp <- Clock.instant
            request = ClientRequest(
              RequestId.fromLong(1L),
              ByteVector.fromValidHex("deadbeef"),
              timestamp
            )
            _ <- sendClientMessage(client, request)
            
            error <- waitForMessage[SessionClosed](client)
          } yield {
            assertTrue(error.reason == SessionCloseReason.SessionError)
        }
      }.provide(ZContext.live)
    }
  }

  // ==========================================================================
  // Leader Session Creation Tests
  // ==========================================================================

  def leaderSessionCreationSuite = suiteAll("Leader - Session Creation"){
    test("should create session and send SessionCreated after Raft confirmation") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            // Transition to leader
            _ <- server.stepUp(Map.empty)
            _ <- ZIO.sleep(100.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            // Send CreateSession
            nonce <- Nonce.generate()
            capabilities = Map("worker" -> "v1.0", "priority" -> "high")
            _ <- sendClientMessage(client, CreateSession(capabilities, nonce))
            
            // Get Raft action
            _ <- ZIO.sleep(100.millis)
            action <- server.raftActions.take(1).runCollect.map(_.head)
            
            sessionId = action.asInstanceOf[RaftAction.CreateSession].sessionId
            
            // Simulate Raft confirming the session
            _ <- server.confirmSessionCreation(sessionId)
            _ <- ZIO.sleep(100.millis)
            
            // Expect SessionCreated
            created <- waitForMessage[SessionCreated](client)
          } yield {
            assertTrue(created.sessionId == sessionId) &&
            assertTrue(created.nonce == nonce)
        }
      }.provide(ZContext.live)
    }
  }

  // ==========================================================================
  // Leader Session Continuation Tests
  // ==========================================================================

  def leaderSessionContinuationSuite = suiteAll("Leader - Session Continuation"){
    test("should reconnect existing session") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            // Setup existing session
            sessionId <- SessionId.generate()
            timestamp <- Clock.instant
            metadata = SessionMetadata(Map("worker" -> "v1.0"), timestamp)
            _ <- server.stepUp(Map(sessionId -> metadata))
            _ <- ZIO.sleep(100.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            // Send ContinueSession
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, ContinueSession(sessionId, nonce))
            
            // Expect SessionContinued
            continued <- waitForMessage[SessionContinued](client)
          } yield {
            assertTrue(continued.nonce == nonce)
        }
      }.provide(ZContext.live)
    }

    test("should reject ContinueSession for non-existent session") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            _ <- server.stepUp(Map.empty)
            _ <- ZIO.sleep(100.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            fakeSessionId <- SessionId.generate()
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, ContinueSession(fakeSessionId, nonce))
            
            rejection <- waitForMessage[SessionRejected](client)
          } yield {
            assertTrue(rejection.reason == RejectionReason.SessionNotFound) &&
            assertTrue(rejection.nonce == nonce)
        }
      }.provide(ZContext.live)
    }

    test("should handle reconnection from new socket (different routing ID)") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            sessionId <- SessionId.generate()
            timestamp <- Clock.instant
            metadata = SessionMetadata(Map("worker" -> "v1.0"), timestamp)
            _ <- server.stepUp(Map(sessionId -> metadata))
            _ <- ZIO.sleep(100.millis)
            
            // First client connects
            _ <- ZIO.scoped {
              for {
                client1 <- ZSocket.client
                _ <- client1.connect(serverAddress)
                nonce1 <- Nonce.generate()
                _ <- sendClientMessage(client1, ContinueSession(sessionId, nonce1))
                _ <- waitForMessage[SessionContinued](client1)
                
                // Send ConnectionClosed before scope closes
                _ <- sendClientMessage(client1, ConnectionClosed)
                _ <- ZIO.sleep(100.millis)
              } yield ()
            }
            
            // New client reconnects (different socket = different routing ID)
            client2 <- ZSocket.client
            _ <- client2.connect(serverAddress)
            nonce2 <- Nonce.generate()
            _ <- sendClientMessage(client2, ContinueSession(sessionId, nonce2))
            
            // Should successfully reconnect
            continued <- waitForMessage[SessionContinued](client2)
          } yield {
            assertTrue(continued.nonce == nonce2)
        }
      }.provide(ZContext.live)
    }
  }

  // ==========================================================================
  // Keep-Alive Tests
  // ==========================================================================

  def leaderKeepAliveSuite = suiteAll("Leader - Keep-Alive"){
    test("should respond to keep-alive for connected session") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            sessionId <- SessionId.generate()
            timestamp <- Clock.instant
            metadata = SessionMetadata(Map("test" -> "v1"), timestamp)
            _ <- server.stepUp(Map(sessionId -> metadata))
            _ <- ZIO.sleep(100.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            // Connect session first
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, ContinueSession(sessionId, nonce))
            _ <- waitForMessage[SessionContinued](client)
            
            // Send keep-alive
            keepAliveTime <- Clock.instant
            _ <- sendClientMessage(client, KeepAlive(keepAliveTime))
            
            // Expect KeepAliveResponse
            response <- waitForMessage[KeepAliveResponse](client)
          } yield {
            // Timestamp might lose precision in serialization (nanoseconds → milliseconds)
            val diff = java.time.Duration.between(keepAliveTime, response.timestamp).abs().toMillis
            assertTrue(diff < 10) // Within 10ms is fine
        }
      }.provide(ZContext.live)
    }

    test("should reject keep-alive for unknown session") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            _ <- server.stepUp(Map.empty)
            _ <- ZIO.sleep(100.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            // Send keep-alive without session
            timestamp <- Clock.instant
            _ <- sendClientMessage(client, KeepAlive(timestamp))
            
            // Expect SessionClosed error
            error <- waitForMessage[SessionClosed](client)
          } yield {
            assertTrue(error.reason == SessionCloseReason.SessionError)
        }
      }.provide(ZContext.live)
    }
  }

  // ==========================================================================
  // Client Request Tests
  // ==========================================================================

  def leaderClientRequestSuite = suiteAll("Leader - Client Requests"){
    test("should forward ClientRequest to Raft") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            sessionId <- SessionId.generate()
            timestamp <- Clock.instant
            metadata = SessionMetadata(Map("test" -> "v1"), timestamp)
            _ <- server.stepUp(Map(sessionId -> metadata))
            _ <- ZIO.sleep(100.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            // Connect session
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, ContinueSession(sessionId, nonce))
            _ <- waitForMessage[SessionContinued](client)
            
            // Send client request
            requestId = RequestId.fromLong(42L)
            payload = ByteVector.fromValidHex("cafebabe")
            requestTime <- Clock.instant
            _ <- sendClientMessage(client, ClientRequest(requestId, payload, requestTime))
            
            // Verify RaftAction was queued
            _ <- ZIO.sleep(100.millis)
            action <- server.raftActions.take(1).runCollect.map(_.head)
          } yield {
            assertTrue(action.isInstanceOf[RaftAction.ClientRequest]) &&
            assertTrue(action.asInstanceOf[RaftAction.ClientRequest].sessionId == sessionId) &&
            assertTrue(action.asInstanceOf[RaftAction.ClientRequest].requestId == requestId) &&
            assertTrue(action.asInstanceOf[RaftAction.ClientRequest].payload == payload)
        }
      }.provide(ZContext.live)
    }

    test("should send ClientResponse back to client") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            sessionId <- SessionId.generate()
            timestamp <- Clock.instant
            metadata = SessionMetadata(Map("test" -> "v1"), timestamp)
            _ <- server.stepUp(Map(sessionId -> metadata))
            _ <- ZIO.sleep(100.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, ContinueSession(sessionId, nonce))
            _ <- waitForMessage[SessionContinued](client)
            
            // Server sends response
            requestId = RequestId.fromLong(99L)
            result = ByteVector.fromValidHex("facade00")
            _ <- server.sendClientResponse(sessionId, ClientResponse(requestId, result))
            
            // Client receives response
            response <- waitForMessage[ClientResponse](client)
          } yield {
            assertTrue(response.requestId == requestId) &&
            assertTrue(response.result == result)
        }
      }.provide(ZContext.live)
    }
  }

  // ==========================================================================
  // Session Closure Tests
  // ==========================================================================

  def sessionClosureSuite = suiteAll("Session Closure"){
    test("should remove session on CloseSession and notify Raft") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            sessionId <- SessionId.generate()
            timestamp <- Clock.instant
            metadata = SessionMetadata(Map("test" -> "v1"), timestamp)
            _ <- server.stepUp(Map(sessionId -> metadata))
            _ <- ZIO.sleep(100.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            // Connect session
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, ContinueSession(sessionId, nonce))
            _ <- waitForMessage[SessionContinued](client)
            
            // Close session
            _ <- sendClientMessage(client, CloseSession(CloseReason.ClientShutdown))
            _ <- ZIO.sleep(100.millis)
            
            // Check RaftAction.ExpireSession was queued
            action <- server.raftActions.take(1).runCollect.map(_.head)
          } yield {
            assertTrue(action.isInstanceOf[RaftAction.ExpireSession]) &&
            assertTrue(action.asInstanceOf[RaftAction.ExpireSession].sessionId == sessionId)
        }
      }.provide(ZContext.live)
    }

    test("session cannot be reconnected after CloseSession") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            sessionId <- SessionId.generate()
            timestamp <- Clock.instant
            metadata = SessionMetadata(Map("test" -> "v1"), timestamp)
            _ <- server.stepUp(Map(sessionId -> metadata))
            _ <- ZIO.sleep(100.millis)
            
            // First client connects and closes
            _ <- ZIO.scoped {
              for {
                client1 <- ZSocket.client
                _ <- client1.connect(serverAddress)
                nonce1 <- Nonce.generate()
                _ <- sendClientMessage(client1, ContinueSession(sessionId, nonce1))
                _ <- waitForMessage[SessionContinued](client1)
                _ <- sendClientMessage(client1, CloseSession(CloseReason.ClientShutdown))
                _ <- ZIO.sleep(100.millis)
              } yield ()
            }
            
            // Drain expire action
            _ <- server.raftActions.take(1).runCollect
            
            // Try to reconnect - should fail
            client2 <- ZSocket.client
            _ <- client2.connect(serverAddress)
            nonce2 <- Nonce.generate()
            _ <- sendClientMessage(client2, ContinueSession(sessionId, nonce2))
            
            rejection <- waitForMessage[SessionRejected](client2)
          } yield {
            assertTrue(rejection.reason == RejectionReason.SessionNotFound)
        }
      }.provide(ZContext.live)
    }
  }

  // ==========================================================================
  // ConnectionClosed Tests
  // ==========================================================================

  def connectionClosedSuite = suiteAll("ConnectionClosed Handling"){
    test("should preserve session and allow reconnection after ConnectionClosed") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            sessionId <- SessionId.generate()
            timestamp <- Clock.instant
            metadata = SessionMetadata(Map("worker" -> "v1.0"), timestamp)
            _ <- server.stepUp(Map(sessionId -> metadata))
            _ <- ZIO.sleep(100.millis)
            
            // First client connects and sends ConnectionClosed
            _ <- ZIO.scoped {
              for {
                client1 <- ZSocket.client
                _ <- client1.connect(serverAddress)
                nonce1 <- Nonce.generate()
                _ <- sendClientMessage(client1, ContinueSession(sessionId, nonce1))
                _ <- waitForMessage[SessionContinued](client1)
                _ <- sendClientMessage(client1, ConnectionClosed)
                _ <- ZIO.sleep(100.millis)
              } yield ()
            }
            
            // New client reconnects (different routing ID due to new socket)
            client2 <- ZSocket.client
            _ <- client2.connect(serverAddress)
            nonce2 <- Nonce.generate()
            _ <- sendClientMessage(client2, ContinueSession(sessionId, nonce2))
            
            // Should successfully reconnect
            continued <- waitForMessage[SessionContinued](client2)
          } yield {
            assertTrue(continued.nonce == nonce2)
        }
      }.provide(ZContext.live)
    }

    test("ConnectionClosed should NOT send RaftAction.ExpireSession") {
      ZIO.scoped {
        for {
            server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
            _ <- ZIO.sleep(300.millis)
            
            sessionId <- SessionId.generate()
            timestamp <- Clock.instant
            metadata = SessionMetadata(Map("worker" -> "v1.0"), timestamp)
            _ <- server.stepUp(Map(sessionId -> metadata))
            _ <- ZIO.sleep(100.millis)
            
            client <- ZSocket.client
            _ <- client.connect(serverAddress)
            
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, ContinueSession(sessionId, nonce))
            _ <- waitForMessage[SessionContinued](client)
            
            // Send ConnectionClosed
            _ <- sendClientMessage(client, ConnectionClosed)
            _ <- ZIO.sleep(200.millis)
            
            // Try to collect Raft actions with timeout - should be empty
            actions <- server.raftActions.take(1).runCollect.timeout(500.millis)
          } yield {
            assertTrue(actions.isEmpty) // No ExpireSession action
        }
      }.provide(ZContext.live)
    }
  }
}
