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

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi}

/**
 * Created by PM on 07/12/2016.
 */

sealed trait CommitmentOutput

object CommitmentOutput {

  case object ToLocal extends CommitmentOutput

  case object ToRemote extends CommitmentOutput

  case object ToLocalAnchor extends CommitmentOutput

  case object ToRemoteAnchor extends CommitmentOutput

  case class InHtlc(incomingHtlc: IncomingTlc) extends CommitmentOutput

  case class OutHtlc(outgoingHtlc: OutgoingTlc) extends CommitmentOutput

}

sealed trait DirectedTlc {
  def message: UpdateAddMessage
  def amountMsat: MilliSatoshi
  def paymentHash: ByteVector32
  def cltvExpiry: CltvExpiry
  def mkString: String
}

sealed trait IncomingTlc extends DirectedTlc
sealed trait OutgoingTlc extends DirectedTlc

sealed trait DirectedPtlc extends DirectedTlc {
  val add: UpdateAddPtlc

  override def message: UpdateAddMessage = add

  override def amountMsat: MilliSatoshi = add.amountMsat

  override def cltvExpiry: CltvExpiry = add.cltvExpiry

  override def paymentHash: ByteVector32 = add.paymentPoint

  override def mkString: String = s"${direction} ${add.id} ${add.cltvExpiry}"

  def opposite: DirectedPtlc = this match {
    case IncomingPtlc(_) => OutgoingPtlc(add)
    case OutgoingPtlc(_) => IncomingPtlc(add)
  }

  def direction: String = this match {
    case IncomingPtlc(_) => "IN"
    case OutgoingPtlc(_) => "OUT"
  }
}

case class IncomingPtlc(add: UpdateAddPtlc) extends DirectedPtlc with IncomingTlc

case class OutgoingPtlc(add: UpdateAddPtlc) extends DirectedPtlc with OutgoingTlc

sealed trait DirectedHtlc extends DirectedTlc{
  val add: UpdateAddHtlc

  override def message: UpdateAddMessage = add

  override def amountMsat: MilliSatoshi = add.amountMsat

  override def cltvExpiry: CltvExpiry = add.cltvExpiry

  override def paymentHash: ByteVector32 = add.paymentHash

  override def mkString: String = s"${direction} ${add.id} ${add.cltvExpiry}"

  def opposite: DirectedHtlc = this match {
    case IncomingHtlc(_) => OutgoingHtlc(add)
    case OutgoingHtlc(_) => IncomingHtlc(add)
  }

  def direction: String = this match {
    case IncomingHtlc(_) => "IN"
    case OutgoingHtlc(_) => "OUT"
  }
}

object DirectedTlc {
  def incoming: PartialFunction[DirectedTlc, UpdateAddMessage] = {
    case h: IncomingHtlc => h.add
    case h: IncomingPtlc => h.add
  }

  def outgoing: PartialFunction[DirectedTlc, UpdateAddMessage] = {
    case h: OutgoingHtlc => h.add
    case h: OutgoingPtlc => h.add
  }
}

case class IncomingHtlc(add: UpdateAddHtlc) extends DirectedHtlc with IncomingTlc

case class OutgoingHtlc(add: UpdateAddHtlc) extends DirectedHtlc with OutgoingTlc

final case class CommitmentSpec(htlcs: Set[DirectedTlc], feeratePerKw: FeeratePerKw, toLocal: MilliSatoshi, toRemote: MilliSatoshi) {

  def findIncomingHtlcById(id: Long): Option[IncomingHtlc] = htlcs.collectFirst { case htlc: IncomingHtlc if htlc.add.id == id => htlc }

  def findOutgoingHtlcById(id: Long): Option[OutgoingHtlc] = htlcs.collectFirst { case htlc: OutgoingHtlc if htlc.add.id == id => htlc }

  def findIncomingPtlcById(id: Long): Option[IncomingPtlc] = htlcs.collectFirst { case htlc: IncomingPtlc if htlc.add.id == id => htlc }

  def findOutgoingPtlcById(id: Long): Option[OutgoingPtlc] = htlcs.collectFirst { case htlc: OutgoingPtlc if htlc.add.id == id => htlc }

  val totalFunds: MilliSatoshi = toLocal + toRemote + htlcs.toSeq.map(_.amountMsat).sum
}

