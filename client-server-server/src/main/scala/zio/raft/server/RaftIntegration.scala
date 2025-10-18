package zio.raft.server

import zio._
import zio.stream._
import zio.raft.protocol._

/**
 * Raft state machine integration for server actions.
 * 
 * Handles:
 * - Forwarding server actions to Raft state machine
 * - Processing Raft responses and client notifications
 * - Session state synchronization with Raft
 * - Leadership change notifications
 * - State machine command execution
 */
trait RaftIntegration {
  
  /**
   * Forward a server action to the Raft state machine.
   */
  def forwardAction(action: ServerAction): Task[Unit]
  
  /**
   * Get stream of actions to forward to Raft.
   */
  def actionStream: ZStream[Any, Throwable, ServerAction]
  
  /**
   * Handle Raft response for a client request.
   */
  def handleRaftResponse(
    sessionId: SessionId,
    requestId: RequestId, 
    response: Either[String, scodec.bits.ByteVector]
  ): UIO[Unit]
  
  /**
   * Handle leadership change notification.
   */
  def handleLeadershipChange(
    isLeader: Boolean,
    currentTerm: Long,
    existingSessions: Map[SessionId, SessionMetadata]
  ): UIO[Unit]
  
  /**
   * Get current Raft state information.
   */
  def getRaftState(): UIO[RaftStateInfo]
  
  /**
   * Check if this server is currently the Raft leader.
   */
  def isLeader(): UIO[Boolean]
  
  /**
   * Get current term number.
   */
  def getCurrentTerm(): UIO[Long]
  
  /**
   * Get cluster membership information.
   */
  def getClusterMembers(): UIO[List[MemberId]]
  
  /**
   * Submit a command to the Raft state machine.
   */
  def submitCommand(command: RaftCommand): Task[RaftCommandResult]
  
  /**
   * Submit a read query to the Raft state machine.
   */
  def submitQuery(query: RaftQuery): Task[RaftQueryResult]
  
  /**
   * Initialize session state from Raft on startup.
   */
  def initializeSessionState(): Task[Map[SessionId, SessionMetadata]]
}

object RaftIntegration {
  
  /**
   * Create a RaftIntegration with the given dependencies.
   */
  def make(
    sessionManager: SessionManager,
    clientHandler: ClientHandler,
    actionStream: ActionStream,
    config: ServerConfig
  ): UIO[RaftIntegration] = 
    for {
      isLeaderRef <- Ref.make(false)
      currentTermRef <- Ref.make(0L)
      clusterMembersRef <- Ref.make(List.empty[MemberId])
    } yield new RaftIntegrationImpl(
      sessionManager,
      clientHandler, 
      actionStream,
      isLeaderRef,
      currentTermRef,
      clusterMembersRef,
      config
    )
}

/**
 * Internal implementation of RaftIntegration.
 */
