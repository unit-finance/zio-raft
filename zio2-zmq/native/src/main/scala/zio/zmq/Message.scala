package zio.zmq

final case class Message(data: Array[Byte], routingId: RoutingId)
