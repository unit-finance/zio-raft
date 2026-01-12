package zio.zmq

import zio.{ZIO, ZLayer}

class ZContext(context: zmq.Context) {
  private[zmq] def shutdown(): Unit = {
    val rc = zmq.zmq_ctx_term(context)
    if (rc != 0) throw new ZMQException(zmq.zmq_errno())
  }

  private[zmq] def createSocket(socketType: Int): zmq.Socket = {
    val socket = zmq.zmq_socket(context, socketType)
    if (socket == null) throw new ZMQException(zmq.zmq_errno())
    socket
  }
}

object ZContext {
  val InterruptedFunction = 4

  val live: ZLayer[Any, ZMQException, ZContext] =
    ZLayer.scoped(
      ZIO.acquireRelease(
        ZIO
          .attemptBlocking {
            val ctx = zmq.zmq_ctx_new()
            if (ctx == null) throw new ZMQException(zmq.zmq_errno())
            new ZContext(ctx)
          }
          .refineToOrDie[ZMQException]
      )(ctx =>
        ZIO
          .attemptBlocking {
            try ctx.shutdown()
            catch {
              case e: ZMQException if e.getErrorCode() == InterruptedFunction => ()
            }
          }
          .orDie
      )
    )
}
