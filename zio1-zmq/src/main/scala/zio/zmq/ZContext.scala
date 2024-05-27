package zio.zmq

import org.zeromq.ZMQException
import zio.blocking._
import zio.{Has, ZIO, ZLayer, ZManaged}
import zmq.Ctx

class ZContext {
  private val ctx = new Ctx()

  def shutdown() = ctx.terminate()

  private[zmq] def createSocket(socketType: Int) = ctx.createSocket(socketType)
}

object ZContext {
  val InterruptedFunction = 4

  val live: ZLayer[Blocking, ZMQException, Has[ZContext]] =
    ZManaged
      .make(effectBlocking(new ZContext).refineToOrDie[ZMQException])(ctx =>
        effectBlocking(ctx.shutdown()).catchSome {
          case e: ZMQException if e.getErrorCode == InterruptedFunction =>
            ZIO.unit
        }.orDie
      )
      .toLayer
}
