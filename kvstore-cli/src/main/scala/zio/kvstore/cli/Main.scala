package zio.kvstore.cli

import zio.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.raft.protocol.MemberId

object Main extends ZIOCliDefault:

  private def parseEndpoints(endpoints: String): Map[MemberId, String] =
    endpoints
      .split(",")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { kv =>
        val Array(id, addr) = kv.split("=", 2)
        MemberId(id) -> addr
      }
      .toMap

  // Model for CLI
  sealed trait Action
  final case class DoSet(endpoints: String, key: String, value: String) extends Action
  final case class DoGet(endpoints: String, key: String) extends Action
  final case class DoWatch(endpoints: String, key: String) extends Action

  // Shared options/args
  private val endpointsOpt: Options[String] =
    Options.text("endpoints").alias("e").withDefault("node-1=tcp://127.0.0.1:7001")
  private val keyArg: Args[String] = Args.text("key")
  private val valueArg: Args[String] = Args.text("value")

  // Commands
  private val setCmd: Command[Action] =
    Command("set", endpointsOpt, keyArg ++ valueArg)
      .withHelp(HelpDoc.p("Set key to value"))
      .map { case (endpoints, (key, value)) => DoSet(endpoints, key, value) }

  private val getCmd: Command[Action] =
    Command("get", endpointsOpt, keyArg)
      .withHelp(HelpDoc.p("Get key"))
      .map { case (endpoints, key) => DoGet(endpoints, key) }

  private val watchCmd: Command[Action] =
    Command("watch", endpointsOpt, keyArg)
      .withHelp(HelpDoc.p("Watch key for updates"))
      .map { case (endpoints, key) => DoWatch(endpoints, key) }

  private val root: Command[Action] = Command("kvstore").subcommands(setCmd, getCmd, watchCmd)

  val cliApp: CliApp[Any, Any, Unit] =
    CliApp.make[Any, Any, Action, Unit](
      name = "kvstore",
      version = "0.1.0",
      summary = text("KVStore CLI"),
      command = root
    ) {
      case DoSet(endpoints, key, value) =>
        val members = parseEndpoints(endpoints)
        (for
          client <- KVClient.make(members)
          _ <- client.run().forkScoped
          _ <- client.connect()
          _ <- client.set(key, value)
          _ <- Console.printLine("OK")
        yield ()).provideSomeLayer(zio.zmq.ZContext.live ++ Scope.default)

      case DoGet(endpoints, key) =>
        val members = parseEndpoints(endpoints)
        (for
          client <- KVClient.make(members)
          _ <- client.run().forkScoped
          _ <- client.connect()
          result <- client.get(key)
          _ <- Console.printLine(s"${key} = ${result.getOrElse("<none>")}")
        yield ()).provideSomeLayer(zio.zmq.ZContext.live ++ Scope.default)

      case DoWatch(endpoints, key) =>
        val members = parseEndpoints(endpoints)
        (for
          client <- KVClient.make(members)
          _ <- client.run().forkScoped
          _ <- client.connect()
          _ <- client.watch(key)
          _ <- Console.printLine(s"watching ${key} - press Ctrl+C to stop")
          _ <- client.notifications.map(n => s"${n.key} -> ${n.value}").foreach(Console.printLine(_))
        yield ()).provideSomeLayer(zio.zmq.ZContext.live ++ Scope.default)
    }
end Main
