package zio.zmq

import scala.scalanative.unsafe.extern
import scala.scalanative.unsafe
import scala.scalanative.unsafe.Nat
import scala.scalanative.unsafe.link
import scala.scalanative.unsafe.blocking

@link("zmq")
@extern
object zmq {

  type MsgSize = Nat.Digit2[Nat._6, Nat._4]
  type Msg = unsafe.CArray[unsafe.CChar, MsgSize]

  type Context = unsafe.CVoidPtr
  type Socket = unsafe.CVoidPtr

  def zmq_errno (): unsafe.CInt = extern
  
  def zmq_ctx_new (): unsafe.CVoidPtr = extern
  @blocking def zmq_ctx_term (context_ : unsafe.CVoidPtr): unsafe.CInt = extern
  @blocking def zmq_ctx_shutdown (context_ : unsafe.CVoidPtr): unsafe.CInt = extern
  def zmq_ctx_set (context_ : unsafe.CVoidPtr, option_ : unsafe.CInt, optval_ : unsafe.CInt): unsafe.CInt = extern
  def zmq_ctx_get (context_ : unsafe.CVoidPtr, option_ : unsafe.CInt): unsafe.CInt = extern
  
  def zmq_socket (ctx: unsafe.CVoidPtr, type_ : unsafe.CInt): unsafe.CVoidPtr = extern
  @blocking def zmq_close (socket: unsafe.CVoidPtr): unsafe.CInt = extern
  def zmq_setsockopt (s_ : unsafe.CVoidPtr, option_ : unsafe.CInt, optval_ : unsafe.CVoidPtr, optvallen_ : unsafe.CSize): unsafe.CInt = extern
  def zmq_getsockopt (s_ : unsafe.CVoidPtr, option_ : unsafe.CInt, optval_ : unsafe.CVoidPtr, optvallen_ : unsafe.Ptr[unsafe.CSize]): unsafe.CInt = extern
  def zmq_bind (s_ : unsafe.CVoidPtr, addr_ : unsafe.CString): unsafe.CInt = extern
  def zmq_connect (s_ : unsafe.CVoidPtr, addr_ : unsafe.CString): unsafe.CInt = extern
  def zmq_connect_peer (s_ : unsafe.CVoidPtr, addr_ : unsafe.CString): unsafe.CUnsignedInt = extern
  def zmq_unbind (s_ : unsafe.CVoidPtr, addr_ : unsafe.CString): unsafe.CInt = extern
  def zmq_disconnect (s_ : unsafe.CVoidPtr, addr_ : unsafe.CString): unsafe.CInt = extern
  def zmq_disconnect_peer (s_ : unsafe.CVoidPtr, routing_id: unsafe.CUnsignedInt): unsafe.CInt = extern
  
  def zmq_msg_init (msg_ : unsafe.Ptr[Msg]): unsafe.CInt = extern
  def zmq_msg_init_size (msg_ : unsafe.Ptr[Msg], size_ : unsafe.CSize): unsafe.CInt = extern
  def zmq_msg_init_data (msg_ : unsafe.Ptr[Msg], data_ : unsafe.CVoidPtr, size_ : unsafe.CSize, ffn_ : unsafe.CFuncPtr2[unsafe.CVoidPtr, unsafe.CVoidPtr, Unit], hint_ : unsafe.CVoidPtr): unsafe.CInt = extern
  
  @blocking def zmq_msg_send (msg_ : unsafe.Ptr[Msg], s_ : unsafe.CVoidPtr, flags_ : unsafe.CInt): unsafe.CInt = extern
  @blocking def zmq_msg_recv (msg_ : unsafe.Ptr[Msg], s_ : unsafe.CVoidPtr, flags_ : unsafe.CInt): unsafe.CInt = extern
  def zmq_msg_close (msg_ : unsafe.Ptr[Msg]): unsafe.CInt = extern
  def zmq_msg_move (dest_ : unsafe.Ptr[Msg], src_ : unsafe.Ptr[Msg]): unsafe.CInt = extern
  def zmq_msg_copy (dest_ : unsafe.Ptr[Msg], src_ : unsafe.Ptr[Msg]): unsafe.CInt = extern
  def zmq_msg_data (msg_ : unsafe.Ptr[Msg]): unsafe.Ptr[Byte] = extern
  def zmq_msg_size (msg_ : unsafe.Ptr[Msg]): unsafe.CSize = extern
  def zmq_msg_more (msg_ : unsafe.Ptr[Msg]): unsafe.CInt = extern
  def zmq_msg_set_routing_id (msg_ : unsafe.Ptr[Msg], routing_id_ : unsafe.CUnsignedInt): unsafe.CInt = extern
  def zmq_msg_routing_id (msg_ : unsafe.Ptr[Msg]): unsafe.CUnsignedInt = extern
}
