/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.TlvCodecs._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, ShortChannelId, UInt64}
import scodec.bits.{BitVector, ByteVector}

/**
 * Created by t-bast on 05/07/2019.
 */

/*
We support multiple payment flows, each having different requirements for what the onions contain. The following is an
overview of the onion contents we support.

STANDARD PAYMENT (fully source-routed, single-part):

    a -------------> b --------------------------> c --------------------------> d ---------------------------> e
          +-----------------------+     +-----------------------+     +-----------------------+     +-----------------------+
          | amount_fwd: 1025 msat |     | amount_fwd: 1010 msat |     | amount_fwd: 1000 msat |     | amount_fwd: 1000 msat |
          | expiry: 600030        |     | expiry: 600012        |     | expiry: 600000        |     | expiry: 600000        |
          | channel_id: 1105      |     | channel_id: 561       |     | channel_id: 42        |     | secret: xyz (opt)     |
          |-----------------------|     |-----------------------|     |-----------------------|     +-----------------------+
          |     (encrypted)       |     |     (encrypted)       |     |     (encrypted)       |     |          EOF          |
          +-----------------------+     +-----------------------+     +-----------------------+     +-----------------------+

STANDARD MULTI-PART PAYMENT (fully source-routed, multi-part):

    a -------------> b --------------------------> c --------------------------> d ---------------------------> e
          +-----------------------+     +-----------------------+     +-----------------------+     +-------------------------+
          | amount_fwd: 1025 msat |     | amount_fwd: 1010 msat |     | amount_fwd: 1000 msat |     | amount_fwd: 1000 msat   |
          | expiry: 600030        |     | expiry: 600012        |     | expiry: 600000        |     | expiry: 600000          |
          | channel_id: 1105      |     | channel_id: 561       |     | channel_id: 42        |     | secret: xyz             |
          |-----------------------|     |-----------------------|     |-----------------------|     | total_amount: 1500 msat |
          |     (encrypted)       |     |     (encrypted)       |     |     (encrypted)       |     +-------------------------+
          +-----------------------+     +-----------------------+     +-----------------------+     |           EOF           |
                                                                                                    +-------------------------+

TRAMPOLINE PAYMENT (partially source-routed, multi-part):

    a -------------> b ---------------------------> t1 -----------------------------> t2 -------------------------------> e
          +----------------------+     +---------------------------+     +---------------------------+     +-----------------------------+
          | amount_fwd: 900 msat |     | amount_fwd: 900 msat      |     | amount_fwd: 750 msat      |     | amount_fwd: 1000 msat       |
          | expiry: 600112       |     | expiry: 600112            |     | expiry: 600042            |     | expiry: 600000              |
          | channel_id: 42       |     | secret: aaaaa             |     | secret: zzzzz             |     | secret: xxxxx               | <- randomly generated by t2 (NOT the invoice secret)
          |----------------------|     | total_amount: 1650 msat   |     | total_amount: 1600 msat   |     | total_amount: 1500 msat     | <- t2 is using multi-part to pay e, still 500 msat more to receive
          |     (encrypted)      |     | trampoline_onion:         |     | trampoline_onion:         |     | trampoline_onion:           |
          +----------------------+     | +-----------------------+ |     | +-----------------------+ |     | +-------------------------+ |
                                       | | amount_fwd: 1600 msat | |     | | amount_fwd: 1500 msat | |     | | amount_fwd: 1500 msat   | |
                                       | | expiry: 600042        | |     | | expiry: 600000        | |     | | expiry: 600000          | |
                                       | | node_id: t2           | |     | | node_id: e            | |     | | total_amount: 2500 msat | | <- may be bigger than amount_fwd in case the payment is split among multiple trampoline routes
                                       | +-----------------------+ |     | +-----------------------+ |     | | secret: yyyyy           | | <- invoice secret
                                       | |      (encrypted)      | |     | |      (encrypted)      | |     | +-------------------------+ |
                                       | +-----------------------+ |     | +-----------------------+ |     | |         EOF             | |
                                       +---------------------------+     +---------------------------+     | +-------------------------+ |
                                       |             EOF           |     |             EOF           |     +-----------------------------+
                                       +---------------------------+     +---------------------------+     |             EOF             |
                                                                                                           +-----------------------------+

Notes:
  - there may be two layers of multi-part: a may split the payment between multiple trampoline routes, and inside each
  trampoline route payments may be split into multiple parts.
  - when multi-part is used to reach trampoline nodes, the payment secret in the outer onion is NOT the invoice secret.
  We want only the recipient to receive the invoice payment secret. The payment secrets in outer onions are generated
  randomly by the sender to simply prevent next-to-last non-trampoline nodes from probing their position in the route or
  steal some fees.

TRAMPOLINE PAYMENT TO LEGACY RECIPIENT (the last trampoline node converts to a standard payment to the final recipient):

    a -------------> b ---------------------------> t1 -----------------------------> t2 ------------------------------ -> e ---------------------------> f
         +----------------------+     +---------------------------+     +---------------------------------+     +-----------------------+     +-------------------------+
         | amount_fwd: 950 msat |     | amount_fwd: 950 msat      |     | amount_fwd: 750 msat            |     | amount_fwd: 1000 msat |     | amount_fwd: 1000 msat   |
         | expiry: 600112       |     | expiry: 600112            |     | expiry: 600042                  |     | expiry: 600000        |     | expiry: 600000          |
         | channel_id: 42       |     | secret: yyyyy             |     | secret: zzzzz                   |     | channel_id: 42        |     | secret: xyz             | <- invoice secret (omitted if not supported by invoice)
         |----------------------|     | total_amount: 1750 msat   |     | total_amount: 1600 msat         |     |-----------------------|     | total_amount: 2500 msat | <- t2 is using multi-part to pay 1500 msat to f, for a total payment
         |     (encrypted)      |     | trampoline_onion:         |     | trampoline_onion:               |     |     (encrypted)       |     +-------------------------+    of 2500 msat split between multiple trampoline routes (omitted if
         +----------------------+     | +-----------------------+ |     | +-----------------------------+ |     +-----------------------+     |           EOF           |    MPP not supported by invoice)
                                      | | amount_fwd: 1600 msat | |     | | amount_fwd: 1500 msat       | |                                   +-------------------------+
                                      | | expiry: 600042        | |     | | expiry: 600000              | |
                                      | | node_id: t2           | |     | | total_amount: 2500 msat     | |
                                      | +-----------------------+ |     | | secret: xyz                 | |
                                      | |      (encrypted)      | |     | | node_id: f                  | |
                                      | +-----------------------+ |     | | invoice_features: 0x0a      | |
                                      +---------------------------+     | | invoice_routing_info: ..... | |
                                      |             EOF           |     | +-----------------------------+ |
                                      +---------------------------+     | |      (encrypted)            | |
                                                                        | +-----------------------------+ |
                                                                        +---------------------------------+
                                                                        |             EOF                 |
                                                                        +---------------------------------+

Notes:
  - the last trampoline node learns the payment details (who the recipient is, the payment amount and secret)
  - but it doesn't learn the sender's identity
  - if the invoice doesn't specify an amount, the last trampoline node can pay a lower amount than what the sender
  intended, thus stealing a lot of fees for himself (the wallet should disable paying to a 0-value invoice via
  trampoline to prevent this attack)
  - if f doesn't support MPP, t2 will send a single-part payment or fail if there isn't enough capacity
  - as in normal trampoline scenario, payment secrets in the outer onion are NOT the invoice secret

*/

