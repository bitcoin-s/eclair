package fr.acinq.eclair.blockchain.bitcoins

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorLogging, Stash, Terminated}
import fr.acinq.bitcoin.{BlockHeader, ByteVector32, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.BITCOIN_PARENT_TX_CONFIRMED
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.{LongToBtcAmount, ShortChannelId, TxCoordinates}

import scala.collection.immutable.{Queue, SortedMap}
import scala.concurrent.ExecutionContext

// @formatter:off
sealed trait NeutrinoEvent
case class BestBlockHeader(height: Int, blockHeader: BlockHeader) extends NeutrinoEvent
case class BlockHeaderConnected(height: Int, blockHeader: BlockHeader) extends NeutrinoEvent
case class TransactionProcessed(height: Int, tx: Transaction, blockHash: ByteVector32, pos: Int) extends NeutrinoEvent
// @formatter:on

class NeutrinoWatcher(blockCount: AtomicLong, initialTip: BlockHeader, wallet: NeutrinoWallet)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with Stash with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[NeutrinoEvent])

  override def unhandled(message: Any): Unit = message match {
    case ValidateRequest(c) =>
      log.info(s"blindly validating channel=$c")
      val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(c.bitcoinKey1, c.bitcoinKey2)))
      val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
      val fakeFundingTx = Transaction(
        version = 2,
        txIn = Seq.empty[TxIn],
        txOut = List.fill(outputIndex + 1)(TxOut(0 sat, pubkeyScript)), // quick and dirty way to be sure that the outputIndex'th output is of the expected format
        lockTime = 0)
      sender ! ValidateResult(c, Right((fakeFundingTx, UtxoStatus.Unspent)))

    case _ => log.warning(s"unhandled message $message")
  }

  override def receive: Receive = running(blockCount.get().toInt, initialTip, Set.empty, Map.empty, SortedMap.empty, Queue.empty)

  def running(height: Int, tip: BlockHeader, watches: Set[Watch], scriptHashStatus: Map[ByteVector32, String], block2tx: SortedMap[Long, Seq[Transaction]], sent: Queue[Transaction]): Receive = {
    case BlockHeaderConnected(_, newtip) if tip == newtip => ()

    case BlockHeaderConnected(newheight, newtip) =>
      log.debug(s"new tip: ${newtip.blockId} $newheight")

      blockCount.set(newheight)

      val toPublish = block2tx.filterKeys(_ <= newheight)
      toPublish.values.flatten.foreach(tx => self ! PublishAsap(tx))
      context become running(newheight, newtip, watches, scriptHashStatus, block2tx -- toPublish.keys, sent)

    case watch: Watch if watches.contains(watch) => ()

    case watch@WatchSpent(_, txid, outputIndex, publicKeyScript, _) =>
      log.info(s"added watch-spent on output=$txid:$outputIndex publicKeyScript=$publicKeyScript")
      wallet.watchPublicKeyScript(publicKeyScript)
      context.watch(watch.replyTo)
      context become running(height, tip, watches + watch, scriptHashStatus, block2tx, sent)

    case watch@WatchSpentBasic(_, txid, outputIndex, publicKeyScript, _) =>
      log.info(s"added watch-spent-basic on output=$txid:$outputIndex publicKeyScript=$publicKeyScript")
      wallet.watchPublicKeyScript(publicKeyScript)
      context.watch(watch.replyTo)
      context become running(height, tip, watches + watch, scriptHashStatus, block2tx, sent)

    case watch@WatchConfirmed(_, txid, publicKeyScript, _, _) =>
      log.info(s"added watch-confirmed on txid=$txid publicKeyScript=$publicKeyScript")
      wallet.watchPublicKeyScript(publicKeyScript)
      context.watch(watch.replyTo)
      context become running(height, tip, watches + watch, scriptHashStatus, block2tx, sent)

    case _: WatchLost => () // TODO: not implemented

    case Terminated(actor) =>
      val watches1 = watches.filterNot(_.replyTo == actor)
      context become running(height, tip, watches1, scriptHashStatus, block2tx, sent)

    case TransactionProcessed(txheight, tx, _, pos) =>
      // this is for WatchSpent/WatchSpendBasic
      val watchSpentTriggered = tx.txIn.map(_.outPoint).flatMap(outPoint => watches.collect {
        case WatchSpent(channel, txid, pos, _, event) if txid == outPoint.txid && pos == outPoint.index.toInt =>
          log.info(s"output $txid:$pos spent by transaction ${tx.txid}")
          channel ! WatchEventSpent(event, tx)
          // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
          // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
          None
        case w@WatchSpentBasic(channel, txid, pos, _, event) if txid == outPoint.txid && pos == outPoint.index.toInt =>
          log.info(s"output $txid:$pos spent by transaction ${tx.txid}")
          channel ! WatchEventSpentBasic(event)
          Some(w)
      }).flatten

      // this is for WatchConfirmed
      val confirmations = height - txheight + 1
      val watchConfirmedTriggered = watches.collect {
        case w@WatchConfirmed(channel, txid, _, minDepth, event) if txid == tx.txid && confirmations >= minDepth =>
          log.info(s"txid=$txid had confirmations=$confirmations in block=$txheight pos=$pos")
          channel ! WatchEventConfirmed(event, txheight.toInt, pos, tx)
          w
      }
      context become running(height, tip, watches -- watchSpentTriggered -- watchConfirmedTriggered, scriptHashStatus, block2tx, sent)

    case PublishAsap(tx) =>
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn.head.outPoint.txid
        log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=$tx")
        val parentPublicKeyScript = WatchConfirmed.extractPublicKeyScript(tx.txIn.head.witness)
        self ! WatchConfirmed(self, parentTxid, parentPublicKeyScript, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx))
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx1, sent)
      } else {
        publish(wallet, tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx, sent :+ tx)
      }

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = this.blockCount.get()
      val csvTimeout = Scripts.csvTimeout(tx)
      val absTimeout = blockHeight + csvTimeout
      if (absTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$absTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(absTimeout, block2tx.getOrElse(absTimeout, Seq.empty[Transaction]) :+ tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx1, sent)
      } else {
        publish(wallet, tx)
        context become running(height, tip, watches, scriptHashStatus, block2tx, sent :+ tx)
      }

  }

  // NOTE: we use a single thread to publish transactions so that it preserves order.
  // CHANGING THIS WILL RESULT IN CONCURRENCY ISSUES WHILE PUBLISHING PARENT AND CHILD TXS
  val singleThreadExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  def publish(wallet: NeutrinoWallet, tx: Transaction): Unit = {
    log.info(s"publishing tx: txid=${tx.txid} tx=$tx")
    val published = wallet.publishTransaction(tx)(singleThreadExecutionContext)
    published.failed.foreach(log.error(s"cannot publish transaction ${tx.txid} ", _))
  }

}

