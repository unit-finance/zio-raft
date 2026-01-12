package zio.zmq


object ZError:  
  val EHOSTUNREACH = scala.scalanative.posix.errno.EHOSTUNREACH.toInt
  val EAGAIN = scala.scalanative.posix.errno.EAGAIN.toInt  
  