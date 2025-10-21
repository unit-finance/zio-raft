package zio.raft.stores.segmentedlog

import zio.{UIO, ZIO}

sealed trait LocalLongRef[A]:
  self =>
  def get: UIO[A]

  def set(a: A): UIO[Unit]

  def update(f: A => A) = modify(a => ((), f(a)))

  def getAndUpdate(f: A => A): UIO[A] = modify(v => (v, f(v)))

  def modify[B](f: A => (B, A)): UIO[B]

  def dimap[B](ab: A => B, ba: B => A): LocalLongRef[B]
object LocalLongRef:
  def make(value: Long): UIO[LocalLongRef[Long]] = ZIO.succeed(new VolatileLongRef(value))

  private class VolatileLongRef(@volatile private var value: Long) extends LocalLongRef[Long]:
    self =>
    def get = ZIO.succeed(value)

    def set(a: Long) = ZIO.succeed(this.value = a)

    override def update(f: Long => Long) = ZIO.succeed(this.value = f(value))

    override def getAndUpdate(f: Long => Long): UIO[Long] =
      ZIO.succeed:
        val a = this.value
        this.value = f(a)
        a

    def modify[B](f: Long => (B, Long)) =
      ZIO.succeed:
        val (b, a) = f(value)
        this.value = a
        b

    def dimap[B](ab: Long => B, ba: B => Long): LocalLongRef[B] =
      new Derived[B]:
        type S = Long

        val value = self

        def longToA(s: S) = ab(s)

        def aToLong(a: B) = ba(a)

  private abstract class Derived[A] extends LocalLongRef[A]:
    self =>
    val value: VolatileLongRef

    def longToA(s: Long): A

    def aToLong(a: A): Long

    def get = value.get.map(longToA)

    def set(a: A) = value.set(aToLong(a))

    def modify[B](f: A => (B, A)) =
      value.modify { s =>
        val (b, a) = f(longToA(s))
        val s1 = aToLong(a)
        (b, s1)
      }

    def dimap[B](ab: A => B, ba: B => A): LocalLongRef[B] =
      new Derived[B]:

        def longToA(s: Long): B =
          ab(self.longToA(s))
        def aToLong(b: B): Long =
          self.aToLong(ba(b))
        val value = self.value
end LocalLongRef
