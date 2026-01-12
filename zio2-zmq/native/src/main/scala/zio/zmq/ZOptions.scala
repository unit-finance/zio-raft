package zio.zmq

import zio.{Task, ZIO}
import scalanative.unsafe
import scalanative.unsafe.Zone
import scalanative.libc.string.memcpy
import scalanative.libc.string.memset
import scalanative.unsafe.UnsafeRichArray
import java.nio.charset.StandardCharsets

class ZOptions (socket: zmq.Socket) {

  // Constants from libzmq zmq.h (stable/draft)
  private object Opt:
    // Stable
    final val ZMQ_LINGER        = 17
    final val ZMQ_SNDHWM        = 23
    final val ZMQ_RCVHWM        = 24
    final val ZMQ_LAST_ENDPOINT = 32
    final val ZMQ_TCP_KEEPALIVE = 34
    final val ZMQ_IMMEDIATE     = 39
    // Draft (enabled in our builds)
    final val ZMQ_HEARTBEAT_IVL     = 75
    final val ZMQ_HEARTBEAT_TTL     = 77
    final val ZMQ_HEARTBEAT_TIMEOUT = 79
    final val ZMQ_HELLO_MSG         = 110
    final val ZMQ_DISCONNECT_MSG    = 111
    final val ZMQ_HICCUP_MSG        = 114

  private def setInt(option: Int, value: Int): Task[Unit] =
    ZIO.attemptBlocking {
      Zone {
        val ptr = unsafe.stackalloc[unsafe.CInt]()
        !ptr = value
        val rc = zmq.zmq_setsockopt(socket, option, ptr, unsafe.sizeof[unsafe.CInt])
        if rc != 0 then throw new ZMQException(zmq.zmq_errno())
      }
    }.refineToOrDie[ZMQException]

  private def setBytes(option: Int, bytes: Array[Byte]): Task[Unit] =
    ZIO.attemptBlocking {
      Zone {
        val size = bytes.length
        val buf  = unsafe.stackalloc[Byte](size)
        val lenPtr = unsafe.stackalloc[unsafe.CSize]()
        // zero-init then write Int into the CSize storage
        memset(lenPtr.asInstanceOf[unsafe.Ptr[Byte]], 0, unsafe.sizeof[unsafe.CSize])
        val lenAsInt = lenPtr.asInstanceOf[unsafe.Ptr[unsafe.CInt]]
        !lenAsInt = size
        val lenVal = !lenPtr
        memcpy(buf, bytes.at(0), lenVal) // length in bytes
        val rc = zmq.zmq_setsockopt(socket, option, buf.asInstanceOf[unsafe.CVoidPtr], lenVal) // optvallen
        if rc != 0 then throw new ZMQException(zmq.zmq_errno())
      }
    }.refineToOrDie[ZMQException]

  def setHelloMessage(msg: Array[Byte]): Task[Unit] =
    setBytes(Opt.ZMQ_HELLO_MSG, msg)

  def setDisconnectMessage(msg: Array[Byte]): Task[Unit] =
    setBytes(Opt.ZMQ_DISCONNECT_MSG, msg)

  def setHiccupMessage(msg: Array[Byte]): Task[Unit] =
    setBytes(Opt.ZMQ_HICCUP_MSG, msg)

  def setTcpKeepAlive(enable: Boolean): Task[Unit] =
    setInt(Opt.ZMQ_TCP_KEEPALIVE, if enable then 1 else 0)

  def setHeartbeat(interval: zio.Duration, timeout: zio.Duration, ttl: zio.Duration): Task[Unit] =
    setInt(Opt.ZMQ_HEARTBEAT_IVL, interval.toMillis.toInt) *>
      setInt(Opt.ZMQ_HEARTBEAT_TIMEOUT, timeout.toMillis.toInt) *>
      setInt(Opt.ZMQ_HEARTBEAT_TTL, ttl.toMillis.toInt)

  def setLinger(linger: Int): Task[Unit] =
    setInt(Opt.ZMQ_LINGER, linger)

  def lastEndpoint: Task[String] =
    ZIO.attemptBlocking {
      Zone {
        // Buffer for endpoint string
        val cap       = 512
        val buf       = unsafe.stackalloc[Byte](cap)
        val optLenPtr = unsafe.stackalloc[unsafe.CSize]()
        memset(optLenPtr.asInstanceOf[unsafe.Ptr[Byte]], 0, unsafe.sizeof[unsafe.CSize])
        val capAsInt = optLenPtr.asInstanceOf[unsafe.Ptr[unsafe.CInt]]
        !capAsInt = cap
        val rc = zmq.zmq_getsockopt(socket, Opt.ZMQ_LAST_ENDPOINT, buf.asInstanceOf[unsafe.CVoidPtr], optLenPtr)
        if rc != 0 then throw new ZMQException(zmq.zmq_errno())
        unsafe.fromCString(buf.asInstanceOf[unsafe.CString], StandardCharsets.UTF_8)
      }
    }.refineToOrDie[ZMQException]

  def setHighWatermark(receive: Int, send: Int): Task[Unit] =
    setInt(Opt.ZMQ_SNDHWM, send) *> setInt(Opt.ZMQ_RCVHWM, receive)

  def setImmediate(immediate: Boolean): Task[Unit] =
    setInt(Opt.ZMQ_IMMEDIATE, if immediate then 1 else 0)
}
