package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.delivery.DeliverableRelease
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.delivery.DeliveryFixtures
import dev.eryalabs.nenya.delivery.ServedBytesVerified
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.FakeClock
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The fixtures behind every transition test, and the generator behind every value they carry.
 *
 * Not a scratch file. Nothing here is typed: preimages come from the payment package's pinned
 * `java.util.Random` and their payment hashes from `MessageDigest`; blobs and their `x` / `ox`
 * come from the delivery package's generator. Both are reused rather than re-implemented, so a
 * reviewer changing either seed re-runs this file too.
 *
 * ### Every fixture order is *reached*, never fabricated
 *
 * [orders] drives a real [OrderMachine] through real §11.2 transitions to obtain one order in
 * each of §11.1's states, and asserts at every step that the machine actually advanced. That is
 * deliberate: a test that constructed an order in state `paid` directly would be proving things
 * about a state the library cannot produce, and — since [Order] has no public constructor — would
 * not compile in the first place. The one exception is [OrderState.UNKNOWN], which no transition
 * reaches by design and which [OrderMachine.unrecognised] is the only door to.
 */
internal object OrderFixtures {

    /** §7.5's acceptance deadline for every fixture order. An arbitrary, fixed unix second. */
    const val EXPIRATION: Long = 1_757_016_400L

    /** §7.5's delivery deadline. Strictly after [EXPIRATION], as §7.5 requires. */
    const val DELIVER_BY: Long = 1_757_066_034L

    /** A clock reading before both deadlines: nothing has expired. */
    const val BEFORE_DEADLINES: Long = EXPIRATION - 3_600L

    /** A clock reading after both deadlines: everything a deadline can fire on, has. */
    const val AFTER_DEADLINES: Long = DELIVER_BY + 3_600L

    /** §8.3's own worked rate: 250 bps is 2.5%, and on this price the fee is non-zero. */
    const val FEE_BASIS_POINTS: Int = 250

    /** §7.5's own worked price: 90 000 000 msat. Large enough that 250 bps rounds above zero. */
    val PRICE: Msat = Msat.ofMsat(90_000_000L)

    /**
     * A fee-bearing order's terms: both payees required, both deadlines present.
     *
     * The default for every cross-product fixture on purpose. A zero-fee order is the *easier*
     * case — one invoice, one receipt — and an implementation that required an invoice per
     * **named** payee would pass a cross-product built on it while deadlocking every fee-bearing
     * order into `expired`. See [zeroFeeTerms] for the other side of that probe (§8.3, §18).
     */
    val TERMS: OrderTerms =
        OrderTerms.of(PRICE, FeeTerm.of(FEE_BASIS_POINTS), EXPIRATION, DELIVER_BY)

    /**
     * §8.3's own zero-fee example: `floor(3000 × 1 / 10000) = 0` with a **non-zero** `bps`.
     *
     * The terms name a fee payee and no fee invoice may legally exist. §8.3, §9.2 and §11.1 all
     * say the same thing about an implementation that waits for one: it deadlocks the order into
     * `expired`.
     */
    val zeroFeeTerms: OrderTerms
        get() = OrderTerms.of(Msat.ofMsat(3_000L), FeeTerm.of(1), EXPIRATION, DELIVER_BY)

    /** §8.1's proposal with no `fee` tag at all: complete terms meaning zero fee. */
    val absentFeeTerms: OrderTerms
        get() = OrderTerms.of(PRICE, FeeTerm.Absent, EXPIRATION, DELIVER_BY)

    /** A machine whose clock is before both deadlines — the one the fixture chain is built with. */
    fun machineBeforeDeadlines(): OrderMachine = OrderMachine(FakeClock(BEFORE_DEADLINES))

    /** A machine whose clock has passed both deadlines, so every deadline row can fire. */
    fun machineAfterDeadlines(): OrderMachine = OrderMachine(FakeClock(AFTER_DEADLINES))

    /** The one generated deliverable every fixture order commits to and releases. */
    val blob: DeliveryFixtures.Blob = DeliveryFixtures.blob()

    /** A second, unrelated deliverable, for the "evidence from another order thread" control. */
    val otherBlob: DeliveryFixtures.Blob = DeliveryFixtures.blobs(3)[2]

    /**
     * A `VerifiedPayment` for [payee], built the only way one can be: a generated preimage, its
     * `SHA-256` computed by `MessageDigest`, and `VerifiedPayment.verify` comparing the two.
     *
     * @param stream picks a distinct preimage per payee, so no two receipts in one order share
     *   evidence.
     */
    fun receipt(payee: Payee, stream: Int = 0): VerifiedPayment {
        val preimage = preimage(stream)
        return VerifiedPayment.verify(payee, PaymentFixtures.paymentHashOf(preimage), preimage)
    }

