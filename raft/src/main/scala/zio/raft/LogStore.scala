package zio.raft

import zio.{ZIO, UIO}

trait LogStore:
  // def firstIndex: UIO[Index]
  def lastIndex: UIO[Index] // TODO: cache
  def lastTerm: UIO[Term] // TODO: cache
  def getLog(index: Index) : UIO[Option[LogEntry]]
   def getLogs(from: Index, toInclusive: Index) : UIO[List[LogEntry]]
  def storeLog(logEntry: LogEntry) : UIO[Unit]
  // def storeLogs(logEntries: Array[LogEntry]) : UIO[Unit]
  // def deleteRange(min: Index, max: Index) : UIO[Unit]
  def deleteFrom(minInclusive: Index): UIO[Unit]
end LogStore

object LogStore:
  // def firstIndex = ZIO.service[LogStore].flatMap(_.firstIndex)
  def lastIndex = ZIO.service[LogStore].flatMap(_.lastIndex)
  def lastTerm = ZIO.service[LogStore].flatMap(_.lastTerm)
  
  def logTerm(index: Index) = 
    if index.isZero then ZIO.succeed(Term.zero)
    else
      for
        entry <- getLog(index)
      yield 
        entry match 
          case Some(entry) => entry.term
          case None => Term.zero
      
  def getLog(index: Index) = ZIO.service[LogStore].flatMap(_.getLog(index))
  def getLogs(from: Index, toInclusive: Index) = ZIO.service[LogStore].flatMap(_.getLogs(from, toInclusive))
  def storeLog(logEntry: LogEntry) = ZIO.service[LogStore].flatMap(_.storeLog(logEntry))
  // def storeLogs(logEntries: Array[LogEntry]) = ZIO.service[LogStore].flatMap(_.storeLogs(logEntries))
  // def deleteRange(min: Index, max: Index) = ZIO.service[LogStore].flatMap(_.deleteRange(min, max))
  def deleteFrom(minInclusive: Index) = ZIO.service[LogStore].flatMap(_.deleteFrom(minInclusive))
end LogStore
