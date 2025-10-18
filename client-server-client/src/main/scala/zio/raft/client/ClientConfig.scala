package zio.raft.client

import zio._

/**
 * Client configuration for ZIO Raft client-server communication.
 * 
 * Contains all configurable parameters for:
 * - Connection and session management
 * - Request timeout and retry behavior
 * - Keep-alive and heartbeat settings
 * - Capability validation
 * - Performance tuning
 */
case class ClientConfig(
  // Connection Settings - must be provided
  clusterAddresses: List[String],
  capabilities: Map[String, String],
  
  // Connection and Session Management
  connectionTimeout: Duration = ClientConfig.DEFAULT_CONNECTION_TIMEOUT,
  sessionTimeout: Duration = ClientConfig.DEFAULT_SESSION_TIMEOUT,
  keepAliveInterval: Duration = ClientConfig.DEFAULT_KEEP_ALIVE_INTERVAL,
  
  // Request Handling
  maxConcurrentRequests: Int = ClientConfig.DEFAULT_MAX_CONCURRENT_REQUESTS,
  
  // Capability Validation
  maxCapabilityCount: Int = ClientConfig.DEFAULT_MAX_CAPABILITY_COUNT,
  maxCapabilityValueLength: Int = ClientConfig.DEFAULT_MAX_CAPABILITY_VALUE_LENGTH,
  
  // ZeroMQ Transport
  maxMessageSize: Int = ClientConfig.DEFAULT_MAX_MESSAGE_SIZE,
  
  // Performance
  messageBufferSize: Int = ClientConfig.DEFAULT_MESSAGE_BUFFER_SIZE,
  enableHeartbeatOptimization: Boolean = true,
  
  // Debugging & Monitoring
  enableRequestTracing: Boolean = false,
  logLevel: String = "INFO"
) {
  
  /**
   * Validate configuration parameters.
   */
  def validate(): Either[String, Unit] = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    
    if (clusterAddresses.isEmpty) {
      errors += "clusterAddresses cannot be empty"
    }
    
    clusterAddresses.foreach { address =>
      if (!isValidAddress(address)) {
        errors += s"Invalid cluster address: $address"
      }
    }
    
    if (connectionTimeout.toSeconds <= 0) {
      errors += "connectionTimeout must be positive"
    }
    
    if (sessionTimeout.toSeconds <= 0) {
      errors += "sessionTimeout must be positive"
    }
    
    if (keepAliveInterval.toSeconds <= 0) {
      errors += "keepAliveInterval must be positive"
    }
    
    if (keepAliveInterval >= sessionTimeout) {
      errors += "keepAliveInterval must be less than sessionTimeout"
    }
    
    if (maxConcurrentRequests < 1) {
      errors += s"maxConcurrentRequests must be at least 1, got $maxConcurrentRequests"
    }
    
    if (capabilities.size > maxCapabilityCount) {
      errors += s"Too many capabilities: ${capabilities.size} > $maxCapabilityCount"
    }
    
    capabilities.foreach { case (key, value) =>
      if (key.isEmpty) {
        errors += "Capability key cannot be empty"
      }
      if (value.length > maxCapabilityValueLength) {
        errors += s"Capability value too long: ${value.length} > $maxCapabilityValueLength"
      }
    }
    
    if (maxMessageSize < 1024) {
      errors += s"maxMessageSize must be at least 1024 bytes, got $maxMessageSize"
    }
    
    if (messageBufferSize < 1) {
      errors += s"messageBufferSize must be at least 1, got $messageBufferSize"
    }
    
    if (errors.nonEmpty) {
      Left(s"ClientConfig validation failed: ${errors.mkString(", ")}")
    } else {
      Right(())
    }
  }
  
  /**
   * Get keep-alive interval adjusted for network conditions.
   */
  def adaptiveKeepAliveInterval(networkLatencyMs: Long): Duration = {
    val baseInterval = keepAliveInterval.toMillis
    val adjusted = Math.max(baseInterval, networkLatencyMs * 3) // 3x latency minimum
    Duration.fromMillis(adjusted)
  }
  
  /**
   * Check if all required capabilities are present.
   */
  def hasRequiredCapabilities(required: Set[String]): Boolean = {
    required.subsetOf(capabilities.keySet)
  }
  
  /**
   * Get capability value with default.
   */
  def getCapability(key: String, default: String = ""): String = {
    capabilities.getOrElse(key, default)
  }
  
  private def isValidAddress(address: String): Boolean = {
    // Basic validation for tcp://host:port format
    val tcpPattern = """^tcp://[\w\.-]+:\d+$""".r
    tcpPattern.matches(address)
  }
}

