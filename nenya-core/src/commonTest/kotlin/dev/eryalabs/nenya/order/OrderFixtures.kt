package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.channel.Acceptance
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
 * ### And every event in that chain comes out of a codec — decision J
 *
 * The chain used to assemble two of its own events: `StatusUpdate(ACCEPTED, PROVIDER, terms)` and
 * `PaymentRequestsReceived(setOf(PROVIDER, FEE))`. Neither shape exists any more. [OrderChain]
 * builds one order's whole §7-to-§8 message set for the terms asked for — a `type=1` decoded by
 * T14's codec, a `type=3` sealed by the provider and put through `OrderProposal.accepts` against
 * the key the caller resolved, and one `type=2` per required payee accepted into a
 * [PaymentRequestStore] by T24's `AcceptedPaymentRequest.accept` — so the fixture chain reaches
 * `accepted` and `awaiting_payment` through exactly the doors a client has.
 *
 * That is what makes the controls in `OrderControlsTest` mean anything: a fixture that could still
 * hand-build an acceptance would let a test "prove" the order-level half of §7.6 while the codec's
 * sender check was deleted.
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
 *
 * Check 3 now reads that same invoice's `p` field, which tightens the rule a step further: a
 * receipt's `<proof>` and its own invoice's payment hash are **one fact** here, chosen together by
 * `(order index, stream)`. A fixture that set them apart would build a receipt the library refuses
 * `PREIMAGE_MISMATCH` before the rule under test was reached — so where a control wants two
 * receipts to prove one payment it draws both at one stream, and where it wants two payments it
 * draws two streams. See [invoice].
 */
internal object OrderFixtures {

    /** §7.5's acceptance deadline for every fixture order. An arbitrary, fixed unix second. */
    const val EXPIRATION: Long = 1_757_016_400L

    /** §7.5's delivery deadline. Strictly after [EXPIRATION], as §7.5 requires. */
    const val DELIVER_BY: Long = 1_757_066_034L

    /** A clock reading before both deadlines: nothing has expired. */
    const val BEFORE_DEADLINES: Long = EXPIRATION - 3_600L

    /**
     * A clock reading after **every** deadline this library evaluates: everything a deadline can
     * fire on, has.
     *
     * Revision `1.6` added a third, and this constant moved with it. `expiration` and `deliver_by`
     * are the two wire terms; §11.2's verification deadline out of `released` is local, runs from
     * the reading taken at release — [BEFORE_DEADLINES] on every fixture chain, since
     * [machineBeforeDeadlines] builds them — and lands a verification window later. At the old
     * value, `DELIVER_BY + 3600`, the cross-product in `TransitionTotalityTest` reached `released`
     * with `ClockChecked` and was refused `DEADLINE_NOT_PASSED`, so the one edge revision `1.6`
     * adds would have been the one edge **that proof** never exercised — `(released, disputed)` is
     * produced by `DeliveryRefused` too, so its set equality holds either way. `VerificationDeadlineTest`
     * covers the edge directly and is what turns red if the branch is deleted; this constant is what
     * puts it inside the totality proof as well. Both windows are added rather than the larger of
     * the two, so no reading of "after both deadlines" is left standing on an inequality between
     * two constants that a later task may change independently.
     */
    const val AFTER_DEADLINES: Long = DELIVER_BY +
        OrderMachine.DEFAULT_RELEASE_TIMEOUT_SECONDS +
        OrderMachine.DEFAULT_VERIFICATION_TIMEOUT_SECONDS +
        3_600L

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
     * §9.2 requires a receipt only from a payee owed a non-zero amount, so `Payee.requiredPayees`
     * is empty here and `paid` is reached on an **empty receipt set**. That is the third answer
     * §17's record has to be able to express and the reason this shape is kept now that a priced
     * order reaches `paid` too: an order whose `paymentChecksPerformed` is empty because nothing
     * applied, beside one whose set is full because everything was performed, is what stops a
     * client reading empty as "verified".
     *
     * Zero price **and** [FeeTerm.Absent], not merely a zero fee: §8.3's `zeroFeeTerms` still owes
     * the provider `price_msat`, which is a receipt. The deadlines are kept so that every clock and
     * deadline row of §11.2 behaves here exactly as it does on the priced chain — the deadline
     * tests written onto it are testing the clock, and terms with no `expiration` would quietly
     * make them test nothing.
     */
    fun freeTerms(terms: OrderTerms = TERMS): OrderTerms =
        OrderTerms.of(Msat.ZERO, FeeTerm.Absent, terms.expiration, terms.deliverBy)

