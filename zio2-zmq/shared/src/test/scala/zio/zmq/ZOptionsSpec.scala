package zio.zmq

import zio.*
import zio.test.*
import zio.test.TestAspect.*

object ZOptionsSpec extends ZIOSpecDefault {
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

  override def spec: Spec[Any, Any] = suite("ZOptions (shared)")(
    test("lastEndpoint returns the bound endpoint") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        last <- server.options.lastEndpoint
      } yield assertTrue(last == endpoint)
    },
    test("setLinger(0) succeeds") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        _ <- server.options.setLinger(0)
      } yield assertTrue(true)
    },
    test("setHighWatermark succeeds") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        _ <- server.options.setHighWatermark(receive = 1, send = 1)
      } yield assertTrue(true)
    },
    test("setImmediate(true) on client succeeds") {
      for {
        client <- ZSocket.client
        _ <- client.options.setImmediate(true)
      } yield assertTrue(true)
    },
    test("setTcpKeepAlive(true) succeeds") {
      for {
        server <- ZSocket.server
        endpoint <- bindOnOpenPort(server)
        _ <- server.options.setTcpKeepAlive(true)
      } yield assertTrue(true)
    },
    test("setHeartbeat succeeds") {
      for {
        client <- ZSocket.client
        _ <- client.options.setHeartbeat(zio.Duration.fromMillis(100), zio.Duration.fromMillis(100), zio.Duration.fromMillis(100))
      } yield assertTrue(true)
    },
    test("setHelloMessage / setDisconnectMessage / setHiccupMessage succeed") {
      for {
        client <- ZSocket.client
        _ <- client.options.setHelloMessage(Array[Byte](0x01, 0x02))
        _ <- client.options.setDisconnectMessage(Array[Byte](0x03))
        _ <- client.options.setHiccupMessage(Array[Byte](0x04))
      } yield assertTrue(true)
    }
  ).provideShared(ZContext.live, Scope.default) @@ sequential
}

