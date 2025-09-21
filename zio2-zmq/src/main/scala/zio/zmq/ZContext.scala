package zio.zmq

import zio.{ZIO, ZLayer}

import org.zeromq.ZMQException
import zio.Duration
import zio.durationInt

class ZContext {
  private val ctx = new org.zeromq.ZContext()

  def shutdown() = ctx.close()

  private[zmq] def createSocket(socketType: Int) = ctx.createSocket(socketType)
}

object ZContext {
  val InterruptedFunction = 4

  val live = liveWithTimeout()

  def liveWithTimeout(shutdownTimeout: Duration = 60.seconds): ZLayer[Any, ZMQException, ZContext] =
    ZLayer.scoped(
      ZIO.acquireRelease(
        ZIO.attemptBlocking(new ZContext).refineToOrDie[ZMQException]
      )(ctx =>
        ZIO
          .attemptBlocking(ctx.shutdown())
          .catchSome {
            case e: ZMQException if e.getErrorCode == InterruptedFunction =>
              ZIO.unit
          }
          .disconnect
          .timeoutFail(new Exception(s"""ZContext shutdown timed out, timeout="${shutdownTimeout.toMillis()}ms" """))(shutdownTimeout)
          .orDie
      )
    )
}
