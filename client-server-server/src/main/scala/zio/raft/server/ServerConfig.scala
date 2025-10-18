package zio.raft.server

import zio._

/**
 * Server configuration for ZIO Raft client-server communication.
 * 
 * Contains all configurable parameters for:
 * - Session management timeouts
 * - ZeroMQ transport settings
 * - Performance tuning parameters
 * - Security and validation settings
 */
case class ServerConfig(
  // Session Management
  sessionTimeout: Duration = ServerConfig.DEFAULT_SESSION_TIMEOUT,
  keepAliveInterval: Duration = ServerConfig.DEFAULT_KEEP_ALIVE_INTERVAL,
  cleanupInterval: Duration = ServerConfig.DEFAULT_CLEANUP_INTERVAL,
  
  // ZeroMQ Transport  
  bindAddress: String = ServerConfig.DEFAULT_BIND_ADDRESS,
  port: Int = ServerConfig.DEFAULT_PORT,
  zmqSocketType: String = "SERVER", // ZeroMQ SERVER socket
  maxMessageSize: Int = ServerConfig.DEFAULT_MAX_MESSAGE_SIZE,
  
  // Performance
  maxConcurrentSessions: Int = ServerConfig.DEFAULT_MAX_CONCURRENT_SESSIONS,
  messageBufferSize: Int = ServerConfig.DEFAULT_MESSAGE_BUFFER_SIZE,
  
  // Security & Validation
  enableClientValidation: Boolean = true,
  maxCapabilityCount: Int = ServerConfig.DEFAULT_MAX_CAPABILITY_COUNT,
  maxCapabilityValueLength: Int = ServerConfig.DEFAULT_MAX_CAPABILITY_VALUE_LENGTH,
  
  // Leader-specific settings
  leaderTimeout: Duration = ServerConfig.DEFAULT_LEADER_TIMEOUT,
  leaderTransitionGracePeriod: Duration = ServerConfig.DEFAULT_LEADER_TRANSITION_GRACE_PERIOD
) {
  
  /**
   * Validate configuration parameters.
   */
  def validate(): Either[String, Unit] = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    
    if (sessionTimeout.toSeconds <= 0) {
      errors += "sessionTimeout must be positive"
    }
    
    if (keepAliveInterval.toSeconds <= 0) {
      errors += "keepAliveInterval must be positive" 
    }
    
    if (keepAliveInterval >= sessionTimeout) {
      errors += "keepAliveInterval must be less than sessionTimeout"
    }
    
    if (cleanupInterval.toSeconds <= 0) {
      errors += "cleanupInterval must be positive"
    }
    
    if (port < 1 || port > 65535) {
      errors += s"port must be between 1 and 65535, got $port"
    }
    
    if (maxMessageSize < 1024) {
      errors += s"maxMessageSize must be at least 1024 bytes, got $maxMessageSize"
    }
    
    if (maxConcurrentSessions < 1) {
      errors += s"maxConcurrentSessions must be at least 1, got $maxConcurrentSessions"
    }
    
    if (messageBufferSize < 1) {
      errors += s"messageBufferSize must be at least 1, got $messageBufferSize"
    }
    
    if (maxCapabilityCount < 1) {
      errors += s"maxCapabilityCount must be at least 1, got $maxCapabilityCount"
    }
    
    if (maxCapabilityValueLength < 1) {
      errors += s"maxCapabilityValueLength must be at least 1, got $maxCapabilityValueLength"
    }
    
    if (leaderTimeout.toSeconds <= 0) {
      errors += "leaderTimeout must be positive"
    }
    
    if (leaderTransitionGracePeriod.toSeconds < 0) {
      errors += "leaderTransitionGracePeriod must be non-negative"
    }
    
    if (errors.nonEmpty) {
      Left(s"ServerConfig validation failed: ${errors.mkString(", ")}")
    } else {
      Right(())
    }
  }
  
  /**
   * Get the full bind address for ZeroMQ.
   */
  def fullBindAddress: String = s"tcp://$bindAddress:$port"
  
  /**
   * Calculate session timeout with grace period during leader transitions.
   */
  def sessionTimeoutWithGrace: Duration = sessionTimeout + leaderTransitionGracePeriod
  
  /**
   * Check if cleanup should use more frequent intervals during leader transitions.
   */
  def adaptiveCleanupInterval(isLeaderTransition: Boolean): Duration = {
    if (isLeaderTransition) {
      cleanupInterval.min(Duration.fromSeconds(1)) // More frequent during transitions
    } else {
      cleanupInterval
    }
  }
}