private class RaftIntegrationImpl(
  sessionManager: SessionManager,
  clientHandler: ClientHandler,
  actionStream: ActionStream,
  isLeaderRef: Ref[Boolean],
  currentTermRef: Ref[Long],
  clusterMembersRef: Ref[List[MemberId]],
  config: ServerConfig
) extends RaftIntegration {
  
  override def forwardAction(action: ServerAction): Task[Unit] = {
    action match {
      case CreateSessionAction(routingId, capabilities, nonce) =>
        for {
          isLeader <- isLeaderRef.get
          _ <- if (isLeader) {
            // Convert to Raft command and submit
            val command = RaftCreateSessionCommand(capabilities, nonce)
            submitRaftCommand(command).flatMap { result =>
              result match {
                case RaftCommandResult.Success(sessionIdBytes) =>
                  for {
                    sessionId <- ZIO.attempt(SessionId.fromString(new String(sessionIdBytes.toArray)))
                    response <- ZIO.succeed(SessionCreated(sessionId, nonce))
                    _ <- clientHandler.deliverResponse(routingId, response)
                  } yield ()
                case RaftCommandResult.Failure(error) =>
                  for {
                    response <- ZIO.succeed(SessionRejected(ServerError, nonce, None))
                    _ <- clientHandler.deliverResponse(routingId, response)
                  } yield ()
              }
            }
          } else {
            // Not leader, send rejection
            for {
              currentLeader <- getCurrentLeader()
              response <- ZIO.succeed(SessionRejected(NotLeader, nonce, currentLeader))
              _ <- clientHandler.deliverResponse(routingId, response)
            } yield ()
          }
        } yield ()
      
      case ClientMessageAction(sessionId, requestId, payload) =>
        for {
          isLeader <- isLeaderRef.get
          _ <- if (isLeader) {
            // Convert to Raft command and submit
            val command = RaftClientCommand(sessionId, requestId, payload)
            submitRaftCommand(command).flatMap { result =>
              result match {
                case RaftCommandResult.Success(responseBytes) =>
                  for {
                    response <- ZIO.succeed(ClientResponse(requestId, responseBytes))
                    _ <- clientHandler.deliverClientResponse(sessionId, response)
                  } yield ()
                case RaftCommandResult.Failure(error) =>
                  for {
                    errorResponse <- ZIO.succeed(RequestError(ProcessingFailed, None))
                    _ <- clientHandler.sendErrorToSession(sessionId, errorResponse)
                  } yield ()
              }
            }
          } else {
            // Not leader, send redirection
            for {
              currentLeader <- getCurrentLeader()
              errorResponse <- ZIO.succeed(RequestError(NotLeaderRequest, currentLeader))
              _ <- clientHandler.sendErrorToSession(sessionId, errorResponse)
            } yield ()
          }
        } yield ()
      
      case ExpireSessionAction(sessionId) =>
        for {
          _ <- ZIO.logInfo(s"Expiring session: $sessionId")
          // Submit session expiration to Raft
          command = RaftExpireSessionCommand(sessionId)
          _ <- submitRaftCommand(command).ignore // Fire and forget for cleanup
        } yield ()
    }
  }
  
  override def actionStream: ZStream[Any, Throwable, ServerAction] = 
    this.actionStream.resultStream
  
  override def handleRaftResponse(
    sessionId: SessionId,
    requestId: RequestId,
    response: Either[String, scodec.bits.ByteVector]
  ): UIO[Unit] = 
    response match {
      case Right(responseBytes) =>
        for {
          clientResponse <- ZIO.succeed(ClientResponse(requestId, responseBytes))
          _ <- clientHandler.deliverClientResponse(sessionId, clientResponse)
        } yield ()
      case Left(error) =>
        for {
          errorResponse <- ZIO.succeed(RequestError(ProcessingFailed, None))
          _ <- clientHandler.sendErrorToSession(sessionId, errorResponse)
        } yield ()
    }
  
  override def handleLeadershipChange(
    isLeader: Boolean,
    currentTerm: Long,
    existingSessions: Map[SessionId, SessionMetadata]
  ): UIO[Unit] = 
    for {
      _ <- isLeaderRef.set(isLeader)
      _ <- currentTermRef.set(currentTerm)
      _ <- if (isLeader) {
        for {
          _ <- ZIO.logInfo(s"Became leader in term $currentTerm with ${existingSessions.size} existing sessions")
          _ <- sessionManager.initializeFromRaftState(existingSessions)
        } yield ()
      } else {
        for {
          _ <- ZIO.logInfo(s"Lost leadership in term $currentTerm")
          // Clear local session state when not leader
          _ <- sessionManager.initializeFromRaftState(Map.empty)
        } yield ()
      }
    } yield ()
  
  override def getRaftState(): UIO[RaftStateInfo] = 
    for {
      isLeader <- isLeaderRef.get
      term <- currentTermRef.get
      members <- clusterMembersRef.get
    } yield RaftStateInfo(
      isLeader = isLeader,
      currentTerm = term,
      clusterMembers = members,
      commitIndex = 0L, // Would get from actual Raft implementation
      lastApplied = 0L // Would get from actual Raft implementation
    )
  
  override def isLeader(): UIO[Boolean] = 
    isLeaderRef.get
  
  override def getCurrentTerm(): UIO[Long] = 
    currentTermRef.get
  
  override def getClusterMembers(): UIO[List[MemberId]] = 
    clusterMembersRef.get
  
  override def submitCommand(command: RaftCommand): Task[RaftCommandResult] = 
    submitRaftCommand(command)
  
  override def submitQuery(query: RaftQuery): Task[RaftQueryResult] = 
    submitRaftQuery(query)
  
  override def initializeSessionState(): Task[Map[SessionId, SessionMetadata]] = 
    for {
      // Query Raft state machine for existing sessions
      query <- ZIO.succeed(RaftGetSessionsQuery())
      result <- submitRaftQuery(query)
      sessions <- result match {
        case RaftQueryResult.Success(sessionsBytes) =>
          ZIO.attempt {
            // Deserialize sessions from Raft state
            // This would use proper serialization in practice
            Map.empty[SessionId, SessionMetadata] // Placeholder
          }
        case RaftQueryResult.Failure(error) =>
          ZIO.logWarning(s"Failed to initialize session state: $error") *>
          ZIO.succeed(Map.empty[SessionId, SessionMetadata])
      }
    } yield sessions
  
  private def submitRaftCommand(command: RaftCommand): Task[RaftCommandResult] = {
    // This would integrate with actual Raft implementation
    // For now, return placeholder results
    command match {
      case RaftCreateSessionCommand(capabilities, nonce) =>
        for {
          sessionId <- SessionId.generate()
          responseBytes = scodec.bits.ByteVector(sessionId.toString.getBytes)
        } yield RaftCommandResult.Success(responseBytes)
      
      case RaftClientCommand(sessionId, requestId, payload) =>
        // Echo the payload for testing
        ZIO.succeed(RaftCommandResult.Success(payload))
      
      case RaftExpireSessionCommand(sessionId) =>
        ZIO.succeed(RaftCommandResult.Success(scodec.bits.ByteVector.empty))
      
      case _ =>
        ZIO.succeed(RaftCommandResult.Failure("Unknown command"))
    }
  }
  
  private def submitRaftQuery(query: RaftQuery): Task[RaftQueryResult] = {
    // This would integrate with actual Raft implementation
    query match {
      case RaftGetSessionsQuery() =>
        ZIO.succeed(RaftQueryResult.Success(scodec.bits.ByteVector.empty))
      
      case _ =>
        ZIO.succeed(RaftQueryResult.Failure("Unknown query"))
    }
  }
  
  private def getCurrentLeader(): UIO[Option[MemberId]] = {
    // This would get the current leader from Raft implementation
    ZIO.succeed(None) // Placeholder
  }
}

