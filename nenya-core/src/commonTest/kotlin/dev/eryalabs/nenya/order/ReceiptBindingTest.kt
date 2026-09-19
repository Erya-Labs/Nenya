package dev.eryalabs.nenya.order

import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.settlement.Settlement
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What `awaiting_payment → paid` now consumes: §9.2's evidence, bound to **this** order.
 *
 * ### The two shapes this file exists to keep closed
 *
 * While `OrderEvent.ReceiptsVerified` took a bare `Set<VerifiedPayment>`, reaching `paid` needed
 * only a preimage and a payment hash — both caller-supplied. Two probes followed from that and
 * neither touched the store or §9.2 check 6:
 *
 * - a receipt for an invoice the **buyer issued to itself**, which hashes correctly and settles
 *   nothing the provider sent; and
 * - a receipt whose **payee label was swapped** on the way in, so a fee payment was offered as the
 *   provider's or the other way round.
 *
 * Both are now unrepresentable rather than refused: the payee is the one the `kind:17`'s own
 * `["payee", …]` tag carried, and the invoice is the one the store held under `(order, payee)`.
 * `OrderStructureTest` asserts the absence by reflection, which is the half a behavioural test
 * cannot reach — a rule stated as "you cannot write that" needs a test that fails when you can.
 *
 * ### And the one the type system could not close
 *
 * Evidence is verifiable and still belong to somebody else. A `Settlement.Evidenced` for another
 * order at the same price passes every check this transition made before today: the payee is
 * right, the payment hash is unique, the set is complete. So the order carries its own id and the
 * transition compares it — [TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER], before every other
 * refusal, because a caller whose evidence is for another thread is not told its set is
 * incomplete.
 */
class ReceiptBindingTest {

    private companion object {

        /** §9.2 check 6's three obligations, spelled out so the derived constant has an anchor. */
        val CHECK_SIX: Set<PaymentCheck> = setOf(
            PaymentCheck.FEE_TERM_MATCH,
            PaymentCheck.FEE_SEALING_KEY,
            PaymentCheck.FEE_STATE_PRECONDITION,
        )

        /**
         * §9.2 checks 1 to 5, which apply to **every** receipt whatever its payee.
         *
         * All five are performed on both settlement doors now: check 1's byte comparison against
         * the store, checks 4 and 5 against the stored invoice's amount and expiry, and — since
         * `Settlement.verify` stopped taking a payment hash — check 3's operand out of that same
         * invoice's `p` field, which is what closed `PAYMENT_HASH_PROVENANCE`. So this is the set
         * a provider receipt must report performed, and `missing` must never name any of it.
         */
        val CHECKS_ONE_TO_FIVE: Set<PaymentCheck> = setOf(
            PaymentCheck.PREIMAGE_SHAPE,
            PaymentCheck.PREIMAGE_HASH_COMPARISON,
            PaymentCheck.INVOICE_IDENTITY,
            PaymentCheck.PAYMENT_HASH_PROVENANCE,
            PaymentCheck.INVOICE_AMOUNT,
            PaymentCheck.INVOICE_EXPIRY,
        )
    }

    private val machine = OrderFixtures.machineBeforeDeadlines()

    // ---------------------------------------------------------------- what the order now claims

    /**
     * The headline, read off a `paid` order again: a fee-bearing order fed the two receipts §9.2
     * requires reaches `paid`, and its record names every check both receipts' paths performed.
     *
     * Asserted as an exact equality on the record. A membership assertion would be satisfied by an
     * order that also *claimed* a check nobody performed — §17's over-claim — and the transition
     * itself is what says the gate did not demand one that was done, which is the mirror failure
     * that deadlocks an order for ever over work somebody already did.
     */
    @JsName("a_fee_bearing_order_reaches_paid_claiming_check_1_and_check_6")
    @Test
    fun `a fee-bearing order reaches paid claiming check 1 and check 6`() {
        val awaiting = OrderFixtures.orders().getValue(OrderState.AWAITING_PAYMENT)
        assertEquals(
            setOf(Payee.PROVIDER, Payee.FEE),
            Payee.requiredPayees(awaiting.terms.split),
            "this order must owe a fee, or check 6 never applies and the test is about nothing",
        )

        val paid = OrderFixtures.advanced(
            machine,
            awaiting,
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts()),
        )

