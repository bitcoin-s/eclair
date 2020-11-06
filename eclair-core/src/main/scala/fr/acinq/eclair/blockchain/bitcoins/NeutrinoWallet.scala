package fr.acinq.eclair.blockchain.bitcoins

import java.io.ByteArrayInputStream
import java.nio.file.{Path, Paths}

import akka.Done
import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi, Transaction, _}
import fr.acinq.eclair.blockchain.EclairWallet.Neutrino
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet.WalletTransaction
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import grizzled.slf4j.Logging
import org.bitcoins.chain.blockchain.ChainHandler
import org.bitcoins.chain.config.ChainAppConfig
import org.bitcoins.chain.models._
import org.bitcoins.chain.{ChainCallbacks, OnBlockHeaderConnected}
import org.bitcoins.core.api.wallet.db.SpendingInfoDb
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.hd.AddressType
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.util.NetworkUtil
import org.bitcoins.core.wallet.fee._
import org.bitcoins.core.wallet.utxo.TxoState
import org.bitcoins.crypto.DoubleSha256DigestBE
import org.bitcoins.feeprovider.BitcoinerLiveFeeRateProvider
import org.bitcoins.node._
import org.bitcoins.node.config.NodeAppConfig
import org.bitcoins.node.models.Peer
import org.bitcoins.wallet.Wallet
import org.bitcoins.wallet.config.WalletAppConfig
import scodec.bits.ByteVector

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Properties

