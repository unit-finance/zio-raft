package zio.raft.server

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.raft.protocol.*
import zio.raft.protocol.Codecs.*
import zio.raft.server.RaftServer.*
import zio.zmq.*
import scodec.bits.ByteVector

object QueryServerSpec extends ZIOSpec[TestEnvironment & ZContext]:

  override def bootstrap: ZLayer[Any, Any, TestEnvironment & ZContext] =
    testEnvironment ++ ZContext.live

  // Helpers
  def sendClientMessage(socket: ZSocket, message: ClientMessage): Task[Unit] =
    for
      bytes <- ZIO.attempt(clientMessageCodec.encode(message).require.toByteArray)
      _ <- socket.send(bytes)
    yield ()

  def receiveServerMessage(socket: ZSocket): Task[ServerMessage] =
    for
      chunk <- socket.receive
      bytes = ByteVector(chunk.toArray)
      message <- ZIO
        .fromEither(serverMessageCodec.decode(bytes.bits).toEither.map(_.value))
        .mapError(err => new RuntimeException(s"Failed to decode: $err"))
    yield message

  def waitForMessage[A <: ServerMessage](socket: ZSocket, timeout: Duration = 3.seconds)(implicit
    tt: scala.reflect.TypeTest[ServerMessage, A],
    ct: scala.reflect.ClassTag[A]
  ): Task[A] =
    receiveServerMessage(socket).timeout(timeout).flatMap {
      case Some(msg: A) => ZIO.succeed(msg)
      case Some(other)  => ZIO.fail(new RuntimeException(s"Expected ${ct.runtimeClass.getSimpleName}, got ${other.getClass.getSimpleName}"))
      case None         => ZIO.fail(new RuntimeException(s"Timeout waiting for ${ct.runtimeClass.getSimpleName}"))
    }

  private val testPort = 25556
  private val serverAddress = s"tcp://127.0.0.1:$testPort"

  override def spec = suiteAll("Server Query Handling") {

    test("Leader forwards Query to Raft and send QueryResponse back") {
      for
        server <- RaftServer.make(s"tcp://0.0.0.0:$testPort")
        _ <- server.stepUp(Map.empty)

        client <- ZSocket.client
        _ <- client.connect(serverAddress)

        // Create session first
        nonce <- Nonce.generate()
        _ <- sendClientMessage(client, CreateSession(Map("kv" -> "v1"), nonce))
        action <- server.raftActions.take(1).runCollect.map(_.head)
        sessionId = action.asInstanceOf[RaftAction.CreateSession].sessionId
        _ <- server.confirmSessionCreation(sessionId)
        _ <- waitForMessage[SessionCreated](client)

        // Send Query
        corr = CorrelationId.fromString("corr-xyz")
        payload = ByteVector(0x0, 0x1, 0x2)
        now <- Clock.instant
        _ <- sendClientMessage(client, Query(corr, payload, now))

        // Verify RaftAction.Query queued
        qAction <- server.raftActions.take(1).runCollect.map(_.head)
        verified = qAction.isInstanceOf[RaftAction.Query]
        _ <- ZIO.fail(new RuntimeException("Expected RaftAction.Query")).unless(verified).ignore
        q = qAction.asInstanceOf[RaftAction.Query]

        // Send QueryResponse back
        result = ByteVector(0xA, 0xB)
        _ <- server.sendQueryResponse(sessionId, QueryResponse(corr, result))
        qr <- waitForMessage[QueryResponse](client)
      yield assertTrue(q.sessionId == sessionId) &&
        assertTrue(q.correlationId == corr) &&
        assertTrue(q.payload == payload) &&
        assertTrue(qr.correlationId == corr) &&
        assertTrue(qr.result == result)
    }
  } @@ TestAspect.sequential @@ TestAspect.withLiveClock
end QueryServerSpec


