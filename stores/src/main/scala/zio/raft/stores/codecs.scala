package zio.raft.stores

import scodec.codecs.{int32, int64, utf8_32, bool, optional, checksummed, bits, variableSizeBytes}
import zio.raft.{Term, MemberId}
import zio.raft.Index

object codecs:
  val termCodec = int64.as[Term]
  val indexCodec = int64.as[Index]
  val memberIdCodec = utf8_32.as[MemberId]


  val stableCodec = (termCodec :: optional(bool(8), memberIdCodec)).as[(Term, Option[MemberId])]
  val stableCodecWithChucksum = 
    checksummed(stableCodec, scodec.bits.crc.crc32, variableSizeBytes(int32, bits) :: bits(32))