class NeutrinoWallet(initialSyncDone: Option[Promise[Done]], bip39PasswordOpt: Option[String] = None)(implicit system: ActorSystem,
                                                                                                      walletConf: WalletAppConfig,
                                                                                                      nodeConf: NodeAppConfig,
                                                                                                      chainConf: ChainAppConfig) extends EclairWallet with Neutrino with Logging {
  logger.info("Initializing Bitcoin-S wallet")

  import system.dispatcher

  require(walletConf.defaultAddressType != AddressType.Legacy, "Must use segwit for LN")
  require(nodeConf.nodeType == NodeType.NeutrinoNode, "Must use Neutrino for LN")

  private val chainApiF = for {
    callbacks <- createChainCallbacks
    _ = chainConf.addCallbacks(callbacks)
    chainApi <- chainApiFromDb
    isMissingChainWork <- chainApi.isMissingChainWork
    chainApiWithWork <-
      if (isMissingChainWork) {
        chainApi.recalculateChainWork
      } else {
        Future.successful(chainApi)
      }
  } yield chainApiWithWork

  private def chainApiFromDb(): Future[ChainHandler] = {
    ChainHandler.fromDatabase(blockHeaderDAO = BlockHeaderDAO(),
      CompactFilterHeaderDAO(),
      CompactFilterDAO())
  }

  private val peerSocket =
    NetworkUtil.parseInetSocketAddress(nodeConf.peers.head,
      nodeConf.network.port)
  private val peer = Peer.fromSocket(peerSocket)
  private val uninitializedNodeF = chainApiF.flatMap { _ =>
    nodeConf.createNode(peer, initialSyncDone)(chainConf, system)
  }

  //get our wallet
  private val configuredWalletF: Future[Wallet] = for {
    uninitializedNode <- uninitializedNodeF
    chainApi <- chainApiF
    wallet <- walletConf.createHDWallet(uninitializedNode,
      chainApi,
      BitcoinerLiveFeeRateProvider(60))
//      bip39PasswordOpt)
  } yield {
    wallet
  }

  private val configuredNodeF: Future[Node] = for {
    uninitializedNode <- uninitializedNodeF
    wallet <- configuredWalletF
    nodeCallbacks <- createNodeCallbacks(wallet)
    _ = nodeConf.addCallbacks(nodeCallbacks)
  } yield {
    uninitializedNode
  }

  private val startedWalletF: Future[Wallet] = for {
    node <- configuredNodeF
    wallet <- configuredWalletF
    _ <- node.start()
    _ <- wallet.start()
  } yield {
    sys.addShutdownHook {
      wallet.stop()
      for {
        _ <- node.stop()
        _ = logger.info(s"Stopped neutrino node")
      } yield ()
    }
    wallet
  }

  startedWalletF.failed.foreach { err =>
    logger.error(s"Error on Neutrino wallet startup!", err)
  }

  def sync(): Future[Unit] =  for {
    node <- configuredNodeF
    _ <- node.sync()
    _ <- initialSyncDone.getOrElse(Promise.successful(Done)).future
  } yield ()

  def rescan(): Future[Unit] = configuredWalletF.flatMap(_.rescanNeutrinoWallet(startOpt = None, endOpt = None, addressBatchSize = 20, useCreationTime = true))

  def getBlockHeight(blockHash: DoubleSha256DigestBE): Future[Option[Int]] =
    chainApiFromDb().flatMap(_.getBlockHeight(blockHash))

  def getBestBlockHash(): Future[DoubleSha256DigestBE] =
    chainApiFromDb().flatMap(_.getBestBlockHash())

  def getBestBlockHeader(): Future[(Int, fr.acinq.bitcoin.BlockHeader)] = {
    for {
      chainApi <- chainApiFromDb()
      headerDb <- chainApi.getBestBlockHeader()
    } yield {
      (headerDb.height, toAcinqBlockHeader(headerDb.blockHeader))
    }
  }

  def getBlockCount(): Future[Int] = {
    for {
      chainApi <- chainApiFromDb()
      count <- chainApi.getBlockCount()
    } yield count
  }

  def getFilterHeaderCount(): Future[Int] = {
    for {
      chainApi <- chainApiFromDb()
      count <- chainApi.getFilterHeaderCount()
    } yield count
  }

  def getFilterCount(): Future[Int] = {
    for {
      chainApi <- chainApiFromDb()
      count <- chainApi.getFilterCount()
    } yield count
  }

  def listTransactions(count: Int, skip: Int): Future[List[WalletTransaction]] = {
    for {
      wallet <- startedWalletF
      utxos <- wallet.listUtxos()
    } yield {
      val txs = utxos.map { utxo =>
        WalletTransaction(
          address = BitcoinAddress.fromScriptPubKeyT(utxo.output.scriptPubKey, RegTest).get.toString,
          amount = Satoshi(utxo.output.value.satoshis.toLong),
          fees = Satoshi(0L),
          blockHash = utxo.blockHash.map(blockHash => ByteVector32(blockHash.bytes)).getOrElse(ByteVector32.Zeroes),
          confirmations = 0,
          txid = ByteVector32(utxo.txid.bytes),
          timestamp = 0
        )
      }
      txs.slice(skip, skip + count).toList
    }
  }

  def sendToAddress(address: String, amount: Satoshi, confirmationTarget: Long): Future[ByteVector32] = {
    for {
      wallet <- startedWalletF
      tx <- wallet.sendToAddress(BitcoinAddress.fromString(address), Satoshis.apply(amount.toLong), None)
    } yield ByteVector32(tx.txIdBE.bytes)
  }

  override def getBalance: Future[OnChainBalance] = {
    for {
      wallet <- startedWalletF
      confirmed <- wallet.getConfirmedBalance()
      unconfirmed <- wallet.getUnconfirmedBalance()
    } yield OnChainBalance(Satoshi(confirmed.satoshis.toLong), Satoshi(unconfirmed.satoshis.toLong))
  }

  override def getReceiveAddress: Future[String] = {
    for {
      wallet <- startedWalletF
      address <- wallet.getNewAddress()
    } yield address.value
  }

  override def getReceivePubkey(receiveAddress: Option[String] = None): Future[PublicKey] =
    for {
      wallet <- startedWalletF
      addressStr <- receiveAddress.map(Future.successful).getOrElse(getReceiveAddress)
      address = BitcoinAddress.fromString(addressStr)
      addressInfo <- wallet.getAddressInfo(address)
    } yield {
      addressInfo
        .map(info => PublicKey(info.pubkey.bytes))
        .getOrElse(throw new RuntimeException(s"cannot get receive pubkey: unknown address $address"))
    }

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw): Future[MakeFundingTxResponse] = {
    val spk = ScriptPubKey.fromAsmBytes(pubkeyScript)
    val sats = Satoshis(amount.toLong)
    val output = Vector(TransactionOutput(sats, spk))
    val feeRate = SatoshisPerKW(Satoshis(feeRatePerKw.feerate.toLong))

    for {
      wallet <- startedWalletF
      tx <- wallet.sendToOutputs(output, feeRate)
      eclairTx = fr.acinq.bitcoin.Transaction.read(tx.bytes.toArray)
      outputIndex = tx.outputs.zipWithIndex
        .find(_._1.scriptPubKey == spk).get._2
      fee = feeRate.calc(tx)
    } yield MakeFundingTxResponse(eclairTx, outputIndex, Satoshi(fee.satoshis.toLong))
  }

  override def watchPublicKeyScript(pubkeyScript: ByteVector): Future[Unit] = {
    val spk = ScriptPubKey.fromAsmBytes(pubkeyScript)
    for {
      wallet <- startedWalletF
      _ <- wallet.watchScriptPubKey(spk)
    } yield ()
  }

  override def commit(tx: Transaction): Future[Boolean] = {
    publishTransaction(tx).map(_ => true)
  }

  override def rollback(tx: Transaction): Future[Boolean] = {
    val bsTx = toBitcoinsTx(tx)
    val txOutPoints = bsTx.inputs.map(_.previousOutput)

    for {
      wallet <- startedWalletF
      utxos <- wallet.listUtxos(txOutPoints.toVector)
      _ <- wallet.unmarkUTXOsAsReserved(utxos.map(_.copyWithState(TxoState.Reserved)))
    } yield true

  }

  override def doubleSpent(tx: Transaction): Future[Boolean] =
    for {
      wallet <- startedWalletF
      txs <- wallet.listTransactions()
    } yield {
      txs
        .map(txn => toAcinqTransaction(txn.transaction))
        .exists { spendingTx =>
          spendingTx.txIn.map(_.outPoint).toSet.intersect(tx.txIn.map(_.outPoint).toSet).nonEmpty && spendingTx.txid != tx.txid
        }
    }

  def listReservedUtxos: Future[Vector[SpendingInfoDb]] = {
    for {
      wallet <- startedWalletF
      utxos <- wallet.listUtxos(TxoState.Reserved)
    } yield utxos
  }

  def listUtxos: Future[Vector[SpendingInfoDb]] = {
    for {
      wallet <- startedWalletF
      utxos <- wallet.listUtxos()
    } yield utxos
  }

  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] = {
    val txn = toBitcoinsTx(tx)
    for {
      wallet <- startedWalletF
      _ <- wallet.processTransaction(txn, None)
      node <- configuredNodeF
      _ <- node.broadcastTransaction(txn)
    } yield tx.txid.toHex
  }

  private def createChainCallbacks()(implicit
                                     chainConf: ChainAppConfig,
                                     ec: ExecutionContext): Future[ChainCallbacks] = {
    lazy val onHeaderConnected: OnBlockHeaderConnected = {
      case (height, header) =>
        Future.successful(system.eventStream.publish(BlockHeaderConnected(height, toAcinqBlockHeader(header))))
    }

    Future.successful(ChainCallbacks(onBlockHeaderConnected = Vector(onHeaderConnected)))
  }

  private def createNodeCallbacks(wallet: Wallet)(implicit
                                                  nodeConf: NodeAppConfig,
                                                  ec: ExecutionContext): Future[NodeCallbacks] = {
    lazy val onCompactFilters: OnCompactFiltersReceived = { blockFilters =>
      wallet
        .processCompactFilters(blockFilters = blockFilters)
        .map(_ => ())
    }
    lazy val onBlock: OnBlockReceived = { block =>
      for {
        _ <- wallet.processBlock(block)
        heightOpt <- getBlockHeight(block.blockHeader.hashBE).recover { ex =>
          logger.error(s"cannot find block height for ${block.blockHeader.hashBE} ", ex)
          None
        }
      } yield {
        heightOpt match {
          case Some(height) =>
            block.transactions.zipWithIndex.foreach { case (tx, pos) =>
              system.eventStream.publish(TransactionProcessed(height, toAcinqTransaction(tx), ByteVector32(block.blockHeader.hashBE.bytes), pos))
            }
          case None =>
            logger.error(s"unknown block hash ${block.blockHeader.hashBE}")
        }
      }
    }
    lazy val onHeaders: OnBlockHeadersReceived = { headers =>
      if (headers.isEmpty) {
        Future.unit
      } else {
        wallet.updateUtxoPendingStates().map(_ => ())
      }
    }
    Future.successful(
      NodeCallbacks(onBlockReceived = Vector(onBlock),
        onCompactFiltersReceived = Vector(onCompactFilters),
        onBlockHeadersReceived = Vector(onHeaders)
      ))
  }

  /** Initializes the wallet and starts the node */
  def start(): Future[NeutrinoWallet] =
    startedWalletF.map(_ => this)

  private def toBitcoinsTx(tx: fr.acinq.bitcoin.Transaction): org.bitcoins.core.protocol.transaction.Transaction = {
    org.bitcoins.core.protocol.transaction.Transaction.fromBytes(tx.bin)
  }

  private def toInputStream(bytes: ByteVector): ByteArrayInputStream = new ByteArrayInputStream(bytes.toArray)

  private def toAcinqBlockHeader(bitcoinSHeader: org.bitcoins.core.protocol.blockchain.BlockHeader): fr.acinq.bitcoin.BlockHeader =
    fr.acinq.bitcoin.BlockHeader.read(toInputStream(bitcoinSHeader.bytes))

  private def toAcinqTransaction(bitcoinSTx: org.bitcoins.core.protocol.transaction.Transaction): fr.acinq.bitcoin.Transaction =
    fr.acinq.bitcoin.Transaction.read(toInputStream(bitcoinSTx.bytes))
}

