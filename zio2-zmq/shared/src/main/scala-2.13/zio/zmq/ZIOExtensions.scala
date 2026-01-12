package zio.zmq

import zio.ZIO

class ZIOExtensions {
  implicit class ZIOExtensions[R](zio: ZIO[R, ZMQException, Unit]) {
    def ignoreHostUnreachable: ZIO[R, ZMQException, Unit] = zio.catchSome {
      case ex if ex.getErrorCode == _root_.zmq.ZError.EHOSTUNREACH => ZIO.unit
    }
  }
}
