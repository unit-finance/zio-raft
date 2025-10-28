package zio.kvstore.protocol

import scodec.Codec
import scodec.codecs.{ascii, bool, optional, discriminated, fixedSizeBytes, utf8_32}

// Client-visible request types (distinct from internal Raft state machine commands)
sealed trait KVClientRequest
object KVClientRequest:
  final case class Set(key: String, value: String) extends KVClientRequest
  final case class Watch(key: String) extends KVClientRequest

  private val setCodec: Codec[Set] = (utf8_32 :: utf8_32).as[Set]
  private val watchCodec: Codec[Watch] = utf8_32.as[Watch]

  given Codec[KVClientRequest] =
    discriminated[KVClientRequest].by(fixedSizeBytes(1, ascii))
      .typecase("S", setCodec)
      .typecase("W", watchCodec)

// Query types (used in client Query payloads)
sealed trait KVQuery
object KVQuery:
  final case class Get(key: String) extends KVQuery

  private val getCodec: Codec[Get] = utf8_32.as[Get]

  given Codec[KVQuery] =
    discriminated[KVQuery].by(fixedSizeBytes(1, ascii))
      .typecase("G", getCodec)

// Plain Scala response types for the client API
object KVClientResponse:
  type SetDone = Unit
  type GetResult = Option[String]
  type WatchDone = Unit

  given unitCodec: Codec[Unit] = scodec.codecs.provide(())

  given optionStringCodec: Codec[Option[String]] =
    optional(bool(8), utf8_32)