    /**
     * The only §9.2 gap this library can still produce: check 6's three, on a fee receipt that went
     * through plain `Settlement.verify`.
     *
     * Nothing else is left. `Settlement.verify` performs checks 1, 4 and 5 and — since check 3's
     * operand became the stored invoice's `p` field rather than a parameter — the provenance too,
     * so a provider receipt from that door has an empty not-performed set and a fee receipt from
     * `verifyFeeReceipt` has one as well. The general entry point performs no part of check 6,
     * which is what `receiptThroughVerify` builds and what decision B's gate must go on demanding:
     * the refusal is computed from what *applies* to the payee, not from what the record admits.
     *
     * **This used to be `PAYMENT_HASH_PROVENANCE`, the check no path performed.** Its closing is
     * what lets a priced order reach `paid` again, and the fixtures below assert both halves in one
     * place — complete evidence advances, incomplete evidence is refused naming exactly this set.
     *
     * Written out rather than derived from the library's own constants on purpose: a set computed
     * the way the gate computes it would agree with a broken gate. Enum constants are names, not
     * encoded values, so the Definition of done's rule against typed fixtures does not reach them.
     */
    val CHECK_SIX_UNPERFORMED: Set<PaymentCheck> = setOf(
        PaymentCheck.FEE_TERM_MATCH,
        PaymentCheck.FEE_SEALING_KEY,
        PaymentCheck.FEE_STATE_PRECONDITION,
    )

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
        missing: Set<PaymentCheck> = CHECK_SIX_UNPERFORMED,
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
     * @param stream picks which generated preimage this order's invoices are derived from, and so
     *   which payment the receipt proves. Two receipts drawn at different streams prove different
     *   payments; two drawn at the **same** stream prove one payment offered twice, which is the
     *   shape §8.6's non-custodial rule is about and which a control builds deliberately.
     *
     *   It selects the preimage rather than merely the proof, and it has to. §9.2 check 3's operand
     *   is the `p` field of the **stored** invoice, so a receipt's proof and its own invoice's `p`
     *   are one fact: a fixture that varied the proof alone would build a receipt this library
     *   refuses `PREIMAGE_MISMATCH` before any rule under test was reached.
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
    fun preimage(index: Int = ORDER_INDEX, stream: Int = 0): Preimage =
        Preimage.ofHex(preimageHex(index, stream))