/**
 * Raft state information for monitoring.
 */
case class RaftStateInfo(
  isLeader: Boolean,
  currentTerm: Long,
  clusterMembers: List[MemberId],
  commitIndex: Long,
  lastApplied: Long
)

/**
 * Raft command types for session management.
 */
sealed trait RaftCommand

case class RaftCreateSessionCommand(
  capabilities: Map[String, String],
  nonce: Nonce
) extends RaftCommand

case class RaftClientCommand(
  sessionId: SessionId,
  requestId: RequestId,
  payload: scodec.bits.ByteVector
) extends RaftCommand

case class RaftExpireSessionCommand(
  sessionId: SessionId
) extends RaftCommand

/**
 * Raft query types for session state.
 */
sealed trait RaftQuery

case class RaftGetSessionsQuery() extends RaftQuery

/**
 * Raft command execution results.
 */
sealed trait RaftCommandResult

object RaftCommandResult {
  case class Success(response: scodec.bits.ByteVector) extends RaftCommandResult
  case class Failure(error: String) extends RaftCommandResult
}

/**
 * Raft query execution results.
 */
sealed trait RaftQueryResult

object RaftQueryResult {
  case class Success(response: scodec.bits.ByteVector) extends RaftQueryResult
  case class Failure(error: String) extends RaftQueryResult
}
/**
 * Additional error reasons for Raft integration.
 */
// Note: RejectionReason and RequestErrorReason values are defined in the protocol Messages.scala file