object NeutrinoWallet extends Logging {
  val defaultDatadir: Path = Paths.get(Properties.userHome, ".bitcoin-s")

  def fromDatadir(datadir: Path = defaultDatadir, chainHash: ByteVector32, overrideConfig: Config = ConfigFactory.empty())(implicit system: ActorSystem): NeutrinoWallet = {
    import system.dispatcher
    val networkConf = networkConfig(chainHash)
    val segwitConf = ConfigFactory.parseString("bitcoin-s.wallet.defaultAccountType = segwit")
    val sysProps = ConfigFactory.parseProperties(System.getProperties)

    implicit val walletConf: WalletAppConfig = WalletAppConfig(datadir, sysProps, segwitConf, networkConf, overrideConfig)

    implicit val nodeConf: NodeAppConfig = NodeAppConfig(datadir, sysProps, segwitConf, networkConf, overrideConfig)

    implicit val chainConf: ChainAppConfig = ChainAppConfig(datadir, sysProps, segwitConf, networkConf, overrideConfig)

    val initialSyncDone: Promise[Done] = if (chainConf.chain.network == RegTest) {
      // don't wait for full sync on regtest
      Promise.successful(Done)
    } else {
      Promise[Done]()
    }

    val wallet = for {
      _ <- chainConf.start()
      _ <- walletConf.start()
      _ <- nodeConf.start()
      started <- new NeutrinoWallet(Some(initialSyncDone)).start()
    } yield started

    Await.result(wallet, Duration.Inf)
  }

  def networkConfig(chainHash: ByteVector32): Config = {
    val network_opt = chainHash match {
      case Block.LivenetGenesisBlock.hash => Some("mainnet")
      case Block.TestnetGenesisBlock.hash => Some("testnet3")
      case Block.RegtestGenesisBlock.hash => Some("regtest")
      case _ =>
        logger.warn(s"Unknown chainHash ${chainHash}")
        None
    }
    network_opt
      .map(network => ConfigFactory.parseString(s"bitcoin-s.network = $network"))
      .getOrElse(ConfigFactory.empty())
  }
}
