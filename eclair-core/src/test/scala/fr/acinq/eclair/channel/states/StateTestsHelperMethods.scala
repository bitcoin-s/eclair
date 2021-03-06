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

package fr.acinq.eclair.channel.states

import java.util.UUID

import akka.actor.ActorRef
import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, Crypto, ScriptFlags, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob, TestFeeEstimator}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeTargets
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{FeatureSupport, Features, NodeParams, TestConstants, randomBytes32, _}
import org.scalatest.{FixtureTestSuite, ParallelTestExecution}

import scala.concurrent.duration._

/**
 * Created by PM on 23/08/2016.
 */
trait StateTestsHelperMethods extends TestKitBase with FixtureTestSuite with ParallelTestExecution {

  case class SetupFixture(alice: TestFSMRef[State, Data, Channel],
                          bob: TestFSMRef[State, Data, Channel],
                          alice2bob: TestProbe,
                          bob2alice: TestProbe,
                          alice2blockchain: TestProbe,
                          bob2blockchain: TestProbe,
                          router: TestProbe,
                          relayerA: TestProbe,
                          relayerB: TestProbe,
                          channelUpdateListener: TestProbe,
                          wallet: EclairWallet) {
    def currentBlockHeight: Long = alice.underlyingActor.nodeParams.currentBlockHeight
  }

