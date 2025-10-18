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
  // Connection Settings
  clusterAddresses: List[String] = ClientConfig.DEFAULT_CLUSTER_ADDRESSES,
  connectionTimeout: Duration = ClientConfig.DEFAULT_CONNECTION_TIMEOUT,
  maxReconnectAttempts: Int = ClientConfig.DEFAULT_MAX_RECONNECT_ATTEMPTS,
  reconnectDelay: Duration = ClientConfig.DEFAULT_RECONNECT_DELAY,
  
  // Session Management
  capabilities: Map[String, String] = ClientConfig.DEFAULT_CAPABILITIES,
  sessionTimeout: Duration = ClientConfig.DEFAULT_SESSION_TIMEOUT,
  keepAliveInterval: Duration = ClientConfig.DEFAULT_KEEP_ALIVE_INTERVAL,
  
  // Request Handling
  requestTimeout: Duration = ClientConfig.DEFAULT_REQUEST_TIMEOUT,
  maxConcurrentRequests: Int = ClientConfig.DEFAULT_MAX_CONCURRENT_REQUESTS,
  
  // Capability Validation
  maxCapabilityCount: Int = ClientConfig.DEFAULT_MAX_CAPABILITY_COUNT,
  maxCapabilityValueLength: Int = ClientConfig.DEFAULT_MAX_CAPABILITY_VALUE_LENGTH,
  
  // ZeroMQ Transport
  zmqSocketType: String = "CLIENT", // ZeroMQ CLIENT socket
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
    
    if (maxReconnectAttempts < 0) {
      errors += "maxReconnectAttempts must be non-negative"
    }
    
    if (reconnectDelay.toSeconds < 0) {
      errors += "reconnectDelay must be non-negative"
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
    
    if (requestTimeout.toSeconds <= 0) {
      errors += "requestTimeout must be positive"
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
   * Get request timeout with backoff for retries.
   */
  def backoffRequestTimeout(attempt: Int): Duration = {
    val baseTimeout = requestTimeout.toMillis
    val backoffMultiplier = Math.pow(1.5, attempt.min(5)).toLong // Cap at 5 attempts
    Duration.fromMillis(baseTimeout * backoffMultiplier)
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
  val DEFAULT_CLUSTER_ADDRESSES: List[String] = List("tcp://localhost:5555")
  val DEFAULT_CONNECTION_TIMEOUT: Duration = 5.seconds
  val DEFAULT_MAX_RECONNECT_ATTEMPTS: Int = 3
  val DEFAULT_RECONNECT_DELAY: Duration = 1.second
  
  val DEFAULT_CAPABILITIES: Map[String, String] = Map("client-type" -> "default")
  val DEFAULT_SESSION_TIMEOUT: Duration = 90.seconds
  val DEFAULT_KEEP_ALIVE_INTERVAL: Duration = 30.seconds
  
  val DEFAULT_REQUEST_TIMEOUT: Duration = 10.seconds
  val DEFAULT_MAX_CONCURRENT_REQUESTS: Int = 100
  
  val DEFAULT_MAX_CAPABILITY_COUNT: Int = 20
  val DEFAULT_MAX_CAPABILITY_VALUE_LENGTH: Int = 256
  
  val DEFAULT_MAX_MESSAGE_SIZE: Int = 1024 * 1024 // 1MB
  val DEFAULT_MESSAGE_BUFFER_SIZE: Int = 1000
  
  /**
   * Create default configuration.
   */
  def default: ClientConfig = ClientConfig()
  
  /**
   * Create configuration for development/testing.
   */
  def development: ClientConfig = ClientConfig(
    clusterAddresses = List("tcp://localhost:5556"),
    connectionTimeout = 2.seconds,
    sessionTimeout = 30.seconds,
    keepAliveInterval = 10.seconds,
    requestTimeout = 5.seconds,
    maxReconnectAttempts = 1,
    enableRequestTracing = true,
    logLevel = "DEBUG"
  )
  
  /**
   * Create configuration for production.
   */
  def production: ClientConfig = ClientConfig(
    sessionTimeout = 120.seconds,
    keepAliveInterval = 30.seconds,
    requestTimeout = 15.seconds,
    maxConcurrentRequests = 500,
    maxReconnectAttempts = 5,
    reconnectDelay = 2.seconds,
    enableHeartbeatOptimization = true,
    logLevel = "INFO"
  )
  
  /**
   * Create configuration for high-throughput scenarios.
   */
  def highThroughput: ClientConfig = ClientConfig(
    maxConcurrentRequests = 1000,
    messageBufferSize = 10000,
    keepAliveInterval = 15.seconds,
    requestTimeout = 30.seconds,
    enableHeartbeatOptimization = true
  )
  
  /**
   * Create configuration for low-latency scenarios.
   */
  def lowLatency: ClientConfig = ClientConfig(
    connectionTimeout = 1.second,
    keepAliveInterval = 5.seconds,
    requestTimeout = 3.seconds,
    reconnectDelay = 500.millis,
    messageBufferSize = 100
  )
  
  /**
   * Load configuration from environment variables and system properties.
   */
  def fromEnvironment: UIO[ClientConfig] = 
    for {
      addresses <- loadAddressesFromEnv("RAFT_CLUSTER_ADDRESSES", DEFAULT_CLUSTER_ADDRESSES)
      connectionTimeout <- loadDurationFromEnv("RAFT_CONNECTION_TIMEOUT", DEFAULT_CONNECTION_TIMEOUT)
      sessionTimeout <- loadDurationFromEnv("RAFT_SESSION_TIMEOUT", DEFAULT_SESSION_TIMEOUT)
      keepAliveInterval <- loadDurationFromEnv("RAFT_KEEP_ALIVE_INTERVAL", DEFAULT_KEEP_ALIVE_INTERVAL)
      requestTimeout <- loadDurationFromEnv("RAFT_REQUEST_TIMEOUT", DEFAULT_REQUEST_TIMEOUT)
      capabilities <- loadCapabilitiesFromEnv("RAFT_CLIENT_CAPABILITIES", DEFAULT_CAPABILITIES)
    } yield ClientConfig(
      clusterAddresses = addresses,
      connectionTimeout = connectionTimeout,
      sessionTimeout = sessionTimeout,
      keepAliveInterval = keepAliveInterval,
      requestTimeout = requestTimeout,
      capabilities = capabilities
    )
  
  private def loadAddressesFromEnv(envVar: String, default: List[String]): UIO[List[String]] = 
    ZIO.succeed(
      sys.env.get(envVar)
        .map(_.split(",").map(_.trim).toList)
        .getOrElse(default)
    )
  
  private def loadDurationFromEnv(envVar: String, default: Duration): UIO[Duration] = 
    ZIO.succeed(
      sys.env.get(envVar)
        .flatMap(s => scala.util.Try(Duration.fromSeconds(s.toLong)).toOption)
        .getOrElse(default)
    )
  
  private def loadCapabilitiesFromEnv(envVar: String, default: Map[String, String]): UIO[Map[String, String]] = 
    ZIO.succeed(
      sys.env.get(envVar)
        .map { capsString =>
          capsString.split(",").map(_.trim).foldLeft(Map.empty[String, String]) { (acc, pair) =>
            pair.split("=", 2) match {
              case Array(key, value) => acc + (key.trim -> value.trim)
              case _ => acc
            }
          }
        }
        .getOrElse(default)
    )
  
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
  def builder: ClientConfigBuilder = new ClientConfigBuilder(default)
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
    session: Duration = config.sessionTimeout,
    request: Duration = config.requestTimeout
  ): ClientConfigBuilder = {
    config = config.copy(
      connectionTimeout = connection,
      sessionTimeout = session,
      requestTimeout = request
    )
    this
  }
  
  def withKeepAlive(interval: Duration): ClientConfigBuilder = {
    config = config.copy(keepAliveInterval = interval)
    this
  }
  
  def withReconnect(maxAttempts: Int, delay: Duration): ClientConfigBuilder = {
    config = config.copy(
      maxReconnectAttempts = maxAttempts,
      reconnectDelay = delay
    )
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
