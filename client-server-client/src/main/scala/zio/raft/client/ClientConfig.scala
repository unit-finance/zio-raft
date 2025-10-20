package zio.raft.client

import zio.*
import zio.raft.protocol.MemberId

/** Client configuration for ZIO Raft client-server communication.
  */
case class ClientConfig(
  clusterMembers: Map[MemberId, String],
  capabilities: Map[String, String],
  connectionTimeout: Duration = ClientConfig.DEFAULT_CONNECTION_TIMEOUT,
  keepAliveInterval: Duration = ClientConfig.DEFAULT_KEEP_ALIVE_INTERVAL,
  requestTimeout: Duration = ClientConfig.DEFAULT_REQUEST_TIMEOUT
) {

  def validate(): Either[String, Unit] = {
    if (clusterMembers.isEmpty) {
      Left("clusterMembers cannot be empty")
    } else if (capabilities.isEmpty) {
      Left("capabilities cannot be empty")
    } else {
      Right(())
    }
  }
}

object ClientConfig {

  val DEFAULT_CONNECTION_TIMEOUT: Duration = 5.seconds
  val DEFAULT_KEEP_ALIVE_INTERVAL: Duration = 30.seconds
  val DEFAULT_REQUEST_TIMEOUT: Duration = 10.seconds

  def make(members: Map[MemberId, String], capabilities: Map[String, String]): ClientConfig =
    ClientConfig(
      clusterMembers = members,
      capabilities = capabilities
    )

  def validated(config: ClientConfig): IO[String, ClientConfig] =
    config.validate() match {
      case Right(_)    => ZIO.succeed(config)
      case Left(error) => ZIO.fail(error)
    }
}
