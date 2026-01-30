package zio.kvstore

import zio.*
import zio.raft.{MemberId as CoreMemberId}
import zio.kvstore.node.Node
import zio.lmdb.Environment as LmdbEnv
import zio.config.magnolia.*
import zio.logging.{consoleLogger, ConsoleLoggerConfig, LogFilter}
import java.io.File
import java.net.URI

final case class ServerAppConfig(
  serverPort: Int = 7001,
  memberId: String = "node-1",
  members: String = "node-1=tcp://127.0.0.1:7002",
  logDir: String = "data/log",
  snapshotDir: String = "data/snapshots",
  lmdbDirectory: String = "data/lmdb"
)

object ServerAppConfig:
  val config: Config[ServerAppConfig] =
    deriveConfig[ServerAppConfig]

object KVStoreServerApp extends ZIOAppDefault:

  // Force exit after 2 seconds on shutdown
  java.lang.Runtime.getRuntime.addShutdownHook(new Thread:
    override def run(): Unit =
      Thread.sleep(2000)
      // If we're still here after 2 seconds, force termination
      java.lang.Runtime.getRuntime.halt(0)
  )

  private def parseEndpoints(endpoints: String): Map[CoreMemberId, String] =
    endpoints
      .split(",")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { kv =>
        val Array(id, addr) = kv.split("=", 2)
        CoreMemberId(id) -> addr
      }
      .toMap

  private def rewriteHostToLocalhost(address: String): String =
    val uri = URI.create(address)
    new URI(
      uri.getScheme,
      uri.getUserInfo,
      "localhost",
      uri.getPort,
      uri.getPath,
      uri.getQuery,
      uri.getFragment
    ).toString

  val logConfig = ConsoleLoggerConfig.default.copy(filter = LogFilter.LogLevelByNameConfig(zio.LogLevel.Debug))

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    ZLayer.fromZIO(ZIO.service[ZIOAppArgs].map(args => Runtime.setConfigProvider(ConfigProvider.fromAppArgs(args)))) ++
      zio.Runtime.removeDefaultLoggers >>> consoleLogger(logConfig)

  override def run =
    for
      config <- ZIO.config(ServerAppConfig.config)

      _ <- ZIO.logInfo(s"Config: $config")

      // Ensure the lmdb directory exists
      _ <- ZIO.attemptBlockingIO {
        val dir = new File(config.lmdbDirectory)
        if !dir.exists() then dir.mkdirs()
      }

      // Ensure the log directory exists
      _ <- ZIO.attemptBlockingIO {
        val dir = new File(config.logDir)
        if !dir.exists() then dir.mkdirs()
      }

      // Ensure the snapshot directory exists
      _ <- ZIO.attemptBlockingIO {
        val dir = new File(config.snapshotDir)
        if !dir.exists() then dir.mkdirs()
      }

      program =
        for
          endpoints <- ZIO.succeed(parseEndpoints(config.members))
          selfId = CoreMemberId(config.memberId)
          nodeAddr <- ZIO.fromOption(endpoints.get(selfId)).orElseFail(new IllegalArgumentException(
            "self member must be included in members"
          ))
          localNodeAddr = rewriteHostToLocalhost(nodeAddr)
          peersMap = endpoints - selfId

          node <- Node.make(
            serverAddress = s"tcp://localhost:${config.serverPort}",
            nodeAddress = localNodeAddr,
            logDirectory = config.logDir,
            snapshotDirectory = config.snapshotDir,
            memberId = selfId,
            peers = peersMap
          ).debug("Node.make")
          _ <- node.run
        yield ()
      _ <- program.provideSomeLayer(
        (LmdbEnv.builder.withMaxDbs(3).layer(new File(config.lmdbDirectory)) ++ zio.zmq.ZContext.live)
      )
    yield ()
end KVStoreServerApp