object ClientConfig {
  
  // Default values
  val DEFAULT_CONNECTION_TIMEOUT: Duration = 5.seconds
  val DEFAULT_SESSION_TIMEOUT: Duration = 90.seconds
  val DEFAULT_KEEP_ALIVE_INTERVAL: Duration = 30.seconds
  
  val DEFAULT_MAX_CONCURRENT_REQUESTS: Int = 100
  
  val DEFAULT_MAX_CAPABILITY_COUNT: Int = 20
  val DEFAULT_MAX_CAPABILITY_VALUE_LENGTH: Int = 256
  
  val DEFAULT_MAX_MESSAGE_SIZE: Int = 1024 * 1024 // 1MB
  val DEFAULT_MESSAGE_BUFFER_SIZE: Int = 1000
  
  // Hard-coded constants
  val REQUEST_TIMEOUT: Duration = 10.seconds
  val ZMQ_SOCKET_TYPE: String = "CLIENT"
  
  /**
   * Create configuration with required parameters.
   */
  def make(addresses: List[String], capabilities: Map[String, String]): ClientConfig = 
    ClientConfig(
      clusterAddresses = addresses,
      capabilities = capabilities
    )
  
  /**
   * Create configuration for development/testing.
   */
  def development(addresses: List[String], capabilities: Map[String, String]): ClientConfig = 
    ClientConfig(
      clusterAddresses = addresses,
      capabilities = capabilities,
      connectionTimeout = 2.seconds,
      sessionTimeout = 30.seconds,
      keepAliveInterval = 10.seconds,
      enableRequestTracing = true,
      logLevel = "DEBUG"
    )
  
  /**
   * Create configuration for production.
   */
  def production(addresses: List[String], capabilities: Map[String, String]): ClientConfig = 
    ClientConfig(
      clusterAddresses = addresses,
      capabilities = capabilities,
      sessionTimeout = 120.seconds,
      keepAliveInterval = 30.seconds,
      maxConcurrentRequests = 500,
      enableHeartbeatOptimization = true,
      logLevel = "INFO"
    )
  
  /**
   * Create configuration for high-throughput scenarios.
   */
  def highThroughput(addresses: List[String], capabilities: Map[String, String]): ClientConfig = 
    ClientConfig(
      clusterAddresses = addresses,
      capabilities = capabilities,
      maxConcurrentRequests = 1000,
      messageBufferSize = 10000,
      keepAliveInterval = 15.seconds,
      enableHeartbeatOptimization = true
    )
  
  /**
   * Create configuration for low-latency scenarios.
   */
  def lowLatency(addresses: List[String], capabilities: Map[String, String]): ClientConfig = 
    ClientConfig(
      clusterAddresses = addresses,
      capabilities = capabilities,
      connectionTimeout = 1.second,
      keepAliveInterval = 5.seconds,
      messageBufferSize = 100
    )
  
