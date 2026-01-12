package zio.zmq

import zio.{Chunk, Scope, ZIO, ZLayer}
import zio.test.*
import zio.test.TestAspect.sequential

object ZSocketSpec extends ZIOSpecDefault {
  // Bind the given server on an available port by trial
  private def bindOnOpenPort(server: ZSocket): ZIO[Any, ZMQException, String] = {
    def tryPorts(start: Int, attempts: Int): ZIO[Any, ZMQException, String] =
      if (attempts <= 0) ZIO.fail(new ZMQException(-1))
      else {
        val port = start + scala.util.Random.nextInt(1000)
        val endpoint = s"tcp://127.0.0.1:$port"
        server.bind(endpoint).as(endpoint).catchSome {
          case _: ZMQException => tryPorts(start, attempts - 1)
        }
      }
    tryPorts(42000, 200)
  }

  def spec: Spec[Any, Any] = suite("ZSocket (shared)")(
    test("bind/connect and client->server send/receive") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        client <- ZSocket.client
        _ <- client.connect(endpoint)
        bytes = Array[Byte](1, 2, 3, 4)
        _ <- client.send(bytes)
        res <- server.receive
      } yield assertTrue(res.data == bytes)
    },
    test("disconnectPeer prevents sending to that routing id") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        client <- ZSocket.client
        _ <- client.connect(endpoint)
        // establish routing id by first message
        _ <- client.send(Array[Byte](0x01))
        msg <- server.receive
        // disconnect that specific peer
        ok <- server.disconnectPeer(msg.routingId)
        // attempt to send to the disconnected routing id must fail with EHOSTUNREACH
        sendRes <- server.send(msg.routingId, Array[Byte](0x02)).either
      } yield assertTrue(ok) &&
        assertTrue(sendRes match {
          case Left(ex) => ex.getErrorCode() == ZError.EHOSTUNREACH
          case Right(_) => false
        })
    },
    test("server->client reply roundtrip") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        client <- ZSocket.client
        _ <- client.connect(endpoint)
        bytes1 = Array[Byte](10, 20, 30)
        _ <- client.send(bytes1)
        msg <- server.receive
        _ <- server.send(msg.routingId, bytes1)
        received2 <- client.receive
      } yield assertTrue(msg.data == bytes1) &&
        assertTrue(received2.data == bytes1)
    },
    test("stream (no-arg) yields one item") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        client <- ZSocket.client
        _ <- client.connect(endpoint)
        bytes = Array[Byte](7, 8, 9)
        fib <- server.stream.take(1).runCollect.fork
        _ <- client.send(bytes)
        out <- fib.join
      } yield assertTrue(out.nonEmpty) &&
        assertTrue(out.head.data == bytes)
    },
    test("sendImmediately returns true when deliverable") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        client <- ZSocket.client
        _ <- client.connect(endpoint)
        ok <- client.sendImmediately(Array[Byte](42))
        _ <- server.receive // drain
      } yield assertTrue(ok)
    },
    test("disconnect completes without error") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        _ <- server.disconnect(endpoint)
      } yield assertTrue(true)
    },
    test("connectPeer either returns routingId or fails with ZMQException") {
      val prog =
        for {
          server <- ZSocket.server
          endpoint <- bindOnOpenPort(server)
          res <- server.connectPeer(endpoint)
        } yield res

      prog.either.map {
        case Right(rid)            => assertTrue(rid.value >= 0)
        case Left(_: ZMQException) => assertTrue(true)
      }
    },
    test("options is accessible") {
      for {
        client <- ZSocket.client
      } yield assertTrue(client.options != null)
    }
  ).provideShared(ZContext.live, Scope.default) @@ sequential
}