    /**
     * `SHA-256` of the preimage `(index, stream)`'s invoices carry in their `p` field, by the other
     * route: the platform oracle in the payment package's own fixture.
     *
     * The value a test compares `Settlement.Evidenced.payment.paymentHash` against to say check 3's
     * operand came out of the stored invoice. Two independent routes to one number — the composer
     * wrote `p` from this preimage, this hashes it — which is what makes that equality evidence
     * rather than a tautology.
     */
    fun paymentHashFor(index: Int = ORDER_INDEX, stream: Int = 0): PaymentHash =
        PaymentFixtures.paymentHashOf(preimage(index, stream))

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
     * ### `paid`, `released` and `settled` are on the caller's own [terms] again
     *
     * One chain, one machine, one set of terms, from `proposed` all the way to `settled`. T20 had
     * to reach the last three states on [freeTerms] instead, because decision B refuses `paid`
     * while any §9.2 check that applies was performed by nobody and check 3's *provenance* was
     * performed by nobody: `Settlement.verify` took the payment hash as a parameter. It takes none
     * now — the operand is the `p` field of the stored invoice — so a priced order's receipts leave
     * no applicable check unperformed and the gate lets them through. A caller that asks for
     * `orders(TERMS)[OrderState.PAID]` gets a **fee-bearing** order in `paid` again.
     *
     * ### Decision B's gate is still asserted here, from both sides
     *
     * The gate is what every fixture past `awaiting_payment` now depends on, so this function
     * exercises both of its answers rather than only the one it needs:
     *
     * - **refused** — the same order offered the same provider receipt beside a fee receipt put
     *   through plain `Settlement.verify`, which performs no part of check 6, must come back
     *   [TransitionRejection.PAYMENT_CHECKS_NOT_PERFORMED] naming exactly [CHECK_SIX_UNPERFORMED].
     *   Only a fee-bearing [terms] can offer that shape, so the probe is skipped where no fee is
     *   owed and the `advanced` below carries the whole statement;
     * - **advanced** — the real receipt set, the fee one through `verifyFeeReceipt`, moves it.
     *
     * A gate deleted turns the first red; a gate that refuses everything turns the second red. The
     * pairing is why neither is written as a comment about the other.
     */
    fun orders(terms: OrderTerms = TERMS, index: Int = ORDER_INDEX): Map<OrderState, Order> {
        val machine = machineBeforeDeadlines()
        val chain = chain(terms, index)
        val required = Payee.requiredPayees(terms.split)

        val proposed = machine.open(chain.proposal.asOrderEvent())
        // The chain's `type=1` tags are built *from* [terms], so this is the assertion that the
        // round trip through §7.5's tags and T14's codec landed on the very deal the caller asked
        // for. Without it a fixture whose fee tag or deadline failed to survive encoding would
        // quietly hand every test below an order about something else.
        assertEquals(terms, proposed.terms, "the decoded `type=1` must carry the terms asked for")
        assertEquals(orderId(index), proposed.id, "and the `order` tag the codec read")

        val accepted = advanced(machine, proposed, OrderEvent.AcceptanceReceived(chain.accepted))
        val committed = advanced(
            machine,
            accepted,
            OrderEvent.DeliveryCommitted(blob.commitment, Party.PROVIDER),
        )
        val awaitingPayment =
            advanced(machine, committed, OrderEvent.PaymentRequestsReceived(chain.requests))
        assertEquals(
            required,
            chain.requests.mapTo(LinkedHashSet()) { it.payee },
            "the stored `type=2`s must cover exactly the payees §9.2 requires, or the transition " +
                "above was reached on the wrong set",
        )

        // The refusing half of decision B's gate, on the one incomplete shape this library can
        // still build. The streams match `receipts` below — provider 0, fee 1 — so the two offers
        // differ in the fee receipt's *door* and in nothing else.
        if (Payee.FEE in required) {
            refusedForChecks(
                machine,
                awaitingPayment,
                OrderEvent.ReceiptsVerified(
                    setOf(
                        receipt(Payee.PROVIDER, stream = 0, index = index),
                        receiptThroughVerify(Payee.FEE, stream = 1, index = index),
                    ),
                ),
            )
        }

        val paid = advanced(
            machine,
            awaitingPayment,
            OrderEvent.ReceiptsVerified(receipts(required, index)),
        )
        // Both hold by construction now that nothing is substituted, and both are kept: they are
        // what would turn red if a later task reintroduced a chain that zeroed the amounts and
        // carried its own deadlines. A test probing §11.2's own-release timeout on terms with
        // `deliverBy = null` would otherwise quietly exercise the `deliver_by` branch instead, and
        // would stay green with that timeout deleted.
        assertEquals(
            terms.deliverBy,
            paid.terms.deliverBy,
            "the chain must carry the caller's `deliver_by`, including its absence",
        )
        assertEquals(
            terms.expiration,
            paid.terms.expiration,
            "and the caller's `expiration`, for the same reason",
        )
        val released = advanced(
            machine,
            paid,
            OrderEvent.DeliverableReleased(matchingRelease(), Party.PROVIDER),
        )
        val settled =
            advanced(machine, released, OrderEvent.DeliveryVerified(evidence()))

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

    // ------------------------------------------------------------------ the §7-to-§8 chain

    /**
     * Which of the two preimage streams the stored `type=2`s of a chain are derived from.
     *
     * Stream `0`, so that for [TERMS] a chain's provider invoice is character-for-character the one
     * [invoice] hands the receipt fixtures — same preimage, same amount, same composer. The two
     * live in different stores and are the same invoice, which is what a real order has.
     */
    const val REQUEST_STREAM: Int = 0

    /**
     * One order's whole §7-to-§8 message set, for [terms] at [index], every value decoded or
     * checked by the codec that owns it.
     *
     * Cached by the five things that distinguish one: the index, the price, the fee term's shape
     * and the two deadlines. Building one decodes three events and recomputes as many SHA-256 event
     * ids, derives up to two BOLT-11 invoices, and the cross-product asks for the same few
     * thousands of times.
     */
    fun chain(terms: OrderTerms = TERMS, index: Int = ORDER_INDEX): OrderChain =
        chains.getOrPut(chainKey(terms, index)) { OrderChain(terms, index) }

    private val chains: MutableMap<String, OrderChain> = mutableMapOf()

    private fun chainKey(terms: OrderTerms, index: Int): String {
        val fee =
            if (terms.split.term == FeeTerm.Absent) "absent" else terms.split.term.basisPoints.toString()
        return "$index/${terms.split.price.millisatoshis}/$fee/${terms.expiration}/${terms.deliverBy}"
    }

    /**
     * A `payee=provider` record for **this** order id that every check T24 makes accepts, and that
     * §11.2 must still refuse.
     *
     * The shape decision I's order-level half is about. A caller resolved a stranger as the
     * provider and put a `type=3` that stranger really did seal through `accepts`; §7.6 is
     * satisfied — the seal equals the key it was told to compare against — so it holds a
     * well-formed `Acceptance.Accepted` for this order. The provider `type=2` it then accepts under
     * that acceptance is sealed by the stranger, which is exactly what §8.6 requires *of that
     * acceptance*. Nothing in the settlement package can tell that the caller resolved the wrong
     * party; only the key **the order** recorded when it advanced to `accepted` can.
     *
     * Its invoice is the chain's own provider invoice, so the refusal under test cannot be the
     * amount, the expiry or the order id.
     */
    fun providerRequestUnderAStranger(index: Int = ORDER_INDEX): AcceptedPaymentRequest =
        strangerRequests.getOrPut(index) {
            val chain = chain(TERMS, index)
            val stranger = SettlementFixtures.stranger(index)
            val sealed = chain.acceptanceSealedBy(stranger)
            val acceptance = chain.proposal.accepts(sealed, stranger) as? Acceptance.Accepted
                ?: fail("a `type=3` sealed by the key it is checked against must accept")
            AcceptedPaymentRequest.accept(
                SettlementFixtures.requestSealedBy(
                    stranger,
                    SettlementFixtures.requestTags(
                        index = index,
                        payment = SettlementFixtures.requestPaymentTag(chain.requestInvoice(Payee.PROVIDER)),
                        payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER, index),
                    ),
                    TERMS.split,
                ),
                acceptance,
                PaymentRequestStore.inMemory(),
                FakeClock(SettlementFixtures.ACCEPTED_AT),
            )
        }

