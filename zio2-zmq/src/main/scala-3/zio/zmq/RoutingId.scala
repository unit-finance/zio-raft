package zio.zmq

opaque type RoutingId = Int

extension (routingId: RoutingId)
  def value: Int = routingId

object RoutingId:
    def apply(value: Int): RoutingId = value