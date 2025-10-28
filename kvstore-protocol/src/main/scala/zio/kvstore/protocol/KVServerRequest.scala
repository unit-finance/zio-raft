package zio.kvstore.protocol

import scodec.Codec
import scodec.codecs.{ascii, discriminated, fixedSizeBytes, utf8_32}

sealed trait KVServerRequest
object KVServerRequest:
  final case class Notification(key: String, value: String) extends KVServerRequest

  private val notificationCodec: Codec[Notification] = (utf8_32 :: utf8_32).as[Notification]

  given Codec[KVServerRequest] =
    discriminated[KVServerRequest]
      .by(fixedSizeBytes(1, ascii))
      .typecase("N", notificationCodec)
