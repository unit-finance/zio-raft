package zio.raft.client

import zio.*
import zio.test.*
import zio.test.Assertion.*
import scodec.bits.ByteVector
import zio.raft.protocol.*
import zio.stream.ZStream

object PendingQueriesSpec extends ZIOSpecDefault {

  override def spec: Spec[Environment & TestEnvironment & Scope, Any] =
    suiteAll("PendingQueries") {

      test("resendAll resends all pending queries and updates lastSentAt") {
        for {
          p <- Promise.make[Nothing, ByteVector]
          now <- Clock.instant
          pq = PendingQueries.empty.add(CorrelationId.fromString("c1"), ByteVector(1,2,3), p, now)
          sentRef <- Ref.make(0)
          transport = new ClientTransport {
            def connect(address: String) = ZIO.unit
            def disconnect() = ZIO.unit
            def sendMessage(message: ClientMessage) = sentRef.update(_ + 1).unit
            def incomingMessages = ZStream.empty
          }
          _ <- pq.resendAll(transport)
          sent <- sentRef.get
        } yield assertTrue(sent == 1)
      }

      test("complete delivers single completion and removes pending entry") {
        for {
          p <- Promise.make[Nothing, ByteVector]
          now <- Clock.instant
          cid = CorrelationId.fromString("c2")
          pq = PendingQueries.empty.add(cid, ByteVector(9), p, now)
          pq2 <- pq.complete(cid, ByteVector(7))
          r <- p.await
        } yield assertTrue(r == ByteVector(7))
      }
    }
}


