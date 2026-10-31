package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.settlement.AcceptedPaymentRequest
import dev.eryalabs.nenya.settlement.FeeTermPoint
import dev.eryalabs.nenya.settlement.FeeTermSighting
import dev.eryalabs.nenya.settlement.PaymentReceipt
import dev.eryalabs.nenya.settlement.PaymentRequest
import dev.eryalabs.nenya.settlement.PaymentRequestStore
import dev.eryalabs.nenya.settlement.Settlement
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.settlement.SettlementRejection
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T71's three crossings of §8.4's signed points, each held equal to the Kotlin door it makes one call
 * to: [proposalFeeSighting] to `OrderProposal.decode` then `FeeTermSighting.onProposal`,
 * [acceptanceFeeSighting] to `OrderStatusMessage.decode` then `onAcceptance`, and
 * [paymentRequestFeeSighting] to `PaymentRequest.decode` against §7.6's checked acceptance's split
 * then `onPaymentRequest`.
 *
 * Each crossed sighting is also handed back through `asFeeTermSighting`, and the door-made sightings
 * are then the only `earlierPoints` [acceptPaymentRequest] and [verifyFeeReceipt] are given — so the
 * shape a page receives here is shown to be the shape it can pass on, and to be accepted there.
 */
class JsSignedFeeSightingEqualityTest {

    private companion object {

        /** Enough of `ProposalFixtures.pairs`' corpus to reach a fee-less, a zero and a non-zero fee term. */
        const val PAIRS: Int = 60
    }

    /** [crossed] names the same point and the same row as [expected], and survives the trip back. */
    private fun assertSame(expected: FeeTermSighting, crossed: JsFeeTermSightingResult, what: String) {
        assertTrue(crossed.ok, "$what: ${crossed.reason}")
        assertEquals("fee_term_sighting", crossed.kind, what)
        assertNull(crossed.reason, what)
        assertNull(crossed.reasonVocabulary, what)
        assertNull(crossed.tag, what)
        assertNull(crossed.detail, what)
        val sighting = assertNotNull(crossed.sighting, "$what: an answer carries its sighting")
        assertEquals(expected.point.name, sighting.point, "$what: the point")
        assertEquals(expected.term, sighting.feeTag?.toList(), "$what: the fee row")
        val back = sighting.asFeeTermSighting()
        assertEquals(expected.point, back.point, "$what: the point, handed back")
        assertEquals(expected.term, back.term, "$what: the fee row, handed back")
    }

    /** A refusal as a value: [reason] from [vocabulary], and no sighting beside it. */
    private fun assertRefused(answer: JsFeeTermSightingResult, reason: String, vocabulary: String, what: String) {
        assertFalse(answer.ok, what)
        assertEquals("refused", answer.kind, what)
        assertNull(answer.sighting, "$what: a refusal carries no sighting")
        assertEquals(reason, answer.reason, what)
        assertEquals(vocabulary, answer.reasonVocabulary, what)
        assertNotNull(answer.detail, what)
    }

    @Test
    @JsName("the_proposal_and_acceptance_sightings_equal_their_Kotlin_doors_over_the_proposal_corpus")
    fun `the proposal and acceptance sightings equal their Kotlin doors over the proposal corpus`() {
        var absent = 0
        for (fixture in ProposalFixtures.pairs(PAIRS)) {
            val proposal = ProposalFixtures.bound(fixture.proposalTags, fixture.index)
            val acceptance = ProposalFixtures.bound(fixture.acceptanceTags, fixture.index + 1)
            val crossed = proposalFeeSighting(JsRumor(proposal))
            if (crossed.sighting?.feeTag == null) absent++
            assertSame(FeeTermSighting.onProposal(OrderProposal.decode(proposal)), crossed, "proposal ${fixture.index}")
            assertSame(
                FeeTermSighting.onAcceptance(OrderStatusMessage.decode(acceptance)),
                acceptanceFeeSighting(JsRumor(acceptance)),
                "acceptance ${fixture.index}",
            )
        }
        assertTrue(absent in 1 until PAIRS, "the corpus must reach both a fee-bearing and a fee-less proposal")
    }

