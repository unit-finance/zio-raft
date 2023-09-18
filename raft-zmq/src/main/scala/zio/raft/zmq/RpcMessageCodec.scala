package zio.raft.zmq

import scodec.codecs.{discriminated, uint8, int64, utf8_32, listOfN, int32}
import zio.raft.*
import scodec.{Codec, DecodeResult}
import zio.Chunk
import scodec.bits.BitVector
import scodec.Attempt
import zio.raft.AppendEntriesResult.Success

object RpcMessageCodec:        
    def codec[A <: Command](commandCodec: Codec[A]): Codec[RPCMessage[A]] = discriminated[RPCMessage[A]].by(uint8)
        .typecase(0, appendEntriesCodec(commandCodec))
        .typecase(1, appendEntriesResultCodec[A])
        .typecase(2, requestVoteRequestCodec[A])
        .typecase(3, requestVoteResultCodec[A])

    private def termCodec = int64.xmap(Term(_), _.value)
    private def indexCodec = int64.xmap(Index(_), _.value)
    private def memberIdCodec = utf8_32.xmap(MemberId(_), _.value)    

    private def logEntryCodec[A <: Command](commandCodec: Codec[A]) = 
        (commandCodec :: termCodec :: indexCodec).as[LogEntry[A]]

    private def entriesCodec[A <: Command](commandCodec: Codec[A]): Codec[List[LogEntry[A]]] = 
        listOfN(int32, logEntryCodec(commandCodec))

    private def appendEntriesCodec[A <: Command](commandCodec: Codec[A]) =
        (termCodec :: memberIdCodec :: indexCodec :: termCodec :: entriesCodec(commandCodec) :: indexCodec).as[AppendEntriesRequest[A]]
          
    private def appendEntriesResultCodec[A <: Command] = discriminated[AppendEntriesResult[A]].by(uint8)
        .typecase(0, (memberIdCodec :: termCodec :: indexCodec).as[AppendEntriesResult.Success[A]])
        .typecase(1, (memberIdCodec :: termCodec :: indexCodec).as[AppendEntriesResult.Failure[A]])

    private def requestVoteRequestCodec[A <: Command] = (termCodec :: memberIdCodec :: indexCodec :: termCodec).as[RequestVoteRequest[A]]
    
    private def requestVoteResultCodec[A <: Command] = discriminated[RequestVoteResult[A]].by(uint8)
        .typecase(0, (memberIdCodec :: termCodec).as[RequestVoteResult.Rejected[A]])
        .typecase(1, (memberIdCodec :: termCodec).as[RequestVoteResult.Granted[A]])