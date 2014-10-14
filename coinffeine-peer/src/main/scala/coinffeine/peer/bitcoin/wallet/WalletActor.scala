package coinffeine.peer.bitcoin.wallet

import scala.collection.JavaConversions._
import scala.util.control.NonFatal

import akka.actor.{Address => _, _}

import coinffeine.model.bitcoin._
import coinffeine.model.currency.{Bitcoin, BitcoinBalance}
import coinffeine.model.exchange.{Both, ExchangeId}

private class WalletActor(
    properties: MutableWalletProperties,
    wallet: SmartWallet) extends Actor with ActorLogging {

  import WalletActor._

  private val blockedOutputs = new BlockedOutputs()
  private var listeners = Set.empty[ActorRef]

  override def preStart(): Unit = {
    subscribeToWalletChanges()
    updateBalance()
    updateWalletPrimaryKeys()
    updateActivity()
  }

  override def postStop(): Unit = {
    unsubscribeFromWalletChanges()
  }

  override val receive: Receive = {

    case req @ CreateDeposit(coinsId, signatures, amount, transactionFee) =>
      try {
        val inputs = blockedOutputs.use(coinsId, amount + transactionFee)
        val tx = wallet.createMultisignTransaction(inputs, signatures, amount, transactionFee)
        sender ! WalletActor.DepositCreated(req, tx)
      } catch {
        case NonFatal(ex) => sender ! WalletActor.DepositCreationError(req, ex)
      }

    case req @ WalletActor.CreateTransaction(amount, to) =>
      try {
        val tx = wallet.createTransaction(blockedOutputs.useUnblockedFunds(amount), amount, to)
        sender ! WalletActor.TransactionCreated(req, tx)
      } catch {
        case NonFatal(ex) => sender ! WalletActor.TransactionCreationFailure(req, ex)
      }

    case WalletActor.ReleaseDeposit(tx) =>
      val releasedOutputs = wallet.releaseTransaction(tx)
      blockedOutputs.cancelUsage(releasedOutputs)

    case CreateKeyPair =>
      sender() ! KeyPairCreated(wallet.freshKeyPair())

    case InternalWalletChanged =>
      updateBalance()
      updateActivity()
      notifyListeners()

    case BlockBitcoins(fundsId, amount) =>
      sender() ! blockedOutputs.block(fundsId, amount)
        .fold[BlockBitcoinsResponse](CannotBlockBitcoins)(BlockedBitcoins.apply)

    case UnblockBitcoins(id) =>
      blockedOutputs.unblock(id)

    case SubscribeToWalletChanges =>
      context.watch(sender())
      listeners += sender()

    case UnsubscribeToWalletChanges =>
      context.unwatch(sender())
      listeners -= sender()

    case Terminated(listener) =>
      listeners -= listener
  }

  private def updateActivity(): Unit = {
    val transactions = wallet.delegate.getTransactionsByTime
    properties.activity.set(WalletActivity(wallet.delegate, transactions: _*))
  }

  private def updateBalance(): Unit = {
    blockedOutputs.setSpendCandidates(wallet.spendCandidates.toSet)
    properties.balance.set(Some(BitcoinBalance(
      estimated = wallet.estimatedBalance,
      available = wallet.availableBalance,
      minOutput = blockedOutputs.minOutput,
      blocked = blockedOutputs.blocked
    )))
  }

  private def updateWalletPrimaryKeys(): Unit = {
    properties.primaryAddress.set(Some(wallet.currentReceiveAddress))
  }

  private def subscribeToWalletChanges(): Unit = {
    wallet.addListener(WalletListener)(context.dispatcher)
  }

  private def unsubscribeFromWalletChanges(): Unit = {
    wallet.removeListener(WalletListener)
  }

  private def notifyListeners(): Unit = {
    listeners.foreach(_ ! WalletChanged)
  }

  private object WalletListener extends SmartWallet.Listener {
    override def onChange() = {
      self ! InternalWalletChanged
    }
  }
}

object WalletActor {
  private[bitcoin] def props(properties: MutableWalletProperties, wallet: SmartWallet) =
    Props(new WalletActor(properties, wallet))

  private case object InternalWalletChanged

  /** A request message to create a new transaction. */
  case class CreateTransaction(amount: Bitcoin.Amount, to: Address)

  /** A successful response to a transaction creation request. */
  case class TransactionCreated(req: CreateTransaction, tx: ImmutableTransaction)

  /** A failure response to a transaction creation request. */
  case class TransactionCreationFailure(req: CreateTransaction, failure: Throwable)

  /** Subscribe to wallet changes. The sender will receive [[WalletChanged]] after sending this
    * message to the wallet actor and until being stopped or sending [[UnsubscribeToWalletChanges]].
    */
  case object SubscribeToWalletChanges
  case object UnsubscribeToWalletChanges
  case object WalletChanged

  /** A message sent to the wallet actor to block an amount of coins for exclusive use. */
  case class BlockBitcoins(id: ExchangeId, amount: Bitcoin.Amount)

  /** Responses to [[BlockBitcoins]] */
  sealed trait BlockBitcoinsResponse

  /** Bitcoin amount was blocked successfully */
  case class BlockedBitcoins(id: ExchangeId) extends BlockBitcoinsResponse

  /** Cannot block the requested amount of bitcoins */
  case object CannotBlockBitcoins extends BlockBitcoinsResponse

  /** A message sent to the wallet actor to release for general use the previously blocked
    * bitcoins.
    */
  case class UnblockBitcoins(id: ExchangeId)

  /** A message sent to the wallet actor in order to create a multisigned deposit transaction.
    *
    * This message requests some funds to be mark as used by the wallet. This will produce a new
    * transaction included in a [[DepositCreated]] reply message, or [[DepositCreationError]] if
    * something goes wrong. The resulting transaction can be safely sent to the blockchain with the
    * guarantee that the outputs it spends are not used in any other transaction. If the transaction
    * is not finally broadcast to the blockchain, the funds can be unblocked by sending a
    * [[ReleaseDeposit]] message.
    *
    * @param coinsId            Source of the bitcoins to use for this deposit
    * @param requiredSignatures The signatures required to spend the tx in a multisign script
    * @param amount             The amount of bitcoins to be blocked and included in the transaction
    * @param transactionFee     The fee to include in the transaction
    */
  case class CreateDeposit(coinsId: ExchangeId,
                           requiredSignatures: Both[KeyPair],
                           amount: Bitcoin.Amount,
                           transactionFee: Bitcoin.Amount)

  /** A message sent by the wallet actor in reply to a [[CreateDeposit]] message to report
    * a successful funds blocking.
    *
    * @param request  The request this message is replying to
    * @param tx       The resulting transaction that contains the funds that have been blocked
    */
  case class DepositCreated(request: CreateDeposit, tx: ImmutableTransaction)

  /** A message sent by the wallet actor in reply to a [[CreateDeposit]] message to report
    * an error while blocking the funds.
    */
  case class DepositCreationError(request: CreateDeposit, error: Throwable)

  /** A message sent to the wallet actor in order to release the funds that of a non published
    * deposit.
    */
  case class ReleaseDeposit(tx: ImmutableTransaction)

  /** A message sent to the wallet actor to ask for a fresh key pair */
  case object CreateKeyPair

  /** Response to [[CreateKeyPair]] */
  case class KeyPairCreated(keyPair: KeyPair) {
    require(keyPair.canSign, s"Can't sign with $keyPair")
  }
}