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

package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.TestConstants.TestFeeEstimator
import fr.acinq.eclair.blockchain.fee.{FeeTargets, FeeratePerKw, FeerateTolerance, OnChainFeeConf}
import fr.acinq.eclair.channel.Commitments._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{IncorrectOrUnknownPaymentDetails, UpdateAddPtlc}
import fr.acinq.eclair.{TestKitBaseClass, _}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

class PtlcCommitmentsSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  val feeConfNoMismatch = OnChainFeeConf(FeeTargets(6, 2, 2, 6), new TestFeeEstimator, FeerateTolerance(0.00001, 100000.0), closeOnOfflineMismatch = false, 1.0)

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

  test("take additional HTLC fee into account") { f =>
    import f._
    // The fee for a single HTLC is 1720000 msat but the funder keeps an extra reserve to make sure we're able to handle
    // an additional HTLC at twice the feerate (hence the multiplier).
    val htlcOutputFee = 3 * 1720000 msat
    val a = 772760000 msat // initial balance alice
    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    // we need to take the additional HTLC fee into account because balances are above the trim threshold.
    assert(ac0.availableBalanceForSend == a - htlcOutputFee)
    assert(bc0.availableBalanceForReceive == a - htlcOutputFee)

    val (_, _, cmdAdd) = makeCmdAddPtlc(a - htlcOutputFee - 1000.msat, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    val Right((ac1, add)) = sendAddPtlc(ac0, cmdAdd, currentBlockHeight, alice.underlyingActor.nodeParams.onChainFeeConf)
    val Success(bc1) = receiveAddPtlc(bc0, add, bob.underlyingActor.nodeParams.onChainFeeConf)
    val Success((_, commit1)) = sendCommit(ac1, alice.underlyingActor.nodeParams.keyManager)
    val Success((bc2, _)) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.keyManager)
    // we don't take into account the additional HTLC fee since Alice's balance is below the trim threshold.
    assert(ac1.availableBalanceForSend == 1000.msat)
    assert(bc2.availableBalanceForReceive == 1000.msat)
  }

  test("correct values for availableForSend/availableForReceive (success case)") { f =>
    import f._

    val fee = 1720000 msat // fee due to the additional htlc output
    val funderFeeReserve = fee * 2 // extra reserve to handle future fee increase
    val a = (772760000 msat) - fee - funderFeeReserve // initial balance alice
    val b = 190000000 msat // initial balance bob
    val p = 42000000 msat // a->b payment

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments //.copy(channelVersion = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.channelVersion.copy(commitmentFormat = ))
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > p) // alice can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val paymentScalar = randomKey
    val paymentPoint = paymentScalar.publicKey
    val tweak = randomKey

    val (pp, tw, cmdAdd) = makeCmdAddPtlc(p, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight, paymentPoint, tweak, nextPaymentPoint = paymentPoint + tweak.publicKey, nextPointTweak = tweak)
    val Right((ac1, add)) = sendAddPtlc(ac0, cmdAdd, currentBlockHeight, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac1.availableBalanceForSend == a - p - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val Success(bc1) = receiveAddPtlc(bc0, add, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc1.availableBalanceForSend == b)
    assert(bc1.availableBalanceForReceive == a - p - fee)

    val Success((ac2, commit1)) = sendCommit(ac1, alice.underlyingActor.nodeParams.keyManager)
    assert(ac2.availableBalanceForSend == a - p - fee)
    assert(ac2.availableBalanceForReceive == b)

    val Success((bc2, revocation1)) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.keyManager)
    assert(bc2.availableBalanceForSend == b)
    assert(bc2.availableBalanceForReceive == a - p - fee)

    val Success((ac3, _)) = receiveRevocation(ac2, revocation1)
    assert(ac3.availableBalanceForSend == a - p - fee)
    assert(ac3.availableBalanceForReceive == b)

    val Success((bc3, commit2)) = sendCommit(bc2, bob.underlyingActor.nodeParams.keyManager)
    assert(bc3.availableBalanceForSend == b)
    assert(bc3.availableBalanceForReceive == a - p - fee)

    val Success((ac4, revocation2)) = receiveCommit(ac3, commit2, alice.underlyingActor.nodeParams.keyManager)
    assert(ac4.availableBalanceForSend == a - p - fee)
    assert(ac4.availableBalanceForReceive == b)
    val Success((bc4, _)) = receiveRevocation(bc3, revocation2)
    assert(bc4.availableBalanceForSend == b)
    assert(bc4.availableBalanceForReceive == a - p - fee)

    val cmdFulfill = CMD_FULFILL_PTLC(0, paymentScalar + tweak, paymentPoint)
    val Success((bc5, fulfill)) = sendFulfillPtlc(bc4, cmdFulfill, bob.underlyingActor.nodeParams.privateKey)
    assert(bc5.availableBalanceForSend == b + p) // as soon as we have the fulfill, the balance increases
    assert(bc5.availableBalanceForReceive == a - p - fee)

    val Success((ac5, _, _)) = receiveFulfillPtlc(ac4, fulfill)
    assert(ac5.availableBalanceForSend == a - p - fee)
    assert(ac5.availableBalanceForReceive == b + p)

    val Success((bc6, commit3)) = sendCommit(bc5, bob.underlyingActor.nodeParams.keyManager)
    assert(bc6.availableBalanceForSend == b + p)
    assert(bc6.availableBalanceForReceive == a - p - fee)

    val Success((ac6, revocation3)) = receiveCommit(ac5, commit3, alice.underlyingActor.nodeParams.keyManager)
    assert(ac6.availableBalanceForSend == a - p)
    assert(ac6.availableBalanceForReceive == b + p)

    val Success((bc7, _)) = receiveRevocation(bc6, revocation3)
    assert(bc7.availableBalanceForSend == b + p)
    assert(bc7.availableBalanceForReceive == a - p)

    val Success((ac7, commit4)) = sendCommit(ac6, alice.underlyingActor.nodeParams.keyManager)
    assert(ac7.availableBalanceForSend == a - p)
    assert(ac7.availableBalanceForReceive == b + p)

    val Success((bc8, revocation4)) = receiveCommit(bc7, commit4, bob.underlyingActor.nodeParams.keyManager)
    assert(bc8.availableBalanceForSend == b + p)
    assert(bc8.availableBalanceForReceive == a - p)

    val Success((ac8, _)) = receiveRevocation(ac7, revocation4)
    assert(ac8.availableBalanceForSend == a - p)
    assert(ac8.availableBalanceForReceive == b + p)
  }

  test("correct values for availableForSend/availableForReceive (failure case)") { f =>
    import f._

    val fee = 1720000 msat // fee due to the additional htlc output
    val funderFeeReserve = fee * 2 // extra reserve to handle future fee increase
    val a = (772760000 msat) - fee - funderFeeReserve // initial balance alice
    val b = 190000000 msat // initial balance bob
    val p = 42000000 msat // a->b payment

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > p) // alice can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val paymentScalar = randomKey
    val paymentPoint = paymentScalar.publicKey
    val tweak = randomKey

    val (_, _, cmdAdd) = makeCmdAddPtlc(p, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight, paymentPoint, tweak, nextPaymentPoint = paymentPoint + tweak.publicKey)
    val Right((ac1, add)) = sendAddPtlc(ac0, cmdAdd, currentBlockHeight, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac1.availableBalanceForSend == a - p - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val Success(bc1) = receiveAddPtlc(bc0, add, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc1.availableBalanceForSend == b)
    assert(bc1.availableBalanceForReceive == a - p - fee)

    val Success((ac2, commit1)) = sendCommit(ac1, alice.underlyingActor.nodeParams.keyManager)
    assert(ac2.availableBalanceForSend == a - p - fee)
    assert(ac2.availableBalanceForReceive == b)

    val Success((bc2, revocation1)) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.keyManager)
    assert(bc2.availableBalanceForSend == b)
    assert(bc2.availableBalanceForReceive == a - p - fee)

    val Success((ac3, _)) = receiveRevocation(ac2, revocation1)
    assert(ac3.availableBalanceForSend == a - p - fee)
    assert(ac3.availableBalanceForReceive == b)

    val Success((bc3, commit2)) = sendCommit(bc2, bob.underlyingActor.nodeParams.keyManager)
    assert(bc3.availableBalanceForSend == b)
    assert(bc3.availableBalanceForReceive == a - p - fee)

    val Success((ac4, revocation2)) = receiveCommit(ac3, commit2, alice.underlyingActor.nodeParams.keyManager)
    assert(ac4.availableBalanceForSend == a - p - fee)
    assert(ac4.availableBalanceForReceive == b)

    val Success((bc4, _)) = receiveRevocation(bc3, revocation2)
    assert(bc4.availableBalanceForSend == b)
    assert(bc4.availableBalanceForReceive == a - p - fee)

    val cmdFail = CMD_FAIL_PTLC(0, Right(IncorrectOrUnknownPaymentDetails(p, 42)))
    val Success((bc5, fail)) = sendFailPtlc(bc4, cmdFail, bob.underlyingActor.nodeParams.privateKey)
    assert(bc5.availableBalanceForSend == b)
    assert(bc5.availableBalanceForReceive == a - p - fee) // a's balance won't return to previous before she acknowledges the fail

    val Success((ac5, _, _)) = receiveFailPtlc(ac4, fail)
    assert(ac5.availableBalanceForSend == a - p - fee)
    assert(ac5.availableBalanceForReceive == b)

    val Success((bc6, commit3)) = sendCommit(bc5, bob.underlyingActor.nodeParams.keyManager)
    assert(bc6.availableBalanceForSend == b)
    assert(bc6.availableBalanceForReceive == a - p - fee)

    val Success((ac6, revocation3)) = receiveCommit(ac5, commit3, alice.underlyingActor.nodeParams.keyManager)
    assert(ac6.availableBalanceForSend == a)
    assert(ac6.availableBalanceForReceive == b)

    val Success((bc7, _)) = receiveRevocation(bc6, revocation3)
    assert(bc7.availableBalanceForSend == b)
    assert(bc7.availableBalanceForReceive == a)

    val Success((ac7, commit4)) = sendCommit(ac6, alice.underlyingActor.nodeParams.keyManager)
    assert(ac7.availableBalanceForSend == a)
    assert(ac7.availableBalanceForReceive == b)

    val Success((bc8, revocation4)) = receiveCommit(bc7, commit4, bob.underlyingActor.nodeParams.keyManager)
    assert(bc8.availableBalanceForSend == b)
    assert(bc8.availableBalanceForReceive == a)

    val Success((ac8, _)) = receiveRevocation(ac7, revocation4)
    assert(ac8.availableBalanceForSend == a)
    assert(ac8.availableBalanceForReceive == b)
  }

  test("correct values for availableForSend/availableForReceive (multiple htlcs)") { f =>
    import f._

    val fee = 1720000 msat // fee due to the additional htlc output
    val funderFeeReserve = fee * 2 // extra reserve to handle future fee increase
    val a = (772760000 msat) - fee - funderFeeReserve // initial balance alice
    val b = 190000000 msat // initial balance bob
    val p1 = 10000000 msat // a->b payment
    val p2 = 20000000 msat // a->b payment
    val p3 = 40000000 msat // b->a payment

    val ac0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bc0 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments

    assert(ac0.availableBalanceForSend > (p1 + p2)) // alice can afford the payments
    assert(bc0.availableBalanceForSend > p3) // bob can afford the payment
    assert(ac0.availableBalanceForSend == a)
    assert(ac0.availableBalanceForReceive == b)
    assert(bc0.availableBalanceForSend == b)
    assert(bc0.availableBalanceForReceive == a)

    val paymentScalar1 = randomKey
    val paymentPoint1 = paymentScalar1.publicKey
    val tweak1 = randomKey

    val paymentScalar2 = randomKey
    val paymentPoint2 = paymentScalar2.publicKey
    val tweak2 = randomKey

    val paymentScalar3 = randomKey
    val paymentPoint3 = paymentScalar3.publicKey
    val tweak3 = randomKey

    val (_, _, cmdAdd1) = makeCmdAddPtlc(p1, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight, paymentPoint1, tweak1, nextPaymentPoint = paymentPoint1 + tweak1.publicKey, nextPointTweak = tweak1)
    val Right((ac1, add1)) = sendAddPtlc(ac0, cmdAdd1, currentBlockHeight, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac1.availableBalanceForSend == a - p1 - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac1.availableBalanceForReceive == b)

    val (_, _, cmdAdd2) = makeCmdAddPtlc(p2, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight, paymentPoint2, tweak2, nextPaymentPoint = paymentPoint2 + tweak2.publicKey, nextPointTweak = tweak2)
    val Right((ac2, add2)) = sendAddPtlc(ac1, cmdAdd2, currentBlockHeight, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac2.availableBalanceForSend == a - p1 - fee - p2 - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
    assert(ac2.availableBalanceForReceive == b)

    val (_, _, cmdAdd3) = makeCmdAddPtlc(p3, alice.underlyingActor.nodeParams.nodeId, currentBlockHeight, paymentPoint3, tweak3, nextPaymentPoint = paymentPoint3 + tweak3.publicKey, nextPointTweak = tweak3)
    val Right((bc1, add3)) = sendAddPtlc(bc0, cmdAdd3, currentBlockHeight, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc1.availableBalanceForSend == b - p3) // bob doesn't pay the fee
    assert(bc1.availableBalanceForReceive == a)

    val Success(bc2) = receiveAddPtlc(bc1, add1, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc2.availableBalanceForSend == b - p3)
    assert(bc2.availableBalanceForReceive == a - p1 - fee)

    val Success(bc3) = receiveAddPtlc(bc2, add2, bob.underlyingActor.nodeParams.onChainFeeConf)
    assert(bc3.availableBalanceForSend == b - p3)
    assert(bc3.availableBalanceForReceive == a - p1 - fee - p2 - fee)

    val Success(ac3) = receiveAddPtlc(ac2, add3, alice.underlyingActor.nodeParams.onChainFeeConf)
    assert(ac3.availableBalanceForSend == a - p1 - fee - p2 - fee)
    assert(ac3.availableBalanceForReceive == b - p3)

    val Success((ac4, commit1)) = sendCommit(ac3, alice.underlyingActor.nodeParams.keyManager)
    assert(ac4.availableBalanceForSend == a - p1 - fee - p2 - fee)
    assert(ac4.availableBalanceForReceive == b - p3)

    val Success((bc4, revocation1)) = receiveCommit(bc3, commit1, bob.underlyingActor.nodeParams.keyManager)
    assert(bc4.availableBalanceForSend == b - p3)
    assert(bc4.availableBalanceForReceive == a - p1 - fee - p2 - fee)

    val Success((ac5, _)) = receiveRevocation(ac4, revocation1)
    assert(ac5.availableBalanceForSend == a - p1 - fee - p2 - fee)
    assert(ac5.availableBalanceForReceive == b - p3)

    val Success((bc5, commit2)) = sendCommit(bc4, bob.underlyingActor.nodeParams.keyManager)
    assert(bc5.availableBalanceForSend == b - p3)
    assert(bc5.availableBalanceForReceive == a - p1 - fee - p2 - fee)

    val Success((ac6, revocation2)) = receiveCommit(ac5, commit2, alice.underlyingActor.nodeParams.keyManager)
    assert(ac6.availableBalanceForSend == a - p1 - fee - p2 - fee - fee) // alice has acknowledged b's hltc so it needs to pay the fee for it
    assert(ac6.availableBalanceForReceive == b - p3)

    val Success((bc6, _)) = receiveRevocation(bc5, revocation2)
    assert(bc6.availableBalanceForSend == b - p3)
    assert(bc6.availableBalanceForReceive == a - p1 - fee - p2 - fee - fee)

    val Success((ac7, commit3)) = sendCommit(ac6, alice.underlyingActor.nodeParams.keyManager)
    assert(ac7.availableBalanceForSend == a - p1 - fee - p2 - fee - fee)
    assert(ac7.availableBalanceForReceive == b - p3)

    val Success((bc7, revocation3)) = receiveCommit(bc6, commit3, bob.underlyingActor.nodeParams.keyManager)
    assert(bc7.availableBalanceForSend == b - p3)
    assert(bc7.availableBalanceForReceive == a - p1 - fee - p2 - fee - fee)

    val Success((ac8, _)) = receiveRevocation(ac7, revocation3)
    assert(ac8.availableBalanceForSend == a - p1 - fee - p2 - fee - fee)
    assert(ac8.availableBalanceForReceive == b - p3)

    val cmdFulfill1 = CMD_FULFILL_PTLC(0, paymentScalar1 + tweak1, paymentPoint1)
    val Success((bc8, fulfill1)) = sendFulfillPtlc(bc7, cmdFulfill1, bob.underlyingActor.nodeParams.privateKey)
    assert(bc8.availableBalanceForSend == b + p1 - p3) // as soon as we have the fulfill, the balance increases
    assert(bc8.availableBalanceForReceive == a - p1 - fee - p2 - fee - fee)

    val cmdFail2 = CMD_FAIL_PTLC(1, Right(IncorrectOrUnknownPaymentDetails(p2, 42)))
    val Success((bc9, fail2)) = sendFailPtlc(bc8, cmdFail2, bob.underlyingActor.nodeParams.privateKey)
    assert(bc9.availableBalanceForSend == b + p1 - p3)
    assert(bc9.availableBalanceForReceive == a - p1 - fee - p2 - fee - fee) // a's balance won't return to previous before she acknowledges the fail

    val cmdFulfill3 = CMD_FULFILL_PTLC(0, paymentScalar3 + tweak3, paymentPoint3)
    val Success((ac9, fulfill3)) = sendFulfillPtlc(ac8, cmdFulfill3, alice.underlyingActor.nodeParams.privateKey)
    assert(ac9.availableBalanceForSend == a - p1 - fee - p2 - fee + p3) // as soon as we have the fulfill, the balance increases
    assert(ac9.availableBalanceForReceive == b - p3)

    val Success((ac10, _, _)) = receiveFulfillPtlc(ac9, fulfill1)
    assert(ac10.availableBalanceForSend == a - p1 - fee - p2 - fee + p3)
    assert(ac10.availableBalanceForReceive == b + p1 - p3)

    val Success((ac11, _, _)) = receiveFailPtlc(ac10, fail2)
    assert(ac11.availableBalanceForSend == a - p1 - fee - p2 - fee + p3)
    assert(ac11.availableBalanceForReceive == b + p1 - p3)

    val Success((bc10, _, _)) = receiveFulfillPtlc(bc9, fulfill3)
    assert(bc10.availableBalanceForSend == b + p1 - p3)
    assert(bc10.availableBalanceForReceive == a - p1 - fee - p2 - fee + p3) // the fee for p3 disappears

    val Success((ac12, commit4)) = sendCommit(ac11, alice.underlyingActor.nodeParams.keyManager)
    assert(ac12.availableBalanceForSend == a - p1 - fee - p2 - fee + p3)
    assert(ac12.availableBalanceForReceive == b + p1 - p3)

    val Success((bc11, revocation4)) = receiveCommit(bc10, commit4, bob.underlyingActor.nodeParams.keyManager)
    assert(bc11.availableBalanceForSend == b + p1 - p3)
    assert(bc11.availableBalanceForReceive == a - p1 - fee - p2 - fee + p3)

    val Success((ac13, _)) = receiveRevocation(ac12, revocation4)
    assert(ac13.availableBalanceForSend == a - p1 - fee - p2 - fee + p3)
    assert(ac13.availableBalanceForReceive == b + p1 - p3)

    val Success((bc12, commit5)) = sendCommit(bc11, bob.underlyingActor.nodeParams.keyManager)
    assert(bc12.availableBalanceForSend == b + p1 - p3)
    assert(bc12.availableBalanceForReceive == a - p1 - fee - p2 - fee + p3)

    val Success((ac14, revocation5)) = receiveCommit(ac13, commit5, alice.underlyingActor.nodeParams.keyManager)
    assert(ac14.availableBalanceForSend == a - p1 + p3)
    assert(ac14.availableBalanceForReceive == b + p1 - p3)

    val Success((bc13, _)) = receiveRevocation(bc12, revocation5)
    assert(bc13.availableBalanceForSend == b + p1 - p3)
    assert(bc13.availableBalanceForReceive == a - p1 + p3)

    val Success((ac15, commit6)) = sendCommit(ac14, alice.underlyingActor.nodeParams.keyManager)
    assert(ac15.availableBalanceForSend == a - p1 + p3)
    assert(ac15.availableBalanceForReceive == b + p1 - p3)

    val Success((bc14, revocation6)) = receiveCommit(bc13, commit6, bob.underlyingActor.nodeParams.keyManager)
    assert(bc14.availableBalanceForSend == b + p1 - p3)
    assert(bc14.availableBalanceForReceive == a - p1 + p3)

    val Success((ac16, _)) = receiveRevocation(ac15, revocation6)
    assert(ac16.availableBalanceForSend == a - p1 + p3)
    assert(ac16.availableBalanceForReceive == b + p1 - p3)
  }

  // See https://github.com/lightningnetwork/lightning-rfc/issues/728
  test("funder keeps additional reserve to avoid channel being stuck") { f =>
    val isFunder = true
    val c = CommitmentsSpec.makeCommitments(100000000 msat, 50000000 msat, FeeratePerKw(2500 sat), 546 sat, isFunder)
    val (_, _, cmdAdd) = makeCmdAddPtlc(c.availableBalanceForSend, randomKey.publicKey, f.currentBlockHeight)
    val Right((c1, _)) = sendAddPtlc(c, cmdAdd, f.currentBlockHeight, feeConfNoMismatch)
    assert(c1.availableBalanceForSend === 0.msat)

    // We should be able to handle a fee increase.
    val Success((c2, _)) = sendFee(c1, CMD_UPDATE_FEE(FeeratePerKw(3000 sat)))

    // Now we shouldn't be able to send until we receive enough to handle the updated commit tx fee (even trimmed HTLCs shouldn't be sent).
    val (_, _, cmdAdd1) = makeCmdAddPtlc(100 msat, randomKey.publicKey, f.currentBlockHeight)
    val Left(_: InsufficientFunds) = sendAddPtlc(c2, cmdAdd1, f.currentBlockHeight, feeConfNoMismatch)
  }

  test("can send availableForSend") { f =>
    for (isFunder <- Seq(true, false)) {
      val c = CommitmentsSpec.makeCommitments(702000000 msat, 52000000 msat, FeeratePerKw(2679 sat), 546 sat, isFunder)
      val (_, _, cmdAdd) = makeCmdAddPtlc(c.availableBalanceForSend, randomKey.publicKey, f.currentBlockHeight)
      val result = sendAddPtlc(c, cmdAdd, f.currentBlockHeight, feeConfNoMismatch)
      assert(result.isRight, result)
    }
  }

  test("can receive availableForReceive") { f =>
    for (isFunder <- Seq(true, false)) {
      val c = CommitmentsSpec.makeCommitments(31000000 msat, 702000000 msat, FeeratePerKw(2679 sat), 546 sat, isFunder)
      val add = UpdateAddPtlc(randomBytes32, c.remoteNextHtlcId, c.availableBalanceForReceive, randomKey.publicKey, CltvExpiry(f.currentBlockHeight), TestConstants.emptyOnionPacket)
      receiveAddPtlc(c, add, feeConfNoMismatch)
    }
  }

  test("should always be able to send availableForSend", Tag("fuzzy")) { f =>
    val maxPendingHtlcAmount = 1000000.msat
    case class FuzzTest(isFunder: Boolean, pendingHtlcs: Int, feeRatePerKw: FeeratePerKw, dustLimit: Satoshi, toLocal: MilliSatoshi, toRemote: MilliSatoshi)
    for (_ <- 1 to 100) {
      val t = FuzzTest(
        isFunder = Random.nextInt(2) == 0,
        pendingHtlcs = Random.nextInt(10),
        feeRatePerKw = FeeratePerKw(Random.nextInt(10000).max(1).sat),
        dustLimit = Random.nextInt(1000).sat,
        // We make sure both sides have enough to send/receive at least the initial pending HTLCs.
        toLocal = maxPendingHtlcAmount * 2 * 10 + Random.nextInt(1000000000).msat,
        toRemote = maxPendingHtlcAmount * 2 * 10 + Random.nextInt(1000000000).msat)
      var c = CommitmentsSpec.makeCommitments(t.toLocal, t.toRemote, t.feeRatePerKw, t.dustLimit, t.isFunder)
      // Add some initial HTLCs to the pending list (bigger commit tx).
      for (_ <- 0 to t.pendingHtlcs) {
        val amount = Random.nextInt(maxPendingHtlcAmount.toLong.toInt).msat.max(1 msat)
        val (_, _, cmdAdd) = makeCmdAddPtlc(amount, randomKey.publicKey, f.currentBlockHeight)
        sendAddPtlc(c, cmdAdd, f.currentBlockHeight, feeConfNoMismatch) match {
          case Right((cc, _)) => c = cc
          case Left(e) => fail(s"$t -> could not setup initial htlcs: $e")
        }
      }
      val (_, _, cmdAdd) = makeCmdAddPtlc(c.availableBalanceForSend, randomKey.publicKey, f.currentBlockHeight)
      val result = sendAddPtlc(c, cmdAdd, f.currentBlockHeight, feeConfNoMismatch)
      assert(result.isRight, s"$t -> $result")
    }
  }

  test("should always be able to receive availableForReceive", Tag("fuzzy")) { f =>
    val maxPendingHtlcAmount = 1000000.msat
    case class FuzzTest(isFunder: Boolean, pendingHtlcs: Int, feeRatePerKw: FeeratePerKw, dustLimit: Satoshi, toLocal: MilliSatoshi, toRemote: MilliSatoshi)
    for (_ <- 1 to 100) {
      val t = FuzzTest(
        isFunder = Random.nextInt(2) == 0,
        pendingHtlcs = Random.nextInt(10),
        feeRatePerKw = FeeratePerKw(Random.nextInt(10000).max(1).sat),
        dustLimit = Random.nextInt(1000).sat,
        // We make sure both sides have enough to send/receive at least the initial pending HTLCs.
        toLocal = maxPendingHtlcAmount * 2 * 10 + Random.nextInt(1000000000).msat,
        toRemote = maxPendingHtlcAmount * 2 * 10 + Random.nextInt(1000000000).msat)
      var c = CommitmentsSpec.makeCommitments(t.toLocal, t.toRemote, t.feeRatePerKw, t.dustLimit, t.isFunder)
      // Add some initial HTLCs to the pending list (bigger commit tx).
      for (_ <- 0 to t.pendingHtlcs) {
        val amount = Random.nextInt(maxPendingHtlcAmount.toLong.toInt).msat.max(1 msat)
        val add = UpdateAddPtlc(randomBytes32, c.remoteNextHtlcId, amount, randomKey.publicKey, CltvExpiry(f.currentBlockHeight), TestConstants.emptyOnionPacket)
        receiveAddPtlc(c, add, feeConfNoMismatch) match {
          case Success(cc) => c = cc
          case Failure(e) => fail(s"$t -> could not setup initial htlcs: $e")
        }
      }
      val add = UpdateAddPtlc(randomBytes32, c.remoteNextHtlcId, c.availableBalanceForReceive, randomKey.publicKey, CltvExpiry(f.currentBlockHeight), TestConstants.emptyOnionPacket)
      receiveAddPtlc(c, add, feeConfNoMismatch) match {
        case Success(_) => ()
        case Failure(e) => fail(s"$t -> $e")
      }
    }
  }

}

