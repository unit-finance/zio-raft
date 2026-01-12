package zio.kvstore

import zio.*
import zio.test.*
import zio.raft.protocol.*
import zio.raft.protocol.Codecs.*
import zio.kvstore.protocol.*
import zio.kvstore.protocol.KVClientResponse.given
import scodec.bits.ByteVector
import scodec.Codec
import zio.zmq.*
import java.nio.file.Files
import zio.lmdb.Environment as LmdbEnv

object KVStoreQueryGetSpec extends ZIOSpec[TestEnvironment & ZContext]:

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
      msg <- socket.receive
      bytes = ByteVector(msg.data)
      message <- ZIO
        .fromEither(serverMessageCodec.decode(bytes.bits).toEither.map(_.value))
        .mapError(err => new RuntimeException(s"Failed to decode: $err"))
    yield message

  def waitForMessage[A <: ServerMessage](socket: ZSocket, timeout: Duration = 5.seconds)(implicit
    tt: scala.reflect.TypeTest[ServerMessage, A],
    ct: scala.reflect.ClassTag[A]
  ): Task[A] =
    receiveServerMessage(socket).timeout(timeout).flatMap {
      case Some(msg: A) => ZIO.succeed(msg)
      case Some(other) => ZIO.fail(
          new RuntimeException(s"Expected ${ct.runtimeClass.getSimpleName}, got ${other.getClass.getSimpleName}")
        )
      case None => ZIO.fail(new RuntimeException(s"Timeout waiting for ${ct.runtimeClass.getSimpleName}"))
    }

  private val serverPort = 26555
  private val raftRpcPort = 26556
  private val serverAddress = s"tcp://127.0.0.1:$serverPort"
  private val nodeAddress = s"tcp://127.0.0.1:$raftRpcPort"

  override def spec = suiteAll("kvstore GET via Query") {

    test("set then get returns current value; GET is served via Query") {
      ZIO.scoped {
        for
          logDir <- ZIO.attempt(Files.createTempDirectory("kv-log-").toFile).map(_.getAbsolutePath)
          snapDir <- ZIO.attempt(Files.createTempDirectory("kv-snap-").toFile).map(_.getAbsolutePath)
          lmdbDir <- ZIO.attempt(Files.createTempFile("kv-lmdb-", ".lmdb").toFile).map(_.getAbsolutePath)

          result <- (for
            // Start node (single-node bootstrap)
            node <- zio.kvstore.node.Node.make(
              serverAddress = s"tcp://0.0.0.0:$serverPort",
              nodeAddress = nodeAddress,
              logDirectory = logDir,
              snapshotDirectory = snapDir,
              memberId = zio.raft.MemberId("node-1"),
              peers = Map.empty
            )
            _ <- node.run.forkScoped
            _ <- ZIO.sleep(600.millis) // let services bind

            // Client connects
            client <- ZSocket.client
            _ <- client.connect(serverAddress)

            // Create session
            nonce <- Nonce.generate()
            _ <- sendClientMessage(client, CreateSession(Map("kv" -> "v1"), nonce))
            created <- waitForMessage[SessionCreated](client)
            sessionId = created.sessionId

            // Send ClientRequest(Set)
            rid = RequestId.fromLong(1L)
            payloadSet <- ZIO.fromEither(
              implicitly[Codec[KVClientRequest]].encode(KVClientRequest.Set("foo", "bar")).toEither
            ).map(_.bytes)
            _ <- sendClientMessage(client, ClientRequest(rid, rid, payloadSet, java.time.Instant.now()))
            _ <- waitForMessage[ClientResponse](client)

            // Send Query(Get)
            corr = CorrelationId.fromString("q-1")
            payloadGet <- ZIO.fromEither(implicitly[Codec[KVQuery]].encode(KVQuery.Get("foo")).toEither).map(_.bytes)
            _ <- sendClientMessage(client, Query(corr, payloadGet, java.time.Instant.now()))
            qres <- waitForMessage[QueryResponse](client)

            // Decode Query result Option[String]
            getResult <-
              ZIO.fromEither(implicitly[Codec[Option[String]]].decode(qres.result.bits).toEither.map(_.value))
          yield assertTrue(getResult.contains("bar"))).provideSomeLayer(
            LmdbEnv.builder.withFlags(
              org.lmdbjava.EnvFlags.MDB_NOSUBDIR,
              org.lmdbjava.EnvFlags.MDB_NOSYNC,
              org.lmdbjava.EnvFlags.MDB_NOLOCK
            ).withMaxDbs(3).layer(new java.io.File(lmdbDir))
          )
        yield result
      }
    }
  } @@ TestAspect.sequential @@ TestAspect.withLiveClock
end KVStoreQueryGetSpec
