package zio.zmq

import zio.ZIO
import org.zeromq.ZMQException


extension [R](zio: ZIO[R, ZMQException, Unit])
    def ignoreHostUnreachable: ZIO[R, ZMQException, Unit] = zio.catchSome:
      case ex if ex.getErrorCode == _root_.zmq.ZError.EHOSTUNREACH => ZIO.unit