  /**
   * Load configuration from environment variables and system properties.
   * Requires RAFT_CLUSTER_ADDRESSES and RAFT_CLIENT_CAPABILITIES environment variables.
   */
  def fromEnvironment: IO[String, ClientConfig] = 
    for {
      addresses <- loadAddressesFromEnv("RAFT_CLUSTER_ADDRESSES")
      capabilities <- loadCapabilitiesFromEnv("RAFT_CLIENT_CAPABILITIES")
      connectionTimeout <- loadDurationFromEnv("RAFT_CONNECTION_TIMEOUT", DEFAULT_CONNECTION_TIMEOUT)
      sessionTimeout <- loadDurationFromEnv("RAFT_SESSION_TIMEOUT", DEFAULT_SESSION_TIMEOUT)
      keepAliveInterval <- loadDurationFromEnv("RAFT_KEEP_ALIVE_INTERVAL", DEFAULT_KEEP_ALIVE_INTERVAL)
    } yield ClientConfig(
      clusterAddresses = addresses,
      capabilities = capabilities,
      connectionTimeout = connectionTimeout,
      sessionTimeout = sessionTimeout,
      keepAliveInterval = keepAliveInterval
    )
  
  private def loadAddressesFromEnv(envVar: String): IO[String, List[String]] = 
    ZIO.succeed(sys.env.get(envVar))
      .flatMap {
        case Some(value) => ZIO.succeed(value.split(",").map(_.trim).toList)
        case None => ZIO.fail(s"Required environment variable $envVar not found")
      }
  
  private def loadDurationFromEnv(envVar: String, default: Duration): UIO[Duration] = 
    ZIO.succeed(
      sys.env.get(envVar)
        .flatMap(s => scala.util.Try(Duration.fromSeconds(s.toLong)).toOption)
        .getOrElse(default)
    )
  
  private def loadCapabilitiesFromEnv(envVar: String): IO[String, Map[String, String]] = 
    ZIO.succeed(sys.env.get(envVar))
      .flatMap {
        case Some(capsString) =>
          ZIO.succeed(
            capsString.split(",").map(_.trim).foldLeft(Map.empty[String, String]) { (acc, pair) =>
              pair.split("=", 2) match {
                case Array(key, value) => acc + (key.trim -> value.trim)
                case _ => acc
              }
            }
          )
        case None => ZIO.fail(s"Required environment variable $envVar not found")
      }
  
  /**
   * Create configuration with validation.
   */
  def validated(config: ClientConfig): IO[String, ClientConfig] = 
    config.validate() match {
      case Right(_) => ZIO.succeed(config)
      case Left(error) => ZIO.fail(error)
    }
  
  /**
   * Builder pattern for creating configurations.
   */
  def builder(addresses: List[String], capabilities: Map[String, String]): ClientConfigBuilder = 
    new ClientConfigBuilder(make(addresses, capabilities))
}

/**
 * Builder for ClientConfig.
 */
class ClientConfigBuilder(private var config: ClientConfig) {
  
  def withClusterAddresses(addresses: List[String]): ClientConfigBuilder = {
    config = config.copy(clusterAddresses = addresses)
    this
  }
  
  def withClusterAddress(address: String): ClientConfigBuilder = {
    config = config.copy(clusterAddresses = List(address))
    this
  }
  
  def withCapabilities(capabilities: Map[String, String]): ClientConfigBuilder = {
    config = config.copy(capabilities = capabilities)
    this
  }
  
  def withCapability(key: String, value: String): ClientConfigBuilder = {
    config = config.copy(capabilities = config.capabilities + (key -> value))
    this
  }
  
  def withTimeouts(
    connection: Duration = config.connectionTimeout,
    session: Duration = config.sessionTimeout
  ): ClientConfigBuilder = {
    config = config.copy(
      connectionTimeout = connection,
      sessionTimeout = session
    )
    this
  }
  
  def withKeepAlive(interval: Duration): ClientConfigBuilder = {
    config = config.copy(keepAliveInterval = interval)
    this
  }
  
  def enableTracing(): ClientConfigBuilder = {
    config = config.copy(enableRequestTracing = true)
    this
  }
  
  def withLogLevel(level: String): ClientConfigBuilder = {
    config = config.copy(logLevel = level)
    this
  }
  
  def build(): Task[ClientConfig] = 
    ClientConfig.validated(config).mapError(new IllegalArgumentException(_))
}
