package zio.zmq

import org.zeromq.ZMQException
import zio.{ZIO, ZLayer}
import zmq.Ctx

class ZContext {
  private val ctx = new Ctx()

  def shutdown() = ctx.terminate()

  private[zmq] def createSocket(socketType: Int) = ctx.createSocket(socketType)
}

object ZContext {
  val InterruptedFunction = 4

  val live: ZLayer[Any, ZMQException, ZContext] =
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
          .orDie
      )
    )
}
