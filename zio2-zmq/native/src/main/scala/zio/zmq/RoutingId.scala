package zio.zmq

opaque type RoutingId = Int

object RoutingId:
  def apply(value: Int): RoutingId = value

  extension (self: RoutingId)
    def value: Int = self  
