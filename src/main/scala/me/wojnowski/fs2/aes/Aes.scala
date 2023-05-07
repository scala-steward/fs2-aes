package me.wojnowski.fs2.aes

import fs2.Chunk
import fs2.Pipe
import fs2.Pull
import fs2.RaiseThrowable
import fs2.Stream

import cats.effect.Sync
import cats.effect.std.SecureRandom
import cats.syntax.all._

import scala.util.Try
import scala.util.control.NonFatal

import java.nio.ByteBuffer

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Aes {

  private[aes] val IntSizeInBytes     = 4
  private[aes] val IvLengthBytes      = 12
  private[aes] val AuthTagLengthBytes = 16
  private val DefaultChunkSize        = 4 * 1024 * 1024

  private val transformation = "AES/GCM/NoPadding"
  private val keyAlgorithm   = "AES"

  def decrypt[F[_]: Sync](key: SecretKey): Pipe[F, Byte, Byte] =
    (stream: Stream[F, Byte]) =>
      readFirstN(IntSizeInBytes, stream) { (chunkSizeBytes, remainingStream) =>
        val chunkSize = bytesToChunkSize(chunkSizeBytes)

        remainingStream
          .chunkN(IvLengthBytes + chunkSize + AuthTagLengthBytes)
          .flatMap { chunk =>
            readFirstN(IvLengthBytes, Stream.chunk(chunk).covary[F]) { (ivChunk, stream) =>
              Stream.eval(createCipher(Cipher.DECRYPT_MODE, key, ivChunk.toArray)).flatMap { cipher =>
                stream.mapChunks(cipherChunk(cipher, _))
              }
            }
          }
          .adaptError { case NonFatal(throwable) =>
            Error.DecryptionError(throwable)
          }

      }

  def encrypt[F[_]: Sync: SecureRandom](key: SecretKey, chunkSize: Int = DefaultChunkSize): Pipe[F, Byte, Byte] =
    (stream: Stream[F, Byte]) =>
      Stream.chunk(chunkSizeToBytes(chunkSize)) ++
        stream
          .chunkN(chunkSize)
          .flatMap { chunk =>
            Stream.eval(SecureRandom[F].nextBytes(IvLengthBytes)).flatMap { ivBytes =>
              Stream.eval(createCipher[F](Cipher.ENCRYPT_MODE, key, ivBytes)).flatMap { cipher =>
                Stream.chunk(Chunk.array(ivBytes)) ++ Stream.chunk(cipherChunk(cipher, chunk))
              }
            }
          }
          .adaptError { case NonFatal(throwable) =>
            Error.EncryptionError(throwable)
          }

  // TODO validation?
  def keyFromHex(hexString: String): Option[SecretKey] =
    Try {
      new SecretKeySpec(hexString.sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte), keyAlgorithm)
    }.toOption

  private type Mode = Int

  private def createCipher[F[_]: Sync](mode: Mode, key: SecretKey, ivBytes: Array[Byte]): F[Cipher] =
    Sync[F].delay {
      val cipher = Cipher.getInstance(transformation)
      cipher.init(mode, key, new GCMParameterSpec(AuthTagLengthBytes * 8, ivBytes))
      cipher
    }

  private def readFirstN[F[_]: RaiseThrowable, A, B](
    n: Int,
    stream: Stream[F, A]
  )(
    f: (Chunk[A], Stream[F, A]) => Stream[F, B]
  ): Stream[F, B] =
    stream
      .pull
      .unconsN(n)
      .flatMap {
        case Some((chunk, stream)) =>
          f(chunk, stream).pull.echo
        case None                  =>
          Pull.raiseError(Error.DataTooShort)
      }
      .stream

  private def cipherChunk(cipher: Cipher, chunk: Chunk[Byte]): Chunk[Byte] = {
    val inputBuffer  = chunk.toByteBuffer
    val outputBuffer = ByteBuffer.allocate(cipher.getOutputSize(inputBuffer.remaining()))
    cipher.doFinal(inputBuffer, outputBuffer)
    Chunk.byteBuffer(outputBuffer.rewind())
  }

  private def chunkSizeToBytes(chunkSize: Int): Chunk[Byte] = {
    val buffer = ByteBuffer.allocate(IntSizeInBytes)
    buffer.putInt(chunkSize)
    Chunk.byteBuffer(buffer.rewind())
  }

  private def bytesToChunkSize(bytes: Chunk[Byte]): Int =
    bytes.toByteBuffer.getInt

  sealed abstract class Error(message: String, cause: Option[Throwable] = None)
    extends Exception(message, cause.orNull)
    with Product
    with Serializable

  object Error {
    case object DataTooShort extends Error("Data too short")

    case class EncryptionError(cause: Throwable) extends Error(s"Error during encryption: $cause", cause.some)

    case class DecryptionError(cause: Throwable) extends Error(s"Error during decryption: $cause", cause.some)
  }

}
