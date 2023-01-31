package zio.raft

import zio.{ZIO, UIO}

trait LogStore[A <: Command]:
  // def firstIndex: UIO[Index]
  def lastIndex: UIO[Index] // TODO: cache
  def lastTerm: UIO[Term] // TODO: cache
  def getLog(index: Index): UIO[Option[LogEntry[A]]]
  def logTerm(index: Index) =
    if index.isZero then ZIO.succeed(Term.zero)
    else
      for entry <- getLog(index)
      yield entry match
        case Some(entry) => entry.term
        case None        => Term.zero
  def getLogs(from: Index, toInclusive: Index): UIO[List[LogEntry[A]]]
  def storeLog(logEntry: LogEntry[A]): UIO[Unit]
  // def storeLogs(logEntries: Array[LogEntry]) : UIO[Unit]
  // def deleteRange(min: Index, max: Index) : UIO[Unit]
  def deleteFrom(minInclusive: Index): UIO[Unit]
end LogStore

