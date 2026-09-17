package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.ProposalFixtures
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
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.settlement.AcceptedPaymentRequest
import dev.eryalabs.nenya.settlement.FeeTermSighting
import dev.eryalabs.nenya.settlement.PaymentReceipt
import dev.eryalabs.nenya.settlement.PaymentRequest
import dev.eryalabs.nenya.settlement.PaymentRequestStore
import dev.eryalabs.nenya.settlement.Settlement
import dev.eryalabs.nenya.settlement.SettlementFixtures
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The fixtures behind every transition test, and the generator behind every value they carry.
 *
 * Not a scratch file. Nothing here is typed: preimages come from the payment package's pinned
 * `java.util.Random` algorithm ([dev.eryalabs.nenya.JdkRandom]) and their payment hashes from the
 * platform SHA-256 (`MessageDigest` on the JVM); blobs and their `x` / `ox`
 * come from the delivery package's generator; order ids are SHA-256 digests of a per-index label
 * (`ChannelFixtures`, through [SettlementFixtures.orderHex]) and the pubkeys are real BIP-340 keys
 * out of the vendored `bip340-vectors.csv`. All of them are reused rather than re-implemented, so
 * a reviewer changing any seed re-runs this file too.
 *
 * ### Every fixture order is *reached*, never fabricated
 *
 * [orders] drives a real [OrderMachine] through real §11.2 transitions to obtain one order in
 * each of §11.1's states, and asserts at every step that the machine actually advanced. That is
 * deliberate: a test that constructed an order in state `paid` directly would be proving things
 * about a state the library cannot produce, and — since [Order] has no public constructor — would
 * not compile in the first place. The one exception is [OrderState.UNKNOWN], which no transition
 * reaches by design and which [OrderMachine.unrecognised] is the only door to.
 *
 * ### Every fixture receipt is *verified*, the only way the library now allows
 *
 * `OrderEvent.ReceiptsVerified` takes `Settlement.Evidenced`, so a receipt here cannot be a
 * `VerifiedPayment.verify` over a preimage and a hash. Each one is built the whole way down: a
 * `type=2` decoded by T15's codec and accepted into a [PaymentRequestStore] against a fixed fake
 * clock, a `kind:17` decoded by T15's receipt codec naming **the same invoice string**, and then
 * `Settlement.verify` for the provider or `Settlement.verifyFeeReceipt` for the fee — the latter
 * over §8.4's earlier points taken off a T14-decoded proposal and acceptance. So the payee is the
 * one the receipt's own `["payee", …]` tag carried, and the order id is the one its
 * `["order", …]` tag carried.
 *
 * **The invoice strings are an interim fixture, and are stated as one.** They come from
 * [SettlementFixtures.invoices], which produces BOLT-11-*shaped* strings: the checksums are wrong,
 * the tagged fields are noise, and no payment hash, amount or expiry can be read out of any of
 * them. That is sufficient for everything on this path — §9.2 check 1 is a byte comparison over an
 * opaque string and nothing here parses an invoice — and it is **not** what a receipt derived from
 * the vendored BOLT-11 examples would be. They are generated rather than typed, so the Definition
 * of done holds; the derivation from the vendored examples arrives with the parser, and replaces
 * these.
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

    /** The `order` index every fixture order and every fixture receipt is built at. */
    const val ORDER_INDEX: Int = 0

    /**
     * A second index, for the control that offers one order's receipts to another.
     *
     * Its terms are [TERMS] too — identical in every respect but the id — because an order at a
     * different price would be refused by the amount checks a later task adds, and the refusal
     * under test here has to be the id and nothing else.
     */
    const val OTHER_ORDER_INDEX: Int = 1

    /** §7.4's order id for [ORDER_INDEX]: a digest, read through [OrderId.ofHex]. */
    val ORDER_ID: OrderId = orderId(ORDER_INDEX)

    /** §7.4's order id for [OTHER_ORDER_INDEX]. A genuinely different 32 bytes. */
    val OTHER_ORDER_ID: OrderId = orderId(OTHER_ORDER_INDEX)

    /** §7.4's order id for [index], from the digest `ChannelFixtures` computes for that label. */
    fun orderId(index: Int): OrderId = OrderId.ofHex(SettlementFixtures.orderHex(index))

    /**
     * §9.2's evidence for [payee], bound to the order at [index] — the whole path, every time.
     *
     * The provider's goes through `Settlement.verify` and the fee recipient's through
     * `Settlement.verifyFeeReceipt`, which is what §9.2 asks of each: check 6 is stated "for a
     * `payee=fee` receipt" and refuses a provider one outright. So a fee receipt from this door
     * carries check 6's three obligations as performed and a provider receipt does not, which is
     * the difference an order's own record must end up reflecting.
     *
     * @param stream picks which generated preimage the receipt proves, so no two receipts in one
     *   order share evidence — and so a control can deliberately make two that do.
     */
    fun receipt(payee: Payee, stream: Int = 0, index: Int = ORDER_INDEX): Settlement.Evidenced =
        evidenced(payee, stream, index, throughFeePath = payee == Payee.FEE)

    /**
     * The same receipt put through plain `Settlement.verify`, whatever its payee.
     *
     * The negative control for §9.2 check 6: a caller that used the general entry point performed
     * no part of it, and the order that consumes the result must keep saying so.
     */
    fun receiptThroughVerify(payee: Payee, stream: Int = 0, index: Int = ORDER_INDEX):
        Settlement.Evidenced = evidenced(payee, stream, index, throughFeePath = false)

    /** One generated preimage from the payment package's pinned run. Never typed. */
    fun preimage(stream: Int = 0): Preimage = PaymentFixtures.preimages(stream + 1)[stream]

    /** `SHA-256(preimage)`, computed by the platform oracle in the payment package's own fixture. */
    fun paymentHashOf(preimage: Preimage): PaymentHash = PaymentFixtures.paymentHashOf(preimage)

    /** One verified receipt per payee in [payees], each with its own preimage. */
    fun receipts(payees: Set<Payee>, index: Int = ORDER_INDEX): Set<Settlement.Evidenced> =
        payees.mapIndexedTo(LinkedHashSet()) { stream, payee -> receipt(payee, stream, index) }

    /** The receipts a fee-bearing [TERMS] order requires: one provider, one fee. */
    fun bothReceipts(index: Int = ORDER_INDEX): Set<Settlement.Evidenced> =
        receipts(setOf(Payee.PROVIDER, Payee.FEE), index)

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
    fun orders(terms: OrderTerms = TERMS, index: Int = ORDER_INDEX): Map<OrderState, Order> {
        val machine = machineBeforeDeadlines()
        val required = Payee.requiredPayees(terms.split)

        val proposed = machine.open(OrderEvent.Proposal(orderId(index), terms))
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
        val paid =
            advanced(machine, awaitingPayment, OrderEvent.ReceiptsVerified(receipts(required, index)))
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
            cancelled, expired, disputed, machine.unrecognised(orderId(index), terms),
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

    // ------------------------------------------------------------------ the settlement path

    /**
     * The generated `type=2` / `kind:17` pair, verified, keyed by everything that distinguishes
     * one.
     *
     * Cached because every one of them recomputes two SHA-256 event ids in pure Kotlin and the
     * cross-product asks for the same few receipts thousands of times. The values are immutable
     * and the identity that comes of sharing them is what the cross-product wants anyway: a
     * `Settlement.Evidenced` has no `equals`, so two calls that must land in one `Set` as one
     * element have to be the same instance.
     */
    private val evidence: MutableMap<String, Settlement.Evidenced> = mutableMapOf()

    private fun evidenced(
        payee: Payee,
        stream: Int,
        index: Int,
        throughFeePath: Boolean,
    ): Settlement.Evidenced = evidence.getOrPut("$index/${payee.token}/$stream/$throughFeePath") {
        val messages = messages(index)
        val receipt = messages.receipt(payee, stream)
        val hash = PaymentFixtures.paymentHashOf(preimage(stream))
        val settlement = if (throughFeePath) {
            Settlement.verifyFeeReceipt(
                receipt,
                hash,
                messages.store,
                feeJudgementOrder(index),
                messages.earlierPoints,
            )
        } else {
            Settlement.verify(receipt, hash, messages.store)
        }
        settlement as? Settlement.Evidenced
            ?: fail("a lightning receipt whose preimage hashes must evidence, not yield $settlement")
    }

    /**
     * The `awaiting_payment` order §9.2 check 6's third obligation is read from (§8.5).
     *
     * Always at [TERMS], whatever the order the receipt is eventually offered to carries: check 6
     * asks whether a fee was owed and whether the order had reached `awaiting_payment`, and a
     * fixture that judged the fee receipt against a *zero-fee* order could not build one at all —
     * which is the very refusal `OrderControlsTest` needs a fee receipt in hand to provoke.
     *
     * Built through [OrderMachine] rather than by hand, and without any receipt, so there is no
     * circularity: `proposed → accepted → committed → awaiting_payment` needs no evidence.
     */
    private fun feeJudgementOrder(index: Int): Order = feeJudgementOrders.getOrPut(index) {
        val machine = machineBeforeDeadlines()
        val proposed = machine.open(OrderEvent.Proposal(orderId(index), TERMS))
        val accepted = advanced(
            machine,
            proposed,
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, assertedTerms = TERMS),
        )
        val committed =
            advanced(machine, accepted, OrderEvent.DeliveryCommitted(blob.commitment, Party.PROVIDER))
        advanced(
            machine,
            committed,
            OrderEvent.PaymentRequestsReceived(Payee.requiredPayees(TERMS.split)),
        )
    }

    private val feeJudgementOrders: MutableMap<Int, Order> = mutableMapOf()

    private val messagesByIndex: MutableMap<Int, Messages> = mutableMapOf()

    private fun messages(index: Int): Messages = messagesByIndex.getOrPut(index) { Messages(index) }

    /**
     * Enough BOLT-11-shaped strings for one invoice per payee at each fixture order index.
     *
     * Drawn once from [SettlementFixtures.invoices]' single seeded run and sliced by
     * `(index, payee)`, so the provider's invoice and the fee recipient's are different strings —
     * §8.6's two separate invoices — and so no two orders reuse one. Two receipts deliberately
     * sharing a *payment hash* still name two different invoices, which is the shape §8.6's
     * non-custodial rule is about: one payment offered as evidence of two.
     */
    private val INVOICES: List<String> =
        SettlementFixtures.invoices((OTHER_ORDER_INDEX + 1) * Payee.entries.size)

    /**
     * One order's §8.4 points and §8.6 messages, at [index] — every one of them decoded by the
     * codec the library ships, and the two `type=2`s accepted into a store against a fixed clock.
     */
    private class Messages(val index: Int) {

        /** §8.1's three-element fee term, carried byte-identically at every point §8.4 names. */
        val feeTag: List<String> = ProposalFixtures.feeTag(index, FEE_BASIS_POINTS)

        val proposalTags: List<List<String>> = ProposalFixtures.proposalTags(
            index = index,
            amountMsat = PRICE.millisatoshis.toString(),
            fee = feeTag,
        )

        val proposal: OrderProposal = ProposalFixtures.proposal(proposalTags, index)

        /** §7.6's acceptance, sealed by the provider — a different key from the proposal's. */
        val acceptance: OrderStatusMessage = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(proposalTags, index),
            index + 1,
        )

        /**
         * §8.7's seals: the fee `type=2` arrives under the fee recipient's own key, the provider's
         * under the provider's. A fee invoice forwarded by the provider is what §8.7 refuses, and
         * a fixture that could not choose the seal could not be the accepting side of that rule.
         */
        val requests: Map<Payee, PaymentRequest> = Payee.entries.associateWith { payee ->
            SettlementFixtures.requestSealedBy(
                when (payee) {
                    Payee.PROVIDER -> SettlementFixtures.provider(index)
                    Payee.FEE -> SettlementFixtures.feeRecipient(index)
                },
                SettlementFixtures.requestTags(
                    index = index,
                    payment = SettlementFixtures.requestPaymentTag(invoice(index, payee)),
                    payeeTag = SettlementFixtures.payeeTag(payee, index),
                    // §8.4 marks the tag REQUIRED on the fee request and OPTIONAL on the
                    // provider's, and forbids requiring one there — so the provider's carries none.
                    fee = if (payee == Payee.FEE) feeTag else null,
                ),
                SettlementFixtures.split(),
            )
        }

        /** Both requests accepted at one pinned clock reading — §17 item 6's second value. */
        val store: PaymentRequestStore = PaymentRequestStore.inMemory().also { store ->
            val clock = FakeClock(SettlementFixtures.ACCEPTED_AT)
            for (request in requests.values) AcceptedPaymentRequest.accept(request, store, clock)
        }

        /** §8.4's points before a fee receipt: the proposal, the acceptance and the fee `type=2`. */
        val earlierPoints: List<FeeTermSighting> = listOf(
            FeeTermSighting.onProposal(proposal),
            FeeTermSighting.onAcceptance(acceptance),
            FeeTermSighting.onPaymentRequest(requests.getValue(Payee.FEE)),
        )

        /**
         * §9.2's `kind:17` for [payee], proving [stream]'s preimage against **this order's own**
         * invoice for that payee — sealed by the buyer, which §9.2's worked example is.
         */
        fun receipt(payee: Payee, stream: Int): PaymentReceipt = SettlementFixtures.receiptSealedBy(
            SettlementFixtures.buyer(index),
            SettlementFixtures.receiptTags(
                index = index,
                payment = SettlementFixtures.paymentTag(
                    invoice(index, payee),
                    PaymentFixtures.preimageHex(stream + 1)[stream],
                ),
                payeeTag = SettlementFixtures.payeeTag(payee, index),
                fee = if (payee == Payee.FEE) feeTag else null,
            ),
        )
    }

    /**
     * The BOLT-11-shaped string this fixture uses for [payee] on the order at [index].
     *
     * Fails by name rather than by `IndexOutOfBoundsException` when a third order index is asked
     * for: [INVOICES] is drawn once and sized for the two this file declares, and a fixture that
     * ran off the end would report a stack trace about an array where the fix is one constant.
     */
    fun invoice(index: Int, payee: Payee): String {
        val position = index * Payee.entries.size + payee.ordinal
        if (position !in INVOICES.indices) {
            fail(
                "no generated invoice for order index $index: this fixture draws ${INVOICES.size} " +
                    "of them, enough for order indices 0..$OTHER_ORDER_INDEX. Raise " +
                    "OTHER_ORDER_INDEX or widen the draw",
            )
        }
        return INVOICES[position]
    }
}
