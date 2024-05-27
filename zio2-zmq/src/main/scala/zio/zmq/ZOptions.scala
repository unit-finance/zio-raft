package zio.zmq

import zio.{Task, ZIO}

import zmq.{SocketBase, ZMQ}

class ZOptions(socketBase: SocketBase) {
  def setHelloMessage(msg: Array[Byte]): Task[Unit] =
    ZIO.attempt(require(ZMQ.setSocketOption(socketBase, ZMQ.ZMQ_HELLO_MSG, msg)))

  def setDisconnectMessage(msg: Array[Byte]): Task[Unit] =
    ZIO.attempt(
      require(ZMQ.setSocketOption(socketBase, ZMQ.ZMQ_DISCONNECT_MSG, msg))
    )

  def setHiccupMessage(msg: Array[Byte]): Task[Unit] =
    ZIO.attempt(
      require(ZMQ.setSocketOption(socketBase, ZMQ.ZMQ_HICCUP_MSG, msg))
    )

  def setTcpKeepAlive(enable: Boolean) = {
    val value = if (enable) 1 else 0
    ZIO.attempt(
      require(ZMQ.setSocketOption(socketBase, ZMQ.ZMQ_TCP_KEEPALIVE, value))
    )
  }

  def setHeartbeat(
      interval: zio.Duration,
      timeout: zio.Duration,
      ttl: zio.Duration
  ) =
    ZIO.attempt(
      require(
        ZMQ.setSocketOption(
          socketBase,
          ZMQ.ZMQ_HEARTBEAT_IVL,
          interval.toMillis.toInt
        )
      )
    ) *>
      ZIO.attempt(
        require(
          ZMQ.setSocketOption(
            socketBase,
            ZMQ.ZMQ_HEARTBEAT_TIMEOUT,
            timeout.toMillis.toInt
          )
        )
      ) *>
      ZIO.attempt(
        require(
          ZMQ.setSocketOption(
            socketBase,
            ZMQ.ZMQ_HEARTBEAT_TTL,
            ttl.toMillis.toInt
          )
        )
      )

  def setLinger(linger: Int): Task[Unit] =
    ZIO.attempt(require(ZMQ.setSocketOption(socketBase, ZMQ.ZMQ_LINGER, linger)))

  def lastEndpoint: Task[String] =
    ZIO.attempt(
      ZMQ
        .getSocketOptionExt(socketBase, ZMQ.ZMQ_LAST_ENDPOINT)
        .asInstanceOf[String]
    )

  def setHighWatermark(receive: Int, send: Int) =
    ZIO.attempt {
      require(ZMQ.setSocketOption(socketBase, ZMQ.ZMQ_SNDHWM, send))
      require(ZMQ.setSocketOption(socketBase, ZMQ.ZMQ_RCVHWM, receive))
    }

  def setImmediate(immediate: Boolean) =
    ZIO.attempt(
      require(ZMQ.setSocketOption(socketBase, ZMQ.ZMQ_IMMEDIATE, immediate))
    )
}
