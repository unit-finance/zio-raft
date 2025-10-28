package zio.kvstore

import zio.raft.server.RaftServer
import zio.raft.server.RaftServer.RaftAction
import zio.raft.protocol.{SessionId, CorrelationId, QueryResponse}
import zio.raft.protocol.RequestId
import zio.kvstore.KVServer.KVServerAction
import scodec.bits.ByteVector
import zio.kvstore.protocol.{KVClientRequest, KVQuery}
import scodec.Codec
import zio.raft.protocol.ClientResponse
import zio.kvstore.protocol.KVServerRequest
import zio.raft.MemberId
import zio.raft.sessionstatemachine.SessionMetadata
import zio.raft.protocol.ServerRequest
import java.time.Instant

class KVServer(server: RaftServer):
  private def decodeClientRequest(bytes: ByteVector): KVClientRequest =
    summon[Codec[KVClientRequest]].decode(bytes.bits).require.value

  def stream = server.raftActions.map {
    case RaftAction.CreateSession(sessionId, capabilities) => KVServerAction.CreateSession(sessionId, capabilities)
    case RaftAction.ClientRequest(sessionId, requestId, lowestPendingRequestId, payload) =>
      KVServerAction.ClientRequest(sessionId, requestId, lowestPendingRequestId, decodeClientRequest(payload))
    case RaftAction.ServerRequestAck(sessionId, requestId) => KVServerAction.ServerRequestAck(sessionId, requestId)
    case RaftAction.ExpireSession(sessionId)               => KVServerAction.ExpireSession(sessionId)
    case RaftAction.Query(sessionId, correlationId, payload) =>
      KVServerAction.Query(sessionId, correlationId, summon[Codec[KVQuery]].decode(payload.bits).require.value)
  }

  def reply[A](sessionId: SessionId, requestId: RequestId, response: A)(using Codec[A]) =
    val bytes = summon[Codec[A]].encode(response).require.bytes
    server.sendClientResponse(sessionId, ClientResponse(requestId, bytes))

  def replyQuery[A](sessionId: SessionId, correlationId: CorrelationId, response: A)(using Codec[A]) =
    val bytes = summon[Codec[A]].encode(response).require.bytes
    server.sendQueryResponse(sessionId, QueryResponse(correlationId, bytes))

  def requestError(sessionId: SessionId, requestId: RequestId, reason: zio.raft.sessionstatemachine.RequestError) =
    val serverReason = reason match
      case zio.raft.sessionstatemachine.RequestError.ResponseEvicted =>
        zio.raft.protocol.RequestErrorReason.ResponseEvicted
    server.sendRequestError(sessionId, zio.raft.protocol.RequestError(requestId, serverReason))

  def confirmSessionCreation(sessionId: SessionId) = server.confirmSessionCreation(sessionId)

  def stepUp(sessions: Map[SessionId, SessionMetadata]) =
    server.stepUp(sessions.map((k, v) => (k, zio.raft.server.SessionMetadata(v.capabilities, v.createdAt))))

  def stepDown(leaderId: Option[MemberId]) = server.stepDown(leaderId.map(id => zio.raft.protocol.MemberId(id.value)))

  def leaderChanged(leaderId: MemberId) = server.leaderChanged(zio.raft.protocol.MemberId(leaderId.value))

  def sendServerRequest(now: Instant, sessionId: SessionId, requestId: RequestId, request: KVServerRequest) =
    val payload = summon[Codec[KVServerRequest]].encode(request).require.bytes
    server.sendServerRequest(sessionId, ServerRequest(requestId, payload, now))

object KVServer:
  def make(serverBindAddress: String) =
    for
      server <- RaftServer.make(serverBindAddress)
      kvServer = new KVServer(server)
    yield kvServer

  trait KVServerAction
  object KVServerAction:
    case class CreateSession(sessionId: SessionId, capabilities: Map[String, String]) extends KVServerAction
    case class ClientRequest(
      sessionId: SessionId,
      requestId: RequestId,
      lowestPendingRequestId: RequestId,
      request: KVClientRequest
    ) extends KVServerAction
    case class ServerRequestAck(sessionId: SessionId, requestId: RequestId) extends KVServerAction
    case class ExpireSession(sessionId: SessionId) extends KVServerAction
    case class Query(sessionId: SessionId, correlationId: CorrelationId, query: KVQuery) extends KVServerAction
