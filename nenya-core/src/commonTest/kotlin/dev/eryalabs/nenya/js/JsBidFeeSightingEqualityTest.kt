package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.PrivateBid
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.settlement.FeeTermPoint
import dev.eryalabs.nenya.settlement.FeeTermSighting
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T60's two crossings of §8.4's `BID` point, each held equal to the Kotlin factory it makes one call
 * to: [bidFeeSighting] to `FeeTermSighting.onBid`, [privateBidFeeSighting] to `PrivateBid.decode`
 * then `FeeTermSighting.onPrivateBid`.
 *
 * Each crossed sighting is also handed back through `asFeeTermSighting` — the translation the
 * settlement checks' `earlierPoints` use — so the shape a page receives here is the shape it can pass
 * on unchanged.
 */
class JsBidFeeSightingEqualityTest {

    private companion object {

        /** Enough of `BidFixtures.bids`' corpus to reach both fee arities and the fee-less bid. */
        const val BIDS: Int = 120

        /** Enough of `RumorWriterFixtures.cases` to hold many `PRIVATE_BID` cases. */
        val CASES: Int = RumorWriterFixtures.Shape.entries.size * 30
    }

    /** [crossed] names the same point and the same row as [expected], and survives the trip back. */
    private fun assertSame(expected: FeeTermSighting, crossed: JsFeeTermSighting, what: String) {
        assertEquals(expected.point.name, crossed.point, "$what: the point")
        assertEquals(expected.term, crossed.feeTag?.toList(), "$what: the fee row")
        val back = crossed.asFeeTermSighting()
        assertEquals(expected.point, back.point, "$what: the point, handed back")
        assertEquals(expected.term, back.term, "$what: the fee row, handed back")
    }

    @Test
    @JsName("bidFeeSighting_equals_onBid_over_the_public_bid_corpus")
    fun `bidFeeSighting equals onBid over the public bid corpus`() {
        var absent = 0
        for (fixture in BidFixtures.bids(BIDS)) {
            val bid = BidFixtures.decode(fixture)
            val crossed = bidFeeSighting(JsBid(bid))
            if (crossed.feeTag == null) absent++
            assertSame(FeeTermSighting.onBid(bid), crossed, "bid ${fixture.index}")
        }
        assertTrue(absent in 1 until BIDS, "the corpus must reach both a fee-bearing and a fee-less bid")
    }

    @Test
    @JsName("privateBidFeeSighting_equals_onPrivateBid_over_every_private_bid_case")
    fun `privateBidFeeSighting equals onPrivateBid over every private bid case`() {
        val cases = RumorWriterFixtures.cases(CASES).filter { it.shape == RumorWriterFixtures.Shape.PRIVATE_BID }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val rumor = RumorWriterFixtures.bound(RumorWriterFixtures.build(case))
            val answer = privateBidFeeSighting(JsRumor(rumor))
            assertTrue(answer.ok, "$case: ${answer.reason}")
            assertEquals("fee_term_sighting", answer.kind)
            assertNull(answer.reason)
            assertNull(answer.reasonVocabulary)
            assertNull(answer.tag)
            assertNull(answer.detail)
            val crossed = assertNotNull(answer.sighting, "$case: an answer carries its sighting")
            assertSame(FeeTermSighting.onPrivateBid(PrivateBid.decode(rumor)), crossed, "$case")
        }
    }

    /** §6.1's refusals reach a page as values: a `type=1` proposal, and a `type=6` without `price`. */
    @Test
    @JsName("privateBidFeeSighting_refuses_a_proposal_and_a_bid_without_price_as_values")
    fun `privateBidFeeSighting refuses a proposal and a bid without price as values`() {
        val fixture = ProposalFixtures.pairs(1).single()
        val proposal = privateBidFeeSighting(JsRumor(ProposalFixtures.bound(fixture.proposalTags, fixture.index)))
        assertFalse(proposal.ok)
        assertEquals("refused", proposal.kind)
        assertNull(proposal.sighting, "a refusal carries no sighting")
        assertEquals(ChannelRejection.NOT_A_PRIVATE_BID.name, proposal.reason)
        assertEquals("ChannelRejection", proposal.reasonVocabulary)
        assertNotNull(proposal.detail)

        val bid = RumorWriterFixtures.cases(8).single { it.shape == RumorWriterFixtures.Shape.PRIVATE_BID }
        val tags = RumorWriterFixtures.build(bid).tags.filter { it[0] != "price" }
        val noPrice = privateBidFeeSighting(JsRumor(ProposalFixtures.bound(tags)))
        assertFalse(noPrice.ok)
        assertNull(noPrice.sighting)
        assertEquals(ChannelRejection.MISSING_REQUIRED_TAG.name, noPrice.reason, "§6.1: it MUST carry `price`")
        assertEquals("price", noPrice.tag)
    }

    /** A fee-less public bid crosses at `BID` with no row — §8.4's tagless optional sighting. */
    @Test
    @JsName("a_fee_less_public_bid_crosses_a_tagless_BID_sighting")
    fun `a fee-less public bid crosses a tagless BID sighting`() {
        val crossed = bidFeeSighting(JsBid(BidFixtures.decode(BidFixtures.minimalBid())))
        assertEquals(FeeTermPoint.BID.name, crossed.point)
        assertNull(crossed.feeTag)
    }
}
