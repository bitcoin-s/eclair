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

package fr.acinq.eclair.integration

import java.util.UUID

import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.Features._
import fr.acinq.eclair.LongToBtcAmount
import fr.acinq.eclair.blockchain.{Watch, WatchConfirmed}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentRequest
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{NORMAL => _, State => _}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class PtlcPaymentIntegrationSpec extends IntegrationSpec {

  override def commonFeatures = ConfigFactory.parseMap(Map(
    s"eclair.features.${BasicMultiPartPayment.rfcName}" -> "disabled",
    s"eclair.features.${OptionDataLossProtect.rfcName}" -> "optional",
    s"eclair.features.${InitialRoutingSync.rfcName}" -> "optional",
    s"eclair.features.${ChannelRangeQueries.rfcName}" -> "optional",
    s"eclair.features.${ChannelRangeQueriesExtended.rfcName}" -> "optional",
    s"eclair.features.${VariableLengthOnion.rfcName}" -> "optional",
    s"eclair.features.${PTLC.rfcName}" -> "optional"
  ).asJava)

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.expiry-delta-blocks" -> 130, "eclair.server.port" -> 39730, "eclair.api.port" -> 38080, "eclair.channel-flags" -> 0, "eclair.trampoline-payments-enable" -> false).asJava).withFallback(commonFeatures).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.expiry-delta-blocks" -> 131, "eclair.server.port" -> 39731, "eclair.api.port" -> 38081, "eclair.trampoline-payments-enable" -> false).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.expiry-delta-blocks" -> 132, "eclair.server.port" -> 39732, "eclair.api.port" -> 38082, "eclair.trampoline-payments-enable" -> false).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.expiry-delta-blocks" -> 133, "eclair.server.port" -> 39733, "eclair.api.port" -> 38083, "eclair.trampoline-payments-enable" -> false).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.expiry-delta-blocks" -> 134, "eclair.server.port" -> 39734, "eclair.api.port" -> 38084, "eclair.trampoline-payments-enable" -> false).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("F1", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F1", "eclair.expiry-delta-blocks" -> 135, "eclair.server.port" -> 39735, "eclair.api.port" -> 38085, "eclair.trampoline-payments-enable" -> false).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("F2", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F2", "eclair.expiry-delta-blocks" -> 136, "eclair.server.port" -> 39736, "eclair.api.port" -> 38086, "eclair.trampoline-payments-enable" -> false).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("G", ConfigFactory.parseMap(Map("eclair.node-alias" -> "G", "eclair.expiry-delta-blocks" -> 137, "eclair.server.port" -> 39737, "eclair.api.port" -> 38087, "eclair.fee-base-msat" -> 1010, "eclair.fee-proportional-millionths" -> 102, "eclair.trampoline-payments-enable" -> false).asJava).withFallback(commonConfig))
  }

  test("connect nodes") {
    //       ,--G--,
    //      /       \
    // A---B ------- C ==== D
    //      \       /
    //       '--E--'

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 11000000 sat, 0 msat)
    connect(nodes("B"), nodes("C"), 2000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("B"), nodes("E"), 10000000 sat, 0 msat)
    connect(nodes("E"), nodes("C"), 10000000 sat, 0 msat)
    connect(nodes("B"), nodes("G"), 16000000 sat, 0 msat)
    connect(nodes("G"), nodes("C"), 16000000 sat, 0 msat)

    val numberOfChannels = 8
    val channelEndpointsCount = 2 * numberOfChannels

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      val watches = nodes.values.foldLeft(Set.empty[Watch]) {
        case (watches, setup) =>
          sender.send(setup.watcher, Symbol("watches"))
          watches ++ sender.expectMsgType[Set[Watch]]
      }
      watches.count(_.isInstanceOf[WatchConfirmed]) == channelEndpointsCount
    }, max = 20 seconds, interval = 1 second)

    // confirming the funding tx
    generateBlocks(bitcoincli, 2)

    within(60 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](30 seconds).currentState == NORMAL) count = count + 1
      }
    }
  }

  test("wait for network announcements") {
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    generateBlocks(bitcoincli, 4)
    // A requires private channels, as a consequence:
    // - only A and B know about channel A-B (and there is no channel_announcement)
    // - A is not announced (no node_announcement)
    awaitAnnouncements(nodes.filterKeys(key => List("A", "B").contains(key)).toMap, 5, 7, 16)
    awaitAnnouncements(nodes.filterKeys(key => List("C", "D", "E", "G").contains(key)).toMap, 5, 7, 14)
  }

  test("wait for channels balance") {
    // Channels balance should now be available in the router
    val sender = TestProbe()
    val nodeId = nodes("C").nodeParams.nodeId
    sender.send(nodes("C").router, Router.GetRoutingState)
    val routingState = sender.expectMsgType[Router.RoutingState]
    val publicChannels = routingState.channels.filter(pc => Set(pc.ann.nodeId1, pc.ann.nodeId2).contains(nodeId))
    assert(publicChannels.nonEmpty)
    publicChannels.foreach(pc => assert(pc.meta_opt.map(m => m.balance1 > 0.msat || m.balance2 > 0.msat) === Some(true), pc))
  }

  test("send a PTLC A->B") {
    val sender = TestProbe()
    val amountMsat = 3200000.msat
    // first we retrieve a payment point from D
    sender.send(nodes("B").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowPTLC)
    assert(pr.paymentPoint.isDefined)

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("B").nodeParams.nodeId, paymentRequest = Some(pr), fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)

    awaitCond(nodes("B").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))

    val incomingPayment_opt = nodes("B").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(incomingPayment_opt.nonEmpty)
    val incomingPayment = incomingPayment_opt.get
    assert(incomingPayment.paymentRequest === pr)
    assert(incomingPayment.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(incomingPayment.status.asInstanceOf[IncomingPaymentStatus.Received].amount === amountMsat)
  }

  test("send a PTLC A->D") {
    val sender = TestProbe()
    val amountMsat = 4200000.msat
    // first we retrieve a payment point from D
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowPTLC)
    assert(pr.paymentPoint.isDefined)

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, paymentRequest = Some(pr), fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)

    awaitCond(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))

    val incomingPayment_opt = nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(incomingPayment_opt.nonEmpty)
    val incomingPayment = incomingPayment_opt.get
    assert(incomingPayment.paymentRequest === pr)
    assert(incomingPayment.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(incomingPayment.status.asInstanceOf[IncomingPaymentStatus.Received].amount === amountMsat)
  }
}
