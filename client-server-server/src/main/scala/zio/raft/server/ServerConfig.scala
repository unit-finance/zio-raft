package zio.raft.server

import zio.*

/** Simple server configuration.
  */
case class ServerConfig(
  bindAddress: String,
  sessionTimeout: Duration,
  cleanupInterval: Duration
)

object ServerConfig {

  val DEFAULT_SESSION_TIMEOUT: Duration = 90.seconds
  val DEFAULT_CLEANUP_INTERVAL: Duration = 1.second

  def make(bindAddress: String): ServerConfig =
    ServerConfig(
      bindAddress = bindAddress,
      sessionTimeout = DEFAULT_SESSION_TIMEOUT,
      cleanupInterval = DEFAULT_CLEANUP_INTERVAL
    )

  def validated(config: ServerConfig): Either[String, ServerConfig] = {
    if (config.bindAddress.isEmpty) {
      Left("bindAddress cannot be empty")
    } else if (config.sessionTimeout.toSeconds <= 0) {
      Left("sessionTimeout must be positive")
    } else if (config.cleanupInterval.toSeconds <= 0) {
      Left("cleanupInterval must be positive")
    } else {
      Right(config)
    }
  }
}