object ServerConfig {
  
  // Default values
  val DEFAULT_SESSION_TIMEOUT: Duration = 90.seconds
  val DEFAULT_KEEP_ALIVE_INTERVAL: Duration = 30.seconds
  val DEFAULT_CLEANUP_INTERVAL: Duration = 1.second
  
  val DEFAULT_BIND_ADDRESS: String = "0.0.0.0"
  val DEFAULT_PORT: Int = 5555
  val DEFAULT_MAX_MESSAGE_SIZE: Int = 1024 * 1024 // 1MB
  
  val DEFAULT_MAX_CONCURRENT_SESSIONS: Int = 1000
  val DEFAULT_MESSAGE_BUFFER_SIZE: Int = 1000
  
  val DEFAULT_MAX_CAPABILITY_COUNT: Int = 20
  val DEFAULT_MAX_CAPABILITY_VALUE_LENGTH: Int = 256
  
  val DEFAULT_LEADER_TIMEOUT: Duration = 5.seconds
  val DEFAULT_LEADER_TRANSITION_GRACE_PERIOD: Duration = 30.seconds
  
  /**
   * Create default configuration.
   */
  def default: ServerConfig = ServerConfig()
  
  /**
   * Create configuration for development/testing.
   */
  def development: ServerConfig = ServerConfig(
    sessionTimeout = 30.seconds,
    keepAliveInterval = 10.seconds,
    cleanupInterval = 500.millis,
    port = 5556,
    maxConcurrentSessions = 100,
    leaderTransitionGracePeriod = 10.seconds
  )
  
  /**
   * Create configuration for production.
   */
  def production: ServerConfig = ServerConfig(
    sessionTimeout = 120.seconds,
    keepAliveInterval = 30.seconds,
    cleanupInterval = 1.second,
    maxConcurrentSessions = 5000,
    messageBufferSize = 10000,
    leaderTransitionGracePeriod = 60.seconds
  )
  
  /**
   * Load configuration from environment variables and system properties.
   */
  def fromEnvironment: UIO[ServerConfig] = 
    for {
      sessionTimeout <- loadDurationFromEnv("RAFT_SESSION_TIMEOUT", DEFAULT_SESSION_TIMEOUT)
      keepAliveInterval <- loadDurationFromEnv("RAFT_KEEP_ALIVE_INTERVAL", DEFAULT_KEEP_ALIVE_INTERVAL)
      bindAddress <- loadStringFromEnv("RAFT_BIND_ADDRESS", DEFAULT_BIND_ADDRESS)
      port <- loadIntFromEnv("RAFT_PORT", DEFAULT_PORT)
      maxSessions <- loadIntFromEnv("RAFT_MAX_SESSIONS", DEFAULT_MAX_CONCURRENT_SESSIONS)
    } yield ServerConfig(
      sessionTimeout = sessionTimeout,
      keepAliveInterval = keepAliveInterval,
      bindAddress = bindAddress,
      port = port,
      maxConcurrentSessions = maxSessions
    )
  
  private def loadDurationFromEnv(envVar: String, default: Duration): UIO[Duration] = 
    ZIO.succeed(
      sys.env.get(envVar)
        .flatMap(s => scala.util.Try(Duration.fromSeconds(s.toLong)).toOption)
        .getOrElse(default)
    )
  
  private def loadStringFromEnv(envVar: String, default: String): UIO[String] = 
    ZIO.succeed(sys.env.getOrElse(envVar, default))
  
  private def loadIntFromEnv(envVar: String, default: Int): UIO[Int] = 
    ZIO.succeed(
      sys.env.get(envVar)
        .flatMap(s => scala.util.Try(s.toInt).toOption)
        .getOrElse(default)
    )
  
  /**
   * Create configuration with validation.
   */
  def validated(config: ServerConfig): IO[String, ServerConfig] = 
    config.validate() match {
      case Right(_) => ZIO.succeed(config)
      case Left(error) => ZIO.fail(error)
    }
}

