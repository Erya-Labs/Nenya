package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.settlement.PaymentReceipt
import dev.eryalabs.nenya.settlement.Settlement
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T72: an order opened from the decoded `type=1`, and the proposal's terms published in the shape
 * [openOrder] and [verifySettlement] take.
 *
 * Every `type=1` this file reaches is opened three ways and the three orders compared field for
 * field: [openOrderFromProposal] over the rumor; [openOrder] over the order id, terms and
 * `created_at` [decodeProposal] published; and the Kotlin `OrderMachine.open` over
 * `OrderProposal.asOrderEvent()`. The expected side never calls a facade function.
 */
class JsOrderFromProposalEqualityTest {

    private companion object {

        /** Enough `RumorWriterFixtures` cases to reach twenty-five `type=1`s. */
        val WRITER_CASES: Int = RumorWriterFixtures.Shape.entries.size * 25

        /** Enough of `ProposalFixtures.pairs`' corpus to reach absent deadlines and an absent fee term. */
        const val PAIRS: Int = 60
    }

    /** The machine both sides open with: `open` reads none of it. */
    private fun machine(): OrderMachine = OrderFixtures.machineBeforeDeadlines()

    private fun crossedMachine(): JsOrderMachine =
        JsOrderMachine(JsFlowFixtures.crossedEnvironment(clock = FakeClock(OrderFixtures.BEFORE_DEADLINES)))

    /**
     * Every `type=1` rumor this file opens, named, beside the terms its fixture was **built from** —
     * or `null` where the fixture holds tags rather than terms.
     */
    private fun proposals(): List<Triple<String, AttributedRumor.Bound, OrderTerms?>> = buildList {
        for ((what, terms) in listOf(
            "a priced order with a stated fee" to OrderFixtures.TERMS,
            "§8.1's signed zero" to OrderFixtures.zeroFeeTerms,
            "no fee term at all" to OrderFixtures.absentFeeTerms,
            "a free order" to OrderFixtures.freeTerms(),
        )) {
            val chain = OrderFixtures.chain(terms, OrderFixtures.ORDER_INDEX)
            add(Triple("OrderFixtures chain: $what", ProposalFixtures.bound(chain.proposalTags, chain.index), terms))
        }
        for (index in 0..OrderFixtures.OTHER_ORDER_INDEX) {
            for (stream in 0 until OrderFixtures.STREAMS) {
                add(Triple("OrderFixtures messages $index/$stream", OrderFixtures.messages(index, stream).proposalRumor, null))
            }
        }
        for (case in RumorWriterFixtures.cases(WRITER_CASES).filter { it.shape == RumorWriterFixtures.Shape.PROPOSAL }) {
            add(Triple("RumorWriterFixtures $case", RumorWriterFixtures.bound(RumorWriterFixtures.build(case)), case.terms))
        }
        for (fixture in ProposalFixtures.pairs(PAIRS)) {
            add(Triple("ProposalFixtures pair ${fixture.index}", ProposalFixtures.bound(fixture.proposalTags, fixture.index), null))
        }
    }

    @Test
    @JsName("openOrderFromProposal_equals_openOrder_over_the_published_terms_and_the_Kotlin_open")
    fun `openOrderFromProposal equals openOrder over the published terms and the Kotlin open`() {
        var feeBearing = 0
        var feeless = 0
        var undated = 0
        var writerCases = 0
        for ((what, rumor, built) in proposals()) {
            val decoded = OrderProposal.decode(rumor)
            val published = decodeProposal(JsRumor(rumor)).proposal ?: fail("$what must decode")

            // The published terms, field for field against the decoded proposal and, where the
            // fixture was built from terms, against those inputs too.
            assertEquals(
                JsMessageFixtures.termsValues(decoded.terms),
                JsMessageFixtures.termsValues(published.terms),
                "$what: the published terms",
            )
            if (built != null) {
                assertEquals(
                    JsMessageFixtures.termsValues(built),
                    JsMessageFixtures.termsValues(published.terms),
                    "$what: the published terms are the terms the proposal was built from",
                )
            }
            if (what.startsWith("RumorWriterFixtures")) writerCases++
            when (decoded.terms.split.term) {
                FeeTerm.Absent -> feeless++
                else -> if (decoded.terms.split.fee.millisatoshis > 0) feeBearing++
            }
            if (decoded.terms.expiration == null && decoded.terms.deliverBy == null) undated++

            val expected = machine().open(decoded.asOrderEvent())
            val fromRumor = openOrderFromProposal(crossedMachine(), JsRumor(rumor))
            val fromTerms = openOrder(
                crossedMachine(),
                published.orderIdHex,
                published.terms,
                published.createdAtSeconds,
            )
            for ((door, answer) in listOf("openOrderFromProposal" to fromRumor, "openOrder" to fromTerms)) {
                assertTrue(answer.ok, "$what: $door must open: ${answer.reason} ${answer.detail}")
                assertEquals("order", answer.kind, "$what: $door")
                assertNull(answer.reason, "$what: $door")
                JsBoundaryFixtures.assertSameFields(
                    JsFlowFixtures.orderFields(answer.order ?: fail("$what: $door carries no order"), expected),
                    "$what: $door",
                )
                assertEquals(OrderState.PROPOSED.name, answer.order?.state, "$what: $door — §11.2's first row")
            }
            assertEquals(decoded.order.toHex(), fromRumor.order?.idHex, "$what: the order id is the wire's `order` tag")
        }
        assertEquals(25, writerCases, "every generated `type=1` is reached")
        assertTrue(feeBearing > 0, "a fee-bearing proposal is reached")
        assertTrue(feeless > 0, "a proposal with no fee term is reached")
        assertTrue(undated > 0, "a proposal with neither deadline is reached")
    }

