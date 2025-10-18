package zio.raft.client

import zio._

/**
 * Client configuration for ZIO Raft client-server communication.
 */
case class ClientConfig(
  clusterAddresses: List[String],
  capabilities: Map[String, String],
  connectionTimeout: Duration = ClientConfig.DEFAULT_CONNECTION_TIMEOUT,
  sessionTimeout: Duration = ClientConfig.DEFAULT_SESSION_TIMEOUT,
  keepAliveInterval: Duration = ClientConfig.DEFAULT_KEEP_ALIVE_INTERVAL
) {
  
  def validate(): Either[String, Unit] = {
    if (clusterAddresses.isEmpty) {
      Left("clusterAddresses cannot be empty")
    } else if (capabilities.isEmpty) {
      Left("capabilities cannot be empty")
    } else if (keepAliveInterval >= sessionTimeout) {
      Left("keepAliveInterval must be less than sessionTimeout")
    } else {
      Right(())
    }
  }
}

object ClientConfig {
  
  val DEFAULT_CONNECTION_TIMEOUT: Duration = 5.seconds
  val DEFAULT_SESSION_TIMEOUT: Duration = 90.seconds
  val DEFAULT_KEEP_ALIVE_INTERVAL: Duration = 30.seconds
  val REQUEST_TIMEOUT: Duration = 10.seconds
  
  def make(addresses: List[String], capabilities: Map[String, String]): ClientConfig = 
    ClientConfig(
      clusterAddresses = addresses,
      capabilities = capabilities
    )
  
  def validated(config: ClientConfig): IO[String, ClientConfig] = 
    config.validate() match {
      case Right(_) => ZIO.succeed(config)
      case Left(error) => ZIO.fail(error)
    }
}
