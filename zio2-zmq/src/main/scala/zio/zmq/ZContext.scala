package zio.zmq

import zio.{ZIO, ZLayer}
import zio.durationInt
import org.zeromq.ZMQException
import zmq.Ctx
import zio.Duration

class ZContext {
  private val ctx = new Ctx()

  def shutdown() = ctx.terminate()

  private[zmq] def createSocket(socketType: Int) = ctx.createSocket(socketType)
}

object ZContext {
  val InterruptedFunction = 4

  def live(duration: Duration = 60.seconds): ZLayer[Any, ZMQException, ZContext] =
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
          .timeoutFail(new Exception("shutdown context timedout"))(duration)
          .orDie
      )
    )
}
