package coinffeine.peer.amounts

import coinffeine.model.bitcoin.BitcoinFeeCalculator
import coinffeine.model.currency._
import coinffeine.model.exchange.Exchange._
import coinffeine.model.exchange._
import coinffeine.peer.amounts.StepwisePaymentCalculator.Payment

private[amounts] class DefaultAmountsCalculator(
    stepwiseCalculator: StepwisePaymentCalculator,
    bitcoinFeeCalculator: BitcoinFeeCalculator) extends AmountsCalculator {

  override def exchangeAmountsFor[C <: FiatCurrency](grossBitcoinAmount: Bitcoin.Amount,
                                                     grossFiatAmount: CurrencyAmount[C]) = {
    type FiatAmount = CurrencyAmount[C]
    require(grossBitcoinAmount.isPositive && grossFiatAmount.isPositive,
     s"Gross amounts must be positive ($grossBitcoinAmount, $grossFiatAmount given)")

    val txFee = bitcoinFeeCalculator.defaultTransactionFee
    val netBitcoinAmount = grossBitcoinAmount - txFee * HappyPathTransactions
    require(netBitcoinAmount.isPositive, "No net bitcoin amount to exchange")

    val netFiatAmount = stepwiseCalculator.maximumPaymentWithGrossAmount(grossFiatAmount)
    require(netFiatAmount.isPositive, "No net fiat amount to exchange")

    /** Fiat amount exchanged per step and its fee: best amount except for the last step  */
    val fiatBreakdown: Seq[Payment[C]] = stepwiseCalculator.breakIntoSteps(netFiatAmount)

    /** Bitcoin amount exchanged per step. It mirrors the fiat amounts with a rounding error of at
      * most one satoshi. */
    val bitcoinBreakdown: Seq[Bitcoin.Amount] = ProportionalAllocation
      .allocate(
        amount = netBitcoinAmount.units,
        weights = fiatBreakdown.map(_.netAmount.units).toVector)
      .map(satoshis => CurrencyAmount(satoshis, Bitcoin))

    val maxBitcoinStepSize: Bitcoin.Amount = bitcoinBreakdown.max

    /** How the amount to exchange is split per step */
    val depositSplits: Seq[Both[Bitcoin.Amount]] = {
      cumulative(bitcoinBreakdown).map(boughtAmount =>
        Both(boughtAmount + txFee, netBitcoinAmount - boughtAmount))
    }

    val intermediateSteps: Seq[IntermediateStepAmounts[C]] = {
      val stepProgress = cumulative(bitcoinBreakdown).map { case bitcoin =>
        Exchange.Progress(Both(buyer = bitcoin, seller = bitcoin + txFee * HappyPathTransactions))
      }
      for {
        (payment, split, progress) <- (fiatBreakdown, depositSplits, stepProgress).zipped
      } yield Exchange.IntermediateStepAmounts[C](split, payment.netAmount, payment.fee, progress)
    }.toSeq

    val escrowAmounts = Both(
      buyer = maxBitcoinStepSize * DefaultAmountsCalculator.EscrowSteps.buyer,
      seller = maxBitcoinStepSize * DefaultAmountsCalculator.EscrowSteps.seller
    )

    val deposits = Both(
      buyer = DepositAmounts(input = escrowAmounts.buyer + txFee, output = escrowAmounts.buyer),
      seller = DepositAmounts(
        input = grossBitcoinAmount + escrowAmounts.seller,
        output = grossBitcoinAmount + escrowAmounts.seller - txFee
      )
    )

    val finalStep = Exchange.FinalStepAmounts[C](
      depositSplit = Both(
        buyer = netBitcoinAmount + escrowAmounts.buyer + txFee,
        seller = escrowAmounts.seller
      ),
      progress = Progress(Both(buyer = netBitcoinAmount, seller = grossBitcoinAmount))
    )

    val refunds = deposits.map(_.input - maxBitcoinStepSize)

    Exchange.Amounts(
      grossBitcoinAmount, grossFiatAmount, deposits, refunds, intermediateSteps, finalStep)
  }

  private def cumulative[D <: Currency](amounts: Seq[CurrencyAmount[D]]): Seq[CurrencyAmount[D]] =
    amounts.tail.scan(amounts.head)(_ + _)
}

private object DefaultAmountsCalculator {
  /** Amount of escrow deposits in terms of the amount exchanged on every step */
  private val EscrowSteps = Both(buyer = 2, seller = 1)
}
