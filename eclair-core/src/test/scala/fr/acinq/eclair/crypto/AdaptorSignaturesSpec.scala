package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.crypto.AdaptorSignature._
import fr.acinq.eclair.{randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite

class AdaptorSignaturesSpec extends AnyFunSuite {

  test("adaptor signatures") {
    val privateKey = randomKey
    val publicKey = privateKey.publicKey
    val adaptorSecret = randomKey
    val adaptorPoint = adaptorSecret.publicKey
    val message = randomBytes32

    val adaptorSig = adaptorSign(privateKey, adaptorPoint, message)
    assert(adaptorSig.bytes.length === 162)
    assert(adaptorSig.adaptedSig.length === 65)
    assert(adaptorSig.dleqProof.length === 97)

    assert(adaptorVerify(publicKey, message, adaptorPoint, adaptorSig))

    val sig = completeAdaptorSignature(adaptorSecret, adaptorSig)
    assert(Crypto.verifySignature(message, sig.toByteVector64, publicKey))

    val secret = extractAdaptorSecret(adaptorPoint, adaptorSig, sig)
    assert(secret === adaptorSecret)
  }

}
