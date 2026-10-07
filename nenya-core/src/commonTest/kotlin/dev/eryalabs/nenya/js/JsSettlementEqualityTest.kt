package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelFixtures
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.settlement.PaymentReceipt
import dev.eryalabs.nenya.settlement.PaymentRequestStore
import dev.eryalabs.nenya.settlement.Settlement
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.settlement.SettlementRejection
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §9.2's two doors across the JavaScript boundary: [verifySettlement] and [verifyFeeReceipt] answer
 * exactly what `Settlement.verify` and `Settlement.verifyFeeReceipt` answer.
 *
 * ### These are the two entry points T43 gives its own task to, and this is why
 *
 * §9 calls itself "the load-bearing rule of the entire document". A translation that drifted here
 * would not lose a tag or round a number — it would report a payment as evidenced that this library
 * did not evidence, which is the one failure no test downstream of it could catch. So the equality
 * below is over **every field** `JsSettlement` publishes, including both check sets, and the
 * refusals are compared with their vocabulary attached: §9.2 check 3's `PaymentRejection` is
 * deliberately not re-badged by `Settlement`, and a facade that flattened the two vocabularies into
 * one would pass a comparison of reason names alone.
 *
 * ### One rumor feeds both sides
 *
 * `PaymentReceipt`'s constructor is `internal` and `PaymentReceipt.decode` is the only door, so the
 * facade decodes the rumor itself. Every case here therefore takes one `AttributedRumor.Bound` from
 * the fixtures, hands it to `decode` on the expected side and to [JsRumor] on the crossed side. Two
 * receipts built from two copies of one set of tags could agree while the crossing lost a tag; one
 * rumor cannot.
 */
class JsSettlementEqualityTest {

    /**
     * §9.2's whole evidence path for both payees, across the boundary — the positive case.
     *
     * `checksNotPerformedHere` being **empty** for a provider receipt through this door is the
     * point of the whole settlement path, and it is the field a drifting facade would get wrong in
     * the dangerous direction. The assertion is that it matches the library's, whatever it is.
     */
    @Test
    @JsName("verifySettlement_crosses_section_9_2_s_evidence_for_both_payees")
    fun `verifySettlement crosses §9_2's evidence for both payees`() {
        for (payee in Payee.entries) {
            val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
            val rumor = messages.receiptRumor(payee)
            val crossed = verifyBothWays("a $payee receipt", rumor, messages.store)

            assertTrue(crossed.ok, "a lightning receipt whose preimage hashes must evidence")
            val settlement = crossed.settlement ?: fail("an answer carries a settlement")
            assertTrue(settlement.evidenced, "§9.2's evidence exists for this receipt")
            assertEquals(payee.token, settlement.payee, "§8.6's payee role, as its token")
            assertEquals(
                OrderFixtures.paymentHashFor(OrderFixtures.ORDER_INDEX, 0).toHex(),
                settlement.paymentHashHex,
                "§9.2 check 3's operand must be the `p` of the **stored** invoice, which is the " +
                    "hash of this stream's preimage by the other route",
            )
        }
    }

    /**
     * §9.2 check 6's door, across the boundary, with §8.4's three REQUIRED points supplied.
     *
     * A fee receipt from this door has performed every §9.2 check there is — the store path's four,
     * check 3's two and check 6's three — so its `checksNotPerformedHere` is empty while the same
     * receipt through [verifySettlement] still names check 6's three. Both are asserted, because
     * the difference between them is the only observable evidence that the facade routed each call
     * to the door it was asked for rather than to one of them twice.
     */
    @Test
    @JsName("verifyFeeReceipt_crosses_section_9_2_check_6")
    fun `verifyFeeReceipt crosses §9_2 check 6`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val rumor = messages.receiptRumor(Payee.FEE)
        val order = awaitingPayment()

        val throughCheckSix = feeReceiptBothWays("a fee receipt through check 6", rumor, messages, order)
        val throughVerify = verifyBothWays("the same fee receipt through verify", rumor, messages.store)

