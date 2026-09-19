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
import dev.eryalabs.nenya.payment.PaymentCheck
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
import kotlin.test.assertIs
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
 * **The invoice strings are real invoices**, derived by [SettlementFixtures.invoice] from a
 * vendored BOLT-11 example with its amount, timestamp and payment hash changed and its bech32
 * checksum recomputed (decision D). That matters here rather than only in the settlement package:
 * §9.2 checks 4 and 5 parse the **stored** invoice, so a receipt on this path only evidences
 * anything if its order's invoice really asks for [PRICE] or for §8.3's fee on it and really was
 * live at [SettlementFixtures.ACCEPTED_AT]. The BOLT-11-*shaped* strings this file used to draw
 * could not have satisfied either, which is what makes these fixtures a check on the library
 * rather than on themselves.
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

    /**
     * [terms] with **both** expected amounts taken to zero, and the same two deadlines.
     *
     * The only order that can reach `paid` while decision B stands. §9.2 requires a receipt only
     * from a payee owed a non-zero amount, so `Payee.requiredPayees` is empty here, `paid` is
     * reached on an empty receipt set, and there is no check for the gate to find unperformed.
     * Every other order in this repository's fixtures is refused
     * [TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED] until a BOLT-11 parser closes §9.2 checks
     * 4 and 5 and check 3's provenance.
     *
     * Zero price **and** [FeeTerm.Absent], not merely a zero fee: §8.3's `zeroFeeTerms` still owes
     * the provider `price_msat`, which is a receipt, which is unperformed checks. The deadlines are
     * kept so that every clock and deadline row of §11.2 behaves on the free chain exactly as it
     * did on the priced one — the deadline tests rewritten onto it are testing the clock, and terms
     * with no `expiration` would quietly make them test nothing.
     */
    fun freeTerms(terms: OrderTerms = TERMS): OrderTerms =
        OrderTerms.of(Msat.ZERO, FeeTerm.Absent, terms.expiration, terms.deliverBy)

    /**
     * The one §9.2 check **no** path in this library performs, for either payee.
     *
     * Check 3's *provenance*: the payment hash the comparison runs against is a parameter every
     * entry point takes, so a caller that hands in the SHA-256 of a preimage it chose gets a true
     * comparison about an invoice nobody issued. Checks 4 and 5 used to be here beside it and are
     * not any more — `Settlement.verify` parses the stored invoice and performs both — and this
     * set is therefore exactly the `missing` set decision B's gate reports for every priced order,
     * whichever payee and whichever settlement path: `verify` closes checks 1, 4 and 5, and
     * `verifyFeeReceipt` closes check 6's three besides.
     *
     * **A narrower set because more was verified, and still a refusal.** Parsing the invoice for
     * its amount does not say where the caller's payment hash came from, so the gate still refuses
     * — which is decision B working as the human decided rather than a gap left open.
     *
     * Written out rather than derived from the library's own constants on purpose: a set computed
     * the way the gate computes it would agree with a broken gate. Enum constants are names, not
     * encoded values, so the Definition of done's rule against typed fixtures does not reach them.
     */
    val CHECKS_NO_PATH_PERFORMS: Set<PaymentCheck> = setOf(PaymentCheck.PAYMENT_HASH_PROVENANCE)

    /**
     * The refusal [event] produced, asserted to be decision B's and to name exactly [missing].
     *
     * A helper because eleven tests now make the same two assertions, and because the type test is
     * the load-bearing half: `assertEquals(PAYMENT_CHECKS_NOT_PERFORMED, reason)` alone would pass
     * over a refusal carrying no check names at all.
     */
    fun refusedForChecks(
        machine: OrderMachine,
        order: Order,
        event: OrderEvent,
        missing: Set<PaymentCheck> = CHECKS_NO_PATH_PERFORMS,
    ): OrderOutcome.Refused.ChecksNotPerformed {
        val refused = refusal(machine, order, event)
        assertEquals(
            TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED,
            refused.reason,
            "§9.2 requires all of its checks before a payment is treated as made (decision B)",
        )
        assertIs<OrderOutcome.Refused.ChecksNotPerformed>(
            refused,
            "the refusal must carry the checks it found missing, not merely the constant",
        )
        assertEquals(missing, refused.missing, "the exact set of checks nobody performed")
        return refused
    }

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
     *
     * ### `paid`, `released` and `settled` carry **free** terms, and why
     *
     * The chain up to `awaiting_payment` is built on [terms] as it always was. Past that point it
     * is not, and cannot be: the human's decision B refuses `paid` while any §9.2 check that
     * applies is unperformed, and until a BOLT-11 parser exists checks 4 and 5 and check 3's
     * provenance are unperformed for every receipt this library can produce. So a priced order
     * cannot be `paid` at all, and the last three states are reached on [freeTerms] — price
     * [Msat.ZERO], [FeeTerm.Absent], the same two deadlines, no payment requests and no receipts —
     * which owes no receipt and therefore has no check to be missing.
     *
     * **A caller that reads `orders(TERMS)[OrderState.PAID]` gets an order whose terms are free,
     * whatever it passed in.** That is deliberate and is the only honest fixture available: the
     * alternative is a `paid` order the library cannot produce. Tests that need a *fee-bearing*
     * order past `awaiting_payment` have nothing to assert over and must assert the refusal
     * instead — see `FeeTermCheckTest`, which says so where it does it.
     *
     * The refusal is not assumed here, it is **asserted**: a priced [terms] has its real receipt
     * set offered to the real machine and must come back
     * [TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED]. Every suite in this package therefore
     * re-proves decision B on every run, and a gate deleted or moved turns this fixture red before
     * it turns any single test red — which is the opposite of a silent fallback to free terms.
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

        // Decision B, re-proved here rather than taken on trust, because everything below depends
        // on it: if this order *could* reach `paid`, the switch to free terms would be an
        // unnecessary weakening of every fixture built on this map.
        val freeChain = if (required.isEmpty()) {
            // Already free. The chain continues on its own terms and nothing is substituted.
            FreeChain(machine, awaitingPayment)
        } else {
            val refused = refusal(
                machine,
                awaitingPayment,
                OrderEvent.ReceiptsVerified(receipts(required, index)),
            )
            assertEquals(
                TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED,
                refused.reason,
                "a priced order whose every required receipt has verified must still be refused " +
                    "`paid` while a §9.2 check that applies to it was performed by nobody " +
                    "(decision B). This fixture reaches `paid` on free terms *because* of that " +
                    "refusal, so a run where the refusal is gone must fail here rather than " +
                    "quietly keep substituting",
            )
            freeChainTo(index, terms)
        }

        val paid = advanced(
            freeChain.machine,
            freeChain.awaitingPayment,
            OrderEvent.ReceiptsVerified(emptySet()),
        )
        // Only the two amounts were zeroed. A free chain that also substituted the deadlines would
        // hand a caller asking for `deliverBy = null` an order carrying one, and every test probing
        // §11.2's own-release timeout would quietly exercise the `deliver_by` branch instead — and
        // stay green with that timeout deleted. Asserted here so it is re-proved on every run.
        assertEquals(
            terms.deliverBy,
            paid.terms.deliverBy,
            "the free chain must keep the caller's `deliver_by`, including its absence",
        )
        assertEquals(
            terms.expiration,
            paid.terms.expiration,
            "and the caller's `expiration`, for the same reason",
        )
        val released = advanced(
            freeChain.machine,
            paid,
            OrderEvent.DeliverableReleased(matchingRelease(), Party.PROVIDER),
        )
        val settled =
            advanced(freeChain.machine, released, OrderEvent.DeliveryVerified(evidence()))

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
     * A free order sitting at `awaiting_payment`, and the machine that drove it there.
     *
     * The machine travels with the order because §11.2's clock-dependent rows are evaluated
     * against the one injected into it: continuing a chain on a different machine would silently
     * change which deadlines fire.
     */
    private class FreeChain(val machine: OrderMachine, val awaitingPayment: Order)

    /**
     * `proposed → accepted → committed → awaiting_payment` on `freeTerms(terms)`, at [index]'s
     * order id.
     *
     * **[terms] is threaded through rather than defaulted, and that is load-bearing.** Only the two
     * *amounts* may be zeroed; the deadlines must stay the caller's. A chain built on
     * `freeTerms()`'s default would hand every caller an order carrying [DELIVER_BY] — so a test
     * that asked for terms with `deliverBy = null`, in order to probe §11.2's own-release timeout,
     * would get a `paid` order with a `deliver_by` after all and would silently exercise the
     * *other* deadline branch. It would stay green with the release timeout deleted entirely.
     *
     * No payment requests, because [Payee.requiredPayees] is empty for free terms and §8.6 refuses
     * a request from a payee owed nothing — `PaymentRequestsReceived(emptySet())` is the whole of
     * §11.2's `committed → awaiting_payment` row here.
     */
    private fun freeChainTo(index: Int, terms: OrderTerms): FreeChain {
        val machine = machineBeforeDeadlines()
        val free = freeTerms(terms)
        val proposed = machine.open(OrderEvent.Proposal(orderId(index), free))
        val accepted = advanced(
            machine,
            proposed,
            OrderEvent.StatusUpdate(OrderState.ACCEPTED, Party.PROVIDER, assertedTerms = free),
        )
        val committed = advanced(
            machine,
            accepted,
            OrderEvent.DeliveryCommitted(blob.commitment, Party.PROVIDER),
        )
        return FreeChain(
            machine,
            advanced(machine, committed, OrderEvent.PaymentRequestsReceived(emptySet())),
        )
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
            // §9.2 check 4's expected amounts, from the same terms the fee-judgement order carries
            // — so a receipt verified through the general entry point is held to exactly what one
            // verified through the fee path is.
            Settlement.verify(receipt, hash, messages.store, TERMS.split)
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
     * One real invoice per `(order index, payee)`, derived from a vendored example.
     *
     * Each carries the amount §9.2 check 4 will demand of it — [PRICE] for the provider and §8.3's
     * fee on it for the fee recipient — and, in its `p` field, the SHA-256 of a preimage drawn per
     * position, which is what keeps them distinct. That distinctness is load-bearing twice over:
     * the provider's invoice and the fee recipient's are §8.6's **two separate invoices**, and no
     * two orders reuse one. Two receipts deliberately sharing a *payment hash* still name two
     * different invoices, which is the shape §8.6's non-custodial rule is about — one payment
     * offered as evidence of two — and that control would prove nothing if check 1 could catch the
     * set on its own.
     *
     * Drawn once and sliced by `(index, payee)`, because deriving an invoice recomputes a bech32
     * checksum and a SHA-256 and the cross-product asks for the same few thousands of times.
     */
    private val INVOICES: List<String> by lazy {
        val positions = (OTHER_ORDER_INDEX + 1) * Payee.entries.size
        val preimages = PaymentFixtures.preimageHex(positions)
        List(positions) { position ->
            SettlementFixtures.invoice(
                preimages[position],
                SettlementFixtures.amountFor(Payee.entries[position % Payee.entries.size], TERMS.split),
            )
        }
    }

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
     * The BOLT-11 invoice this fixture uses for [payee] on the order at [index].
     *
     * Fails by name rather than by `IndexOutOfBoundsException` when a third order index is asked
     * for: [INVOICES] is drawn once and sized for the two this file declares, and a fixture that
     * ran off the end would report a stack trace about an array where the fix is one constant.
     */
    fun invoice(index: Int, payee: Payee): String {
        val position = index * Payee.entries.size + payee.ordinal
        if (position !in INVOICES.indices) {
            fail(
                "no derived invoice for order index $index: this fixture builds ${INVOICES.size} " +
                    "of them, enough for order indices 0..$OTHER_ORDER_INDEX. Raise " +
                    "OTHER_ORDER_INDEX or widen the draw",
            )
        }
        return INVOICES[position]
    }
}