case class OnionRoutingPacket(version: Int, publicKey: ByteVector, payload: ByteVector, hmac: ByteVector32)

/** Tlv types used inside onion messages. */
sealed trait OnionTlv extends Tlv

object OnionTlv {

  /** Amount to forward to the next node. */
  case class AmountToForward(amount: MilliSatoshi) extends OnionTlv

  /** CLTV value to use for the HTLC offered to the next node. */
  case class OutgoingCltv(cltv: CltvExpiry) extends OnionTlv

  /** Id of the channel to use to forward a payment to the next node. */
  case class OutgoingChannelId(shortChannelId: ShortChannelId) extends OnionTlv

  /**
   * Bolt 11 payment details (only included for the last node).
   *
   * @param secret      payment secret specified in the Bolt 11 invoice.
   * @param totalAmount total amount in multi-part payments. When missing, assumed to be equal to AmountToForward.
   */
  case class PaymentData(secret: ByteVector32, totalAmount: MilliSatoshi) extends OnionTlv

  /** Id of the next node. */
  case class OutgoingNodeId(nodeId: PublicKey) extends OnionTlv

  /**
   * Invoice feature bits. Only included for intermediate trampoline nodes when they should convert to a legacy payment
   * because the final recipient doesn't support trampoline.
   */
  case class InvoiceFeatures(features: ByteVector) extends OnionTlv