    /** One generated preimage from the payment package's pinned run. Never typed. */
    fun preimage(stream: Int = 0): Preimage = PaymentFixtures.preimages(stream + 1)[stream]

    /** `SHA-256(preimage)`, computed by `MessageDigest` in the payment package's own fixture. */
    fun paymentHashOf(preimage: Preimage): PaymentHash = PaymentFixtures.paymentHashOf(preimage)

    /** One verified receipt per payee in [payees], each with its own preimage. */
    fun receipts(payees: Set<Payee>): Set<VerifiedPayment> =
        payees.mapIndexedTo(LinkedHashSet()) { index, payee -> receipt(payee, index) }

    /** The receipts a fee-bearing [TERMS] order requires: one provider, one fee. */
    fun bothReceipts(): Set<VerifiedPayment> = receipts(setOf(Payee.PROVIDER, Payee.FEE))

    /**
     * §10.4's evidence for [blob], computed here in §10.4's own order: the served bytes hashed
     * against `x` first, and only then the plaintext against `ox`.
     */
    fun evidence(target: DeliveryFixtures.Blob = blob): DeliveryEvidence {
        val served = ServedBytesVerified.verifyServedBytes(target.commitment, target.served)
        return DeliveryEvidence.verifyPlaintextBytes(served, target.plaintext)
    }

    /** A §10.3 release whose four operands all match [blob]'s commitment. */
    fun matchingRelease(): DeliverableRelease = blob.matchingRelease()

    /** A §10.3 release whose `ox` diverges from the commitment's — one operand, on its own. */
    fun divergentRelease(): DeliverableRelease = DeliverableRelease(
        x = blob.commitment.x,
        ox = otherBlob.commitment.ox,
        fileType = blob.commitment.mimeType,
        sizeBytes = blob.commitment.sizeBytes,
    )

    /**
     * One order in each of §11.1's eleven states, every one of them **reached** by driving a real
     * machine through real transitions — except the sink, which nothing reaches.
     *
     * The map is built with [machineBeforeDeadlines] so that no fixture order expires while it is
     * being built. Tests that need a deadline to fire re-run the events against
     * [machineAfterDeadlines].
     */
    fun orders(terms: OrderTerms = TERMS): Map<OrderState, Order> {
        val machine = machineBeforeDeadlines()
        val required = Payee.requiredPayees(terms.split)

        val proposed = machine.open(OrderEvent.Proposal(terms))
        val accepted = advanced(
            machine,
            proposed,
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, assertedTerms = terms),
        )
        val committed = advanced(
            machine,
            accepted,
            OrderEvent.DeliveryCommitted(blob.commitment, Party.PROVIDER),
        )
        val awaitingPayment =
            advanced(machine, committed, OrderEvent.PaymentRequestsReceived(required))
        val paid = advanced(machine, awaitingPayment, OrderEvent.ReceiptsVerified(receipts(required)))
        val released = advanced(
            machine,
            paid,
            OrderEvent.DeliverableReleased(matchingRelease(), Party.PROVIDER),
        )
        val settled = advanced(machine, released, OrderEvent.DeliveryVerified(evidence()))

        val cancelled = advanced(
            machine,
            proposed,
            OrderEvent.StatusUpdate(OrderState.CANCELLED, Party.BUYER),
        )
        val expired = advanced(machineAfterDeadlines(), proposed, OrderEvent.ClockChecked)
        val disputed = advanced(machine, committed, OrderEvent.LocallyDisputed)

        val byState = listOf(
            proposed, accepted, committed, awaitingPayment, paid, released, settled,
            cancelled, expired, disputed, machine.unrecognised(terms),
        ).associateBy { it.state }

        assertEquals(
            OrderState.entries.toSet(),
            byState.keys,
            "the fixture chain must reach every one of §11.1's states, or the cross-product " +
                "below is not over eleven states at all",
        )
        return byState
    }

    /**
     * Run one event and return the order it produced, failing loudly if it was refused.
     *
     * A fixture chain that silently kept the old order on a refusal would build a cross-product
     * over eleven copies of `proposed` and pass every proof in this package.
     */
    fun advanced(machine: OrderMachine, order: Order, event: OrderEvent): Order =
        when (val outcome = machine.on(order, event)) {
            is OrderOutcome.Advanced -> outcome.order
            is OrderOutcome.Refused -> fail(
                "expected $event to advance an order in ${order.state}, but it was refused: " +
                    "${outcome.reason} — ${outcome.detail}",
            )
        }

    /** The refusal an event produced, or a loud failure if the order moved instead. */
    fun refusal(machine: OrderMachine, order: Order, event: OrderEvent): OrderOutcome.Refused =
        when (val outcome = machine.on(order, event)) {
            is OrderOutcome.Refused -> outcome
            is OrderOutcome.Advanced -> fail(
                "expected $event to be refused from ${order.state}, but the order moved to " +
                    "${outcome.order.state}",
            )
        }
}
