package zio.kvstore

import scodec.Codec
import scodec.codecs.{ascii, discriminated, fixedSizeBytes, utf8_32, uint8, provide}

object Codecs:
  // Response codecs
  given Codec[KVResponse.SetDone.type] = scodec.codecs.provide(KVResponse.SetDone)
  given Codec[KVResponse.WatchDone.type] = scodec.codecs.provide(KVResponse.WatchDone)
  given Codec[KVResponse.GetResult] = scodec.codecs.optional(scodec.codecs.bool, utf8_32).xmap(
    opt => KVResponse.GetResult(opt),
    gr => gr.value
  )

  given Codec[KVResponse] =
    discriminated[KVResponse]
      .by(fixedSizeBytes(1, ascii))
      .typecase("S", summon[Codec[KVResponse.SetDone.type]])
      .typecase("W", summon[Codec[KVResponse.WatchDone.type]])
      .typecase("G", summon[Codec[KVResponse.GetResult]])

  given Codec[Either[Nothing, KVResponse]] =
    discriminated[Either[Nothing, KVResponse]].by(uint8)
      .typecase(0, provide(Left(???)))
      .typecase(1, summon[Codec[KVResponse]].xmap(Right(_), _.value))

  // Value codec for KV schema values
  given Codec[String] = utf8_32
  private val sessionIdCodec = zio.raft.protocol.Codecs.sessionIdCodec
  given sessionIdSetCodec: Codec[Set[zio.raft.protocol.SessionId]] =
    scodec.codecs.listOfN(scodec.codecs.uint16, sessionIdCodec).xmap(_.toSet, _.toList)
  given kvKeySetCodec: Codec[Set[KVKey]] =
    scodec.codecs.listOfN(scodec.codecs.uint16, utf8_32).xmap(_.map(KVKey(_)).toSet, _.toList.map(KVKey.unwrap))

  // Command codecs
  private val setCodec: Codec[KVCommand.Set] = (utf8_32 :: utf8_32).as[KVCommand.Set]
  private val watchCodec: Codec[KVCommand.Watch] = utf8_32.as[KVCommand.Watch]
  given Codec[KVCommand] =
    discriminated[KVCommand]
      .by(fixedSizeBytes(1, ascii))
      .typecase("S", setCodec)
      .typecase("W", watchCodec)