  /**
   * Invoice routing hints. Only included for intermediate trampoline nodes when they should convert to a legacy payment
   * because the final recipient doesn't support trampoline.
   */
  case class InvoiceRoutingInfo(extraHops: List[List[PaymentRequest.ExtraHop]]) extends OnionTlv

  /** An encrypted trampoline onion packet. */
  case class TrampolineOnion(packet: OnionRoutingPacket) extends OnionTlv

  /** Pre-image included by the sender of a payment in case of a donation */
  case class KeySend(paymentPreimage: ByteVector32) extends OnionTlv

  case class PTLCData(nextPointTweak: ByteVector32) extends OnionTlv
}

object Onion {

  import OnionTlv._

  /*
   * We use the following architecture for onion payloads:
   *
   *                                                              PerHopPayload
   *                                           _______________________/\__________________________
   *                                          /                                                   \
   *                                 RelayPayload                                              FinalPayload
   *                     _______________/\_________________                                    ____/\______
   *                    /                                  \                                  /            \
   *           ChannelRelayPayload                          \                                /              \
   *         ________/\______________                        \                              /                \
   *        /                        \                        \                            /                  \
   * RelayLegacyPayload     ChannelRelayTlvPayload     NodeRelayPayload          FinalLegacyPayload     FinalTlvPayload
   *
   * We also introduce additional traits to separate payloads based on their encoding (PerHopPayloadFormat) and on the
   * type of onion packet they can be used with (PacketType).
   *
   */

  sealed trait PerHopPayloadFormat

  /** Legacy fixed-size 65-bytes onion payload. */
  sealed trait LegacyFormat extends PerHopPayloadFormat

  /** Variable-length onion payload with optional additional tlv records. */
  sealed trait TlvFormat extends PerHopPayloadFormat {
    def records: TlvStream[OnionTlv]
  }

  /** Onion packet type (see [[fr.acinq.eclair.crypto.Sphinx.OnionRoutingPacket]]). */
  sealed trait PacketType

  /** See [[fr.acinq.eclair.crypto.Sphinx.PaymentPacket]]. */
  sealed trait PaymentPacket extends PacketType

  /** See [[fr.acinq.eclair.crypto.Sphinx.TrampolinePacket]]. */
  sealed trait TrampolinePacket extends PacketType

  /** Per-hop payload from an HTLC's payment onion (after decryption and decoding). */
  sealed trait PerHopPayload

  /** Per-hop payload for an intermediate node. */
  sealed trait RelayPayload extends PerHopPayload with PerHopPayloadFormat {
    /** Amount to forward to the next node. */
    val amountToForward: MilliSatoshi
    /** CLTV value to use for the HTLC offered to the next node. */
    val outgoingCltv: CltvExpiry
  }

  sealed trait ChannelRelayPayload extends RelayPayload with PaymentPacket {
    /** Id of the channel to use to forward a payment to the next node. */
    val outgoingChannelId: ShortChannelId
  }

  /** Per-hop payload for a final node. */
  sealed trait FinalPayload extends PerHopPayload with PerHopPayloadFormat with TrampolinePacket with PaymentPacket {
    val amount: MilliSatoshi
    val expiry: CltvExpiry
    val paymentSecret: Option[ByteVector32]
    val totalAmount: MilliSatoshi
    val paymentPreimage: Option[ByteVector32]
    val nextPointTweak: Option[ByteVector32]
  }

