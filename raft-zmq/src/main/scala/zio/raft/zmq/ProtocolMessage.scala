package zio.raft.zmq

import zio.raft.{Command, MemberId, RPCMessage}

import scodec.Codec
import scodec.codecs.{discriminated, uint8, utf8_32}

sealed trait ProtocolMessage[A <: Command]
object ProtocolMessage:
  case class Hello[A <: Command](peer: MemberId) extends ProtocolMessage[A]
  case class Disconnect[A <: Command]() extends ProtocolMessage[A]
  case class Rpc[A <: Command](message: RPCMessage[A]) extends ProtocolMessage[A]

  def codec[A <: Command: Codec]: Codec[ProtocolMessage[A]] =
    discriminated[ProtocolMessage[A]]
      .by(uint8)
      .typecase(0, memberIdCodec.as[ProtocolMessage.Hello[A]])
      .singleton(1, ProtocolMessage.Disconnect())
      .typecase(2, RpcMessageCodec.codec[A].as[ProtocolMessage.Rpc[A]])

  private def memberIdCodec = utf8_32.xmap(MemberId(_), _.value)

end ProtocolMessage
