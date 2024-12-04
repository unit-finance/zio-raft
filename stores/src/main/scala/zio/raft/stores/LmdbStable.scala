package zio.raft.stores

import zio.raft.Term
import zio.raft.MemberId
import zio.Ref
import zio.lmdb.Database
import zio.raft.Stable
import zio.UIO
import zio.ZIO
import zio.lmdb.Environment
import scodec.bits.BitVector

class LmdbStable(environment: Environment, ref: Ref[(Term, Option[MemberId])], database: Database, key: Array[Byte]) extends Stable:

  override def currentTerm: UIO[Term] = ref.get.map(_._1)

  override def votedFor: UIO[Option[MemberId]] = ref.get.map(_._2)

  override def newTerm(term: Term, voteFor: Option[MemberId]): UIO[Unit] = 
     write(term, voteFor)

  override def voteFor(memberId: MemberId): UIO[Unit] = 
    for
      term <- currentTerm
      _ <- write(term, Some(memberId))
    yield ()

  private def write(term: Term, voteFor: Option[MemberId]) = 
    for
      bytes <- ZIO.fromTry(codecs.stableCodec.encode((term, voteFor)).toTry.map(_.toByteArray)).orDie
      _ <- ref.set((term, voteFor)) 
      _ <- environment.transact(database.put(key, bytes)).orDie
    yield ()

object LmdbStable:
  def make = 
    for
        environment <- ZIO.service[Environment]
        database <- Database.open("stable").orDie
        key  = "stable".getBytes("UTF-8")
        bytes <- environment.transactReadOnly(database.get(key)).orDie
        (term, votedFor) <- bytes match
            case None => ZIO.succeed(Term(0), None)
            case Some(value) => ZIO.fromTry(codecs.stableCodec.decodeValue(BitVector(value)).toTry).orDie
        ref <- Ref.make((term, votedFor))
        
    yield LmdbStable(environment, ref, database, key)


  

  
