package zio.raft

import zio.{ZIO, UIO}
import zio.Ref

trait LogStore[A <: Command]:
  // def firstIndex: UIO[Index]
  def lastIndex: UIO[Index] // TODO: cache
  def lastTerm: UIO[Term] // TODO: cache
  def getLog(index: Index): UIO[Option[LogEntry[A]]]

  // TODO: allow the concrete type to implement this, because caching and different column can be used that would improve performance
  def logTerm(index: Index) =
    if index.isZero then ZIO.succeed(Term.zero)
    else
      getLog(index).map(_.map(_.term).getOrElse(Term.zero))
  def getLogs(from: Index, toInclusive: Index): UIO[List[LogEntry[A]]]
  def storeLog(logEntry: LogEntry[A]): UIO[Unit]
  // def storeLogs(logEntries: Array[LogEntry]) : UIO[Unit]
  // def deleteRange(min: Index, max: Index) : UIO[Unit]
  def deleteFrom(minInclusive: Index): UIO[Unit]
end LogStore

object LogStore:
  def makeInMemoryManaged[A <: Command] = 
    for 
      logs <- Ref.make(List.empty[LogEntry[A]]).toManaged_
    yield new InMemoryLogStore(logs)

  class InMemoryLogStore[A <: Command](
      logs: Ref[List[LogEntry[A]]],      
  ) extends LogStore[A]:
    override def lastIndex = logs.get.map(_.headOption.map(_.index).getOrElse(Index.zero))
    override def lastTerm = logs.get.map(_.headOption.map(_.term).getOrElse(Term.zero))
    override def getLog(index: Index) = logs.get.map(_.find(_.index == index))
    override def getLogs(from: Index, toInclusive: Index) = logs.get.map(_.filter(e => e.index >= from && e.index <= toInclusive).reverse)

    override def storeLog(logEntry: LogEntry[A]) =
      logs.update(logEntry :: _)
    override def deleteFrom(minInclusive: Index): UIO[Unit] = 
      logs.update(_.filter(e => e.index <= minInclusive))

    
