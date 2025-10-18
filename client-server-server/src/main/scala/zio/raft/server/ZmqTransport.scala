package zio.raft.server

import zio._
import zio.stream._
import zio.zmq._
import zio.raft.protocol._
import scodec.bits.ByteVector
import org.zeromq.ZMQException

/**
 * ZeroMQ transport layer for RaftServer.
 * 
 * Provides SERVER socket integration for handling multiple client connections
 * with automatic routing ID management and message serialization/deserialization.
 */
class ZmqTransport private (
  socket: ZSocket,
  messagesSent: Ref[Long],
  messagesReceived: Ref[Long],
  sendErrors: Ref[Long],
  receiveErrors: Ref[Long],
  lastActivity: Ref[Option[java.time.Instant]]
) {
    
    def incomingMessages: ZStream[Any, Throwable, (RoutingId, ClientMessage)] = {
      ZStream
        .fromZIO(socket.receiveWithRoutingId)
        .forever
        .mapZIO { case (routingId, chunkData) =>
          for {
            // Update stats
            _ <- messagesReceived.update(_ + 1)
            _ <- lastActivity.set(Some(java.time.Instant.now()))
            
            // Deserialize message
            byteVector = ByteVector(chunkData.toArray)
            clientMessage <- ZIO.fromEither(
              Codecs.clientMessageCodec.decode(byteVector.bits)
                .toEither
                .map(_.value)
            ).mapError(err => new RuntimeException(s"Failed to decode client message: ${err.message}"))
            
          } yield (routingId, clientMessage)
        }
        .catchSome {
          case ex: ZMQException if ex.getErrorCode == 156384765 => // ETERM
            // Socket terminated - end stream gracefully
            ZStream.empty
        }
        .tapError { err =>
          receiveErrors.update(_ + 1) *>
          ZIO.logError(s"Error receiving message: ${err.getMessage}")
        }
    }
    
    def sendMessage(routingId: RoutingId, message: ServerMessage): IO[Throwable, Unit] = {
      for {
        // Serialize message
        encoded <- ZIO.fromEither(
          Codecs.serverMessageCodec.encode(message)
            .toEither
            .map(_.toByteVector)
        ).mapError(err => new RuntimeException(s"Failed to encode server message: ${err.message}"))
        
        // Send via ZMQ
        _ <- socket.send(routingId, encoded.toArray)
          .tapError { err =>
            sendErrors.update(_ + 1) *>
            ZIO.logError(s"Failed to send message to routing ID ${routingId}: ${err.getMessage}")
          }
        
        // Update stats
        _ <- messagesSent.update(_ + 1)
        _ <- lastActivity.set(Some(java.time.Instant.now()))
        
      } yield ()
    }
    
    def getStats(): UIO[ZmqTransportStats] = 
      for {
        sent <- messagesSent.get
        received <- messagesReceived.get
        sendErrs <- sendErrors.get
        receiveErrs <- receiveErrors.get
        lastAct <- lastActivity.get
      } yield ZmqTransportStats(
        messagesSent = sent,
        messagesReceived = received,
        sendErrors = sendErrs,
        receiveErrors = receiveErrs,
        lastActivityTime = lastAct
      )
}

/**
 * Statistics for ZMQ transport monitoring.
 */
case class ZmqTransportStats(
  messagesSent: Long,
  messagesReceived: Long,
  sendErrors: Long,
  receiveErrors: Long,
  lastActivityTime: Option[java.time.Instant]
)

object ZmqTransport {
  
  /**
   * Create a ZeroMQ transport layer for the server.
   * 
   * @param config Server configuration
   * @return Scoped ZmqTransport instance
   */
  def make(config: ServerConfig): ZIO[ZContext & Scope, Throwable, ZmqTransport] = 
    for {
      // Create SERVER socket
      socket <- ZSocket.server
      
      // Bind to configured address
      _ <- socket.bind(config.fullBindAddress)
        .tapError(err => ZIO.logError(s"Failed to bind ZMQ socket to ${config.fullBindAddress}: ${err.getMessage}"))
      
      _ <- ZIO.logInfo(s"ZMQ SERVER socket bound to ${config.fullBindAddress}")
      
      // Create stats tracking
      messagesSent <- Ref.make(0L)
      messagesReceived <- Ref.make(0L)
      sendErrors <- Ref.make(0L)
      receiveErrors <- Ref.make(0L)
      lastActivity <- Ref.make[Option[java.time.Instant]](None)
      
      // Create implementation
      transport = new ZmqTransport(
        socket,
        messagesSent,
        messagesReceived,
        sendErrors,
        receiveErrors,
        lastActivity
      )
      
    } yield transport
}