object CommitmentSpec {
  def removeHtlc(changes: List[UpdateMessage], id: Long): List[UpdateMessage] = changes.filterNot {
    case u: UpdateAddHtlc => u.id == id
    case u: UpdateAddPtlc => u.id == id
    case _ => false
  }

  def addHtlc(spec: CommitmentSpec, directedHtlc: DirectedTlc): CommitmentSpec = {
    directedHtlc match {
      case OutgoingHtlc(add) => spec.copy(toLocal = spec.toLocal - add.amountMsat, htlcs = spec.htlcs + directedHtlc)
      case IncomingHtlc(add) => spec.copy(toRemote = spec.toRemote - add.amountMsat, htlcs = spec.htlcs + directedHtlc)
      case OutgoingPtlc(add) => spec.copy(toLocal = spec.toLocal - add.amountMsat, htlcs = spec.htlcs + directedHtlc)
      case IncomingPtlc(add) => spec.copy(toRemote = spec.toRemote - add.amountMsat, htlcs = spec.htlcs + directedHtlc)
    }
  }

  def fulfillIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
    spec.findIncomingHtlcById(htlcId) match {
      case Some(htlc) => spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => spec.findIncomingPtlcById(htlcId) match {
        case Some(ptlc) => spec.copy(toLocal = spec.toLocal + ptlc.add.amountMsat, htlcs = spec.htlcs - ptlc)
        case None => throw new RuntimeException(s"cannot find ptlc id=$htlcId")
      }
    }
  }

  def fulfillOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
    spec.findOutgoingHtlcById(htlcId) match {
      case Some(htlc) => spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => spec.findOutgoingPtlcById(htlcId) match {
        case Some(ptlc) => spec.copy(toRemote = spec.toRemote + ptlc.add.amountMsat, htlcs = spec.htlcs - ptlc)
        case None => throw new RuntimeException(s"cannot find ptlc id=$htlcId")
      }
    }
  }

  def failIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
    spec.findIncomingHtlcById(htlcId) match {
      case Some(htlc) => spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None =>     spec.findIncomingPtlcById(htlcId) match {
        case Some(ptlc) => spec.copy(toRemote = spec.toRemote + ptlc.add.amountMsat, htlcs = spec.htlcs - ptlc)
        case None => throw new RuntimeException(s"cannot find ptlc id=$htlcId")
      }
    }
  }

  def failOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
    spec.findOutgoingHtlcById(htlcId) match {
      case Some(htlc) => spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None =>     spec.findOutgoingPtlcById(htlcId) match {
        case Some(ptlc) => spec.copy(toLocal = spec.toLocal + ptlc.add.amountMsat, htlcs = spec.htlcs - ptlc)
        case None => throw new RuntimeException(s"cannot find ptlc id=$htlcId")
      }
    }
  }

  def reduce(localCommitSpec: CommitmentSpec, localChanges: List[UpdateMessage], remoteChanges: List[UpdateMessage]): CommitmentSpec = {
    val spec1 = localChanges.foldLeft(localCommitSpec) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, OutgoingHtlc(u))
      case (spec, u: UpdateAddPtlc) => addHtlc(spec, OutgoingPtlc(u))
      case (spec, _) => spec
    }
    val spec2 = remoteChanges.foldLeft(spec1) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, IncomingHtlc(u))
      case (spec, u: UpdateAddPtlc) => addHtlc(spec, IncomingPtlc(u))
      case (spec, _) => spec
    }
    val spec3 = localChanges.foldLeft(spec2) {
      case (spec, u: UpdateFulfillHtlc) => fulfillIncomingHtlc(spec, u.id)
      case (spec, u: UpdateFailHtlc) => failIncomingHtlc(spec, u.id)
      case (spec, u: UpdateFailMalformedHtlc) => failIncomingHtlc(spec, u.id)
      case (spec, _) => spec
    }
    val spec4 = remoteChanges.foldLeft(spec3) {
      case (spec, u: UpdateFulfillHtlc) => fulfillOutgoingHtlc(spec, u.id)
      case (spec, u: UpdateFailHtlc) => failOutgoingHtlc(spec, u.id)
      case (spec, u: UpdateFailMalformedHtlc) => failOutgoingHtlc(spec, u.id)
      case (spec, _) => spec
    }
    val spec5 = (localChanges ++ remoteChanges).foldLeft(spec4) {
      case (spec, u: UpdateFee) => spec.copy(feeratePerKw = u.feeratePerKw)
      case (spec, _) => spec
    }
    spec5
  }

}