    /**
     * The control the mutation names, in-suite: terms with the fee term dropped are not the published
     * terms of a fee-bearing proposal, and the order they open is not the order the rumor opens.
     */
    @Test
    @JsName("terms_with_the_fee_term_dropped_differ_from_a_fee_bearing_proposal_s_published_terms")
    fun `terms with the fee term dropped differ from a fee-bearing proposal's published terms`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val published = decodeProposal(JsRumor(messages.proposalRumor)).proposal ?: fail("the proposal must decode")
        assertEquals(OrderFixtures.FEE_BASIS_POINTS, published.terms.feeBasisPoints, "the fixture is fee-bearing")
        val dropped = published.terms.let {
            JsOrderTerms(it.priceMsat, null, it.expirationSeconds, it.deliverBySeconds)
        }
        assertNotEquals(
            JsMessageFixtures.termsValues(messages.proposal.terms),
            JsMessageFixtures.termsValues(dropped),
            "a dropped fee term is a different signed statement (§8.1)",
        )
        val fromDropped = openOrder(crossedMachine(), published.orderIdHex, dropped, published.createdAtSeconds)
        val fromRumor = openOrderFromProposal(crossedMachine(), JsRumor(messages.proposalRumor))
        assertNotEquals(fromRumor.order?.feeBasisPoints, fromDropped.order?.feeBasisPoints, "the fee term")
        assertNotEquals(fromRumor.order?.feeMsat, fromDropped.order?.feeMsat, "§8.3's fee")
    }

    /**
     * `verifySettlement` over the published terms answers exactly as over the fixture's own terms, and
     * as the Kotlin call does — for every payee's receipt, this order's and the other order's.
     */
    @Test
    @JsName("verifySettlement_over_the_published_terms_answers_as_over_the_fixture_s_terms")
    fun `verifySettlement over the published terms answers as over the fixture's terms`() {
        var accepted = 0
        var refused = 0
        for (index in 0..OrderFixtures.OTHER_ORDER_INDEX) {
            for (stream in 0 until OrderFixtures.STREAMS) {
                val messages = OrderFixtures.messages(index, stream)
                val published = decodeProposal(JsRumor(messages.proposalRumor)).proposal
                    ?: fail("order $index/$stream: the proposal must decode")
                for (receiptIndex in 0..OrderFixtures.OTHER_ORDER_INDEX) {
                    for (payee in Payee.entries) {
                        val receipt = OrderFixtures.messages(receiptIndex, stream).receiptRumor(payee)
                        val what = "order $index/$stream, the $payee receipt of order $receiptIndex"
                        val expected = JsFlowFixtures.settlementAnswerOf {
                            Settlement.verify(PaymentReceipt.decode(receipt), messages.store, OrderFixtures.TERMS.split)
                        }
                        val overPublished =
                            verifySettlement(JsRumor(receipt), JsFlowFixtures.crossed(messages.store), published.terms)
                        val overFixture = verifySettlement(
                            JsRumor(receipt),
                            JsFlowFixtures.crossed(messages.store),
                            JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
                        )
                        JsFlowFixtures.assertSameSettlement(overPublished, expected, "$what, over the published terms")
                        JsFlowFixtures.assertSameSettlement(overFixture, expected, "$what, over the fixture's terms")
                        if (overPublished.ok) accepted++ else refused++
                    }
                }
            }
        }
        assertTrue(accepted > 0, "an accepted receipt is reached")
        assertTrue(refused > 0, "a refused receipt is reached")
    }

    /** A `type=3` is not a proposal: the decoder's own `NOT_A_PROPOSAL`, as a value, and no order. */
    @Test
    @JsName("a_status_update_handed_to_openOrderFromProposal_is_refused_NOT_A_PROPOSAL_with_no_order")
    fun `a status update handed to openOrderFromProposal is refused NOT_A_PROPOSAL with no order`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val answer = openOrderFromProposal(crossedMachine(), JsRumor(messages.acceptanceRumor))
        assertFalse(answer.ok, "a type=3 opens no order")
        assertEquals("refused", answer.kind)
        assertNull(answer.order, "a refusal carries no order")
        assertEquals(ChannelRejection.NOT_A_PROPOSAL.name, answer.reason)
        assertEquals("ChannelRejection", answer.reasonVocabulary)
        assertNotNull(answer.detail)
    }

    /** As [openOrder]: a machine configured with a non-positive timeout is refused at the first call. */
    @Test
    @JsName("openOrderFromProposal_refuses_a_zero_timeout_machine_as_a_value")
    fun `openOrderFromProposal refuses a zero-timeout machine as a value`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val answer = openOrderFromProposal(
            JsOrderMachine(JsFlowFixtures.crossedEnvironment(), releaseTimeoutSeconds = "0"),
            JsRumor(messages.proposalRumor),
        )
        val expected = openOrder(
            JsOrderMachine(JsFlowFixtures.crossedEnvironment(), releaseTimeoutSeconds = "0"),
            OrderFixtures.ORDER_ID.toHex(),
            JsBoundaryFixtures.crossed(OrderFixtures.TERMS),
        )
        assertFalse(answer.ok, "a zero release timeout is refused")
        assertNull(answer.order)
        assertEquals(expected.reason, answer.reason, "the same refusal openOrder gives")
        assertEquals("OrderStateRejection", answer.reasonVocabulary, "`OrderMachine`'s own init")
    }
}