  case class RelayLegacyPayload(outgoingChannelId: ShortChannelId, amountToForward: MilliSatoshi, outgoingCltv: CltvExpiry) extends ChannelRelayPayload with LegacyFormat

  case class FinalLegacyPayload(amount: MilliSatoshi, expiry: CltvExpiry) extends FinalPayload with LegacyFormat {
    override val paymentSecret = None
    override val totalAmount = amount
    override val paymentPreimage = None
    override val nextPointTweak = None
  }

  case class ChannelRelayTlvPayload(records: TlvStream[OnionTlv]) extends ChannelRelayPayload with TlvFormat {
    override val amountToForward = records.get[AmountToForward].get.amount
    override val outgoingCltv = records.get[OutgoingCltv].get.cltv
    override val outgoingChannelId = records.get[OutgoingChannelId].get.shortChannelId
  }

  case class NodeRelayPayload(records: TlvStream[OnionTlv]) extends RelayPayload with TlvFormat with TrampolinePacket {
    val amountToForward = records.get[AmountToForward].get.amount
    val outgoingCltv = records.get[OutgoingCltv].get.cltv
    val outgoingNodeId = records.get[OutgoingNodeId].get.nodeId
    val totalAmount = records.get[PaymentData].map(_.totalAmount match {
      case MilliSatoshi(0) => amountToForward
      case totalAmount => totalAmount
    }).getOrElse(amountToForward)
    val paymentSecret = records.get[PaymentData].map(_.secret)
    val invoiceFeatures = records.get[InvoiceFeatures].map(_.features)
    val invoiceRoutingInfo = records.get[InvoiceRoutingInfo].map(_.extraHops)
  }

  case class FinalTlvPayload(records: TlvStream[OnionTlv]) extends FinalPayload with TlvFormat {
    override val amount = records.get[AmountToForward].get.amount
    override val expiry = records.get[OutgoingCltv].get.cltv
    override val paymentSecret = records.get[PaymentData].map(_.secret)
    override val totalAmount = records.get[PaymentData].map(_.totalAmount match {
      case MilliSatoshi(0) => amount
      case totalAmount => totalAmount
    }).getOrElse(amount)
    override val paymentPreimage = records.get[KeySend].map(_.paymentPreimage)
    override val nextPointTweak = records.get[PTLCData].map(_.nextPointTweak)
  }

  def createNodeRelayPayload(amount: MilliSatoshi, expiry: CltvExpiry, nextNodeId: PublicKey): NodeRelayPayload =
    NodeRelayPayload(TlvStream(AmountToForward(amount), OutgoingCltv(expiry), OutgoingNodeId(nextNodeId)))

  /** Create a trampoline inner payload instructing the trampoline node to relay via a non-trampoline payment. */
  def createNodeRelayToNonTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, targetNodeId: PublicKey, invoice: PaymentRequest): NodeRelayPayload = {
    val tlvs = Seq[OnionTlv](AmountToForward(amount), OutgoingCltv(expiry), OutgoingNodeId(targetNodeId), InvoiceFeatures(invoice.features.toByteVector), InvoiceRoutingInfo(invoice.routingInfo.toList.map(_.toList)))
    val tlvs2 = invoice.paymentSecret.map(s => tlvs :+ PaymentData(s, totalAmount)).getOrElse(tlvs)
    NodeRelayPayload(TlvStream(tlvs2))
  }

  def createSinglePartPayload(amount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: Option[ByteVector32] = None, userCustomTlvs: Seq[GenericTlv] = Nil, additionalTlvs: Seq[OnionTlv] = Nil): FinalPayload = paymentSecret match {
    case Some(paymentSecret) => FinalTlvPayload(TlvStream(Seq(AmountToForward(amount), OutgoingCltv(expiry), PaymentData(paymentSecret, amount)), userCustomTlvs))
    case None if userCustomTlvs.nonEmpty => FinalTlvPayload(TlvStream(AmountToForward(amount) +: OutgoingCltv(expiry) +: additionalTlvs, userCustomTlvs))
    case None => FinalLegacyPayload(amount, expiry)
  }

  def createMultiPartPayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, additionalTlvs: Seq[OnionTlv] = Nil, userCustomTlvs: Seq[GenericTlv] = Nil): FinalPayload =
    FinalTlvPayload(TlvStream(AmountToForward(amount) +: OutgoingCltv(expiry) +: PaymentData(paymentSecret, totalAmount) +: additionalTlvs, userCustomTlvs))

  /** Create a trampoline outer payload. */
  def createTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, trampolinePacket: OnionRoutingPacket): FinalPayload = {
    FinalTlvPayload(TlvStream(AmountToForward(amount), OutgoingCltv(expiry), PaymentData(paymentSecret, totalAmount), TrampolineOnion(trampolinePacket)))
  }
}