    private val strangerRequests: MutableMap<Int, AcceptedPaymentRequest> = mutableMapOf()

    /**
     * A **second**, distinct `payee=provider` record for the same order — and one that every check
     * T24 makes accepts, because §8.6's no-replacement rule is asked of one store and this record
     * went into another.
     *
     * Same order, same provider key, same amount, an invoice derived from the other preimage
     * stream. That is the shape §8.6's "one invoice per payee" forbids and the shape a set the
     * caller assembles can express: two records a client kept across two stores, or one it held on
     * to after its own store refused the replacement. Nothing about the sender, the amount, the
     * expiry or the order id is wrong with it, which is what makes the refusal it earns the
     * duplicate rule and nothing else.
     */
    fun secondProviderRequest(index: Int = ORDER_INDEX): AcceptedPaymentRequest =
        secondProviderRequests.getOrPut(index) {
            AcceptedPaymentRequest.accept(
                SettlementFixtures.requestSealedBy(
                    SettlementFixtures.provider(index),
                    SettlementFixtures.requestTags(
                        index = index,
                        payment = SettlementFixtures.requestPaymentTag(
                            SettlementFixtures.invoice(
                                preimageHex(index, REQUEST_STREAM + 1),
                                SettlementFixtures.amountFor(Payee.PROVIDER, TERMS.split),
                            ),
                        ),
                        payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER, index),
                    ),
                    TERMS.split,
                ),
                chain(TERMS, index).accepted,
                PaymentRequestStore.inMemory(),
                FakeClock(SettlementFixtures.ACCEPTED_AT),
            )
        }

    private val secondProviderRequests: MutableMap<Int, AcceptedPaymentRequest> = mutableMapOf()

    /** [tags] decoded as a `type=3` whose **seal** is [author] — §7.6's and §8.7's operand. */
    fun statusSealedBy(author: String, tags: List<List<String>>): OrderStatusMessage =
        OrderStatusMessage.decode(SettlementFixtures.boundSealedBy(author, tags))

    /**
     * The §7.5 proposal, §7.6 acceptance and §8.6 payment requests of one fixture order.
     *
     * Everything here is generated: the tags come from [ProposalFixtures] and [SettlementFixtures],
     * the keys are real BIP-340 keys out of the vendored file, the order id is a digest and the
     * invoices are derived from a vendored BOLT-11 example (decision D).
     */
    internal class OrderChain(val terms: OrderTerms, val index: Int) {

        /** §8.1's fee tag for these terms, or `null` for a proposal carrying no `fee` tag at all. */
        val feeTag: List<String>? =
            if (terms.split.term == FeeTerm.Absent) null
            else ProposalFixtures.feeTag(index, terms.split.term.basisPoints)

        /** §7.5's tags, built to express exactly [terms] — the assertion [orders] makes on them. */
        val proposalTags: List<List<String>> = ProposalFixtures.proposalTags(
            index = index,
            amountMsat = terms.split.price.millisatoshis.toString(),
            fee = feeTag,
            deliverBy = terms.deliverBy,
            expiration = terms.expiration,
        )

        /** The `type=1`, sealed by the buyer and decoded by T14's codec. */
        val proposal: OrderProposal = ProposalFixtures.proposal(proposalTags, index)

        /** §7.6's `type=3`, sealed by the provider, carrying the four terms byte for byte. */
        val update: OrderStatusMessage = acceptanceSealedBy(SettlementFixtures.provider(index))

        /**
         * §7.6's checked answer, against the provider key the caller resolved.
         *
         * `Acceptance.Accepted`'s constructor is `internal` and therefore reachable from this
         * source set — building one directly would make every order test pass with §7.6's own
         * sender check deleted, which is half of what this task is about.
         */
        val accepted: Acceptance.Accepted =
            proposal.accepts(update, SettlementFixtures.provider(index)).let {
                it as? Acceptance.Accepted ?: fail("the fixture acceptance must accept, not $it")
            }

        /** §8.6's `type=2` for [payee], sealed by the key §8.6 and §8.7 require of it. */
        fun paymentRequest(payee: Payee): PaymentRequest = SettlementFixtures.requestSealedBy(
            when (payee) {
                Payee.PROVIDER -> SettlementFixtures.provider(index)
                Payee.FEE -> SettlementFixtures.feeRecipient(index)
            },
            SettlementFixtures.requestTags(
                index = index,
                payment = SettlementFixtures.requestPaymentTag(requestInvoice(payee)),
                payeeTag = SettlementFixtures.payeeTag(payee, index),
                // §8.4 marks the tag REQUIRED on the fee request and OPTIONAL on the provider's,
                // and forbids requiring one there — so the provider's carries none.
                fee = if (payee == Payee.FEE) feeTag else null,
            ),
            terms.split,
        )

        /** A real invoice for what [payee] is owed under [terms] (decision D). */
        fun requestInvoice(payee: Payee): String = SettlementFixtures.invoice(
            preimageHex(index, REQUEST_STREAM),
            SettlementFixtures.amountFor(payee, terms.split),
        )

        /** The store the records below were accepted into, at [SettlementFixtures.ACCEPTED_AT]. */
        val store: PaymentRequestStore = PaymentRequestStore.inMemory()

        /**
         * One accepted, stored `type=2` per required payee — §11.2's
         * `committed → awaiting_payment` trigger, minted the only way T24 allows.
         *
         * Empty for an order that owes nobody anything, which is §9.2's non-zero clause and the
         * reason `awaiting_payment` is reachable for a free order at all.
         */
        val requests: Set<AcceptedPaymentRequest> =
            Payee.requiredPayees(terms.split).mapTo(LinkedHashSet()) { payee ->
                AcceptedPaymentRequest.accept(
                    paymentRequest(payee),
                    accepted,
                    store,
                    FakeClock(SettlementFixtures.ACCEPTED_AT),
                    signedPoints(),
                )
            }

        /** The stored record for [payee], or a loud failure where these terms owe none. */
        fun request(payee: Payee): AcceptedPaymentRequest =
            requests.firstOrNull { it.payee == payee }
                ?: fail("this chain owes $payee nothing, so it accepted no `type=2` for it")

        /** §8.4's two REQUIRED points that precede every `type=2`, in message order. */
        fun signedPoints(): List<FeeTermSighting> = listOf(
            FeeTermSighting.onProposal(proposal),
            FeeTermSighting.onAcceptance(update),
        )

        /**
         * §7.6's `type=3` for this proposal, sealed by [author] and carrying [signedTerms].
         *
         * The seal is a parameter because every §7.6 control is about it: an acceptance sealed by
         * the buyer, or by a stranger, is what decision H refuses, and a fixture that could not
         * choose the sealing key could not state the rule at all. So is [signedTerms]: §8.4's fee
         * pair is what gap G3 was about, and the control that inverts it needs a `type=3` naming
         * the same basis points and a different recipient.
         */
        fun acceptanceSealedBy(
            author: String,
            signedTerms: List<List<String>> = proposalTerms(),
        ): OrderStatusMessage = statusSealedBy(
            author,
            ProposalFixtures.acceptanceTags(proposalTags, index, terms = signedTerms),
        )

        /** §7.6's four terms, exactly as the proposal signed them. */
        fun proposalTerms(): List<List<String>> =
            proposalTags.filter { it[0] in ProposalFixtures.TERM_NAMES }
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
        val messages = messages(index, stream)
        val receipt = messages.receipt(payee)
        // No payment hash is handed to either door: check 3's operand is the `p` field of the
        // invoice the store holds under (order, payee). [preimage] and [paymentHashFor] are what a
        // test uses to reach that same value by the other route — the seeded preimage the composer
        // wrote `p` from, hashed by the platform oracle — and comparing the two is what says the
        // operand came out of the invoice rather than out of anybody's hand.
        val settlement = if (throughFeePath) {
            Settlement.verifyFeeReceipt(
                receipt,
                messages.store,
                feeJudgementOrder(index),
                messages.earlierPoints,
            )
        } else {
            // §9.2 check 4's expected amounts, from the same terms the fee-judgement order carries
            // — so a receipt verified through the general entry point is held to exactly what one
            // verified through the fee path is.
            Settlement.verify(receipt, messages.store, TERMS.split)
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
        val chain = chain(TERMS, index)
        val proposed = machine.open(chain.proposal.asOrderEvent())
        val accepted = advanced(machine, proposed, OrderEvent.AcceptanceReceived(chain.accepted))
        val committed =
            advanced(machine, accepted, OrderEvent.DeliveryCommitted(blob.commitment, Party.PROVIDER))
        advanced(machine, committed, OrderEvent.PaymentRequestsReceived(chain.requests))
    }

    private val feeJudgementOrders: MutableMap<Int, Order> = mutableMapOf()

    private val messagesByKey: MutableMap<String, Messages> = mutableMapOf()

    private fun messages(index: Int, stream: Int): Messages =
        messagesByKey.getOrPut("$index/$stream") { Messages(index, stream) }

    /**
     * How many preimage streams each order's fixtures are drawn at.
     *
     * Two, which is what the controls need: one receipt per payee proving a different payment, and
     * a second draw for the sets that want two receipts for one payee or a fee receipt whose
     * evidence is not the provider's. Raising it widens [PREIMAGES] and nothing else.
     */
    const val STREAMS: Int = 2

    /**
     * One real preimage per `(order index, stream)`, from the payment package's pinned run.
     *
     * Keyed by the pair rather than by `(index, payee)`, and that is the whole shape of these
     * fixtures since §9.2 check 3's operand became the stored invoice's `p` field. **Both** of an
     * order's invoices at one stream are derived from this one preimage — they differ by their
     * amount, which is what makes them §8.6's two separate invoices — so a receipt drawn for the
     * provider at stream *s* and one drawn for the fee recipient at the same *s* prove one payment
     * twice, and the `RECEIPT_PAYMENT_DUPLICATED` control has a shape to be built from without any
     * value being typed. Two receipts at different streams prove two payments, which is what an
     * order that reaches `paid` needs.
     */
    private val PREIMAGES: List<String> by lazy {
        PaymentFixtures.preimageHex((OTHER_ORDER_INDEX + 1) * STREAMS)
    }

    /**
     * The preimage `(index, stream)`'s invoices carry the SHA-256 of, failing loudly off the end.
     *
     * [PREIMAGES] is drawn once and sized for the two order indices and [STREAMS] streams this file
     * declares; a fixture that ran off it would report a stack trace about an array where the fix
     * is one constant.
     */
    private fun preimageHex(index: Int, stream: Int): String {
        val position = index * STREAMS + stream
        if (position !in PREIMAGES.indices) {
            fail(
                "no preimage for order index $index at stream $stream: this fixture draws " +
                    "${PREIMAGES.size} of them, enough for order indices 0..$OTHER_ORDER_INDEX at " +
                    "streams 0..${STREAMS - 1}. Raise OTHER_ORDER_INDEX or STREAMS",
            )
        }
        return PREIMAGES[position]
    }

    /**
     * One order's §8.4 points and §8.6 messages, at [index] and drawn at [stream] — every one of
     * them decoded by the codec the library ships, and the two `type=2`s accepted into a store
     * against a fixed clock.
     */
    private class Messages(val index: Int, val stream: Int) {

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
                    payment = SettlementFixtures.requestPaymentTag(invoice(index, payee, stream)),
                    payeeTag = SettlementFixtures.payeeTag(payee, index),
                    // §8.4 marks the tag REQUIRED on the fee request and OPTIONAL on the
                    // provider's, and forbids requiring one there — so the provider's carries none.
                    fee = if (payee == Payee.FEE) feeTag else null,
                ),
                SettlementFixtures.split(),
            )
        }

        /**
         * §7.6's answer, checked against the provider key the caller resolved — which is what
         * [store] now needs and what makes these fixtures a full §7.6-to-§8.6 chain rather than a
         * set of messages that happen to name one order.
         */
        val accepted: Acceptance.Accepted =
            proposal.accepts(acceptance, SettlementFixtures.provider(index)).let {
                it as? Acceptance.Accepted ?: fail("the fixture acceptance must accept, not $it")
            }

        /**
         * Both requests accepted at one pinned clock reading — §17 item 6's second value.
         *
         * The fee request is handed §8.4's two signed points, because revision `1.5` makes
         * `Settlement.checkFeePaymentRequest` part of acceptance: a fee `type=2` stored here has
         * passed §8.4 and §8.7 before any receipt for it exists.
         */
        val store: PaymentRequestStore = PaymentRequestStore.inMemory().also { store ->
            val clock = FakeClock(SettlementFixtures.ACCEPTED_AT)
            val signed = listOf(
                FeeTermSighting.onProposal(proposal),
                FeeTermSighting.onAcceptance(acceptance),
            )
            for (request in requests.values) {
                AcceptedPaymentRequest.accept(request, accepted, store, clock, signed)
            }
        }

        /** §8.4's points before a fee receipt: the proposal, the acceptance and the fee `type=2`. */
        val earlierPoints: List<FeeTermSighting> = listOf(
            FeeTermSighting.onProposal(proposal),
            FeeTermSighting.onAcceptance(acceptance),
            FeeTermSighting.onPaymentRequest(requests.getValue(Payee.FEE)),
        )

        /**
         * §9.2's `kind:17` for [payee] — sealed by the buyer, which §9.2's worked example is.
         *
         * Its `<proof>` is [stream]'s preimage and its `<reference>` is the invoice that preimage's
         * hash was written into, so check 1 finds the stored string and check 3 finds a `p` the
         * proof satisfies. The two are one fact here and cannot be varied apart, which is the shape
         * the library now enforces.
         */
        fun receipt(payee: Payee): PaymentReceipt = SettlementFixtures.receiptSealedBy(
            SettlementFixtures.buyer(index),
            SettlementFixtures.receiptTags(
                index = index,
                payment = SettlementFixtures.paymentTag(
                    invoice(index, payee, stream),
                    preimageHex(index, stream),
                ),
                payeeTag = SettlementFixtures.payeeTag(payee, index),
                fee = if (payee == Payee.FEE) feeTag else null,
            ),
        )
    }

    /**
     * The BOLT-11 invoice this fixture uses for [payee] on the order at [index], at [stream].
     *
     * A real invoice derived from a vendored example (decision D), carrying the amount §9.2 check 4
     * will demand of it — [PRICE] for the provider and §8.3's fee on it for the fee recipient — and,
     * in its `p` field, the SHA-256 of `(index, stream)`'s preimage.
     *
     * **The two payees' invoices at one stream share that `p` and differ in their amount.** That is
     * §8.6's two separate invoices, and it is what makes one payment offered as evidence of two a
     * constructible shape: a set of those two receipts passes check 1 twice, because each names its
     * own stored string, and is refused for the payment they have in common. A receipt at a
     * different stream, or for a different order, proves a different payment.
     *
     * Cached, because deriving an invoice recomputes a bech32 checksum and a SHA-256 and the
     * cross-product asks for the same few thousands of times.
     */
    fun invoice(index: Int, payee: Payee, stream: Int = 0): String =
        invoices.getOrPut("$index/${payee.token}/$stream") {
            SettlementFixtures.invoice(
                preimageHex(index, stream),
                SettlementFixtures.amountFor(payee, TERMS.split),
            )
        }

    private val invoices: MutableMap<String, String> = mutableMapOf()
}