        assertEquals(OrderState.PAID, paid.state)
        assertTrue(
            PaymentCheck.INVOICE_IDENTITY in paid.paymentChecksPerformed,
            "§9.2 check 1 was performed against the stored `type=2` for both receipts",
        )
        assertTrue(
            CHECK_SIX.all { it in paid.paymentChecksPerformed },
            "and check 6's three were performed for the fee receipt: ${paid.paymentChecksPerformed}",
        )
        assertEquals(
            CHECKS_ONE_TO_FIVE + CHECK_SIX,
            paid.paymentChecksPerformed,
            "exactly §9.2's nine obligations over the two payees, and nothing invented: the " +
                "provider's five and the fee recipient's five plus check 6's three",
        )
        assertEquals(emptySet(), paid.paymentChecksNotPerformedHere)
    }

    /**
     * The negative control that keeps the claim above honest, and the one the queue names: the
     * **same** fee receipt put through plain `Settlement.verify` performed no part of check 6, and
     * the gate **demands** it rather than merely recording it.
     *
     * Two failure modes die here and neither is hypothetical.
     *
     * - A machine that credited check 6 to every `Payee.FEE` receipt would pass the test above
     *   exactly as one reading the evidence's own record does. Here it would let the order past.
     * - A gate computing `missing` from the receipt's `checksNotPerformedHere` instead of from what
     *   *applies* would agree with this assertion today — and would stop agreeing the moment a
     *   settlement path subtracted a check it had not done. That is why the queue's mutation pairs
     *   the two changes: only "applicable minus performed" survives both.
     */
    @JsName("a_fee_receipt_through_plain_verify_leaves_check_6_demanded_by_the_gate")
    @Test
    fun `a fee receipt through plain verify leaves check 6 demanded by the gate`() {
        val throughVerify = OrderFixtures.receiptThroughVerify(Payee.FEE, stream = 1)
        assertEquals(Payee.FEE, throughVerify.payee, "still a fee receipt; only the path differs")

        val refused = OrderFixtures.refusedForChecks(
            machine,
            OrderFixtures.orders().getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(
                setOf(OrderFixtures.receipt(Payee.PROVIDER, stream = 0), throughVerify),
            ),
            missing = CHECK_SIX,
        )

        assertTrue(
            CHECK_SIX.all { it in refused.missing },
            "a caller that used the general entry point got no part of check 6, and the gate must " +
                "refuse on it rather than record it: ${refused.missing}",
        )
        for (check in CHECKS_ONE_TO_FIVE) {
            assertFalse(
                check in refused.missing,
                "$check is performed on both doors, so it is still performed here and still not " +
                    "demanded — the difference between the two paths is check 6 and only check 6",
            )
        }
    }

    // ---------------------------------------------------------------- the binding to this order

    /**
     * Receipts verified for a **second** order — identical terms, a different id — are refused,
     * and the same receipts are accepted by the order they name.
     *
     * The pairing is the test. A refusal on its own is satisfied by a transition that refuses
     * everything, and this is the one comparison whose absence left `paid` reachable on somebody
     * else's verified payment: every other rule here is about the payee and the payment hash, and
     * neither is order-specific.
     */
    @JsName("receipts_for_another_order_with_identical_terms_are_refused_and_their_own_order_accepts_them")
    @Test
    fun `receipts for another order with identical terms are refused, and their own order accepts them`() {
        val mine = OrderFixtures.orders().getValue(OrderState.AWAITING_PAYMENT)
        val theirs = OrderFixtures.orders(index = OrderFixtures.OTHER_ORDER_INDEX)
            .getValue(OrderState.AWAITING_PAYMENT)
        assertEquals(mine.terms, theirs.terms, "identical terms, so only the id can be the reason")
        assertFalse(mine.id == theirs.id, "and the two ids must genuinely differ")

        val theirReceipts = OrderFixtures.bothReceipts(OrderFixtures.OTHER_ORDER_INDEX)
        val refusal =
            OrderFixtures.refusal(machine, mine, OrderEvent.ReceiptsVerified(theirReceipts))

        assertEquals(TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER, refusal.reason)
        assertEquals(
            OrderState.AWAITING_PAYMENT,
            refusal.order.state,
            "a refusal leaves the order exactly where it was",
        )

        // ...and the same evidence is not merely unusable: its own order accepts it and advances.
        // A transition that refused everything would satisfy the first half of this test, and this
        // is what separates the two.
        val own = OrderFixtures.advanced(
            machine,
            theirs,
            OrderEvent.ReceiptsVerified(theirReceipts),
        )
        assertEquals(
            OrderState.PAID,
            own.state,
            "these receipts name *this* order, so the id comparison is satisfied rather than " +
                "merely reached — and nothing downstream of it holds them either",
        )
    }

    /**
     * Mixed sets too: one receipt for this order and one for another is refused on the stranger.
     *
     * `any` and not `all` is the rule, and the difference is the whole of it — a comparison
     * written the other way would let a set through as long as one member belonged here.
     */
    @JsName("a_receipt_set_carrying_one_receipt_from_another_order_is_refused")
    @Test
    fun `a receipt set carrying one receipt from another order is refused`() {
        val mine = OrderFixtures.orders().getValue(OrderState.AWAITING_PAYMENT)
        val mixed = setOf(
            OrderFixtures.receipt(Payee.PROVIDER, stream = 0),
            OrderFixtures.receipt(Payee.FEE, stream = 1, index = OrderFixtures.OTHER_ORDER_INDEX),
        )

        val refusal = OrderFixtures.refusal(machine, mine, OrderEvent.ReceiptsVerified(mixed))

        assertEquals(TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER, refusal.reason)
    }

    // ---------------------------------------------------------------- the payee is the tag's

    /**
     * §8.3 and §8.6, with the payee read off the receipt rather than taken from a caller: a
     * `kind:17` carrying `["payee", "fee", …]` offered to an order that owes no fee is refused as
     * a receipt for a payee no payment is owed to.
     *
     * The predecessor of this shape was a caller-chosen `Payee` argument, where the same bytes
     * could be presented as either role. Here the role comes from the tag the buyer signed over
     * and there is no argument to change it, so what is left to assert is that the transition
     * reads it.
     */
    @JsName("a_receipt_whose_payee_tag_says_fee_is_refused_by_an_order_that_owes_no_fee")
    @Test
    fun `a receipt whose payee tag says fee is refused by an order that owes no fee`() {
        val zeroFee = OrderFixtures.orders(OrderFixtures.zeroFeeTerms)
            .getValue(OrderState.AWAITING_PAYMENT)
        assertEquals(
            setOf(Payee.PROVIDER),
            Payee.requiredPayees(zeroFee.terms.split),
            "§8.3: `floor(3000 × 1 / 10000)` is 0, so no fee invoice may legally exist",
        )
        // Through plain `verify`, because `verifyFeeReceipt` would have refused it one layer down
        // on the same clause — and the point here is that the *machine* refuses it too.
        val feeReceipt = OrderFixtures.receiptThroughVerify(Payee.FEE, stream = 1)
        assertEquals(Payee.FEE, feeReceipt.payee, "the role came from the `payee` tag")

        val refusal = OrderFixtures.refusal(
            machine,
            zeroFee,
            OrderEvent.ReceiptsVerified(
                setOf(OrderFixtures.receipt(Payee.PROVIDER, stream = 0), feeReceipt),
            ),
        )

        assertEquals(TransitionRejection.RECEIPT_NOT_REQUIRED, refusal.reason)
    }

    // ---------------------------------------------------------------- §12 item 11

    /** §12 item 11 and STOP RULE 14: the order id is a correlation handle and is never printed. */
    @JsName("no_order_prints_its_own_id")
    @Test
    fun `no order prints its own id`() {
        val hex = OrderFixtures.ORDER_ID.toHex()
        assertEquals(64, hex.length, "the control needs a value long enough not to match by luck")

        for ((state, order) in OrderFixtures.orders()) {
            assertEquals(OrderFixtures.ORDER_ID, order.id, "every fixture order is this one, at $state")
            assertFalse(order.toString().contains(hex), "$state printed its order id")
        }
        assertFalse(
            OrderFixtures.ORDER_ID.toString().contains(hex),
            "and `OrderId` redacts itself, so neither layer leaks it",
        )
    }

    /** A refusal names a rule and a role; it never names the order id either. */
    @JsName("a_refusal_about_the_order_id_never_names_one")
    @Test
    fun `a refusal about the order id never names one`() {
        val refusal = OrderFixtures.refusal(
            machine,
            OrderFixtures.orders().getValue(OrderState.AWAITING_PAYMENT),
            OrderEvent.ReceiptsVerified(OrderFixtures.bothReceipts(OrderFixtures.OTHER_ORDER_INDEX)),
        )

        assertEquals(TransitionRejection.RECEIPT_FOR_ANOTHER_ORDER, refusal.reason)
        for (id in listOf(OrderFixtures.ORDER_ID, OrderFixtures.OTHER_ORDER_ID)) {
            assertFalse(
                refusal.detail.contains(id.toHex()),
                "the refusal that is *about* two order ids is the one most likely to print one",
            )
        }
    }

    // ---------------------------------------------------------------- the evidence itself

    /**
     * The non-vacuity floor under all of the above: every fixture receipt really is
     * `Settlement.Evidenced`, really names this order, and really carries a record that is not the
     * `VerifiedPayment`'s.
     *
     * If the fixtures had quietly become something weaker, every assertion in this file would
     * still pass for the wrong reason.
     */
    @JsName("every_fixture_receipt_is_settlement_evidence_naming_this_order")
    @Test
    fun `every fixture receipt is settlement evidence naming this order`() {
        val receipts = OrderFixtures.bothReceipts()
        assertEquals(2, receipts.size)

        for (receipt in receipts) {
            assertEquals(OrderFixtures.ORDER_ID, receipt.order, "receipt for ${receipt.payee}")
            assertTrue(
                PaymentCheck.INVOICE_IDENTITY in receipt.checksPerformed,
                "the store path performed check 1 for ${receipt.payee}",
            )
            assertFalse(
                PaymentCheck.INVOICE_IDENTITY in receipt.payment.checksPerformed,
                "and the `VerifiedPayment` underneath still does not claim it — which is why the " +
                    "order reads the settlement result's sets and not its payment's",
            )
        }
        assertEquals(
            Settlement.CHECK_SIX,
            receipts.single { it.payee == Payee.FEE }.checksPerformed -
                Settlement.CHECKS_PERFORMED_ON_THE_STORE_PATH,
            "the fee receipt's path closed check 6's three and the provider's did not",
        )
        assertEquals(
            emptySet(),
            receipts.single { it.payee == Payee.PROVIDER }.checksPerformed -
                Settlement.CHECKS_PERFORMED_ON_THE_STORE_PATH,
        )
    }
}
