package org.gerweck.scala.util.hashing

import java.nio.ByteBuffer

import org.bouncycastle.crypto._
import org.bouncycastle.crypto.digests._

/** A hash algorithm provided by the [[https://www.bouncycastle.org/ Legion of the Bouncy Castle]].
  *
  * @author Sarah Gerweck <sarah.a180@gmail.com>
  */
trait BouncyHashAlgorithm extends HashAlgorithm

object BouncyHashAlgorithm extends StandardHashAlgorithms {
  type AlgorithmType = BouncyHashAlgorithm

  sealed abstract class SimpleBouncyHashAlgorithm private[BouncyHashAlgorithm](final val name: String, final val outBytes: Int) extends BouncyHashAlgorithm with SizedHashAlgorithm {
    protected[this] def instantiate(): Digest

    override def initialize() = new HashAlgorithm.HashingState {
      private[this] val data = instantiate()
      override def update(bytes: Array[Byte]) = data.update(bytes, 0, bytes.length)
      override def update(bb: ByteBuffer) = {
        if (bb.hasArray) {
          data.update(bb.array, bb.arrayOffset() + bb.position(), bb.remaining())
        } else {
          super.update(bb)
        }
      }
      override def digest() = {
        val arr = new Array[Byte](outBytes)
        data.doFinal(arr, 0)
        arr
      }
    }
  }

  object md5 extends SimpleBouncyHashAlgorithm("MD5", 16) {
    def instantiate() = new MD5Digest
  }
  object sha1 extends SimpleBouncyHashAlgorithm("SHA-1", 20) {
    def instantiate() = new SHA1Digest
  }
  object sha_256 extends SimpleBouncyHashAlgorithm("SHA-256", 32) {
    def instantiate() = new SHA256Digest
  }

  sealed abstract class Sha3Algorithm private[BouncyHashAlgorithm](name: String, protected[this] final val bits: Int)
      extends SimpleBouncyHashAlgorithm(name, bits >> 3) {
    def instantiate = new SHA3Digest(bits)
  }
  object sha3_224 extends Sha3Algorithm("SHA3-224", bits = 224)
  object sha3_256 extends Sha3Algorithm("SHA3-256", bits = 256)
  object sha3_384 extends Sha3Algorithm("SHA3-384", bits = 384)
  object sha3_512 extends Sha3Algorithm("SHA3-512", bits = 512)

}