object OnionCodecs {

  import Onion._
  import OnionTlv._
  import scodec.codecs._
  import scodec.{Attempt, Codec, DecodeResult, Decoder, Err}

  def onionRoutingPacketCodec(payloadLength: Int): Codec[OnionRoutingPacket] = (
    ("version" | uint8) ::
      ("publicKey" | bytes(33)) ::
      ("onionPayload" | bytes(payloadLength)) ::
      ("hmac" | bytes32)).as[OnionRoutingPacket]

  val paymentOnionPacketCodec: Codec[OnionRoutingPacket] = onionRoutingPacketCodec(Sphinx.PaymentPacket.PayloadLength)

  val trampolineOnionPacketCodec: Codec[OnionRoutingPacket] = onionRoutingPacketCodec(Sphinx.TrampolinePacket.PayloadLength)

  /**
   * The 1.1 BOLT spec changed the onion frame format to use variable-length per-hop payloads.
   * The first bytes contain a varint encoding the length of the payload data (not including the trailing mac).
   * That varint is considered to be part of the payload, so the payload length includes the number of bytes used by
   * the varint prefix.
   */
  val payloadLengthDecoder = Decoder[Long]((bits: BitVector) =>
    varintoverflow.decode(bits).map(d => DecodeResult(d.value + (bits.length - d.remainder.length) / 8, d.remainder)))

  private val amountToForward: Codec[AmountToForward] = ("amount_msat" | ltmillisatoshi).as[AmountToForward]

  private val outgoingCltv: Codec[OutgoingCltv] = ("cltv" | ltu32).xmap(cltv => OutgoingCltv(CltvExpiry(cltv)), (c: OutgoingCltv) => c.cltv.toLong)

  private val outgoingChannelId: Codec[OutgoingChannelId] = variableSizeBytesLong(varintoverflow, "short_channel_id" | shortchannelid).as[OutgoingChannelId]

  private val paymentData: Codec[PaymentData] = variableSizeBytesLong(varintoverflow, ("payment_secret" | bytes32) :: ("total_msat" | tmillisatoshi)).as[PaymentData]

  private val outgoingNodeId: Codec[OutgoingNodeId] = variableSizeBytesLong(varintoverflow, "node_id" | publicKey).as[OutgoingNodeId]

  private val invoiceFeatures: Codec[InvoiceFeatures] = variableSizeBytesLong(varintoverflow, bytes).as[InvoiceFeatures]

  private val invoiceRoutingInfo: Codec[InvoiceRoutingInfo] = variableSizeBytesLong(varintoverflow, list(listOfN(uint8, PaymentRequest.Codecs.extraHopCodec))).as[InvoiceRoutingInfo]

  private val trampolineOnion: Codec[TrampolineOnion] = variableSizeBytesLong(varintoverflow, trampolineOnionPacketCodec).as[TrampolineOnion]

  private val keySend: Codec[KeySend] = variableSizeBytesLong(varintoverflow, bytes32).as[KeySend]

  private val ptlcData: Codec[PTLCData] = variableSizeBytesLong(varintoverflow, bytes32).as[PTLCData]

  private val onionTlvCodec = discriminated[OnionTlv].by(varint)
    .typecase(UInt64(2), amountToForward)
    .typecase(UInt64(4), outgoingCltv)
    .typecase(UInt64(6), outgoingChannelId)
    .typecase(UInt64(8), paymentData)
    // Types below aren't specified - use cautiously when deploying (be careful with backwards-compatibility).
    .typecase(UInt64(66097), invoiceFeatures)
    .typecase(UInt64(66098), outgoingNodeId)
    .typecase(UInt64(66099), invoiceRoutingInfo)
    .typecase(UInt64(66100), trampolineOnion)
    .typecase(UInt64(5482373484L), keySend)
    .typecase(UInt64(0x5555555555555L), ptlcData)