    @Test
    @JsName("all_three_sightings_equal_their_Kotlin_doors_over_every_order_s_messages")
    fun `all three sightings equal their Kotlin doors over every order's messages`() {
        for (index in 0..OrderFixtures.OTHER_ORDER_INDEX) {
            for (stream in 0 until OrderFixtures.STREAMS) {
                val messages = OrderFixtures.messages(index, stream)
                val what = "order $index at stream $stream"
                assertSame(
                    FeeTermSighting.onProposal(messages.proposal),
                    proposalFeeSighting(JsRumor(messages.proposalRumor)),
                    "$what: the proposal",
                )
                assertSame(
                    FeeTermSighting.onAcceptance(messages.acceptance),
                    acceptanceFeeSighting(JsRumor(messages.acceptanceRumor)),
                    "$what: the acceptance",
                )
                for (payee in Payee.entries) {
                    val crossed = requestSighting(messages, payee)
                    assertSame(
                        FeeTermSighting.onPaymentRequest(messages.requests.getValue(payee)),
                        crossed,
                        "$what: the $payee request",
                    )
                    // The point is the request's own `payee`, never the page's choice.
                    assertEquals(
                        when (payee) {
                            Payee.FEE -> FeeTermPoint.FEE_PAYMENT_REQUEST.name
                            Payee.PROVIDER -> FeeTermPoint.PROVIDER_PAYMENT_REQUEST.name
                        },
                        crossed.sighting?.point,
                        "$what: the $payee request's point",
                    )
                }
                assertEquals(
                    messages.feeTag,
                    requestSighting(messages, Payee.FEE).sighting?.feeTag?.toList(),
                    "$what: §8.4 makes the tag REQUIRED on the fee request",
                )
            }
        }
    }

    /**
     * The door-made sightings are the whole of `earlierPoints`, and both fee doors accept — the same
     * answers the Kotlin calls give over `FeeTermSighting`'s own doors.
     */
    @Test
    @JsName("door_made_sightings_fed_as_earlierPoints_are_accepted_at_both_fee_doors")
    fun `door-made sightings fed as earlierPoints are accepted at both fee doors`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val proposal = proposalFeeSighting(JsRumor(messages.proposalRumor)).sighting ?: fail("the proposal's sighting")
        val acceptance = acceptanceFeeSighting(JsRumor(messages.acceptanceRumor)).sighting
            ?: fail("the acceptance's sighting")
        val feeRequest = requestSighting(messages, Payee.FEE).sighting ?: fail("the fee request's sighting")

        val rumor = messages.requestRumors.getValue(Payee.FEE)
        val provider = SettlementFixtures.provider(messages.index)
        val expectedRequest = JsFlowFixtures.acceptedAnswerOf {
            val accepted = OrderProposal.decode(messages.proposalRumor).accepts(
                OrderStatusMessage.decode(messages.acceptanceRumor),
                provider,
            ) as? Acceptance.Accepted ?: fail("the expected side needs a checked acceptance")
            AcceptedPaymentRequest.accept(
                request = PaymentRequest.decode(rumor, accepted.terms.split),
                acceptance = accepted,
                into = PaymentRequestStore.inMemory(),
                clock = FakeClock(SettlementFixtures.ACCEPTED_AT),
                feeTermPoints = listOf(
                    FeeTermSighting.onProposal(messages.proposal),
                    FeeTermSighting.onAcceptance(messages.acceptance),
                ),
            )
        }
        val crossedRequest = acceptPaymentRequest(
            request = JsRumor(rumor),
            proposalRumor = JsRumor(messages.proposalRumor),
            acceptanceRumor = JsRumor(messages.acceptanceRumor),
            providerPubkey = provider,
            store = JsFlowFixtures.CrossedStore().seam,
            environment = JsFlowFixtures.crossedEnvironment(clock = FakeClock(SettlementFixtures.ACCEPTED_AT)),
            earlierPoints = arrayOf(proposal, acceptance),
        )
        JsFlowFixtures.assertSameAccepted(crossedRequest, expectedRequest, "the fee request")
        assertTrue(crossedRequest.ok, "§8.7's door accepts over door-made points: ${crossedRequest.reason}")

