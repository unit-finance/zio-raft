package zio.lmdb

import org.lmdbjava.*
import java.io.File
import zio.{ZIO, ZLayer}
import zio.Scope
import zio.Exit

trait Environment:
  val env: Env[Array[Byte]]

  def openDatabase(name: String, flags: DatabaseFlags*): ZIO[Any, Throwable, Database] =
    ZIO.attemptBlocking(env.openDbi(name, flags*)).map(new Database(_))

  def transact[R] = Environment.Transact[R](env)
  
  def transactReadOnly[R] = Environment.TransactReadOnly[R](env)
    
object Environment:
  def builder = Builder()

  def test = 
    ZLayer.scoped:
      for 
        path <- zio.nio.file.Files.createTempFileScoped("db")
        env <- Builder().withFlags(org.lmdbjava.EnvFlags.MDB_NOSUBDIR, org.lmdbjava.EnvFlags.MDB_NOSYNC, org.lmdbjava.EnvFlags.MDB_NOLOCK).build(path.toFile)
      yield env

  def transact[R] = TransactPartiallyApplied[R]()

  def transactReadOnly[R] = TransactPartiallyApplied[R]()

  case class Builder(
      mapSize: Option[Long] = None,
      maxDbs: Option[Int] = None,
      flags: List[EnvironmentFlags] = List.empty
  ):
    def withMapSize(size: Long) = copy(mapSize = Some(size))

    def withMaxDbs(max: Int) = copy(maxDbs = Some(max))

    def withFlags(flags: EnvironmentFlags*) = copy(flags = this.flags ++ flags.toList)

    def build(file: File): ZIO[Scope, Throwable, Environment] =
      val b = Env.create(org.lmdbjava.ByteArrayProxy.PROXY_BA)
      mapSize.foreach(b.setMapSize)
      maxDbs.foreach(b.setMaxDbs)

      ZIO
        .acquireRelease(ZIO.attemptBlocking(b.open(file, flags*)))(env => ZIO.attemptBlocking(env.close()).orDie)
        .map(e =>
          new Environment {
            val env = e
          }
        )

    def layer(file: File) = ZLayer.scoped(build(file))

  final class Transact[R](env: Env[Array[Byte]]):
    def apply[E, A](zio: => ZIO[TransactionScope & R, E, A]): ZIO[R, E, A] =
      ZIO.uninterruptibleMask(restore => {
        for
          txn <- ZIO.attemptBlocking(env.txnWrite()).orDie
          exit <- restore(zio.provideSomeLayer(ZLayer.succeed(TransactionScope(txn)))).exit
          _ <- exit match
            case Exit.Success(_) => ZIO.attemptBlocking(txn.commit()).orDie
            case Exit.Failure(_) => ZIO.attemptBlocking(txn.abort()).orDie
          _ <- ZIO.attemptBlocking(txn.close()).orDie
          result <- ZIO.done(exit)
        yield result
      })

  final class TransactReadOnly[R](env: Env[Array[Byte]]):
    def apply[E, A](zio: => ZIO[TransactionScope & R, E, A]): ZIO[R, E, A] =
      ZIO.uninterruptibleMask(restore => {
        for
          txn <- ZIO.attemptBlocking(env.txnRead()).orDie
          exit <- restore(zio.provideSomeLayer(ZLayer.succeed(TransactionScope(txn)))).exit
          _ <- ZIO.attemptBlocking(txn.close()).orDie
          result <- ZIO.done(exit)
        yield result
      })

  final class TransactPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal:
    def apply[E, A](zio: => ZIO[TransactionScope & R, E, A]): ZIO[Environment & R, E, A] =
      ZIO.service[Environment].flatMap(_.transact(zio))
  
  final class TransactReadOnlyPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal:
    def apply[E, A](zio: => ZIO[TransactionScope & R, E, A]): ZIO[Environment & R, E, A] =
      ZIO.service[Environment].flatMap(_.transactReadOnly(zio))