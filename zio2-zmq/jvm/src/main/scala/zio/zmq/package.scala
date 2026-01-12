package zio

import zio.prelude.Newtype

package object zmq {
  type ZMQException = org.zeromq.ZMQException

  type ZError = _root_.zmq.ZError
  object ZError {
    val EHOSTUNREACH = _root_.zmq.ZError.EHOSTUNREACH
    val EAGAIN = _root_.zmq.ZError.EAGAIN
  }

  type Message = Message.Type
  object Message extends Newtype[_root_.zmq.Msg] {
    implicit class MessageSyntax(private val self: Message) extends AnyVal {
      def data: Array[Byte] =
        Message.unwrap(self).data()

      def routingId: RoutingId =
        RoutingId(Message.unwrap(self).getRoutingId())
    }
  }

  type RoutingId = RoutingId.Type

  object RoutingId extends Newtype[Int] {
    implicit class RoutingIdSyntax(private val self: RoutingId) extends AnyVal {
      def value: Int = RoutingId.unwrap(self)
    }
  }
}
