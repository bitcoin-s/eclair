package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import org.bitcoins.crypto.{ECAdaptorSignature, ECDigitalSignature, ECPrivateKey, ECPublicKey}
import scodec.bits.ByteVector

sealed trait Signature {
  def bytes: ByteVector
  def toByteVector64: ByteVector64
}

case class ECDSASignature(sig: ByteVector64) extends Signature {
  override def bytes: ByteVector = sig.bytes
  override def toByteVector64: ByteVector64 = sig
}

case class AdaptorSignature(value: ECAdaptorSignature) extends Signature {
  override def bytes: ByteVector = value.bytes

  override def toByteVector64: ByteVector64 = throw new IllegalArgumentException("ByteVector64 version of adaptor signature is unavailable")

  def adaptedSig: ByteVector = value.adaptedSig

  def dleqProof: ByteVector = value.dleqProof
}

object AdaptorSignature extends App {

  def apply(bytes: ByteVector): AdaptorSignature = AdaptorSignature(ECAdaptorSignature.fromBytes(bytes))

  def adaptorSign(privateKey: PrivateKey, adaptorPoint: PublicKey, msg: ByteVector): AdaptorSignature = {
    val pk = ECPrivateKey.fromBytes(privateKey.value)
    val ap = ECPublicKey.fromBytes(adaptorPoint.value)
    AdaptorSignature(pk.adaptorSign(ap, msg))
  }

  def adaptorVerify(publicKey: PublicKey, msg: ByteVector, adaptorPoint: PublicKey, adaptorSignature: AdaptorSignature): Boolean = {
    val pk = ECPublicKey.fromBytes(publicKey.value)
    val ap = ECPublicKey.fromBytes(adaptorPoint.value)
    val asig = adaptorSignature.value
    pk.adaptorVerify(msg, ap, asig)
  }

  def completeAdaptorSignature(adaptorSecret: PrivateKey, adaptorSignature: AdaptorSignature): ECDSASignature = {
    val as = ECPrivateKey.fromBytes(adaptorSecret.value)
    val asig = adaptorSignature.value
    val sig = as.completeAdaptorSignature(asig)
    ECDSASignature(ByteVector64(sig.toRawRS))
  }

  def extractAdaptorSecret(adaptorPoint: PublicKey, adaptorSignature: AdaptorSignature, signature: ECDSASignature): PrivateKey = {
    val ap = ECPublicKey.fromBytes(adaptorPoint.value)
    val sig = ECDigitalSignature.fromRS(signature.bytes)
    val asig = adaptorSignature.value
    val sec = ap.extractAdaptorSecret(asig, sig)
    PrivateKey.fromBin(sec.bytes)._1
  }

}
