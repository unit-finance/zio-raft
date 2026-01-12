package zio

package object zmq {

  private[zmq] val ZMQ_DONTWAIT = 1

  class ZMQException(private val code: Int) extends Exception(s"ZMQ error: $code") {
    def getErrorCode(): Int = code
  }
}