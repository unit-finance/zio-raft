package zio.raft.client

import _root_.zio.*
import _root_.zio.test.*
import _root_.zio.test.Assertion.*
import _root_.zio.raft.protocol.*
import scodec.bits.ByteVector
import java.time.Instant

object PendingRequestsSpec extends ZIOSpecDefault {

  private class FakeTransport(ref: Ref[List[ClientMessage]]) extends ClientTransport {
    override def connect(address: String): ZIO[Any, Throwable, Unit] = ZIO.unit
    override def disconnect(): ZIO[Any, Throwable, Unit] = ZIO.unit
    override def sendMessage(message: ClientMessage): ZIO[Any, Throwable, Unit] = ref.update(message :: _).unit
    override def incomingMessages: _root_.zio.stream.ZStream[Any, Throwable, ServerMessage] =
      _root_.zio.stream.ZStream.empty
  }

  def spec = suiteAll("PendingRequests") {

    test("lowestPendingRequestIdOr returns min or default") {
      val rid1 = RequestId.fromLong(5L)
      val rid2 = RequestId.fromLong(2L)
      val rid3 = RequestId.fromLong(9L)
      for {
        p <- Promise.make[Nothing, ByteVector]
        now <- Clock.instant
        pending = PendingRequests.empty
          .add(rid1, ByteVector.empty, p, now)
          .add(rid2, ByteVector.empty, p, now)
          .add(rid3, ByteVector.empty, p, now)
      } yield assertTrue(pending.lowestPendingRequestIdOr(rid1) == rid2)
    }

    test("resendAll includes lowestPendingRequestId = min(pending)") {
      val rid1 = RequestId.fromLong(1L)
      val rid2 = RequestId.fromLong(3L)
      for {
        sentRef <- Ref.make(List.empty[ClientMessage])
        transport = new FakeTransport(sentRef)
        p <- Promise.make[Nothing, ByteVector]
        now <- Clock.instant
        pending = PendingRequests.empty
          .add(rid2, ByteVector.fromValidHex("02"), p, now)
          .add(rid1, ByteVector.fromValidHex("01"), p, now)
        _ <- pending.resendAll(transport)
        sent <- sentRef.get
        msgs = sent.collect { case m: ClientRequest => m }
      } yield assertTrue(msgs.nonEmpty) && assertTrue(msgs.forall(_.lowestPendingRequestId == rid1))
    }

    test("resendExpired includes lowestPendingRequestId = min(pending)") {
      val rid1 = RequestId.fromLong(2L)
      val rid2 = RequestId.fromLong(4L)
      for {
        sentRef <- Ref.make(List.empty[ClientMessage])
        transport = new FakeTransport(sentRef)
        p <- Promise.make[Nothing, ByteVector]
        createdAt = Instant.parse("2023-01-01T00:00:00Z")
        pending = PendingRequests(Map(
          rid1 -> PendingRequests.PendingRequestData(ByteVector.fromValidHex("aa"), p, createdAt, createdAt),
          rid2 -> PendingRequests.PendingRequestData(ByteVector.fromValidHex("bb"), p, createdAt, createdAt)
        ))
        current <- ZIO.succeed(Instant.parse("2023-01-01T00:10:00Z"))
        _ <- pending.resendExpired(transport, current, 1.minute)
        sent <- sentRef.get
        msgs = sent.collect { case m: ClientRequest => m }
      } yield assertTrue(msgs.nonEmpty) && assertTrue(msgs.forall(_.lowestPendingRequestId == rid1))
    }

    test("die removes request and completes promise with death") {
      val rid = RequestId.fromLong(7L)
      for {
        p <- Promise.make[Nothing, ByteVector]
        now <- Clock.instant
        pending0 = PendingRequests.empty.add(rid, ByteVector.fromValidHex("aa"), p, now)
        pending1 <- pending0.die(rid, new RuntimeException("boom"))
        fiber <- p.await.fork
        exit <- fiber.await
      } yield assertTrue(!pending1.contains(rid)) && assertTrue(exit.isFailure)
    }

    test("ignore non-pending die invocation leaves state unchanged") {
      val rid = RequestId.fromLong(7L)
      for {
        pending <- ZIO.succeed(PendingRequests.empty)
        pending1 <- pending.die(rid, new RuntimeException("boom"))
      } yield assertTrue(pending1 == pending)
    }
  }
}
