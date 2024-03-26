package zio.raft

import zio.{ZIO, UIO}
import zio.Ref
import zio.ZManaged

trait LogStore[A <: Command]:
  // def firstIndex: UIO[Index]
  def lastIndex: UIO[Index] // TODO: cache
  def lastTerm: UIO[Term] // TODO: cache
  def getLog(index: Index): UIO[Option[LogEntry[A]]]

  // TODO: allow the concrete type to implement this, because caching and different column can be used that would improve performance
  def logTerm(index: Index) =
    if index.isZero then ZIO.some(Term.zero)
    else
      getLog(index).map(_.map(_.term))

  // Should return None if the firstEntry on the store is greater than from
  def getLogs(from: Index, toInclusive: Index): UIO[Option[List[LogEntry[A]]]]
  def storeLog(logEntry: LogEntry[A]): UIO[Unit]  
  def storeLogs(entries: Iterable[LogEntry[A]]): UIO[Unit]  
  // def storeLogs(logEntries: Array[LogEntry]) : UIO[Unit]
  // def deleteRange(min: Index, max: Index) : UIO[Unit]
  def deleteFrom(minInclusive: Index): UIO[Unit]
  def discardEntireLog(previousIndex: Index, previousTerm: Term): UIO[Unit]

  // The previousIndex and previousTerm of the index must be kept, the payload can be dropped
  def discardLogUpTo(index: Index): UIO[Unit]
end LogStore

object LogStore:
  def makeInMemoryManaged[A <: Command] = 
    for 
      logs <- Ref.make(List.empty[LogEntry[A]]).toManaged_
    yield new InMemoryLogStore(logs)

  class InMemoryLogStore[A <: Command](
      logs: Ref[List[LogEntry[A]]]
  ) extends LogStore[A]:

    override def discardLogUpTo(index: Index): UIO[Unit] = 
      logs.update(_.filter(e => e.index >= index))
      
    override def discardEntireLog(previousIndex: Index, previousTerm: Term): UIO[Unit] = 
      logs.set(LogEntry(null.asInstanceOf, previousTerm, previousIndex) :: List.empty[LogEntry[A]])

    override def lastIndex = logs.get.map(_.headOption.map(_.index).getOrElse(Index.zero))
    override def lastTerm = logs.get.map(_.headOption.map(_.term).getOrElse(Term.zero))
    override def getLog(index: Index) = logs.get.map(_.find(_.index == index))
    override def getLogs(from: Index, toInclusive: Index) =       
      for
        firstEntry <- logs.get.map(_.lastOption)

        result <- firstEntry match
          case None => ZIO.none
          case Some(firstEntry) if firstEntry.index > from => ZIO.none
          case _ => logs.get.map(_.filter(e => e.index >= from && e.index <= toInclusive).reverse).asSome
      
      yield result

    override def storeLog(logEntry: LogEntry[A]) =
      logs.update(logEntry :: _)

    override def storeLogs(entries: Iterable[LogEntry[A]]): UIO[Unit] = 
      logs.update(entries.toList ++ _)
    
    override def deleteFrom(minInclusive: Index): UIO[Unit] = 
      logs.update(_.filter(e => e.index < minInclusive))

    
