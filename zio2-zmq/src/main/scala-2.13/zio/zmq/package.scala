package zio

import zio.ZIO
import org.zeromq.ZMQException
import io.estatico.newtype.macros.newtype

package object zmq {
  @newtype case class RoutingId(value: Int)

  implicit class ZIOExtensions[R](zio: ZIO[R, ZMQException, Unit]) {
    def ignoreHostUnreachable: ZIO[R, ZMQException, Unit] = zio.catchSome {
      case ex if ex.getErrorCode == _root_.zmq.ZError.EHOSTUNREACH => ZIO.unit
    }
  }
}
