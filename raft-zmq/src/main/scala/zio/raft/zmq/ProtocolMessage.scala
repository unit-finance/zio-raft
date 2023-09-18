package zio.raft.zmq

import zio.raft.{RPC, Command, MemberId, RPCMessage} 
import scodec.codecs.{discriminated, uint8, utf8_32}
import scodec.Codec

sealed trait ProtocolMessage[A <: Command]
object ProtocolMessage:
    case class Hello[A <: Command](peer: MemberId) extends ProtocolMessage[A]
    case class Disconnect[A <: Command]() extends ProtocolMessage[A]
    case class Rpc[A <: Command](message: RPCMessage[A]) extends ProtocolMessage[A]

    def codec[A <: Command](commandCodec: Codec[A]): Codec[ProtocolMessage[A]] = 
        discriminated[ProtocolMessage[A]].by(uint8)
            .typecase(0, memberIdCodec.as[ProtocolMessage.Hello[A]])
            .singleton(1, ProtocolMessage.Disconnect())
            .typecase(2, RpcMessageCodec.codec[A](commandCodec).as[ProtocolMessage.Rpc[A]])

    def encode[A <: Command](message: ProtocolMessage[A], commandCodec: Codec[A]) = 
        codec(commandCodec).encode(message)

    private def memberIdCodec = utf8_32.xmap(MemberId(_), _.value)

end ProtocolMessage