        val six = throughCheckSix.settlement ?: fail("check 6's door must answer")
        val general = throughVerify.settlement ?: fail("the general door must answer")
        assertTrue(six.evidenced && general.evidenced, "both doors evidence this receipt")
        assertEquals(
            emptySet(),
            six.checksNotPerformedHere.toSet(),
            "a fee receipt through check 6's door has had every §9.2 check performed",
        )
        assertEquals(
            OrderFixtures.CHECK_SIX_UNPERFORMED.map { it.name }.toSet(),
            general.checksNotPerformedHere.toSet(),
            "the general door performs no part of check 6 and must keep saying so (§17)",
        )
        assertTrue(
            six.checksPerformed.toSet().containsAll(general.checksPerformed.toSet()),
            "check 6's door performs everything the general one does and more",
        )
    }

    /**
     * §9.2's refusals, returned as values and equal to the exceptions a JVM caller gets — including
     * which of the two vocabularies answered.
     *
     * `PAYEE_IS_NOT_FEE` is check 6 declining a provider receipt outright, `NO_STORED_REQUEST` is
     * check 1 with nothing to compare against, and `NOT_A_RECEIPT` is the decoder turning away a
     * well-formed message of another kind — which is the refusal that proves the crossing reaches
     * the library's own decoder rather than ruling on the kind itself.
     */
    @Test
    @JsName("the_settlement_entry_points_return_section_9_2_s_refusals_as_values")
    fun `the settlement entry points return §9_2's refusals as values`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)

        // Check 1 with an empty store: §9.2 has nothing to compare the receipt's invoice against.
        val unstored = verifyBothWays(
            "a receipt against an empty store",
            messages.receiptRumor(Payee.PROVIDER),
            PaymentRequestStore.inMemory(),
        )
        assertFalse(unstored.ok, "an unstored receipt evidences nothing")
        assertEquals(SettlementRejection.NO_STORED_REQUEST.name, unstored.reason, "§9.2 check 1")
        assertEquals("SettlementRejection", unstored.reasonVocabulary, "§9.2's own vocabulary")

        // Check 6 declining a provider receipt: §9.2 states it "for a `payee=fee` receipt".
        val provider = feeReceiptBothWays(
            "a provider receipt at check 6's door",
            messages.receiptRumor(Payee.PROVIDER),
            messages,
            awaitingPayment(),
        )
        assertFalse(provider.ok, "check 6 does not apply to a provider receipt at all")
        assertEquals(SettlementRejection.PAYEE_IS_NOT_FEE.name, provider.reason, "§9.2 check 6")

        // §8.4's REQUIRED points absent: the comparison is refused rather than made smaller.
        val noPoints = feeReceiptBothWays(
            "a fee receipt with no earlier §8.4 points",
            messages.receiptRumor(Payee.FEE),
            messages,
            awaitingPayment(),
            earlierPoints = emptyList(),
        )
        assertFalse(noPoints.ok, "§8.4's three REQUIRED points cannot be absent")
        assertEquals(
            SettlementRejection.FEE_TERM_POINT_MISSING.name,
            noPoints.reason,
            "§8.4: an empty list would have the receipt compared against itself and agree",
        )

        // The decoder's own refusal for a well-formed rumor of another bound kind.
        val notAReceipt = verifyBothWays(
            "a kind:16 order message offered as a receipt",
            SettlementFixtures.bound(
                SettlementFixtures.requestTags(
                    index = OrderFixtures.ORDER_INDEX,
                    payment = SettlementFixtures.requestPaymentTag(SettlementFixtures.defaultInvoice()),
                    payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER, OrderFixtures.ORDER_INDEX),
                ),
                NenyaKind.ORDER_MESSAGE,
                OrderFixtures.ORDER_INDEX,
            ),
            messages.store,
        )
        assertFalse(notAReceipt.ok, "a well-formed message of another kind is not a malformed one")
        assertEquals(SettlementRejection.NOT_A_RECEIPT.name, notAReceipt.reason, "§9.2's decoder")
    }

    /**
     * §7.4's `kind:14` offered as a receipt: the crossing's own refusal, because the library's
     * decoder has no parameter for one.
     *
     * The one refusal on this path that is **not** the library's, and the one place T43 widened
     * `JsCrossing`. `PaymentReceipt.decode` takes `AttributedRumor.Bound`, and §7.4 puts `kind:14`
     * outside the bound half in so many words — so there is no value to hand the decoder and
     * `NOT_A_RECEIPT` is unreachable for a chat by construction, not by choice. It is reported
     * under a vocabulary no section of NENYA-1 uses precisely so a page can never read it as a
     * §9.2 ruling: the test asserts the vocabulary as well as the reason.
     */
    @Test
    @JsName("a_chat_rumor_offered_as_a_receipt_is_the_crossing_s_own_refusal")
    fun `a chat rumor offered as a receipt is the crossing's own refusal`() {
        val chat: AttributedRumor = ChannelFixtures.attribute(NenyaKind.CHAT, emptyList())
        val crossed = verifySettlement(
            receipt = JsRumor(chat),
            store = JsFlowFixtures.crossed(PaymentRequestStore.inMemory()),
            terms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
        )

        assertFalse(crossed.ok, "a chat is not a receipt")
        assertEquals(JsCrossing.NOT_A_BOUND_RUMOR, crossed.reason, "the crossing's own reason")
        assertEquals(
            JsCrossing.VOCABULARY,
            crossed.reasonVocabulary,
            "a page must never be able to read this as a §9.2 refusal",
        )
        assertTrue(
            crossed.reason in JsCrossing.REASONS,
            "every reason this boundary reports must be one of the constants it declares",
        )
    }

    /**
     * An absent `find` function fails closed: §9.2 check 1 has nothing to compare against, and the
     * answer is the library's `NO_STORED_REQUEST` rather than an accepted receipt.
     *
     * T43's rule for every plug-in — "each failing closed exactly as its library default does" —
     * and `PaymentRequestStore` is the one seam with no `FAIL_CLOSED` constant to stand in for, so
     * the direction had to be chosen rather than copied. This is the record of which way.
     */
    @Test
    @JsName("a_store_that_answers_nothing_refuses_rather_than_accepts")
    fun `a store that answers nothing refuses rather than accepts`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        for (store in listOf(JsPaymentRequestStore(), JsPaymentRequestStore(find = { _, _ -> null }))) {
            val crossed = verifySettlement(
                receipt = JsRumor(messages.receiptRumor(Payee.PROVIDER)),
                store = store,
                terms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
            )
            assertFalse(crossed.ok, "a store with nothing in it evidences nothing")
            assertEquals(
                SettlementRejection.NO_STORED_REQUEST.name,
                crossed.reason,
                "an absent or empty-answering store is §9.2 check 1 with no operand",
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // The two calls, made twice.
    // -----------------------------------------------------------------------------------------

    /** One rumor through `Settlement.verify` and through [verifySettlement], held equal. */
    private fun verifyBothWays(
        what: String,
        rumor: AttributedRumor.Bound,
        store: PaymentRequestStore,
    ): JsSettlementResult {
        val expected = JsFlowFixtures.settlementAnswerOf {
            Settlement.verify(PaymentReceipt.decode(rumor), store, OrderFixtures.TERMS.split)
        }
        val crossed = verifySettlement(
            receipt = JsRumor(rumor),
            store = JsFlowFixtures.crossed(store),
            terms = JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
        )
        JsFlowFixtures.assertSameSettlement(crossed, expected, what)
        return crossed
    }

    /** One rumor through `Settlement.verifyFeeReceipt` and through [verifyFeeReceipt], held equal. */
    private fun feeReceiptBothWays(
        what: String,
        rumor: AttributedRumor.Bound,
        messages: OrderFixtures.Messages,
        order: Order,
        earlierPoints: List<dev.eryalabs.nenya.settlement.FeeTermSighting> = messages.earlierPoints,
    ): JsSettlementResult {
        val expected = JsFlowFixtures.settlementAnswerOf {
            Settlement.verifyFeeReceipt(
                PaymentReceipt.decode(rumor),
                messages.store,
                order,
                earlierPoints,
            )
        }
        val crossed = verifyFeeReceipt(
            receipt = JsRumor(rumor),
            store = JsFlowFixtures.crossed(messages.store),
            order = JsOrder(order),
            earlierPoints = earlierPoints.map { JsFlowFixtures.crossed(it) }.toTypedArray(),
        )
        JsFlowFixtures.assertSameSettlement(crossed, expected, what)
        return crossed
    }

    /** §8.5's precondition: an order check 6 can be judged against. */
    private fun awaitingPayment(): Order =
        OrderFixtures.orders()[OrderState.AWAITING_PAYMENT]
            ?: fail("the fixture chain must reach awaiting_payment")
}
