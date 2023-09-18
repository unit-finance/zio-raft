import zio.raft.Command
import zio.raft.zmq.ZmqRpc
import zio.Chunk
import scala.util.Try
import zio.raft.Raft
import zio.raft.MemberId
import zio.raft.Stable
import zio.raft.LogStore
import zio.raft.StateMachine
import scodec.Codec
import scodec.codecs.int32
import zio.ExitCode
import zio.URIO
import zio.zmq.ZContext
import zhttp.http.Method.GET
import zhttp.service.server.ServerChannelFactory
import zhttp.http.*
import zhttp.service.Server
import zio.ZIO
import zio.logging.*

case class Add(a: Int, b: Int) extends Command:
  type Response = Int

class CalculatorStateMachine extends StateMachine[Add]:
  override def apply(command: Add): (command.Response, StateMachine[Add]) =
    command match
      case Add(a, b) => (a + b, this)

class HttpServer(raft: Raft[Add]):

  val app = Http.collectZIO[Request]:
    case GET -> !! => ZIO.succeed(Response.text("Hello World!"))
    case GET -> !! / "add" / a / b => raft.sendCommand(Add(a.toInt, b.toInt)).map(r => Response.text(r.toString))
    .catchAll(err => ZIO.succeed(Response.text(err.toString).setStatus(Status.BadRequest) ))

  def run = Server.start(8090, app)

object CalculatorApp extends zio.App:
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = 
    val memberId = MemberId(args(0))
    val peers = Map(
      "peer1" -> "tcp://localhost:5555",
      "peer2" -> "tcp://localhost:5556",
      "peer3" -> "tcp://localhost:5557"
    ).map((k, v) => MemberId(k) -> v)

    val commandCodec = (int32 :: int32).as[Add]

    val raft =
      for
        rpc <- ZmqRpc.makeManaged[Add](
          peers(memberId),
          peers.removed(memberId),
          commandCodec
        )
        stable <- Stable.makeInMemoryManaged
        logStore <- LogStore.makeInMemoryManaged[Add]

        raft <- Raft.makeManaged(
          memberId,
          peers.removed(memberId).keys.toArray,
          stable,
          logStore,
          rpc,
          new CalculatorStateMachine
        )
      yield raft

    val logging = Logging.console(logLevel = LogLevel.Debug, format = LogFormat.ColoredLogFormat()) >>> zio.logging.Logging.withRootLoggerName("raft")

    raft.use(raft => {
      for 
        _ <- new HttpServer(raft).run.fork.when(memberId == MemberId("peer1"))
        _ <- raft.run
      yield ()
    } ).exitCode.provideCustomLayer(ZContext.live.orDie ++ logging)
