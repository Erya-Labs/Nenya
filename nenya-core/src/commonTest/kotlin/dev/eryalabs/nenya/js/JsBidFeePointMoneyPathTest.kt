package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
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
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T63: §8.4's OPTIONAL `BID` point is load-bearing on the money path, across the JavaScript boundary.
 *
 * T60 crossed the bid point as [bidFeeSighting], and T60's reviewer noted that nothing fed one into a
 * fee check. This is that chain, end to end and through the doors a page has: a §6 public bid through
 * [decodeBid], its sighting through [bidFeeSighting], then into the `earlierPoints` of
 * [acceptPaymentRequest] (§8.7's fee `type=2`) and of [verifyFeeReceipt] (§9.2 check 6).
 *
 * ### Only the bid disagrees
 *
 * The proposal, the acceptance, the fee `type=2`, the fee receipt and the order all come from
 * `OrderFixtures` at its own 250 bps, so the bid is the one message whose term can differ. A bid at
 * 251 bps naming the same fee recipient therefore diverges in exactly §8.1's basis points, and §8.4
 * says such a divergence "MUST abort the order". Each refusal is shown to be the bid point's by the
 * identical call without the bid sighting, which accepts.
 *
 * ### Each crossed answer equals the Kotlin one
 *
 * The expected side decodes the same event with `Bid.decode` and takes `FeeTermSighting.onBid`, and
 * hands that to `AcceptedPaymentRequest.accept` and `Settlement.verifyFeeReceipt`; the crossed side
 * uses only what [decodeBid] and [bidFeeSighting] handed back. Every invoice is `SettlementFixtures`'
 * (decision **D**): nothing here is typed.
 */
class JsBidFeePointMoneyPathTest {

    private companion object {

        /** One basis point off the order's own term, so only §8.1's first element differs. */
        const val DIVERGENT_BASIS_POINTS: Int = OrderFixtures.FEE_BASIS_POINTS + 1
    }

    private val messages: OrderFixtures.Messages =
        OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)

    /** A matching bid's sighting passes both doors, and so does the same chain with no bid at all. */
    @Test
    @JsName("a_bid_at_the_order_s_own_fee_term_is_accepted_at_both_doors")
    fun `a bid at the order's own fee term is accepted at both doors`() {
        val bid = BidSide(OrderFixtures.FEE_BASIS_POINTS)
        assertEquals(messages.feeTag, bid.crossed.feeTag?.toList(), "the bid states the order's own fee term")

        assertTrue(feeRequestBothWays("the fee request after a matching bid", bid).ok)
        assertTrue(feeReceiptBothWays("the fee receipt after a matching bid", bid).ok)
        assertTrue(feeRequestBothWays("the fee request with no bid", null).ok)
        assertTrue(feeReceiptBothWays("the fee receipt with no bid", null).ok)
    }

    /** §8.4's "MUST abort": a bid one basis point off refuses the fee request and the fee receipt. */
    @Test
    @JsName("a_bid_one_basis_point_off_refuses_the_fee_request_and_the_fee_receipt")
    fun `a bid one basis point off refuses the fee request and the fee receipt`() {
        val bid = BidSide(DIVERGENT_BASIS_POINTS)

        val request = feeRequestBothWays("the fee request after a diverging bid", bid)
        assertFalse(request.ok, "§8.7's door must refuse a fee request the bid's term disagrees with")
        assertEquals(SettlementRejection.FEE_TERM_MISMATCH.name, request.reason, "§8.4: a terms mismatch")
        assertEquals("SettlementRejection", request.reasonVocabulary)

        val receipt = feeReceiptBothWays("the fee receipt after a diverging bid", bid)
        assertFalse(receipt.ok, "§9.2 check 6 must refuse a fee receipt the bid's term disagrees with")
        assertEquals(SettlementRejection.FEE_TERM_MISMATCH.name, receipt.reason, "§8.4: a terms mismatch")
        assertEquals("SettlementRejection", receipt.reasonVocabulary)

        // The identical calls without the bid sighting accept, so each refusal above is the bid's.
        assertTrue(feeRequestBothWays("the same fee request, bid sighting dropped", null).ok)
        assertTrue(feeReceiptBothWays("the same fee receipt, bid sighting dropped", null).ok)
        assertEquals(
            ProposalFixtures.feeTag(OrderFixtures.ORDER_INDEX, DIVERGENT_BASIS_POINTS),
            bid.crossed.feeTag?.toList(),
            "the crossed bid sighting carries the bid's own row",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The bid, and the two doors, each made twice.
    // -----------------------------------------------------------------------------------------

    /**
     * §6's public bid on this order's listing, from the provider, carrying [basisPoints] to the
     * order's own fee recipient — once decoded by the library and once crossed.
     */
    private class BidSide(basisPoints: Int) {

        private val event: WireEvent = WireEvent(
            SettlementFixtures.provider(OrderFixtures.ORDER_INDEX),
            BidFixtures.CREATED_AT,
            NenyaKind.PUBLIC_BID,
            BidFixtures.with(
                scopedToTheOrdersListing(BidFixtures.minimalBid()),
                ProposalFixtures.feeTag(OrderFixtures.ORDER_INDEX, basisPoints),
            ),
            "",
        )

        /** The Kotlin side: `Bid.decode` then `FeeTermSighting.onBid`. */
        val expected: FeeTermSighting =
            FeeTermSighting.onBid(Bid.decode(CheckedEvent.checkEventId(EventId.of(event).toHex(), event)))

        /** The page's side: [decodeBid] over the event's JSON, then [bidFeeSighting]. */
        val crossed: JsFeeTermSighting = run {
            val answer = decodeBid(JsBoundaryFixtures.json(event))
            assertTrue(answer.ok, "the bid must decode: ${answer.reason}")
            bidFeeSighting(answer.bid ?: fail("an ok answer carries a bid"))
        }

        init {
            assertEquals(FeeTermPoint.BID, expected.point)
            assertEquals(expected.point.name, crossed.point, "the crossed sighting's point")
            assertEquals(expected.term, crossed.feeTag?.toList(), "the crossed sighting equals onBid")
        }

        /** [tags] with §6's `A`/`a`/`P`/`p` pointing at the listing the order's `item` names. */
        private fun scopedToTheOrdersListing(tags: List<List<String>>): List<List<String>> {
            val coordinate = ProposalFixtures.coordinate(OrderFixtures.ORDER_INDEX)
            val author = coordinate.split(":")[1]
            return tags.map { tag ->
                when (tag[0]) {
                    "A", "a" -> listOf(tag[0], coordinate) + tag.drop(2)
                    "P", "p" -> listOf(tag[0], author) + tag.drop(2)
                    else -> tag
                }
            }
        }
    }

    /** The bid's sighting, observed first, then [rest] — the order a client sees them in. */
    private fun <T> withBid(bid: T?, rest: List<T>): List<T> = listOfNotNull(bid) + rest

    /** §8.4's two signed points before a `type=2`: the proposal and the acceptance. */
    private fun signedPoints(): List<FeeTermSighting> = listOf(
        FeeTermSighting.onProposal(messages.proposal),
        FeeTermSighting.onAcceptance(messages.acceptance),
    )

    /** The order's fee `type=2` through `AcceptedPaymentRequest.accept` and [acceptPaymentRequest]. */
    private fun feeRequestBothWays(what: String, bid: BidSide?): JsAcceptedRequestResult {
        val rumor = messages.requestRumors.getValue(Payee.FEE)
        val provider = SettlementFixtures.provider(messages.index)
        val expected = JsFlowFixtures.acceptedAnswerOf {
            val acceptance = OrderProposal.decode(messages.proposalRumor).accepts(
                OrderStatusMessage.decode(messages.acceptanceRumor),
                provider,
            ) as? Acceptance.Accepted ?: fail("$what: the expected side needs a checked acceptance")
            AcceptedPaymentRequest.accept(
                request = PaymentRequest.decode(rumor, acceptance.terms.split),
                acceptance = acceptance,
                into = PaymentRequestStore.inMemory(),
                clock = FakeClock(SettlementFixtures.ACCEPTED_AT),
                feeTermPoints = withBid(bid?.expected, signedPoints()),
            )
        }
        val crossed = acceptPaymentRequest(
            request = JsRumor(rumor),
            proposalRumor = JsRumor(messages.proposalRumor),
            acceptanceRumor = JsRumor(messages.acceptanceRumor),
            providerPubkey = provider,
            store = JsFlowFixtures.CrossedStore().seam,
            environment = JsFlowFixtures.crossedEnvironment(clock = FakeClock(SettlementFixtures.ACCEPTED_AT)),
            earlierPoints = withBid(bid?.crossed, signedPoints().map { JsFlowFixtures.crossed(it) }).toTypedArray(),
        )
        JsFlowFixtures.assertSameAccepted(crossed, expected, what)
        return crossed
    }

    /** The order's fee `kind:17` through `Settlement.verifyFeeReceipt` and [verifyFeeReceipt]. */
    private fun feeReceiptBothWays(what: String, bid: BidSide?): JsSettlementResult {
        val rumor = messages.receiptRumor(Payee.FEE)
        val order = awaitingPayment()
        val expected = JsFlowFixtures.settlementAnswerOf {
            Settlement.verifyFeeReceipt(
                PaymentReceipt.decode(rumor),
                messages.store,
                order,
                withBid(bid?.expected, messages.earlierPoints),
            )
        }
        val crossed = verifyFeeReceipt(
            receipt = JsRumor(rumor),
            store = JsFlowFixtures.crossed(messages.store),
            order = JsOrder(order),
            earlierPoints = withBid(bid?.crossed, messages.earlierPoints.map { JsFlowFixtures.crossed(it) })
                .toTypedArray(),
        )
        JsFlowFixtures.assertSameSettlement(crossed, expected, what)
        return crossed
    }

    /** §8.5's precondition: an order check 6 can be judged against. */
    private fun awaitingPayment(): Order =
        OrderFixtures.orders()[OrderState.AWAITING_PAYMENT]
            ?: fail("the fixture chain must reach awaiting_payment")
}
