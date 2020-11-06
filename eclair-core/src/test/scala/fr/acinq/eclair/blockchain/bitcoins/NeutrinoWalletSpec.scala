package fr.acinq.eclair.blockchain.bitcoins

import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{Block, Btc, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet.{FundTransactionResponse, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{MakeFundingTxResponse, OnChainBalance}
import fr.acinq.eclair.{LongToBtcAmount, TestKitBaseClass}
import org.bitcoins.core.protocol.BitcoinAddress
import org.json4s.JsonAST.{JDecimal, JString}
import org.json4s.{DefaultFormats, JValue}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}

@DoNotDiscover
class NeutrinoWalletSpec extends TestKitBaseClass with NeutrinoService with AnyFunSuiteLike with BeforeAndAfterAll {

  val peerConfig = ConfigFactory.parseString(s"""bitcoin-s.node.peers = ["localhost:${bitcoindPort}"]""")

  val sender: TestProbe = TestProbe()
  val listener: TestProbe = TestProbe()
  val timeout: FiniteDuration = 30.seconds

  implicit val formats: DefaultFormats.type = DefaultFormats

  override def beforeAll(): Unit = {
    logger.info("starting bitcoind")
    startBitcoind()
    waitForBitcoindReady()
    logger.debug(s"beforeAll() ${getBestHeight(bitcoincli)}")
    generateBlocks(bitcoincli, 110)
    logger.debug(s"beforeAll() ${getBestHeight(bitcoincli)}")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    logger.info("stopping bitcoind")
    stopBitcoind()
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  test("process a block") {
    val wallet = newWallet(bitcoincli)

    wallet.getBalance.pipeTo(sender.ref)
    assert(sender.expectMsgType[OnChainBalance](timeout) == OnChainBalance(0 sat, 0 sat))

    wallet.getReceiveAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String](timeout)

    awaitAssert({
      generateBlocks(bitcoincli, 1, Some(address))
      wallet.getBalance.pipeTo(sender.ref)
      val balance = sender.expectMsgType[OnChainBalance](timeout)
      assert(balance.confirmed + balance.unconfirmed > 0.sat)
    }, max = 10 seconds, interval = 1 second)
  }


  test("receive funds") {
    val wallet = newWallet(bitcoincli)

    wallet.getBalance.pipeTo(sender.ref)
    val balance = sender.expectMsgType[OnChainBalance](timeout)
    logger.debug(s"initial balance: $balance")

    // send money to our wallet
    wallet.getReceiveAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String](timeout)

    logger.debug(s"sending 1 btc to $address")
    sender.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    sender.expectMsgType[JValue](timeout)

    wallet.getReceiveAddress.pipeTo(sender.ref)
    val address1 = sender.expectMsgType[String](timeout)

    logger.debug(s"sending 1 btc to $address1")
    sender.send(bitcoincli, BitcoinReq("sendtoaddress", address1, 1.0))
    sender.expectMsgType[JValue](timeout)
    logger.debug(s"sending 0.5 btc to $address1")
    sender.send(bitcoincli, BitcoinReq("sendtoaddress", address1, 0.5))
    sender.expectMsgType[JValue](timeout)

    awaitAssert({
      generateBlocks(bitcoincli, 1)
      waitForNeutrinoSynced(wallet, bitcoincli)

      wallet.getBalance.pipeTo(sender.ref)
      val balance1 = sender.expectMsgType[OnChainBalance](timeout)
      logger.debug(balance1)
      assert(balance1.confirmed + balance1.unconfirmed == balance.confirmed + balance.unconfirmed + 250000000.sat)
    }, max = 10 seconds, interval = 1 second)
  }

  test("handle transactions with identical outputs to us") {
    val wallet = newWallet(bitcoincli)

    wallet.getBalance.pipeTo(sender.ref)
    val balance = sender.expectMsgType[OnChainBalance](timeout)
    logger.info(s"initial balance: $balance")

    // send money to our wallet
    val amount = 750000.sat
    wallet.getReceiveAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String](timeout)

    val tx = Transaction(version = 2,
      txIn = Nil,
      txOut = Seq(
        TxOut(amount, fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)),
        TxOut(amount, fr.acinq.eclair.addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash))
      ), lockTime = 0L)

    val btcWallet = new BitcoinCoreWallet(bitcoinrpcclient)
    val future = for {
      FundTransactionResponse(tx1, _, _) <- btcWallet.fundTransaction(tx, false, FeeratePerKw(Satoshi(10000)))
      SignTransactionResponse(tx2, true) <- btcWallet.signTransaction(tx1)
      res <- btcWallet.commit(tx2)
    } yield res

    future.pipeTo(sender.ref)
    sender.expectMsgType[Boolean](timeout)

    awaitAssert({
      // gen to junk address
      generateBlocks(bitcoincli, 1, Some("2NFyxovf6MyxfHqtVjstGzs6HeLqv92Nq4U"))
      waitForNeutrinoSynced(wallet, bitcoincli)

      wallet.getBalance.pipeTo(sender.ref)
      val balance1 = sender.expectMsgType[OnChainBalance](timeout)
      assert(balance1.unconfirmed + balance1.confirmed == balance.unconfirmed + balance.confirmed + amount + amount)
    }, max = 10 seconds, interval = 1 second)
  }

  test("send money to someone else (we broadcast)") {
    val wallet = newWallet(bitcoincli)

    // send money to our wallet
    fundWallet(wallet, bitcoincli, 2.0)

    // create a tx that sends money to Bitcoin Core's address
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = sender.expectMsgType[JValue](timeout)
    val addr = BitcoinAddress.fromString(address)
    wallet.makeFundingTx(addr.scriptPubKey.asmBytes, Btc(1).toSatoshi, FeeratePerKw(Satoshi(10000))).pipeTo(sender.ref)
    val tx = sender.expectMsgType[MakeFundingTxResponse](timeout).fundingTx

    // send it ourselves
    logger.debug(s"sending 1 btc to $address with tx ${tx.txid}")
    wallet.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsgType[String](timeout)
    logger.debug(tx)

    awaitAssert({
      generateBlocks(bitcoincli, 1, Some("2NFyxovf6MyxfHqtVjstGzs6HeLqv92Nq4U"))
      waitForNeutrinoSynced(wallet, bitcoincli)

      sender.send(bitcoincli, BitcoinReq("getreceivedbyaddress", address))
      val JDecimal(value) = sender.expectMsgType[JValue](timeout)
      logger.debug(value)
      assert(value == BigDecimal(1.0))
    }, max = 10 seconds, interval = 1 second)
  }

  test("send money to ourselves (we broadcast)") {
    val wallet = newWallet(bitcoincli)

    // send money to our wallet
    fundWallet(wallet, bitcoincli, 2.0)

    wallet.getBalance.pipeTo(sender.ref)
    val balance = sender.expectMsgType[OnChainBalance](timeout)
    logger.info(s"initial balance: $balance")

    // create a tx that sends money to our address
    wallet.getReceiveAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String](timeout)

    val addr = BitcoinAddress.fromString(address)
    wallet.makeFundingTx(addr.scriptPubKey.asmBytes, Btc(1).toSatoshi, FeeratePerKw(Satoshi(350))).pipeTo(sender.ref)
    val tx = sender.expectMsgType[MakeFundingTxResponse](timeout).fundingTx

    // send it ourselves
    logger.info(s"sending 1 btc to $address with tx ${tx.txid}")
    wallet.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsgType[String](timeout)

    awaitCond({
      generateBlocks(bitcoincli, 1)
      waitForNeutrinoSynced(wallet, bitcoincli)

      wallet.getBalance.pipeTo(sender.ref)
      val balance1 = sender.expectMsgType[OnChainBalance](timeout)
      logger.debug(s"current balance is $balance1")
      balance1.confirmed + balance1.unconfirmed < balance.confirmed + balance.unconfirmed && balance1.confirmed + balance1.unconfirmed > balance.confirmed + balance.unconfirmed - 50000.sat
    }, max = 10 seconds, interval = 1 second)
  }

}