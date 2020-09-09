package fr.acinq.eclair.blockchain.bitcoins

import java.nio.file.Path

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.pipe
import akka.testkit.{TestKitBase, TestProbe}
import com.typesafe.config.Config
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.LongToBtcAmount
import fr.acinq.eclair.blockchain.OnChainBalance
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import grizzled.slf4j.Logging
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.json4s.JValue
import org.json4s.JsonAST.{JInt, JObject, JString}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}

trait NeutrinoService extends BitcoindService {
  self: TestKitBase =>

  val peerConfig: Config

  def newWallet(bitcoinCli: ActorRef)(implicit system: ActorSystem): NeutrinoWallet = {
    val datadir: Path = BitcoinSTestAppConfig.tmpDir()
    val wallet = NeutrinoWallet
      .fromDatadir(datadir, Block.RegtestGenesisBlock.hash, overrideConfig = peerConfig)
    wallet.sync()
    waitForNeutrinoSynced(wallet, bitcoinCli)
    wallet
  }

  def fundWallet(wallet: NeutrinoWallet, bitcoinCli: ActorRef, amountBtc: Double)(implicit system: ActorSystem): Unit = {
    val sender = TestProbe()

    wallet.getBalance.pipeTo(sender.ref)
    val balance = sender.expectMsgType[OnChainBalance]
    logger.debug(s"initial balance: $balance")

    // send money to our wallet
    wallet.getReceiveAddress.pipeTo(sender.ref)
    val a = sender.expectMsgType[String]
    logger.debug(s"sending 2 btc to $a")
    sender.send(bitcoinCli, BitcoinReq("sendtoaddress", a, amountBtc))
    val JString(txid) = sender.expectMsgType[JString]
    logger.debug(txid)

    awaitAssert({
      generateBlocks(bitcoinCli, 1)
      waitForNeutrinoSynced(wallet, bitcoinCli)

      wallet.getBalance.pipeTo(sender.ref)
      val balance1 = sender.expectMsgType[OnChainBalance]
      logger.debug(s"cur balance: $balance1")
      assert(balance1.confirmed + balance1.unconfirmed == balance.confirmed + balance.unconfirmed + (amountBtc * 100000000).toInt.sat)
    }, max = 30 seconds, interval = 2 second)
  }

  def getBestHeight(bitcoinCli: ActorRef, timeout: FiniteDuration = 10 seconds)(implicit system: ActorSystem): Int = {
    val sender = TestProbe()
    sender.send(bitcoinCli, BitcoinReq("getbestblockhash"))
    val JString(blockHash) = sender.expectMsgType[JValue](timeout)
    sender.send(bitcoinCli, BitcoinReq("getblock", blockHash))
    val JObject(block) = sender.expectMsgType[JValue](timeout)
    val height_opt = block.collectFirst { case ("height", JInt(height)) => height }
    assert(height_opt.nonEmpty)
    height_opt.get.toInt
  }

  def waitForNeutrinoSynced(wallet: NeutrinoWallet, bitcoinCli: ActorRef): Unit = {
    val sender = TestProbe()
    awaitCond({
      (for {
        bestHeight <- wallet.getFilterCount()
      } yield bestHeight).pipeTo(sender.ref)
      val walletBestHeight = sender.expectMsgType[Int]

      val networkBestHeight = getBestHeight(bitcoinCli)

      logger.debug(s"$walletBestHeight $networkBestHeight")

      val res = walletBestHeight == networkBestHeight
      Thread.sleep(500)
      res
    }, max = 60 seconds, interval = 2 second)

  }

}
