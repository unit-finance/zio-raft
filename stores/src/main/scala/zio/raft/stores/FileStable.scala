package zio.raft.stores

import zio.{Ref, ZIO}
import zio.raft.{Term, MemberId, Stable}
import zio.nio.file.Path
import java.nio.ByteBuffer
import zio.nio.channels.AsynchronousFileChannel
import java.nio.file.StandardOpenOption
import zio.nio.Buffer
import zio.nio.file.Files
import java.nio.file.StandardCopyOption
import scodec.bits.BitVector

class FileStable(ref: Ref[(Term, Option[MemberId])], finalPath: Path, backupPath: Path, tempPath: Path) extends Stable:

  override def currentTerm = ref.get.map(_._1)

  private def write(term: Term, voteFor: Option[MemberId]) =
    for
      bytes <- ZIO.fromTry(codecs.stableCodecWithChucksum.encode((term, voteFor)).toTry.map(_.toByteBuffer)).orDie
      _ <- ref.set((term, voteFor))
      _ <- writeFile(bytes, finalPath)
      _ <- writeFile(bytes, backupPath)
    yield ()

  private def writeFile(bytes: ByteBuffer, path: Path) =
    for
      _ <- ZIO.scoped {
        for
          tempChannel <- AsynchronousFileChannel.open(
            tempPath,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.SYNC
          )
          _ <- tempChannel.write(Buffer.byteFromJava(bytes), 0)
        yield ()
      }.orDie
      _ <- Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE).orDie
    yield ()

  override def newTerm(term: Term, voteFor: Option[MemberId]) =
    write(term, voteFor)

  override def voteFor(memberId: MemberId) =
    for
      term <- currentTerm
      _ <- write(term, Some(memberId))
    yield ()

  override def votedFor = ref.get.map(_._2)

object FileStable:

  private def read(path: Path) =
    ZIO.scoped:
      for
        channel <- AsynchronousFileChannel
          .open(
            path,
            StandardOpenOption.READ
          )
          .orDie
        size <- channel.size.orDie
        bytes <- channel.readChunk(size.toInt, 0).map(c => BitVector(c.toArray)).orDie
        decoded <- ZIO.fromEither(codecs.stableCodecWithChucksum.decodeValue(bytes).toEither)
      yield decoded

  def make(directory: String) =
    val finalPath = Path(directory, "stable")
    val backupPath = Path(directory, "stable.backup")
    val tempPath = Path(directory, "stable.temp")

    for
      finalFileExists <- Files.exists(finalPath)
      backupFileExists <- Files.exists(backupPath)

      tuple <-
        (finalFileExists, backupFileExists) match
          case (true, true) =>
            read(finalPath)
              .orElse(read(backupPath))
              .orDieWith(err => new Throwable(s"Error occurred: ${err.messageWithContext}"))
          case (true, false) =>
            read(finalPath).orDieWith(err => new Throwable(s"Error occurred: ${err.messageWithContext}"))
          case (false, true) =>
            read(backupPath).orDieWith(err => new Throwable(s"Error occurred: ${err.messageWithContext}"))
          case (false, false) =>
            ZIO.succeed((Term(0), None))

      (term, votedFor) = tuple

      ref <- Ref.make((term, votedFor))
    yield FileStable(ref, finalPath, backupPath, tempPath)
