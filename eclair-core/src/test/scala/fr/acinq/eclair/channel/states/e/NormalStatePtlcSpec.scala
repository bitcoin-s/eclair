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

package fr.acinq.eclair.channel.states.e

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, ScriptFlags, Transaction}
import fr.acinq.eclair.Features.StaticRemoteKey
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel.Channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{ChannelErrorOccurred, _}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.DirectedTlc._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.{DefaultCommitmentFormat, HtlcSuccessTx, weight2fee}
import fr.acinq.eclair.wire.{AnnouncementSignatures, ChannelUpdate, ClosingSigned, CommitSig, Error, FailureMessageCodecs, PermanentChannelFailure, RevokeAndAck, Shutdown, UpdateAddHtlc, UpdateAddPtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFee, UpdateFulfillHtlc}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits._

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class NormalStatePtlcSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(nodeParamsA = TestConstants.AlicePtlc.nodeParams, nodeParamsB = TestConstants.BobPtlc.nodeParams)
    import setup._
    within(30 seconds) {
      reachNormal(setup, test.tags + "ptlc")
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("recv CMD_ADD_PTLC (empty origin)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[AvailableBalanceChanged])
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 50000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    val e = listener.expectMsgType[AvailableBalanceChanged]
    assert(e.commitments.availableBalanceForSend < initialState.commitments.availableBalanceForSend)
    val ptlc = alice2bob.expectMsgType[UpdateAddPtlc]
    val h =  Crypto.sha256((pp + tw.publicKey).value)
    assert(ptlc.id == 0 && ptlc.paymentHash == h)
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localNextHtlcId = 1,
        localChanges = initialState.commitments.localChanges.copy(proposed = ptlc :: Nil),
        originChannels = Map(0L -> add.origin),
        ptlcKeys = Map(0L -> add.ptlcKeys)
      )))
  }

  test("recv CMD_ADD_PTLC (incrementing ids)") { f =>
    import f._
    val sender = TestProbe()
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    for (i <- 0 until 10) {
      alice ! CMD_ADD_PTLC(sender.ref, 50000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
      val ptlc = alice2bob.expectMsgType[UpdateAddPtlc]
      val h =  Crypto.sha256((pp + tw.publicKey).value)
      assert(ptlc.id == i && ptlc.paymentHash == h)
    }
  }

  test("recv CMD_ADD_PTLC (relayed ptlc)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val originHtlc = UpdateAddPtlc(channelId = randomBytes32, id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentPoint = pp, onionRoutingPacket = TestConstants.emptyOnionPacket)
    val origin = Origin.ChannelRelayedHot(sender.ref, originHtlc, originHtlc.amountMsat)
    val cmd = CMD_ADD_PTLC(sender.ref, originHtlc.amountMsat - 10000.msat, PtlcKeys(pp, tw), pp + tw.publicKey, originHtlc.cltvExpiry - CltvExpiryDelta(7), TestConstants.emptyOnionPacket, origin)
    alice ! cmd
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    val ptlc = alice2bob.expectMsgType[UpdateAddPtlc]
    val h =  Crypto.sha256((pp + tw.publicKey).value)
    assert(ptlc.id == 0 && ptlc.paymentHash == h)
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localNextHtlcId = 1,
        localChanges = initialState.commitments.localChanges.copy(proposed = ptlc :: Nil),
        originChannels = Map(0L -> cmd.origin),
        ptlcKeys = Map(0L -> cmd.ptlcKeys)
      )))
  }

  test("recv CMD_ADD_PTLC (expiry too small)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    // It's usually dangerous for Bob to accept HTLCs that are expiring soon. However it's not Alice's decision to reject
    // them when she's asked to relay; she should forward those HTLCs to Bob, and Bob will choose whether to fail them
    // or fulfill them (Bob could be #reckless and fulfill HTLCs with a very low expiry delta).
    val expiryTooSmall = CltvExpiry(currentBlockHeight + 3)
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 500000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, expiryTooSmall, TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    val htlc = alice2bob.expectMsgType[UpdateAddPtlc]
    assert(htlc.id === 0)
    assert(htlc.cltvExpiry === expiryTooSmall)
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localNextHtlcId = 1,
        localChanges = initialState.commitments.localChanges.copy(proposed = htlc :: Nil),
        originChannels = Map(0L -> add.origin),
        ptlcKeys = Map(0L -> add.ptlcKeys)
      )))
  }

  test("recv CMD_ADD_PTLC (expiry too big)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val expiryTooBig = (Channel.MAX_CLTV_EXPIRY_DELTA + 1).toCltvExpiry(currentBlockHeight)
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 500000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, expiryTooBig, TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = ExpiryTooBig(channelId(alice), maximum = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(currentBlockHeight), actual = expiryTooBig, blockCount = currentBlockHeight)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (value too small)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 50 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = HtlcValueTooSmall(channelId(alice), 1000 msat, 50 msat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (0 msat)") { f =>
    import f._
    val sender = TestProbe()
    // Alice has a minimum set to 0 msat (which should be invalid, but may mislead Bob into relaying 0-value HTLCs which is forbidden by the spec).
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localParams.htlcMinimum === 0.msat)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 0 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    bob ! add
    val error = HtlcValueTooSmall(channelId(bob), 1 msat, 0 msat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (increasing balance but still below reserve)", Tag("no_push_msat")) { f =>
    import f._
    val sender = TestProbe()
    // channel starts with all funds on alice's side, alice sends some funds to bob, but not enough to make it go above reserve
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 50000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
  }

  test("recv CMD_ADD_PTLC (insufficient funds)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, MilliSatoshi(Int.MaxValue), PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = InsufficientFunds(channelId(alice), amount = MilliSatoshi(Int.MaxValue), missing = 1388843 sat, reserve = 20000 sat, fees = 8960 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (insufficient funds) (anchor outputs)", Tag("anchor_outputs")) { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    // The anchor outputs commitment format costs more fees for the funder (bigger commit tx + cost of anchor outputs)
    assert(initialState.commitments.availableBalanceForSend < initialState.commitments.copy(channelVersion = ChannelVersion.PTLC).availableBalanceForSend)
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, initialState.commitments.availableBalanceForSend + 1.msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add

    val error = InsufficientFunds(channelId(alice), amount = add.amount, missing = 0 sat, reserve = 20000 sat, fees = 13620 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (insufficient funds, missing 1 msat)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, initialState.commitments.availableBalanceForSend + 1.msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    bob ! add

    val error = InsufficientFunds(channelId(alice), amount = add.amount, missing = 0 sat, reserve = 10000 sat, fees = 0 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (PTLC dips into remote funder fee reserve)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(758640000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.availableBalanceForSend === 0.msat)

    // actual test begins
    // at this point alice has the minimal amount to sustain a channel
    // alice maintains an extra reserve to accommodate for a few more HTLCs, so the first two HTLCs should be allowed
    for (_ <- 1 to 7) {
      val ps = randomKey
      val pp = ps.publicKey
      val tw = randomKey
      bob ! CMD_ADD_PTLC(sender.ref, 12000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiry(400144), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    }

    // but this one will dip alice below her reserve: we must wait for the two previous HTLCs to settle before sending any more
    val ps = randomKey
    val pp = ps.publicKey
    val tw = randomKey
    val failedAdd = CMD_ADD_PTLC(sender.ref, 11000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiry(400144), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    bob ! failedAdd
    val error = RemoteCannotAffordFeesForNewHtlc(channelId(bob), failedAdd.amount, missing = 1360 sat, 10000 sat, 22720 sat)
    sender.expectMsg(RES_ADD_FAILED(failedAdd, error, Some(bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate)))
  }

  test("recv CMD_ADD_PTLC (insufficient funds w/ pending ptlcs and 0 balance)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]

    {
      val ps = randomKey
      val pp = randomKey.publicKey
      val tw = randomKey
      alice ! CMD_ADD_PTLC(sender.ref, 500000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
      alice2bob.expectMsgType[UpdateAddPtlc]
    }
    {
      val ps = randomKey
      val pp = randomKey.publicKey
      val tw = randomKey
      alice ! CMD_ADD_PTLC(sender.ref, 200000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
      alice2bob.expectMsgType[UpdateAddPtlc]
    }
    {
      val ps = randomKey
      val pp = randomKey.publicKey
      val tw = randomKey
      alice ! CMD_ADD_PTLC(sender.ref, 51760000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
      alice2bob.expectMsgType[UpdateAddPtlc]
    }
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 1000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = InsufficientFunds(channelId(alice), amount = 1000000 msat, missing = 1000 sat, reserve = 20000 sat, fees = 12400 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (insufficient funds w/ pending ptlcs 2/2)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]

    {
      val ps = randomKey
      val pp = randomKey.publicKey
      val tw = randomKey
      alice ! CMD_ADD_PTLC(sender.ref, 300000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
      alice2bob.expectMsgType[UpdateAddPtlc]
    }
    {
      val ps = randomKey
      val pp = randomKey.publicKey
      val tw = randomKey
      alice ! CMD_ADD_PTLC(sender.ref, 300000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
      alice2bob.expectMsgType[UpdateAddPtlc]
    }
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 500000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = InsufficientFunds(channelId(alice), amount = 500000000 msat, missing = 348240 sat, reserve = 20000 sat, fees = 12400 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (over max inflight ptlc value)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 151000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    bob ! add
    val error = HtlcValueTooHighInFlight(channelId(bob), maximum = 150000000, actual = 151000000 msat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (over max inflight ptlc value with duplicate amounts)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    {
      val ps = randomKey
      val pp = randomKey.publicKey
      val tw = randomKey
      val add = CMD_ADD_PTLC(sender.ref, 75500000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      bob ! add
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
      bob2alice.expectMsgType[UpdateAddPtlc]
    }

    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add1 = CMD_ADD_PTLC(sender.ref, 75500000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    bob ! add1
    val error = HtlcValueTooHighInFlight(channelId(bob), maximum = 150000000, actual = 151000000 msat)
    sender.expectMsg(RES_ADD_FAILED(add1, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (over max accepted htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    // Bob accepts a maximum of 30 htlcs
    for (i <- 0 until 30) {
      val ps = randomKey
      val pp = randomKey.publicKey
      val tw = randomKey
      alice ! CMD_ADD_PTLC(sender.ref, 10000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
      alice2bob.expectMsgType[UpdateAddPtlc]
    }
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 10000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = TooManyAcceptedHtlcs(channelId(alice), maximum = 30)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (over capacity)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]

    {
      val ps = randomKey
      val pp = randomKey.publicKey
      val tw = randomKey
      val add1 = CMD_ADD_PTLC(sender.ref, TestConstants.fundingSatoshis.toMilliSatoshi * 2 / 3, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      alice ! add1
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
      alice2bob.expectMsgType[UpdateAddPtlc]
      alice ! CMD_SIGN()
      alice2bob.expectMsgType[CommitSig]
    }
    // this is over channel-capacity
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add2 = CMD_ADD_PTLC(sender.ref, TestConstants.fundingSatoshis.toMilliSatoshi * 2 / 3, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add2
    val error = InsufficientFunds(channelId(alice), add2.amount, 578133 sat, 20000 sat, 10680 sat)
    sender.expectMsg(RES_ADD_FAILED(add2, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (channel feerate mismatch)") { f =>
    import f._

    val sender = TestProbe()
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(20000 sat)))
    bob ! CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(20000 sat)))
    bob2alice.expectNoMsg(100 millis) // we don't close because the commitment doesn't contain any HTLC

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val upstream = localOrigin(sender.ref)
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 500000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, upstream)
    bob ! add
    val error = FeerateTooDifferent(channelId(bob), FeeratePerKw(20000 sat), FeeratePerKw(10000 sat))
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMsg(100 millis) // we don't close the channel, we can simply avoid using it while we disagree on feerate

    // we now agree on feerate so we can send HTLCs
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(11000 sat)))
    bob ! CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(11000 sat)))
    bob2alice.expectNoMsg(100 millis)
    bob ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    bob2alice.expectMsgType[UpdateAddPtlc]
  }

  test("recv CMD_ADD_PTLC (after having sent Shutdown)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined && alice.stateData.asInstanceOf[DATA_NORMAL].remoteShutdown.isEmpty)

    // actual test starts here
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 500000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = NoMoreHtlcsClosingInProgress(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_PTLC (after having received Shutdown)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]

    {
      // let's make alice send an htlc
      val ps = randomKey
      val pp = ps.publicKey
      val tw = randomKey
      val add1 = CMD_ADD_PTLC(sender.ref, 500000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
      alice ! add1
    }

    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    // at the same time bob initiates a closing
    bob ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    // this command will be received by alice right after having received the shutdown
    val ps = randomKey
    val pp = ps.publicKey
    val tw = randomKey
    val add2 = CMD_ADD_PTLC(sender.ref, 100000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiry(300000), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    // messages cross
    alice2bob.expectMsgType[UpdateAddPtlc]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    alice ! add2
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add2, error, None))
  }

  test("recv UpdateAddPtlc") { f =>
    import f._
    val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val ptlc = UpdateAddPtlc(ByteVector32.Zeroes, 0, 150000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket)
    bob ! ptlc
    awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ ptlc), remoteNextHtlcId = 1)))
    // bob won't forward the add before it is cross-signed
    relayerB.expectNoMsg()
  }

  test("recv UpdateAddPtlc (unexpected id)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val ptlc = UpdateAddPtlc(ByteVector32.Zeroes, 42, 150000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket)
    bob ! ptlc.copy(id = 0)
    bob ! ptlc.copy(id = 1)
    bob ! ptlc.copy(id = 2)
    bob ! ptlc.copy(id = 3)
    bob ! ptlc.copy(id = 42)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === UnexpectedHtlcId(channelId(bob), expected = 4, actual = 42).getMessage)
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddPtlc (value too small)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val ptlc = UpdateAddPtlc(ByteVector32.Zeroes, 0, 150 msat, randomKey.publicKey, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket)
    alice2bob.forward(bob, ptlc)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === HtlcValueTooSmall(channelId(bob), minimum = 1000 msat, actual = 150 msat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddPtlc (insufficient funds)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val ptlc = UpdateAddPtlc(ByteVector32.Zeroes, 0, MilliSatoshi(Long.MaxValue), randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket)
    alice2bob.forward(bob, ptlc)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === InsufficientFunds(channelId(bob), amount = MilliSatoshi(Long.MaxValue), missing = 9223372036083735L sat, reserve = 20000 sat, fees = 8960 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddPtlc (insufficient funds w/ pending ptlcs) (anchor outputs)", Tag("anchor_outputs")) { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 0, 400000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 1, 300000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 2, 100000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === InsufficientFunds(channelId(bob), amount = 100000000 msat, missing = 37060 sat, reserve = 20000 sat, fees = 17060 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddPtlc (insufficient funds w/ pending ptlcs 1/2)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 0, 400000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 1, 200000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 2, 167600000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 3, 10000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === InsufficientFunds(channelId(bob), amount = 10000000 msat, missing = 11720 sat, reserve = 20000 sat, fees = 14120 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddPtlc (insufficient funds w/ pending ptlcs 2/2)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 0, 300000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 1, 300000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 2, 500000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === InsufficientFunds(channelId(bob), amount = 500000000 msat, missing = 332400 sat, reserve = 20000 sat, fees = 12400 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddPtlc (over max inflight ptlc value)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice2bob.forward(alice, UpdateAddPtlc(ByteVector32.Zeroes, 0, 151000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) === HtlcValueTooHighInFlight(channelId(alice), maximum = 150000000, actual = 151000000 msat).getMessage)
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddPtlc (over max accepted ptlcs)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    // Bob accepts a maximum of 30 htlcs
    for (i <- 0 until 30) {
      alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, i, 1000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    }
    alice2bob.forward(bob, UpdateAddPtlc(ByteVector32.Zeroes, 30, 1000000 msat, randomKey.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === TooManyAcceptedHtlcs(channelId(bob), maximum = 30).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CMD_SIGN") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    val commitSig = alice2bob.expectMsgType[CommitSig]
    assert(commitSig.htlcSignatures.size == 1)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
  }

  test("recv CMD_SIGN (two identical htlcs in each direction)") { f =>
    import f._
    val sender = TestProbe()
    val ps = randomKey
    val pp = ps.publicKey
    val tw = randomKey
    val add = CMD_ADD_PTLC(sender.ref, 10000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    alice2bob.expectMsgType[UpdateAddPtlc]
    alice2bob.forward(bob)
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    alice2bob.expectMsgType[UpdateAddPtlc]
    alice2bob.forward(bob)

    crossSign(alice, bob, alice2bob, bob2alice)

    bob ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    bob2alice.expectMsgType[UpdateAddPtlc]
    bob2alice.forward(alice)
    bob ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    bob2alice.expectMsgType[UpdateAddPtlc]
    bob2alice.forward(alice)

    // actual test starts here
    bob ! CMD_SIGN()
    val commitSig = bob2alice.expectMsgType[CommitSig]
    assert(commitSig.htlcSignatures.toSet.size == 4)
  }

  ignore("recv CMD_SIGN (check ptlc info are persisted)") { f =>
    import f._
    val sender = TestProbe()
    // for the test to be really useful we have constraint on parameters
    assert(Alice.nodeParams.dustLimit > Bob.nodeParams.dustLimit)
    // we're gonna exchange two htlcs in each direction, the goal is to have bob's commitment have 4 htlcs, and alice's
    // commitment only have 3. We will then check that alice indeed persisted 4 htlcs, and bob only 3.
    val aliceMinReceive = Alice.nodeParams.dustLimit + weight2fee(TestConstants.feeratePerKw, DefaultCommitmentFormat.htlcSuccessWeight)
    val aliceMinOffer = Alice.nodeParams.dustLimit + weight2fee(TestConstants.feeratePerKw, DefaultCommitmentFormat.htlcTimeoutWeight)
    val bobMinReceive = Bob.nodeParams.dustLimit + weight2fee(TestConstants.feeratePerKw, DefaultCommitmentFormat.htlcSuccessWeight)
    val bobMinOffer = Bob.nodeParams.dustLimit + weight2fee(TestConstants.feeratePerKw, DefaultCommitmentFormat.htlcTimeoutWeight)
    val a2b_1 = bobMinReceive + 10.sat // will be in alice and bob tx
    val a2b_2 = bobMinReceive + 20.sat // will be in alice and bob tx
    val b2a_1 = aliceMinReceive + 10.sat // will be in alice and bob tx
    val b2a_2 = bobMinOffer + 10.sat // will be only be in bob tx
    assert(a2b_1 > aliceMinOffer && a2b_1 > bobMinReceive)
    assert(a2b_2 > aliceMinOffer && a2b_2 > bobMinReceive)
    assert(b2a_1 > aliceMinReceive && b2a_1 > bobMinOffer)
    assert(b2a_2 < aliceMinReceive && b2a_2 > bobMinOffer)
    alice ! CMD_ADD_HTLC(sender.ref, a2b_1.toMilliSatoshi, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice ! CMD_ADD_HTLC(sender.ref, a2b_2.toMilliSatoshi, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    bob ! CMD_ADD_HTLC(sender.ref, b2a_1.toMilliSatoshi, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)
    bob ! CMD_ADD_HTLC(sender.ref, b2a_2.toMilliSatoshi, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)

    // actual test starts here
    crossSign(alice, bob, alice2bob, bob2alice)
    // depending on who starts signing first, there will be one or two commitments because both sides have changes
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.index === 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.index === 2)
    assert(alice.underlyingActor.nodeParams.db.channels.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 0).size == 0)
    assert(alice.underlyingActor.nodeParams.db.channels.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 1).size == 2)
    assert(alice.underlyingActor.nodeParams.db.channels.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 2).size == 4)
    assert(bob.underlyingActor.nodeParams.db.channels.listHtlcInfos(bob.stateData.asInstanceOf[DATA_NORMAL].channelId, 0).size == 0)
    assert(bob.underlyingActor.nodeParams.db.channels.listHtlcInfos(bob.stateData.asInstanceOf[DATA_NORMAL].channelId, 1).size == 3)
  }

  ignore("recv CMD_SIGN (htlcs with same pubkeyScript but different amounts)") { f =>
    import f._
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 10000000 msat, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    val epsilons = List(3, 1, 5, 7, 6) // unordered on purpose
    val htlcCount = epsilons.size
    for (i <- epsilons) {
      alice ! add.copy(amount = add.amount + (i * 1000).msat)
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
      alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)
    }
    // actual test starts here
    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    val commitSig = alice2bob.expectMsgType[CommitSig]
    assert(commitSig.htlcSignatures.toSet.size == htlcCount)
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == htlcCount)
    val htlcTxs = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs
    val amounts = htlcTxs.map(_.txinfo.tx.txOut.head.amount.toLong)
    assert(amounts === amounts.sorted)
  }

  ignore("recv CMD_SIGN (no changes)") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_SIGN()
    sender.expectNoMsg(1 second) // just ignored
    //sender.expectMsg("cannot sign when there are no changes")
  }

  test("recv CMD_SIGN (while waiting for RevokeAndAck (no pending changes)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    val waitForRevocation = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.left.toOption.get
    assert(waitForRevocation.reSignAsap === false)

    // actual test starts here
    alice ! CMD_SIGN()
    sender.expectNoMsg(300 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo === Left(waitForRevocation))
  }

  test("recv CMD_SIGN (while waiting for RevokeAndAck (with pending changes)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    val waitForRevocation = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.left.toOption.get
    assert(waitForRevocation.reSignAsap === false)

    // actual test starts here
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    sender.expectNoMsg(300 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo === Left(waitForRevocation.copy(reSignAsap = true)))
  }

  ignore("recv CMD_SIGN (going above reserve)", Tag("no_push_msat")) { f =>
    import f._
    val sender = TestProbe()
    // channel starts with all funds on alice's side, so channel will be initially disabled on bob's side
    assert(Announcements.isEnabled(bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.channelFlags) === false)
    // alice will send enough funds to bob to make it go above reserve
    val (r, _, htlc) = addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FULFILL_HTLC(htlc.id, ByteVector32(r.value))
    sender.expectMsgType[RES_SUCCESS[CMD_FULFILL_HTLC]]
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    // we listen to channel_update events
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[LocalChannelUpdate])

    // actual test starts here
    // when signing the fulfill, bob will have its main output go above reserve in alice's commitment tx
    bob ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]
    // it should update its channel_update
    awaitCond(Announcements.isEnabled(bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.channelFlags))
    // and broadcast it
    assert(listener.expectMsgType[LocalChannelUpdate].channelUpdate === bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate)
  }

  ignore("recv CMD_SIGN (after CMD_UPDATE_FEE)") { f =>
    import f._
    val sender = TestProbe()
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[AvailableBalanceChanged])
    alice ! CMD_UPDATE_FEE(FeeratePerKw(654564 sat))
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_FEE]]
    alice2bob.expectMsgType[UpdateFee]
    alice ! CMD_SIGN()
    listener.expectMsgType[AvailableBalanceChanged]
  }

  test("recv CommitSig (one ptlc received)") { f =>
    import f._
    val sender = TestProbe()

    val (_, _, ptlc) = addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    alice ! CMD_SIGN()

    // actual test begins
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    bob2alice.expectMsgType[RevokeAndAck]
    // bob replies immediately with a signature
    bob2alice.expectMsgType[CommitSig]

    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.collect(incoming).exists(_.id == ptlc.id))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toLocal == initialState.commitments.localCommit.spec.toLocal)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.acked.size == 0)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.signed.size == 1)
  }

  test("recv CommitSig (one ptlc sent)") { f =>
    import f._
    val sender = TestProbe()

    val (_, _, ptlc) = addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // actual test begins (note that channel sends a CMD_SIGN to itself when it receives RevokeAndAck and there are changes)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.collect(outgoing).exists(_.id == ptlc.id))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toLocal == initialState.commitments.localCommit.spec.toLocal)
  }

  test("recv CommitSig (multiple htlcs in both directions)") { f =>
    import f._
    val sender = TestProbe()

    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)

    addPtlc(8000000 msat, alice, bob, alice2bob, bob2alice) //  a->b (regular)

    addPtlc(300000 msat, bob, alice, bob2alice, alice2bob) //   b->a (dust)

    addPtlc(1000000 msat, alice, bob, alice2bob, bob2alice) //  a->b (regular)

    addPtlc(50000000 msat, bob, alice, bob2alice, alice2bob) // b->a (regular)

    addPtlc(500000 msat, alice, bob, alice2bob, bob2alice) //   a->b (dust)

    addPtlc(4000000 msat, bob, alice, bob2alice, alice2bob) //  b->a (regular)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // actual test begins
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.index == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 3)
  }

  ignore("recv CommitSig (only fee update)") { f =>
    import f._
    val sender = TestProbe()

    alice ! CMD_UPDATE_FEE(TestConstants.feeratePerKw + FeeratePerKw(1000 sat), commit = false)
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_FEE]]
    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]

    // actual test begins (note that channel sends a CMD_SIGN to itself when it receives RevokeAndAck and there are changes)
    val updateFee = alice2bob.expectMsgType[UpdateFee]
    assert(updateFee.feeratePerKw === TestConstants.feeratePerKw + FeeratePerKw(1000 sat))
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
  }

  test("recv CommitSig (two htlcs received with same r)") { f =>
    import f._
    val sender = TestProbe()
    val ps = randomKey
    val pp = randomKey.publicKey
    val tw = randomKey

    alice ! CMD_ADD_PTLC(sender.ref, 50000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    val htlc1 = alice2bob.expectMsgType[UpdateAddPtlc]
    alice2bob.forward(bob)

    alice ! CMD_ADD_PTLC(sender.ref, 50000000 msat, PtlcKeys(pp, tw), pp + tw.publicKey, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_PTLC]]
    val htlc2 = alice2bob.expectMsgType[UpdateAddPtlc]
    alice2bob.forward(bob)

    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == htlc1 :: htlc2 :: Nil)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    crossSign(alice, bob, alice2bob, bob2alice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.collect(incoming).exists(_.id == htlc1.id))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toLocal == initialState.commitments.localCommit.spec.toLocal)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx.txOut.count(_.amount == 50000.sat) == 2)
  }

  ignore("recv CommitSig (no changes)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    // signature is invalid but it doesn't matter
    bob ! CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, Nil)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("cannot sign when there are no changes"))
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CommitSig (invalid signature)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    // actual test begins
    bob ! CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, Nil)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid commitment signature"))
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CommitSig (bad ptlc sig count)") { f =>
    import f._
    val sender = TestProbe()

    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    alice ! CMD_SIGN()
    val commitSig = alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val badCommitSig = commitSig.copy(htlcSignatures = commitSig.htlcSignatures ::: commitSig.htlcSignatures)
    bob ! badCommitSig
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === HtlcSigCountMismatch(channelId(bob), expected = 1, actual = 2).getMessage)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CommitSig (invalid ptlc sig)") { f =>
    import f._
    val sender = TestProbe()

    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    alice ! CMD_SIGN()
    val commitSig = alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val badCommitSig = commitSig.copy(htlcSignatures = commitSig.signature :: Nil)
    bob ! badCommitSig
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid htlc signature"))
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv RevokeAndAck (one ptlc sent)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // actual test begins
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localChanges.acked.size == 1)
  }

  ignore("recv RevokeAndAck (one ptlc received)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    // at this point bob still hasn't forwarded the ptlc downstream
    relayerB.expectNoMsg()

    // actual test begins
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    // now bob will forward the ptlc downstream
    val forward = relayerB.expectMsgType[RelayForward]
    assert(forward.add === htlc)
  }

  ignore("recv RevokeAndAck (multiple htlcs in both directions)") { f =>
    import f._
    val sender = TestProbe()
    val (r1, htlc1) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)

    val (r2, htlc2) = addHtlc(8000000 msat, alice, bob, alice2bob, bob2alice) //  a->b (regular)

    val (r3, htlc3) = addHtlc(300000 msat, bob, alice, bob2alice, alice2bob) //   b->a (dust)

    val (r4, htlc4) = addHtlc(1000000 msat, alice, bob, alice2bob, bob2alice) //  a->b (regular)

    val (r5, htlc5) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob) // b->a (regular)

    val (r6, htlc6) = addHtlc(500000 msat, alice, bob, alice2bob, bob2alice) //   a->b (dust)

    val (r7, htlc7) = addHtlc(4000000 msat, bob, alice, bob2alice, alice2bob) //  b->a (regular)

    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    // actual test begins
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)

    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.index == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.spec.htlcs.size == 7)
  }

  ignore("recv RevokeAndAck (with reSignAsap=true)") { f =>
    import f._
    val sender = TestProbe()
    val (r1, htlc1) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    val (r2, htlc2) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    sender.expectNoMsg(300 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.left.toOption.get.reSignAsap === true)

    // actual test starts here
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
  }

  ignore("recv RevokeAndAck (invalid preimage)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    alice ! RevokeAndAck(ByteVector32.Zeroes, PrivateKey(randomBytes32), PrivateKey(randomBytes32).publicKey)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv RevokeAndAck (unexpectedly)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    alice ! RevokeAndAck(ByteVector32.Zeroes, PrivateKey(randomBytes32), PrivateKey(randomBytes32).publicKey)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv RevokeAndAck (forward UpdateFailHtlc)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure))
    sender.expectMsgType[RES_SUCCESS[CMD_FAIL_HTLC]]
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice still hasn't forwarded the fail because it is not yet cross-signed
    relayerA.expectNoMsg()

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // alice will forward the fail upstream
    val forward = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFail]]
    assert(forward.result.fail === fail)
    assert(forward.htlc === htlc)
  }

  ignore("recv RevokeAndAck (forward UpdateFailMalformedHtlc)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FAIL_MALFORMED_HTLC(htlc.id, Sphinx.PaymentPacket.hash(htlc.onionRoutingPacket), FailureMessageCodecs.BADONION)
    sender.expectMsgType[RES_SUCCESS[CMD_FAIL_MALFORMED_HTLC]]
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice still hasn't forwarded the fail because it is not yet cross-signed
    relayerA.expectNoMsg()

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // alice will forward the fail upstream
    val forward = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFailMalformed]]
    assert(forward.result.fail === fail)
    assert(forward.htlc === htlc)
  }

  def testRevokeAndAckHtlcStaticRemoteKey(f: FixtureParam): Unit = {
    import f._
    val sender = TestProbe()

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localParams.features.hasFeature(StaticRemoteKey))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localParams.features.hasFeature(StaticRemoteKey))

    def aliceToRemoteScript(): ByteVector = {
      val toRemoteAmount = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toRemote
      val Some(toRemoteOut) = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx.txOut.find(_.amount == toRemoteAmount.truncateToSatoshi)
      toRemoteOut.publicKeyScript
    }

    val initialToRemoteScript = aliceToRemoteScript()

    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    awaitCond(alice.stateName == NORMAL)
    // using option_static_remotekey alice's view of bob toRemote script stays the same across commitments
    assert(initialToRemoteScript == aliceToRemoteScript())
  }

  ignore("recv RevokeAndAck (one ptlc sent, static_remotekey)", Tag("static_remotekey")) {
    testRevokeAndAckHtlcStaticRemoteKey _
  }

  ignore("recv RevokeAndAck (one ptlc sent, anchor outputs)", Tag("anchor_outputs")) {
    testRevokeAndAckHtlcStaticRemoteKey _
  }

  ignore("recv RevocationTimeout") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // actual test begins
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    val peer = TestProbe()
    alice ! RevocationTimeout(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.index, peer.ref)
    peer.expectMsg(Peer.Disconnect(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteParams.nodeId))
  }

  private def testReceiveCmdFulfillPtlc(f: FixtureParam): Unit = {
    import f._

    val sender = TestProbe()
    val (_, p, htlc) = addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    bob ! CMD_FULFILL_PTLC(htlc.id, p)
    sender.expectMsgType[RES_SUCCESS[CMD_FULFILL_PTLC]]
    val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
    awaitCond(bob.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fulfill))))
  }

  ignore("recv CMD_FULFILL_PTLC") {
    testReceiveCmdFulfillPtlc _
  }

  ignore("recv CMD_FULFILL_PTLC (static_remotekey)", Tag("static_remotekey")) {
    testReceiveCmdFulfillPtlc _
  }

  ignore("recv CMD_FULFILL_PTLC (anchor_outputs)", Tag("anchor_outputs")) {
    testReceiveCmdFulfillPtlc _
  }

  ignore("recv CMD_FULFILL_PTLC (unknown ptlc id)") { f =>
    import f._
    val sender = TestProbe()
    val r = randomKey
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FULFILL_PTLC(42, r)
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  ignore("recv CMD_FULFILL_PTLC (invalid preimage)") { f =>
    import f._
    val sender = TestProbe()
    val (r, _, htlc) = addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val c = CMD_FULFILL_PTLC(htlc.id, PrivateKey(r.value.reverse))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, InvalidHtlcPreimage(channelId(bob), 0)))
    assert(initialState == bob.stateData)
  }

  ignore("recv CMD_FULFILL_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FULFILL_PTLC(42, randomKey)
    bob ! c // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingRelay.listPendingRelay(initialState.channelId).isEmpty)
  }

  private def testUpdateFulfillHtlc(f: FixtureParam): Unit = {
    import f._
    val sender = TestProbe()
    val (_, p, htlc) = addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FULFILL_PTLC(htlc.id, p)
    sender.expectMsgType[RES_SUCCESS[CMD_FULFILL_PTLC]]
    val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob2alice.forward(alice)
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fulfill))))
    // alice immediately propagates the fulfill upstream
    val forward = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFulfillPtlc]]
    assert(forward.result.fulfill === fulfill)
    assert(forward.htlc === htlc)
  }

  ignore("recv UpdateFulfillHtlc") {
    testUpdateFulfillHtlc _
  }

  ignore("recv UpdateFulfillHtlc (static_remotekey)", Tag("(static_remotekey)")) {
    testUpdateFulfillHtlc _
  }

  ignore("recv UpdateFulfillHtlc (anchor_outputs)", Tag("anchor_outputs")) {
    testUpdateFulfillHtlc _
  }

  ignore("recv UpdateFulfillHtlc (sender has not signed ptlc)") { f =>
    import f._
    val sender = TestProbe()
    val (r, _, htlc) = addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! UpdateFulfillHtlc(ByteVector32.Zeroes, htlc.id, ByteVector32(r.value))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv UpdateFulfillHtlc (unknown ptlc id)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    alice ! UpdateFulfillHtlc(ByteVector32.Zeroes, 42, ByteVector32.Zeroes)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv UpdateFulfillHtlc (invalid preimage)") { f =>
    import f._
    val (r, _, htlc) = addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    relayerB.expectMsgType[RelayForward]
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    // actual test begins
    alice ! UpdateFulfillHtlc(ByteVector32.Zeroes, htlc.id, ByteVector32.Zeroes)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // ptlc timeout
    alice2blockchain.expectMsgType[PublishAsap] // ptlc delayed
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  private def testCmdFailHtlc(f: FixtureParam): Unit = {
    import f._
    val sender = TestProbe()
    val (_, _, htlc) = addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    bob ! CMD_FAIL_PTLC(htlc.id, Right(PermanentChannelFailure))
    sender.expectMsgType[RES_SUCCESS[CMD_FAIL_HTLC]]
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    awaitCond(bob.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
  }

  ignore("recv CMD_FAIL_PTLC") {
    testCmdFailHtlc _
  }

  ignore("recv CMD_FAIL_PTLC (static_remotekey)", Tag("static_remotekey")) {
    testCmdFailHtlc _
  }

  ignore("recv CMD_FAIL_PTLC (anchor_outputs)", Tag("anchor_outputs")) {
    testCmdFailHtlc _
  }

  ignore("recv CMD_FAIL_PTLC (unknown ptlc id)") { f =>
    import f._
    val sender = TestProbe()
    val r: ByteVector = randomBytes32
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FAIL_PTLC(42, Right(PermanentChannelFailure))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  ignore("recv CMD_FAIL_PTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FAIL_PTLC(42, Right(PermanentChannelFailure))
    bob ! c // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingRelay.listPendingRelay(initialState.channelId).isEmpty)
  }

  ignore("recv CMD_FAIL_MALFORMED_PTLC") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    bob ! CMD_FAIL_MALFORMED_HTLC(htlc.id, Sphinx.PaymentPacket.hash(htlc.onionRoutingPacket), FailureMessageCodecs.BADONION)
    sender.expectMsgType[RES_SUCCESS[CMD_FAIL_MALFORMED_HTLC]]
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    awaitCond(bob.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
  }

  ignore("recv CMD_FAIL_MALFORMED_HTLC (unknown ptlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FAIL_MALFORMED_HTLC(42, ByteVector32.Zeroes, FailureMessageCodecs.BADONION)
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  ignore("recv CMD_FAIL_MALFORMED_HTLC (invalid failure_code)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val c = CMD_FAIL_MALFORMED_HTLC(42, ByteVector32.Zeroes, 42)
    bob ! c
    sender.expectMsg(RES_FAILURE(c, InvalidFailureCode(channelId(bob))))
    assert(initialState == bob.stateData)
  }

  ignore("recv CMD_FAIL_MALFORMED_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FAIL_MALFORMED_HTLC(42, ByteVector32.Zeroes, FailureMessageCodecs.BADONION)
    bob ! c // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingRelay.listPendingRelay(initialState.channelId).isEmpty)
  }

  private def testUpdateFailHtlc(f: FixtureParam): Unit = {
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure))
    sender.expectMsgType[RES_SUCCESS[CMD_FAIL_HTLC]]
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob2alice.forward(alice)
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fail))))
    // alice won't forward the fail before it is cross-signed
    relayerA.expectNoMsg()
  }

  ignore("recv UpdateFailHtlc") {
    testUpdateFailHtlc _
  }

  ignore("recv UpdateFailHtlc (static_remotekey)", Tag("static_remotekey")) {
    testUpdateFailHtlc _
  }

  ignore("recv UpdateFailHtlc (anchor_outputs)", Tag("anchor_outputs")) {
    testUpdateFailHtlc _
  }

  ignore("recv UpdateFailMalformedHtlc") { f =>
    import f._
    val sender = TestProbe()

    // Alice sends an HTLC to Bob, which they both sign
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    // Bob fails the HTLC because he cannot parse it
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob ! CMD_FAIL_MALFORMED_HTLC(htlc.id, Sphinx.PaymentPacket.hash(htlc.onionRoutingPacket), FailureMessageCodecs.BADONION)
    sender.expectMsgType[RES_SUCCESS[CMD_FAIL_MALFORMED_HTLC]]
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    bob2alice.forward(alice)

    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fail))))
    // alice won't forward the fail before it is cross-signed
    relayerA.expectNoMsg()

    bob ! CMD_SIGN()
    val sig = bob2alice.expectMsgType[CommitSig]
    // Bob should not have the ptlc in its remote commit anymore
    assert(sig.htlcSignatures.isEmpty)

    // and Alice should accept this signature
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
  }

  ignore("recv UpdateFailMalformedHtlc (invalid failure_code)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val fail = UpdateFailMalformedHtlc(ByteVector32.Zeroes, htlc.id, Sphinx.PaymentPacket.hash(htlc.onionRoutingPacket), 42)
    alice ! fail
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) === InvalidFailureCode(ByteVector32.Zeroes).getMessage)
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // ptlc timeout
    alice2blockchain.expectMsgType[PublishAsap] // ptlc delayed
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv UpdateFailHtlc (sender has not signed ptlc)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! UpdateFailHtlc(ByteVector32.Zeroes, htlc.id, ByteVector.fill(152)(0))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv UpdateFailHtlc (unknown ptlc id)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    alice ! UpdateFailHtlc(ByteVector32.Zeroes, 42, ByteVector.fill(152)(0))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv UpdateFailHtlc (invalid onion error length)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    // Bob receives a failure with a completely invalid onion error (missing mac)
    bob ! CMD_FAIL_HTLC(htlc.id, Left(ByteVector.fill(260)(42)))
    sender.expectMsgType[RES_SUCCESS[CMD_FAIL_HTLC]]
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    assert(fail.id === htlc.id)
    // We should rectify the packet length before forwarding upstream.
    assert(fail.reason.length === Sphinx.FailurePacket.PacketLength)
  }

  private def testCmdUpdateFee(f: FixtureParam): Unit = {
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat))
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_FEE]]
    val fee = alice2bob.expectMsgType[UpdateFee]
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fee))))
  }

  ignore("recv CMD_UPDATE_FEE") {
    testCmdUpdateFee _
  }

  ignore("recv CMD_UPDATE_FEE (anchor outputs)", Tag("anchor_outputs")) {
    testCmdUpdateFee _
  }

  ignore("recv CMD_UPDATE_FEE (two in a row)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat))
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_FEE]]
    val fee1 = alice2bob.expectMsgType[UpdateFee]
    alice ! CMD_UPDATE_FEE(FeeratePerKw(30000 sat))
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_FEE]]
    val fee2 = alice2bob.expectMsgType[UpdateFee]
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fee2))))
  }

  ignore("recv CMD_UPDATE_FEE (when fundee)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val c = CMD_UPDATE_FEE(FeeratePerKw(20000 sat))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, FundeeCannotSendUpdateFee(channelId(bob))))
    assert(initialState == bob.stateData)
  }

  ignore("recv UpdateFee") { f =>
    import f._
    val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    bob ! fee
    awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ fee), remoteNextHtlcId = 0)))
  }

  ignore("recv UpdateFee (anchor outputs)", Tag("anchor_outputs")) { f =>
    import f._
    val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(8000 sat))
    bob ! fee
    awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ fee), remoteNextHtlcId = 0)))
  }

  ignore("recv UpdateFee (two in a row)") { f =>
    import f._
    val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val fee1 = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    bob ! fee1
    val fee2 = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(14000 sat))
    bob ! fee2
    awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ fee2), remoteNextHtlcId = 0)))
  }

  ignore("recv UpdateFee (when sender is not funder)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    alice ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv UpdateFee (sender can't afford it)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(100000000 sat))
    // we first update the feerates so that we don't trigger a 'fee too different' error
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(fee.feeratePerKw))
    bob ! fee
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === CannotAffordFees(channelId(bob), missing = 71620000L sat, reserve = 20000L sat, fees = 72400000L sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    //bob2blockchain.expectMsgType[PublishAsap] // main delayed (removed because of the high fees)
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv UpdateFee (sender can't afford it) (anchor outputs)", Tag("anchor_outputs")) { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    // This feerate is just above the threshold: (800000 (alice balance) - 20000 (reserve) - 660 (anchors)) / 1124 (commit tx weight) = 693363
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(693364 sat))
    // we first update the feerates so that we don't trigger a 'fee too different' error
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(fee.feeratePerKw))
    bob ! fee
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === CannotAffordFees(channelId(bob), missing = 1 sat, reserve = 20000 sat, fees = 780001 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    //bob2blockchain.expectMsgType[PublishAsap] // main delayed (removed because of the high fees)
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv UpdateFee (local/remote feerates are too different)") { f =>
    import f._

    bob.feeEstimator.setFeerate(FeeratesPerKw(FeeratePerKw(1000 sat), FeeratePerKw(2000 sat), FeeratePerKw(6000 sat), FeeratePerKw(12000 sat), FeeratePerKw(36000 sat), FeeratePerKw(72000 sat), FeeratePerKw(140000 sat), FeeratePerKw(160000 sat)))
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    val localFeerate = bob.feeEstimator.getFeeratePerKw(bob.feeTargets.commitmentBlockTarget)
    assert(localFeerate === FeeratePerKw(2000 sat))
    val remoteFeerate = FeeratePerKw(4000 sat)
    bob ! UpdateFee(ByteVector32.Zeroes, remoteFeerate)
    bob2alice.expectNoMsg(250 millis) // we don't close because the commitment doesn't contain any HTLC

    // when we try to add an HTLC, we still disagree on the feerate so we close
    alice2bob.send(bob, UpdateAddHtlc(ByteVector32.Zeroes, 0, 2500000 msat, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).contains("local/remote feerates are too different"))
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv UpdateFee (remote feerate is too small)") { f =>
    import f._
    val bobCommitments = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    val tx = bobCommitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    val expectedFeeratePerKw = bob.feeEstimator.getFeeratePerKw(bob.feeTargets.commitmentBlockTarget)
    assert(bobCommitments.localCommit.spec.feeratePerKw == expectedFeeratePerKw)
    bob ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(252 sat))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === "remote fee rate is too small: remoteFeeratePerKw=252")
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  ignore("recv CMD_UPDATE_RELAY_FEE ") { f =>
    import f._
    val sender = TestProbe()
    val newFeeBaseMsat = TestConstants.AlicePtlc.nodeParams.feeBase * 2
    val newFeeProportionalMillionth = TestConstants.Alice.nodeParams.feeProportionalMillionth * 2
    alice ! CMD_UPDATE_RELAY_FEE(ActorRef.noSender, newFeeBaseMsat, newFeeProportionalMillionth)
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_RELAY_FEE]]

    val localUpdate = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(localUpdate.channelUpdate.feeBaseMsat == newFeeBaseMsat)
    assert(localUpdate.channelUpdate.feeProportionalMillionths == newFeeProportionalMillionth)
    relayerA.expectNoMsg(1 seconds)
  }

  def testCmdClose(f: FixtureParam): Unit = {
    import f._
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
    alice ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  ignore("recv CMD_CLOSE (no pending htlcs)") {
    testCmdClose _
  }

  ignore("recv CMD_CLOSE (no pending htlcs) (anchor outputs)", Tag("anchor_outputs")) {
    testCmdClose _
  }

  ignore("recv CMD_CLOSE (with noSender)") { f =>
    import f._
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
    // this makes sure that our backward-compatibility hack for the ask pattern (which uses context.sender as reply-to)
    // works before we fully transition to akka typed
    val c = CMD_CLOSE(ActorRef.noSender, None)
    sender.send(alice, c)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  ignore("recv CMD_CLOSE (with unacked sent ptlcs)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, CannotCloseWithUnsignedOutgoingHtlcs]]
  }

  ignore("recv CMD_CLOSE (with invalid final script)") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, Some(hex"00112233445566778899"))
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, InvalidFinalScript]]
  }

  ignore("recv CMD_CLOSE (with signed sent htlcs)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    alice ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  ignore("recv CMD_CLOSE (two in a row)") { f =>
    import f._
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
    alice ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
    alice ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, ClosingAlreadyInProgress]]
  }

  ignore("recv CMD_CLOSE (while waiting for a RevokeAndAck)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    // actual test begins
    alice ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
  }

  def testShutdown(f: FixtureParam): Unit = {
    import f._
    alice ! Shutdown(ByteVector32.Zeroes, Bob.channelParams.defaultFinalScriptPubKey)
    alice2bob.expectMsgType[Shutdown]
    alice2bob.expectMsgType[ClosingSigned]
    awaitCond(alice.stateName == NEGOTIATING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_NEGOTIATING].channelId)
  }

  ignore("recv Shutdown (no pending htlcs)") {
    testShutdown _
  }

  ignore("recv Shutdown (no pending htlcs) (anchor outputs)", Tag("anchor_outputs")) {
    testShutdown _
  }

  ignore("recv Shutdown (with unacked sent ptlcs)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    bob ! CMD_CLOSE(sender.ref, None)
    bob2alice.expectMsgType[Shutdown]
    // actual test begins
    bob2alice.forward(alice)
    // alice sends a new sig
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // bob replies with a revocation
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // as soon as alice as received the revocation, she will send her shutdown message
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == SHUTDOWN)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_SHUTDOWN].channelId)
  }

  ignore("recv Shutdown (with unacked received htlcs)") { f =>
    import f._
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    // actual test begins
    bob ! Shutdown(ByteVector32.Zeroes, TestConstants.Alice.channelParams.defaultFinalScriptPubKey)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  ignore("recv Shutdown (with invalid final script)") { f =>
    import f._
    bob ! Shutdown(ByteVector32.Zeroes, hex"00112233445566778899")
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  ignore("recv Shutdown (with invalid final script and signed htlcs, in response to a Shutdown)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_CLOSE(sender.ref, None)
    bob2alice.expectMsgType[Shutdown]
    // actual test begins
    bob ! Shutdown(ByteVector32.Zeroes, hex"00112233445566778899")
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  def testShutdownWithPtlcs(f: FixtureParam): Unit = {
    import f._
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    bob ! Shutdown(ByteVector32.Zeroes, TestConstants.Alice.channelParams.defaultFinalScriptPubKey)
    bob2alice.expectMsgType[Shutdown]
    awaitCond(bob.stateName == SHUTDOWN)
  }

  ignore("recv Shutdown (with signed ptlcs)") {
    testShutdownWithPtlcs _
  }

  ignore("recv Shutdown (with signed ptlcs) (anchor outputs)", Tag("anchor_outputs")) {
    testShutdownWithPtlcs _
  }

  ignore("recv Shutdown (while waiting for a RevokeAndAck)") { f =>
    import f._
    val sender = TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    bob ! CMD_CLOSE(sender.ref, None)
    bob2alice.expectMsgType[Shutdown]
    // actual test begins
    bob2alice.forward(alice)
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == SHUTDOWN)
  }

  ignore("recv Shutdown (while waiting for a RevokeAndAck with pending outgoing ptlc)") { f =>
    import f._
    val sender = TestProbe()
    // let's make bob send a Shutdown message
    bob ! CMD_CLOSE(sender.ref, None)
    bob2alice.expectMsgType[Shutdown]
    // this is just so we have something to sign
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    // now we can sign
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // adding an outgoing pending htlc
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    // actual test begins
    // alice eventually gets bob's shutdown
    bob2alice.forward(alice)
    // alice can't do anything for now other than waiting for bob to send the revocation
    alice2bob.expectNoMsg()
    // bob sends the revocation
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // bob will also sign back
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    // then alice can sign the 2nd htlc
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // and reply to bob's first signature
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    // bob replies with the 2nd revocation
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // then alice can send her shutdown
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == SHUTDOWN)
    // note: bob will sign back a second time, but that is out of our scope
  }

  ignore("recv CurrentBlockCount (no ptlc timed out)") { f =>
    import f._
    TestProbe()
    addPtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CurrentBlockCount(400143)
    awaitCond(alice.stateData == initialState)
  }

  ignore("recv CurrentBlockCount (an ptlc timed out)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val aliceCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
    alice ! CurrentBlockCount(400145)
    alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))

    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // ptlc timeout
    alice2blockchain.expectMsgType[PublishAsap] // ptlc delayed
    val watch = alice2blockchain.expectMsgType[WatchConfirmed]
    assert(watch.event === BITCOIN_TX_CONFIRMED(aliceCommitTx))
  }

  ignore("recv CurrentBlockCount (fulfilled signed ptlc ignored by upstream peer)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])

    // actual test begins:
    //  * Bob receives the HTLC pre-image and wants to fulfill
    //  * Alice does not react to the fulfill (drops the message for some reason)
    //  * When the HTLC timeout on Alice side is near, Bob needs to close the channel to avoid an on-chain race
    //    condition between his HTLC-success and Alice's HTLC-timeout
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _) = initialState.commitments.localCommit.publishableTxs.htlcTxsAndSigs.head.txinfo

    bob ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
    sender.expectMsgType[RES_SUCCESS[CMD_FULFILL_HTLC]]
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob ! CurrentBlockCount((htlc.cltvExpiry - Bob.nodeParams.fulfillSafetyBeforeTimeout).toLong)

    val ChannelErrorOccurred(_, _, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

    bob2blockchain.expectMsg(PublishAsap(initialCommitTx))
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    assert(bob2blockchain.expectMsgType[PublishAsap].tx.txOut === htlcSuccessTx.txOut)
    bob2blockchain.expectMsgType[PublishAsap] // ptlc delayed
    assert(bob2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(initialCommitTx))
    alice2blockchain.expectNoMsg(500 millis)
  }

  ignore("recv CurrentBlockCount (fulfilled proposed ptlc ignored by upstream peer)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])

    // actual test begins:
    //  * Bob receives the HTLC pre-image and wants to fulfill but doesn't sign
    //  * Alice does not react to the fulfill (drops the message for some reason)
    //  * When the HTLC timeout on Alice side is near, Bob needs to close the channel to avoid an on-chain race
    //    condition between his HTLC-success and Alice's HTLC-timeout
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _) = initialState.commitments.localCommit.publishableTxs.htlcTxsAndSigs.head.txinfo

    bob ! CMD_FULFILL_HTLC(htlc.id, r, commit = false)
    sender.expectMsgType[RES_SUCCESS[CMD_FULFILL_HTLC]]
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob ! CurrentBlockCount((htlc.cltvExpiry - Bob.nodeParams.fulfillSafetyBeforeTimeout).toLong)

    val ChannelErrorOccurred(_, _, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

    bob2blockchain.expectMsg(PublishAsap(initialCommitTx))
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    assert(bob2blockchain.expectMsgType[PublishAsap].tx.txOut === htlcSuccessTx.txOut)
    bob2blockchain.expectMsgType[PublishAsap] // ptlc delayed
    assert(bob2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(initialCommitTx))
    alice2blockchain.expectNoMsg(500 millis)
  }

  ignore("recv CurrentBlockCount (fulfilled proposed ptlc acked but not committed by upstream peer)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])

    // actual test begins:
    //  * Bob receives the HTLC pre-image and wants to fulfill
    //  * Alice acks but doesn't commit
    //  * When the HTLC timeout on Alice side is near, Bob needs to close the channel to avoid an on-chain race
    //    condition between his HTLC-success and Alice's HTLC-timeout
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _) = initialState.commitments.localCommit.publishableTxs.htlcTxsAndSigs.head.txinfo

    bob ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
    sender.expectMsgType[RES_SUCCESS[CMD_FULFILL_HTLC]]
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    bob ! CurrentBlockCount((htlc.cltvExpiry - Bob.nodeParams.fulfillSafetyBeforeTimeout).toLong)

    val ChannelErrorOccurred(_, _, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

    bob2blockchain.expectMsg(PublishAsap(initialCommitTx))
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    assert(bob2blockchain.expectMsgType[PublishAsap].tx.txOut === htlcSuccessTx.txOut)
    bob2blockchain.expectMsgType[PublishAsap] // ptlc delayed
    assert(bob2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(initialCommitTx))
    alice2blockchain.expectNoMsg(500 millis)
  }

  ignore("recv CurrentFeerate (when funder, triggers an UpdateFee)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val event = CurrentFeerates(FeeratesPerKw(FeeratePerKw(100 sat), FeeratePerKw(200 sat), FeeratePerKw(600 sat), FeeratePerKw(1200 sat), FeeratePerKw(3600 sat), FeeratePerKw(7200 sat), FeeratePerKw(14400 sat), FeeratePerKw(100800 sat)))
    alice ! event
    alice2bob.expectMsg(UpdateFee(initialState.commitments.channelId, event.feeratesPerKw.feePerBlock(Alice.nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget)))
  }

  ignore("recv CurrentFeerate (when funder, doesn't trigger an UpdateFee)") { f =>
    import f._
    val sender = TestProbe()
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(10010 sat)))
    alice ! event
    alice2bob.expectNoMsg(500 millis)
  }

  ignore("recv CurrentFeerate (when fundee, commit-fee/network-fee are close)") { f =>
    import f._
    val sender = TestProbe()
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(11000 sat)))
    bob ! event
    bob2alice.expectNoMsg(500 millis)
  }

  ignore("recv CurrentFeerate (when fundee, commit-fee/network-fee are very different, with HTLCs)") { f =>
    import f._

    addHtlc(10000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val sender = TestProbe()
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(14000 sat)))
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(14000 sat)))
    bob ! event
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap] // commit tx
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  ignore("recv CurrentFeerate (when fundee, commit-fee/network-fee are very different, without HTLCs)") { f =>
    import f._

    val sender = TestProbe()
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(1000 sat)))
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(1000 sat)))
    bob ! event
    bob2alice.expectNoMsg(250 millis) // we don't close because the commitment doesn't contain any HTLC

    // when we try to add an HTLC, we still disagree on the feerate so we close
    alice2bob.send(bob, UpdateAddHtlc(ByteVector32.Zeroes, 0, 2500000 msat, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap] // commit tx
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  ignore("recv BITCOIN_FUNDING_SPENT (their commit w/ ptlc)") { f =>
    import f._
    val sender = TestProbe()

    val (ra1, htlca1) = addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(1, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(0, rb1, alice, bob, alice2bob, bob2alice)

    // at this point here is the situation from alice pov and what she should do when bob publishes his commit tx:
    // balances :
    //    alice's balance : 449 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    assert(bobCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

    // in response to that, alice publishes its claim txes
    val claimTxes = for (i <- 0 until 4) yield alice2blockchain.expectMsgType[PublishAsap].tx
    val claimMain = claimTxes(0)
    // in addition to its main output, alice can only claim 3 out of 4 htlcs, she can't do anything regarding the ptlc sent by bob for which she does not have the preimage
    val amountClaimed = (for (claimHtlcTx <- claimTxes) yield {
      assert(claimHtlcTx.txIn.size == 1)
      assert(claimHtlcTx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.txOut(0).amount
    }).sum
    // at best we have a little less than 450 000 + 250 000 + 100 000 + 50 000 = 850 000 (because fees)
    assert(amountClaimed === 814880.sat)

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(bobCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimMain)) // claim-main
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcSuccessTxs.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcTimeoutTxs.size == 2)

    // assert the feerate of the claim main is what we expect
    val expectedFeeRate = alice.feeEstimator.getFeeratePerKw(alice.feeTargets.claimMainBlockTarget)
    val expectedFee = Transactions.weight2fee(expectedFeeRate, Transactions.claimP2WPKHOutputWeight)
    val claimFee = claimMain.txIn.map(in => bobCommitTx.txOut(in.outPoint.index.toInt).amount).sum - claimMain.txOut.map(_.amount).sum
    assert(claimFee == expectedFee)
  }

  ignore("recv BITCOIN_FUNDING_SPENT (their *next* commit w/ ptlc)") { f =>
    import f._
    val sender = TestProbe()

    val (ra1, htlca1) = addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(1, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(0, rb1, alice, bob, alice2bob, bob2alice)
    // alice sign but we intercept bob's revocation
    alice ! CMD_SIGN()
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]

    // as far as alice knows, bob currently has two valid unrevoked commitment transactions

    // at this point here is the situation from bob's pov with the latest sig received from alice,
    // and what alice should do when bob publishes his commit tx:
    // balances :
    //    alice's balance : 499 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    assert(bobCommitTx.txOut.size == 5) // two main outputs and 3 pending htlcs
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

    // in response to that, alice publishes its claim txes
    val claimTxes = for (i <- 0 until 3) yield alice2blockchain.expectMsgType[PublishAsap].tx
    // in addition to its main output, alice can only claim 2 out of 3 htlcs, she can't do anything regarding the ptlc sent by bob for which she does not have the preimage
    val amountClaimed = (for (claimHtlcTx <- claimTxes) yield {
      assert(claimHtlcTx.txIn.size == 1)
      assert(claimHtlcTx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.txOut(0).amount
    }).sum
    // at best we have a little less than 500 000 + 250 000 + 100 000 = 850 000 (because fees)
    assert(amountClaimed === 822310.sat)

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(bobCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxes(0))) // claim-main
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.isDefined)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get.claimHtlcSuccessTxs.size == 0)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get.claimHtlcTimeoutTxs.size == 2)
  }

  ignore("recv BITCOIN_FUNDING_SPENT (revoked commit)") { f =>
    import f._
    // initially we have :
    // alice = 800 000
    //   bob = 200 000
    def send(): Transaction = {
      // alice sends 8 000 sat
      val (r, htlc) = addHtlc(10000000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)

      bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    }

    val txs = for (i <- 0 until 10) yield send()
    // bob now has 10 spendable tx, 9 of them being revoked

    // let's say that bob published this tx
    val revokedTx = txs(3)
    // channel state for this revoked tx is as follows:
    // alice = 760 000
    //   bob = 200 000
    //  a->b =  10 000
    //  a->b =  10 000
    //  a->b =  10 000
    //  a->b =  10 000
    // two main outputs + 4 ptlc
    assert(revokedTx.txOut.size == 6)
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, revokedTx)
    alice2bob.expectMsgType[Error]

    val mainTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val mainPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val htlcPenaltyTxs = for (i <- 0 until 4) yield alice2blockchain.expectMsgType[PublishAsap].tx
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(revokedTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(mainTx))
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
    // let's make sure that ptlc-penalty txs each spend a different output
    assert(htlcPenaltyTxs.map(_.txIn.head.outPoint.index).toSet.size === htlcPenaltyTxs.size)
    htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))
    alice2blockchain.expectNoMsg(1 second)

    Transaction.correctlySpends(mainTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(mainPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    htlcPenaltyTxs.foreach(htlcPenaltyTx => Transaction.correctlySpends(htlcPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    // two main outputs are 760 000 and 200 000
    assert(mainTx.txOut(0).amount === 741500.sat)
    assert(mainPenaltyTx.txOut(0).amount === 195160.sat)
    assert(htlcPenaltyTxs(0).txOut(0).amount === 4540.sat)
    assert(htlcPenaltyTxs(1).txOut(0).amount === 4540.sat)
    assert(htlcPenaltyTxs(2).txOut(0).amount === 4540.sat)
    assert(htlcPenaltyTxs(3).txOut(0).amount === 4540.sat)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
  }

  ignore("recv BITCOIN_FUNDING_SPENT (revoked commit with identical htlcs)") { f =>
    import f._
    val sender = TestProbe()

    // initially we have :
    // alice = 800 000
    //   bob = 200 000

    val add = CMD_ADD_HTLC(sender.ref, 10000000 msat, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    crossSign(alice, bob, alice2bob, bob2alice)
    // bob will publish this tx after it is revoked
    val revokedTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    crossSign(alice, bob, alice2bob, bob2alice)

    // channel state for this revoked tx is as follows:
    // alice = 780 000
    //   bob = 200 000
    //  a->b =  10 000
    //  a->b =  10 000
    assert(revokedTx.txOut.size == 4)
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, revokedTx)
    alice2bob.expectMsgType[Error]

    val mainTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val mainPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val htlcPenaltyTxs = for (i <- 0 until 2) yield alice2blockchain.expectMsgType[PublishAsap].tx
    // let's make sure that ptlc-penalty txs each spend a different output
    assert(htlcPenaltyTxs.map(_.txIn.head.outPoint.index).toSet.size === htlcPenaltyTxs.size)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(revokedTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(mainTx))
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
    htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))
    alice2blockchain.expectNoMsg(1 second)

    Transaction.correctlySpends(mainTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(mainPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    htlcPenaltyTxs.foreach(htlcPenaltyTx => Transaction.correctlySpends(htlcPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
  }

  ignore("recv Error") { f =>
    import f._
    val (ra1, htlca1) = addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(1, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(0, rb1, alice, bob, alice2bob, bob2alice)

    // at this point here is the situation from alice pov and what she should do when she publishes his commit tx:
    // balances :
    //    alice's balance : 449 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend using 2nd stage ptlc-timeout
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend using 2nd stage ptlc-timeout
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage using ptlc-success
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // an error occurs and alice publishes her commit tx
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
    assert(aliceCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs

    // alice can only claim 3 out of 4 htlcs, she can't do anything regarding the ptlc sent by bob for which she does not have the ptlc
    // so we expect 7 transactions:
    // - 1 tx to claim the main delayed output
    // - 3 txes for each ptlc
    // - 3 txes for each delayed output of the claimed ptlc
    val claimTxs = for (i <- 0 until 7) yield alice2blockchain.expectMsgType[PublishAsap].tx

    // the main delayed output spends the commitment transaction
    Transaction.correctlySpends(claimTxs(0), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // 2nd stage transactions spend the commitment transaction
    Transaction.correctlySpends(claimTxs(1), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(2), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(3), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // 3rd stage transactions spend their respective HTLC-Success/HTLC-Timeout transactions
    Transaction.correctlySpends(claimTxs(4), claimTxs(1) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(5), claimTxs(2) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(6), claimTxs(3) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(aliceCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(0))) // main-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(4))) // ptlc-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(5))) // ptlc-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(6))) // ptlc-delayed
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
    val localCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(localCommitPublished.commitTx == aliceCommitTx)
    assert(localCommitPublished.htlcSuccessTxs.size == 1)
    assert(localCommitPublished.htlcTimeoutTxs.size == 2)
    assert(localCommitPublished.claimHtlcDelayedTxs.size == 3)
  }

  ignore("recv Error (nothing at stake)", Tag("no_push_msat")) { f =>
    import f._

    // when receiving an error bob should publish its commitment even if it has nothing at stake, because alice could
    // have lost its data and need assistance

    // an error occurs and alice publishes her commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    bob ! Error(ByteVector32.Zeroes, "oops")
    bob2blockchain.expectMsg(PublishAsap(bobCommitTx))
    assert(bobCommitTx.txOut.size == 1) // only one main output
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(bob.stateName == CLOSING)
    assert(bob.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
    val localCommitPublished = bob.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(localCommitPublished.commitTx == bobCommitTx)
  }

  ignore("recv BITCOIN_FUNDING_DEEPLYBURIED", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    val annSigs = alice2bob.expectMsgType[AnnouncementSignatures]
    // public channel: we don't send the channel_update directly to the peer
    alice2bob.expectNoMsg(1 second)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == annSigs.shortChannelId && alice.stateData.asInstanceOf[DATA_NORMAL].buried == true)
    // we don't re-publish the same channel_update if there was no change
    channelUpdateListener.expectNoMsg(1 second)
  }

  ignore("recv BITCOIN_FUNDING_DEEPLYBURIED (short channel id changed)", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400001, 22, null)
    val annSigs = alice2bob.expectMsgType[AnnouncementSignatures]
    // public channel: we don't send the channel_update directly to the peer
    alice2bob.expectNoMsg(1 second)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == annSigs.shortChannelId && alice.stateData.asInstanceOf[DATA_NORMAL].buried == true)
    assert(channelUpdateListener.expectMsgType[LocalChannelUpdate].shortChannelId == alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId)
    channelUpdateListener.expectNoMsg(1 second)
  }

  ignore("recv BITCOIN_FUNDING_DEEPLYBURIED (private channel)") { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    // private channel: we send the channel_update directly to the peer
    val channelUpdate = alice2bob.expectMsgType[ChannelUpdate]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == channelUpdate.shortChannelId && alice.stateData.asInstanceOf[DATA_NORMAL].buried == true)
    // we don't re-publish the same channel_update if there was no change
    channelUpdateListener.expectNoMsg(1 second)
  }

  ignore("recv BITCOIN_FUNDING_DEEPLYBURIED (private channel, short channel id changed)") { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400001, 22, null)
    // private channel: we send the channel_update directly to the peer
    val channelUpdate = alice2bob.expectMsgType[ChannelUpdate]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == channelUpdate.shortChannelId && alice.stateData.asInstanceOf[DATA_NORMAL].buried == true)
    // LocalChannelUpdate should not be published
    assert(channelUpdateListener.expectMsgType[LocalChannelUpdate].shortChannelId == alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId)
    channelUpdateListener.expectNoMsg(1 second)
  }

  ignore("recv AnnouncementSignatures", Tag("channels_public")) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    val annSigsA = alice2bob.expectMsgType[AnnouncementSignatures]
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    val annSigsB = bob2alice.expectMsgType[AnnouncementSignatures]
    import initialState.commitments.{localParams, remoteParams}
    val channelAnn = Announcements.makeChannelAnnouncement(Alice.nodeParams.chainHash, annSigsA.shortChannelId, Alice.nodeParams.nodeId, remoteParams.nodeId, Alice.channelKeyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, annSigsA.nodeSignature, annSigsB.nodeSignature, annSigsA.bitcoinSignature, annSigsB.bitcoinSignature)
    // actual test starts here
    bob2alice.forward(alice)
    awaitCond({
      val normal = alice.stateData.asInstanceOf[DATA_NORMAL]
      normal.shortChannelId == annSigsA.shortChannelId && normal.buried && normal.channelAnnouncement.contains(channelAnn) && normal.channelUpdate.shortChannelId == annSigsA.shortChannelId
    })
    assert(channelUpdateListener.expectMsgType[LocalChannelUpdate].channelAnnouncement_opt === Some(channelAnn))
  }

  ignore("recv AnnouncementSignatures (re-send)", Tag("channels_public")) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 42, 10, null)
    val annSigsA = alice2bob.expectMsgType[AnnouncementSignatures]
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 42, 10, null)
    val annSigsB = bob2alice.expectMsgType[AnnouncementSignatures]
    import initialState.commitments.{localParams, remoteParams}
    val channelAnn = Announcements.makeChannelAnnouncement(Alice.nodeParams.chainHash, annSigsA.shortChannelId, Alice.nodeParams.nodeId, remoteParams.nodeId, Alice.channelKeyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, annSigsA.nodeSignature, annSigsB.nodeSignature, annSigsA.bitcoinSignature, annSigsB.bitcoinSignature)
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement === Some(channelAnn))

    // actual test starts here
    // simulate bob re-sending its sigs
    bob2alice.send(alice, annSigsA)
    // alice re-sends her sigs
    alice2bob.expectMsg(annSigsA)
  }

  ignore("recv AnnouncementSignatures (invalid)", Tag("channels_public")) { f =>
    import f._
    val channelId = alice.stateData.asInstanceOf[DATA_NORMAL].channelId
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    alice2bob.expectMsgType[AnnouncementSignatures]
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    val annSigsB = bob2alice.expectMsgType[AnnouncementSignatures]
    // actual test starts here - Bob sends an invalid signature
    val annSigsB_invalid = annSigsB.copy(bitcoinSignature = annSigsB.nodeSignature, nodeSignature = annSigsB.bitcoinSignature)
    bob2alice.forward(alice, annSigsB_invalid)
    alice2bob.expectMsg(Error(channelId, InvalidAnnouncementSignatures(channelId, annSigsB_invalid).getMessage))
    alice2bob.forward(bob)
    alice2bob.expectNoMsg(200 millis)
    awaitCond(alice.stateName == CLOSING)
  }

  ignore("recv BroadcastChannelUpdate", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    val update1 = channelUpdateListener.expectMsgType[LocalChannelUpdate]

    // actual test starts here
    Thread.sleep(1100)
    alice ! BroadcastChannelUpdate(PeriodicRefresh)
    val update2 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(update1.channelUpdate.timestamp < update2.channelUpdate.timestamp)
  }

  ignore("recv BroadcastChannelUpdate (no changes)", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    channelUpdateListener.expectMsgType[LocalChannelUpdate]

    // actual test starts here
    Thread.sleep(1100)
    alice ! BroadcastChannelUpdate(Reconnected)
    channelUpdateListener.expectNoMsg(1 second)
  }

  ignore("recv INPUT_DISCONNECTED") { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    val update1a = alice2bob.expectMsgType[ChannelUpdate]
    assert(Announcements.isEnabled(update1a.channelFlags))

    // actual test starts here
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    alice2bob.expectNoMsg(1 second)
    channelUpdateListener.expectNoMsg(1 second)
  }

  ignore("recv INPUT_DISCONNECTED (with pending unsigned htlcs)") { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    val update1a = alice2bob.expectMsgType[ChannelUpdate]
    assert(Announcements.isEnabled(update1a.channelFlags))
    val (_, htlc1) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val (_, htlc2) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.localChanges.proposed.size == 2)

    // actual test starts here
    Thread.sleep(1100)
    alice ! INPUT_DISCONNECTED
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Disconnected]].htlc.paymentHash === htlc1.paymentHash)
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Disconnected]].htlc.paymentHash === htlc2.paymentHash)
    assert(!Announcements.isEnabled(channelUpdateListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags))
    awaitCond(alice.stateName == OFFLINE)
  }

  ignore("recv INPUT_DISCONNECTED (public channel)", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    val update1 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(Announcements.isEnabled(update1.channelUpdate.channelFlags) == true)

    // actual test starts here
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    channelUpdateListener.expectNoMsg(1 second)
  }

  ignore("recv INPUT_DISCONNECTED (public channel, with pending unsigned htlcs)", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.forward(bob)
    val update1a = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    val update1b = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(Announcements.isEnabled(update1a.channelUpdate.channelFlags))
    val (_, htlc1) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val (_, htlc2) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.localChanges.proposed.size == 2)

    // actual test starts here
    Thread.sleep(1100)
    alice ! INPUT_DISCONNECTED
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Disconnected]].htlc.paymentHash === htlc1.paymentHash)
    assert(relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.Disconnected]].htlc.paymentHash === htlc2.paymentHash)
    val update2a = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(update1a.channelUpdate.timestamp < update2a.channelUpdate.timestamp)
    assert(!Announcements.isEnabled(update2a.channelUpdate.channelFlags))
    awaitCond(alice.stateName == OFFLINE)
  }

}