  val tlvPerHopPayloadCodec: Codec[TlvStream[OnionTlv]] = TlvCodecs.lengthPrefixedTlvStream[OnionTlv](onionTlvCodec).complete

  private val legacyRelayPerHopPayloadCodec: Codec[RelayLegacyPayload] = (
    ("realm" | constant(ByteVector.fromByte(0))) ::
      ("short_channel_id" | shortchannelid) ::
      ("amt_to_forward" | millisatoshi) ::
      ("outgoing_cltv_value" | cltvExpiry) ::
      ("unused_with_v0_version_on_header" | ignore(8 * 12))).as[RelayLegacyPayload]

  private val legacyFinalPerHopPayloadCodec: Codec[FinalLegacyPayload] = (
    ("realm" | constant(ByteVector.fromByte(0))) ::
      ("short_channel_id" | ignore(8 * 8)) ::
      ("amount" | millisatoshi) ::
      ("expiry" | cltvExpiry) ::
      ("unused_with_v0_version_on_header" | ignore(8 * 12))).as[FinalLegacyPayload]

  case class MissingRequiredTlv(tag: UInt64) extends Err {
    // @formatter:off
    val failureMessage: FailureMessage = InvalidOnionPayload(tag, 0)
    override def message = failureMessage.message
    override def context: List[String] = Nil
    override def pushContext(ctx: String): Err = this
    // @formatter:on
  }

  val channelRelayPerHopPayloadCodec: Codec[ChannelRelayPayload] = fallback(tlvPerHopPayloadCodec, legacyRelayPerHopPayloadCodec).narrow({
    case Left(tlvs) if tlvs.get[AmountToForward].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(2)))
    case Left(tlvs) if tlvs.get[OutgoingCltv].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case Left(tlvs) if tlvs.get[OutgoingChannelId].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(6)))
    case Left(tlvs) => Attempt.successful(ChannelRelayTlvPayload(tlvs))
    case Right(legacy) => Attempt.successful(legacy)
  }, {
    case legacy: RelayLegacyPayload => Right(legacy)
    case ChannelRelayTlvPayload(tlvs) => Left(tlvs)
  })

  val nodeRelayPerHopPayloadCodec: Codec[NodeRelayPayload] = tlvPerHopPayloadCodec.narrow({
    case tlvs if tlvs.get[AmountToForward].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(2)))
    case tlvs if tlvs.get[OutgoingCltv].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case tlvs if tlvs.get[OutgoingNodeId].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(66098)))
    case tlvs => Attempt.successful(NodeRelayPayload(tlvs))
  }, {
    case NodeRelayPayload(tlvs) => tlvs
  })

  val finalPerHopPayloadCodec: Codec[FinalPayload] = fallback(tlvPerHopPayloadCodec, legacyFinalPerHopPayloadCodec).narrow({
    case Left(tlvs) if tlvs.get[AmountToForward].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(2)))
    case Left(tlvs) if tlvs.get[OutgoingCltv].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case Left(tlvs) => Attempt.successful(FinalTlvPayload(tlvs))
    case Right(legacy) => Attempt.successful(legacy)
  }, {
    case legacy: FinalLegacyPayload => Right(legacy)
    case FinalTlvPayload(tlvs) => Left(tlvs)
  })

  def perHopPayloadCodecByPacketType[T <: PacketType](packetType: Sphinx.OnionRoutingPacket[T], isLastPacket: Boolean): Codec[PacketType] = packetType match {
    case Sphinx.PaymentPacket => if (isLastPacket) finalPerHopPayloadCodec.upcast[PacketType] else channelRelayPerHopPayloadCodec.upcast[PacketType]
    case Sphinx.TrampolinePacket => if (isLastPacket) finalPerHopPayloadCodec.upcast[PacketType] else nodeRelayPerHopPayloadCodec.upcast[PacketType]
  }

}