  def init(nodeParamsA: NodeParams = TestConstants.Alice.nodeParams, nodeParamsB: NodeParams = TestConstants.Bob.nodeParams, wallet: EclairWallet = new TestWallet): SetupFixture = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alicePeer = TestProbe()
    val bobPeer = TestProbe()
    TestUtils.forwardOutgoingToPipe(alicePeer, alice2bob.ref)
    TestUtils.forwardOutgoingToPipe(bobPeer, bob2alice.ref)
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val relayerA = TestProbe()
    val relayerB = TestProbe()
    val channelUpdateListener = TestProbe()
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])
    val router = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsA, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, relayerA.ref), alicePeer.ref)
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsB, wallet, Alice.nodeParams.nodeId, bob2blockchain.ref, relayerB.ref), bobPeer.ref)
    SetupFixture(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, router, relayerA, relayerB, channelUpdateListener, wallet)
  }

  def reachNormal(setup: SetupFixture, tags: Set[String] = Set.empty): Unit = {
    import setup._
    val channelFlags = if (tags.contains("channels_public")) ChannelFlags.AnnounceChannel else ChannelFlags.Empty
    val pushMsat = if (tags.contains("no_push_msat")) 0.msat else TestConstants.pushMsat
    val (aliceParams, bobParams, channelVersion) = if (tags.contains("anchor_outputs")) {
      val features = Features(Set(ActivatedFeature(Features.StaticRemoteKey, FeatureSupport.Mandatory), ActivatedFeature(Features.AnchorOutputs, FeatureSupport.Optional)))
      (Alice.channelParams.copy(features = features), Bob.channelParams.copy(features = features), ChannelVersion.ANCHOR_OUTPUTS)
    } else if (tags.contains("static_remotekey")) {
      val features = Features(Set(ActivatedFeature(Features.StaticRemoteKey, FeatureSupport.Optional)))
      val aliceParams = Alice.channelParams.copy(features = features, walletStaticPaymentBasepoint = Some(Helpers.getWalletPaymentBasepoint(wallet)))
      val bobParams = Bob.channelParams.copy(features = features, walletStaticPaymentBasepoint = Some(Helpers.getWalletPaymentBasepoint(wallet)))
      (aliceParams, bobParams, ChannelVersion.STATIC_REMOTEKEY)
    } else if (tags.contains("ptlc")) {
      val features = Features(Set(ActivatedFeature(Features.PTLC, FeatureSupport.Mandatory)))
      val aliceParams = Alice.channelParams.copy(features = features)
      val bobParams = Bob.channelParams.copy(features = features)
      (aliceParams, bobParams, ChannelVersion.PTLC)
    } else {
      (Alice.channelParams, Bob.channelParams, ChannelVersion.STANDARD)
    }

    val aliceInit = Init(aliceParams.features)
    val bobInit = Init(bobParams.features)
    alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, aliceParams, alice2bob.ref, bobInit, channelFlags, channelVersion)
    bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit, channelVersion)
    alice2bob.expectMsgType[OpenChannel]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[AcceptChannel]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchSpent]
    alice2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[WatchSpent]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42, fundingTx)
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42, fundingTx)
    alice2blockchain.expectMsgType[WatchLost]
    bob2blockchain.expectMsgType[WatchLost]
    alice2bob.expectMsgType[FundingLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchConfirmed] // deeply buried
    bob2blockchain.expectMsgType[WatchConfirmed] // deeply buried
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.availableBalanceForSend == (pushMsat - aliceParams.channelReserve).max(0 msat))
    // x2 because alice and bob share the same relayer
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
  }

  def localOrigin(replyTo: ActorRef): Origin.LocalHot = Origin.LocalHot(replyTo, UUID.randomUUID)

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = randomBytes32, upstream: Upstream = Upstream.Local(UUID.randomUUID), replyTo: ActorRef = TestProbe().ref): (ByteVector32, CMD_ADD_HTLC) = {
    val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage)
    val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
    val cmd = OutgoingPacket.buildCommand(replyTo, upstream, paymentHash, ChannelHop(null, destination, null) :: Nil, FinalLegacyPayload(amount, expiry))._1.copy(commit = false)
    (paymentPreimage, cmd)
  }

  def makeCmdAddPtlc(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPoint: PublicKey = randomKey.publicKey, pointTweak: PrivateKey = randomKey, nextPaymentPoint: PublicKey = randomKey.publicKey, nextPointTweak: PrivateKey = randomKey, upstream: Upstream = Upstream.Local(UUID.randomUUID), replyTo: ActorRef = ActorRef.noSender): (PublicKey, PrivateKey, CMD_ADD_PTLC) = {
    val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
    val payload = Onion.createSinglePartPayload(amount, expiry, None, Some(nextPointTweak))
    val cmd = OutgoingPacket.buildCommandPtlc(replyTo, upstream, paymentPoint, pointTweak, nextPaymentPoint, ChannelHop(null, destination, null) :: Nil, Seq(nextPointTweak), payload)._1.copy(commit = false)
    (nextPaymentPoint, nextPointTweak, cmd)
  }

  def addHtlc(amount: MilliSatoshi, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe, replyTo: ActorRef = TestProbe().ref): (ByteVector32, UpdateAddHtlc) = {
    val currentBlockHeight = s.underlyingActor.nodeParams.currentBlockHeight
    val (payment_preimage, cmd) = makeCmdAdd(amount, r.underlyingActor.nodeParams.nodeId, currentBlockHeight, replyTo = replyTo)
    val htlc = addHtlc(cmd, s, r, s2r, r2s)
    (payment_preimage, htlc)
  }

  def addHtlc(cmdAdd: CMD_ADD_HTLC, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): UpdateAddHtlc = {
    s ! cmdAdd
    val htlc = s2r.expectMsgType[UpdateAddHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(htlc))
    htlc
  }

  def addPtlc(amount: MilliSatoshi, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe, replyTo: ActorRef = ActorRef.noSender): (PublicKey, PrivateKey, UpdateAddPtlc) = {
    val currentBlockHeight = s.underlyingActor.nodeParams.currentBlockHeight
    val (payment_preimage, tweak, cmd) = makeCmdAddPtlc(amount, r.underlyingActor.nodeParams.nodeId, currentBlockHeight, replyTo = replyTo)
    val htlc = addPtlc(cmd, s, r, s2r, r2s)
    (payment_preimage, tweak, htlc)
  }

  def addPtlc(cmdAdd: CMD_ADD_PTLC, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): UpdateAddPtlc = {
    s ! cmdAdd
    val htlc = s2r.expectMsgType[UpdateAddPtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(htlc))
    htlc
  }

  def fulfillHtlc(id: Long, R: ByteVector32, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s ! CMD_FULFILL_HTLC(id, R)
    val fulfill = s2r.expectMsgType[UpdateFulfillHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(fulfill))
  }

  def crossSign(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    val sender = TestProbe()
    val sCommitIndex = s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rCommitIndex = r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rHasChanges = Commitments.localHasChanges(r.stateData.asInstanceOf[HasCommitments].commitments)
    s ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    s2r.expectMsgType[CommitSig]
    s2r.forward(r)
    r2s.expectMsgType[RevokeAndAck]
    r2s.forward(s)
    r2s.expectMsgType[CommitSig]
    r2s.forward(s)
    s2r.expectMsgType[RevokeAndAck]
    s2r.forward(r)
    if (rHasChanges) {
      s2r.expectMsgType[CommitSig]
      s2r.forward(r)
      r2s.expectMsgType[RevokeAndAck]
      r2s.forward(s)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == sCommitIndex + 1)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == sCommitIndex + 2)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == rCommitIndex + 2)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == rCommitIndex + 1)
    } else {
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == sCommitIndex + 1)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == sCommitIndex + 1)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == rCommitIndex + 1)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == rCommitIndex + 1)
    }
  }

  def crossSignPtlc(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    val sender = TestProbe()
    val sCommitIndex = s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rCommitIndex = r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rHasChanges = Commitments.localHasChanges(r.stateData.asInstanceOf[HasCommitments].commitments)
    s ! CMD_SIGN_PTLC(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN_PTLC]]
    s2r.expectMsgType[CommitSigPtlc]
    s2r.forward(r)
    r2s.expectMsgType[RevokeAndAck]
    r2s.forward(s)
    r2s.expectMsgType[CommitSigPtlc]
    r2s.forward(s)
    s2r.expectMsgType[RevokeAndAck]
    s2r.forward(r)
    if (rHasChanges) {
      s2r.expectMsgType[CommitSigPtlc]
      s2r.forward(r)
      r2s.expectMsgType[RevokeAndAck]
      r2s.forward(s)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == sCommitIndex + 1)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == sCommitIndex + 2)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == rCommitIndex + 2)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == rCommitIndex + 1)
    } else {
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == sCommitIndex + 1)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == sCommitIndex + 1)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == rCommitIndex + 1)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == rCommitIndex + 1)
    }
  }

  def mutualClose(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe, s2blockchain: TestProbe, r2blockchain: TestProbe): Unit = {
    val sender = TestProbe()
    // s initiates a closing
    s ! CMD_CLOSE(sender.ref, None)
    s2r.expectMsgType[Shutdown]
    s2r.forward(r)
    r2s.expectMsgType[Shutdown]
    r2s.forward(s)
    // agreeing on a closing fee
    var sCloseFee, rCloseFee = 0.sat
    do {
      sCloseFee = s2r.expectMsgType[ClosingSigned].feeSatoshis
      s2r.forward(r)
      rCloseFee = r2s.expectMsgType[ClosingSigned].feeSatoshis
      r2s.forward(s)
    } while (sCloseFee != rCloseFee)
    s2blockchain.expectMsgType[PublishAsap]
    s2blockchain.expectMsgType[WatchConfirmed]
    r2blockchain.expectMsgType[PublishAsap]
    r2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(s.stateName == CLOSING)
    awaitCond(r.stateName == CLOSING)
    // both nodes are now in CLOSING state with a mutual close tx pending for confirmation
  }

  def localClose(s: TestFSMRef[State, Data, Channel], s2blockchain: TestProbe): LocalCommitPublished = {
    // an error occurs and s publishes its commit tx
    val commitTx = s.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    s ! Error(ByteVector32.Zeroes, "oops")
    s2blockchain.expectMsg(PublishAsap(commitTx))
    awaitCond(s.stateName == CLOSING)
    val closingState = s.stateData.asInstanceOf[DATA_CLOSING]
    assert(closingState.localCommitPublished.isDefined)
    val localCommitPublished = closingState.localCommitPublished.get

    // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
    localCommitPublished.claimMainDelayedOutputTx.foreach(tx => s2blockchain.expectMsg(PublishAsap(tx)))
    // all htlcs success/timeout should be published
    s2blockchain.expectMsgAllOf((localCommitPublished.htlcSuccessTxs ++ localCommitPublished.htlcTimeoutTxs).map(PublishAsap): _*)
    // and their outputs should be claimed
    s2blockchain.expectMsgAllOf(localCommitPublished.claimHtlcDelayedTxs.map(PublishAsap): _*)

    // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
    assert(s2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(commitTx))
    localCommitPublished.claimMainDelayedOutputTx.foreach(tx => assert(s2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx)))
    assert(localCommitPublished.claimHtlcDelayedTxs.map(_ => s2blockchain.expectMsgType[WatchConfirmed].event).toSet === localCommitPublished.claimHtlcDelayedTxs.map(BITCOIN_TX_CONFIRMED).toSet)

    // we watch outputs of the commitment tx that both parties may spend
    val htlcOutputIndexes = (localCommitPublished.htlcSuccessTxs ++ localCommitPublished.htlcTimeoutTxs).map(tx => tx.txIn.head.outPoint.index)
    val spentWatches = htlcOutputIndexes.map(_ => s2blockchain.expectMsgType[WatchSpent])
    spentWatches.foreach(ws => assert(ws.event === BITCOIN_OUTPUT_SPENT))
    spentWatches.foreach(ws => assert(ws.txId === commitTx.txid))
    assert(spentWatches.map(_.outputIndex).toSet === htlcOutputIndexes.toSet)
    s2blockchain.expectNoMsg(1 second)

    // s is now in CLOSING state with txes pending for confirmation before going in CLOSED state
    s.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
  }

  def remoteClose(rCommitTx: Transaction, s: TestFSMRef[State, Data, Channel], s2blockchain: TestProbe): RemoteCommitPublished = {
    // we make s believe r unilaterally closed the channel
    s ! WatchEventSpent(BITCOIN_FUNDING_SPENT, rCommitTx)
    awaitCond(s.stateName == CLOSING)
    val closingData = s.stateData.asInstanceOf[DATA_CLOSING]

    def getRemoteCommitPublished(d: DATA_CLOSING): Option[RemoteCommitPublished] = d.remoteCommitPublished.orElse(d.nextRemoteCommitPublished).orElse(d.futureRemoteCommitPublished)

    assert(getRemoteCommitPublished(closingData).isDefined)
    assert(closingData.localCommitPublished.isEmpty)
    val remoteCommitPublished = getRemoteCommitPublished(closingData).get

    // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
    remoteCommitPublished.claimMainOutputTx.foreach(tx => {
      Transaction.correctlySpends(tx, rCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      s2blockchain.expectMsg(PublishAsap(tx))
    })
    // all htlcs success/timeout should be claimed
    val claimHtlcTxes = remoteCommitPublished.claimHtlcSuccessTxs ++ remoteCommitPublished.claimHtlcTimeoutTxs
    claimHtlcTxes.foreach(tx => Transaction.correctlySpends(tx, rCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    s2blockchain.expectMsgAllOf(claimHtlcTxes.map(PublishAsap): _*)

    // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
    assert(s2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(rCommitTx))
    remoteCommitPublished.claimMainOutputTx.foreach(tx => assert(s2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx)))

    // we watch outputs of the commitment tx that both parties may spend
    val htlcOutputIndexes = claimHtlcTxes.map(tx => tx.txIn.head.outPoint.index)
    val spentWatches = htlcOutputIndexes.map(_ => s2blockchain.expectMsgType[WatchSpent])
    spentWatches.foreach(ws => assert(ws.event === BITCOIN_OUTPUT_SPENT))
    spentWatches.foreach(ws => assert(ws.txId === rCommitTx.txid))
    assert(spentWatches.map(_.outputIndex).toSet === htlcOutputIndexes.toSet)
    s2blockchain.expectNoMsg(1 second)

    // s is now in CLOSING state with txes pending for confirmation before going in CLOSED state
    getRemoteCommitPublished(s.stateData.asInstanceOf[DATA_CLOSING]).get
  }

  def channelId(a: TestFSMRef[State, Data, Channel]): ByteVector32 = a.stateData.channelId

  // @formatter:off
  implicit class ChannelWithTestFeeConf(a: TestFSMRef[State, Data, Channel]) {
    def feeEstimator: TestFeeEstimator = a.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator]
    def feeTargets: FeeTargets = a.underlyingActor.nodeParams.onChainFeeConf.feeTargets
  }

  implicit class PeerWithTestFeeConf(a: TestFSMRef[Peer.State, Peer.Data, Peer]) {
    def feeEstimator: TestFeeEstimator = a.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator]
    def feeTargets: FeeTargets = a.underlyingActor.nodeParams.onChainFeeConf.feeTargets
  }
  // @formatter:on

}