        val receipt = messages.receiptRumor(Payee.FEE)
        val order = awaitingPayment()
        val expectedReceipt = JsFlowFixtures.settlementAnswerOf {
            Settlement.verifyFeeReceipt(PaymentReceipt.decode(receipt), messages.store, order, messages.earlierPoints)
        }
        val crossedReceipt = verifyFeeReceipt(
            receipt = JsRumor(receipt),
            store = JsFlowFixtures.crossed(messages.store),
            order = JsOrder(order),
            earlierPoints = arrayOf(proposal, acceptance, feeRequest),
        )
        JsFlowFixtures.assertSameSettlement(crossedReceipt, expectedReceipt, "the fee receipt")
        assertTrue(crossedReceipt.ok, "§9.2 check 6 accepts over door-made points: ${crossedReceipt.reason}")
    }

    /** §8.4's second point is "the acceptance (`type=3`, `status=accepted`)", and a `cancelled` update is not it. */
    @Test
    @JsName("a_cancelled_update_is_refused_NOT_AN_ACCEPTANCE_with_no_sighting")
    fun `a cancelled update is refused NOT_AN_ACCEPTANCE with no sighting`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val cancelled = ProposalFixtures.bound(
            ProposalFixtures.acceptanceTags(
                messages.proposalTags,
                messages.index,
                status = OrderStatusCodec.write(OrderState.CANCELLED),
                terms = emptyList(),
            ),
            messages.index + 1,
        )
        assertRefused(
            acceptanceFeeSighting(JsRumor(cancelled)),
            SettlementRejection.NOT_AN_ACCEPTANCE.name,
            "SettlementRejection",
            "a cancelled update",
        )
        // Nor is a payment request decoded against one: §7.6 answers no acceptance for it.
        val request = paymentRequestFeeSighting(
            JsRumor(messages.requestRumors.getValue(Payee.FEE)),
            JsRumor(messages.proposalRumor),
            JsRumor(cancelled),
            SettlementFixtures.provider(messages.index),
        )
        assertFalse(request.ok, "a request read against a cancelled update")
        assertNull(request.sighting, "a refusal carries no sighting")
    }

    @Test
    @JsName("a_status_update_handed_to_proposalFeeSighting_is_refused_NOT_A_PROPOSAL")
    fun `a status update handed to proposalFeeSighting is refused NOT_A_PROPOSAL`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        assertRefused(
            proposalFeeSighting(JsRumor(messages.acceptanceRumor)),
            ChannelRejection.NOT_A_PROPOSAL.name,
            "ChannelRejection",
            "a type=3 as a proposal",
        )
    }

    /** [payee]'s `type=2` read through [paymentRequestFeeSighting] against this order's own acceptance. */
    private fun requestSighting(messages: OrderFixtures.Messages, payee: Payee): JsFeeTermSightingResult =
        paymentRequestFeeSighting(
            request = JsRumor(messages.requestRumors.getValue(payee)),
            proposalRumor = JsRumor(messages.proposalRumor),
            acceptanceRumor = JsRumor(messages.acceptanceRumor),
            providerPubkey = SettlementFixtures.provider(messages.index),
        )

    /** §8.5's precondition: an order check 6 can be judged against. */
    private fun awaitingPayment(): Order =
        OrderFixtures.orders()[OrderState.AWAITING_PAYMENT]
            ?: fail("the fixture chain must reach awaiting_payment")
}
