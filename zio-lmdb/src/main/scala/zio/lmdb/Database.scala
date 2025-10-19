package zio.lmdb

import org.lmdbjava.{DbiFlags, Dbi}
import zio.ZIO
import zio.stream.ZStream
import java.util as ju

class Database(dbi: Dbi[Array[Byte]]):
  def put(key: Array[Byte], value: Array[Byte]): ZIO[TransactionScope, Throwable, Boolean] =
    ZIO.service[TransactionScope].flatMap(txn => ZIO.attemptBlocking(dbi.put(txn.txn, key, value)))

  def get(key: Array[Byte]): ZIO[TransactionScope, Throwable, Option[Array[Byte]]] =
    ZIO.service[TransactionScope].flatMap(txn => ZIO.attemptBlocking(Option(dbi.get(txn.txn, key))))

  def delete(key: Array[Byte]): ZIO[TransactionScope, Throwable, Unit] =
    ZIO.service[TransactionScope].flatMap(txn => ZIO.attemptBlocking(dbi.delete(txn.txn, key)))

  def truncate: ZIO[TransactionScope, Throwable, Unit] =
    ZIO.service[TransactionScope].flatMap(txn => ZIO.attemptBlocking(dbi.drop(txn.txn)))

  private def cursor =
    for
      txn <- ZIO.service[TransactionScope]
      cursor <- ZIO.acquireRelease(ZIO.attemptBlocking(dbi.iterate(txn.txn)))(cursor =>
        ZIO.attemptBlocking(cursor.close()).orDie
      )
    yield cursor.iterator()

  def stream: ZStream[TransactionScope, Throwable, (Array[Byte], Array[Byte])] =
    ZStream
      .scoped(cursor)
      .flatMap(cursor =>
        ZStream.repeatZIOOption(
          ZIO.attemptBlocking(cursor.next()).catchAll {
            case _: ju.NoSuchElementException => ZIO.fail(None)
            case t                            => ZIO.fail(Some(t))
          }
        )
      )
      .map(kv => kv.key() -> kv.`val`())

object Database:
  def open(name: String, flags: DbiFlags*): ZIO[Environment, Throwable, Database] =
    for
      env <- ZIO.service[Environment]
      db <- env.openDatabase(name, flags*)
    yield db
