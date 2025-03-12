package zio.raft

import zio.stream.ZStream
import zio.{Ref, UIO, ZIO}

trait LogStore[A <: Command]:
  // def firstIndex: UIO[Index]
  def lastIndex: UIO[Index] // TODO: cache
  def lastTerm: UIO[Term] // TODO: cache

  def logTerm(index: Index): UIO[Option[Term]]

  // Should return None if the firstEntry on the store is greater than from
  def getLogs(from: Index, toInclusive: Index): UIO[Option[List[LogEntry[A]]]]

  // Should Die if the firstEntry on the store is greater than from
  def stream(fromInclusive: Index, toInclusive: Index): ZStream[Any, Nothing, LogEntry[A]]

  def storeLog(logEntry: LogEntry[A]): UIO[Unit]
  def storeLogs(entries: List[LogEntry[A]]): UIO[Unit]

  def deleteFrom(minInclusive: Index): UIO[Unit]
  def discardEntireLog(previousIndex: Index, previousTerm: Term): UIO[Unit]

  // The previousIndex and previousTerm of the index must be kept, the payload can be dropped
  def discardLogUpTo(index: Index): UIO[Unit]

  def findConflictByTerm(term: Term, index: Index): UIO[(Term, Index)] =
    if index.isZero then ZIO.succeed((Term.zero, Index.zero))
    else
      logTerm(index).flatMap:
        case None                             => ZIO.succeed((Term.zero, index))
        case Some(ourTerm) if ourTerm <= term => ZIO.succeed((ourTerm, index))
        case Some(ourTerm)                    => findConflictByTerm(term, index.minusOne)

end LogStore

object LogStore:
  def makeInMemory[A <: Command] =
    for logs <- Ref.make(List.empty[LogEntry[A]])
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

    private def getLog(index: Index) = logs.get.map(_.find(_.index == index))

    override def logTerm(index: Index): UIO[Option[Term]] =
      if index.isZero then ZIO.some(Term.zero)
      else getLog(index).map(_.map(_.term))

    override def getLogs(from: Index, toInclusive: Index) =
      for
        firstEntry <- logs.get.map(_.lastOption)

        result <- firstEntry match
          case None                                        => ZIO.none
          case Some(firstEntry) if firstEntry.index > from => ZIO.none
          case _ => logs.get.map(_.filter(e => e.index >= from && e.index <= toInclusive).reverse).asSome
      yield result

    override def stream(fromInclusive: Index, toInclusive: Index) = ZStream
      .fromZIO(getLogs(fromInclusive, toInclusive))
      .flatMap:
        case None        => ZStream.die(new Throwable("No logs found"))
        case Some(value) => ZStream.fromIterable(value)

    override def storeLog(logEntry: LogEntry[A]) =
      logs.update(logEntry :: _)

    override def storeLogs(entries: List[LogEntry[A]]): UIO[Unit] =
      logs.update(entries.toList ++ _)

    override def deleteFrom(minInclusive: Index): UIO[Unit] =
      logs.update(_.filter(e => e.index < minInclusive))
