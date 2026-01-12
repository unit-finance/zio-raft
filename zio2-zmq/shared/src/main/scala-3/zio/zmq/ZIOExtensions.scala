package zio.zmq

import zio.ZIO

extension [R](zio: ZIO[R, ZMQException, Unit])
  def ignoreHostUnreachable: ZIO[R, ZMQException, Unit] = zio.catchSome:
    case ex if ex.getErrorCode() == ZError.EHOSTUNREACH => ZIO.unit
