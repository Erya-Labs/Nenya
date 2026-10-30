package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.PrivateBid
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.money.FeeTerm
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T60: §8.4's OPTIONAL `BID` point, read by the library off a decoded bid rather than hand-copied by
 * the caller through [FeeTermSighting.of].
 *
 * Both factories are held to `of(BID, <the bid's own fee tag>)`, where the tag is taken from the
 * **input** — the generated public bid's raw tags, or the fee term and recipient a private-bid case
 * was authored with — and never from the library's own re-encoding, so the comparison is not the
 * implementation agreeing with itself.
 */
class BidFeeSightingTest {

    private companion object {

        /** Enough of `BidFixtures.bids`' seeded corpus to reach both fee arities and the fee-less bid. */
        const val BIDS: Int = 300

        /** Enough of `RumorWriterFixtures.cases` to hold many `PRIVATE_BID` cases of each fee shape. */
        val CASES: Int = RumorWriterFixtures.Shape.entries.size * 60

        /** The proposal fixture index whose fee recipient the controls below share. */
        const val INDEX: Int = 0
    }

    /** The sighting `of` gives for [point] and [tag]: what each factory must equal. */
    private fun assertSameSighting(expected: FeeTermSighting, actual: FeeTermSighting, what: String) {
        assertEquals(expected.point, actual.point, "$what: the point")
        assertEquals(expected.term, actual.term, "$what: the fee row, element for element")
    }

    @Test
    @JsName("onBid_equals_of_BID_over_the_public_bid_s_own_fee_tag")
    fun `onBid equals of BID over the public bid's own fee tag`() {
        var stated = 0
        var absent = 0
        for (fixture in BidFixtures.bids(BIDS)) {
            val raw = fixture.tags.singleOrNull { it[0] == ChannelVocabulary.FEE }
            if (raw == null) absent++ else stated++
            val sighting = FeeTermSighting.onBid(BidFixtures.decode(fixture))
            assertEquals(FeeTermPoint.BID, sighting.point, "bid ${fixture.index}")
            assertSameSighting(FeeTermSighting.of(FeeTermPoint.BID, raw), sighting, "bid ${fixture.index}")
        }
        assertTrue(stated > 0 && absent > 0, "the corpus must reach both a fee-bearing and a fee-less bid")
    }

    @Test
    @JsName("onPrivateBid_equals_of_BID_over_the_fee_term_each_private_bid_was_authored_with")
    fun `onPrivateBid equals of BID over the fee term each private bid was authored with`() {
        val shapes = mutableSetOf<String>()
        for (case in RumorWriterFixtures.cases(CASES).filter { it.shape == RumorWriterFixtures.Shape.PRIVATE_BID }) {
            val terms = assertNotNull(case.terms, "$case carries §6.1's terms")
            val expected: List<String>? = when (val fee = terms.split.term) {
                FeeTerm.Absent -> null
                is FeeTerm.Stated ->
                    if (fee.basisPoints == 0) listOf(ChannelVocabulary.FEE, "0")
                    else listOf(
                        ChannelVocabulary.FEE,
                        fee.basisPoints.toString(),
                        assertNotNull(case.feeRecipient, "$case names a recipient above zero"),
                    )
            }
            shapes += when {
                expected == null -> "absent"
                expected.size == 2 -> "zero"
                else -> "stated"
            }
            val bid = PrivateBid.decode(RumorWriterFixtures.bound(RumorWriterFixtures.build(case)))
            val sighting = FeeTermSighting.onPrivateBid(bid)
            assertSameSighting(FeeTermSighting.of(FeeTermPoint.BID, expected), sighting, "$case")
        }
        assertEquals(setOf("absent", "zero", "stated"), shapes, "every §8.1 fee shape must be reached")
    }

    /** §8.4: "a bid that carried a `fee` tag binds the bidder to that term". 250 against 251 diverges. */
    @Test
    @JsName("a_bid_at_250_bps_beside_a_proposal_at_251_diverges_in_basis_points")
    fun `a bid at 250 bps beside a proposal at 251 diverges in basis points`() {
        val proposal = FeeTermSighting.onProposal(
            ProposalFixtures.proposal(ProposalFixtures.proposalTags(INDEX, fee = ProposalFixtures.feeTag(INDEX, 251))),
        )
        val bid250 = FeeTermSighting.onBid(BidFixtures.decode(bidWithFee(250)))
        val diverged = assertIs<FeeTermAgreement.Diverged>(FeeTermAgreement.across(listOf(bid250, proposal)))
        assertEquals(FeeTermPoint.ORDER_PROPOSAL, diverged.point)
        assertEquals(FeeTermElement.BASIS_POINTS, diverged.element)
        assertEquals(listOf(FeeTermPoint.BID, FeeTermPoint.ORDER_PROPOSAL), diverged.points)

        // The paired positive: at 251 the two agree, so the divergence above is the one basis point
        // and not the bid's recipient or its shape.
        val bid251 = FeeTermSighting.onBid(BidFixtures.decode(bidWithFee(251)))
        val agreed = assertIs<FeeTermAgreement.Agreed>(FeeTermAgreement.across(listOf(bid251, proposal)))
        assertEquals(ProposalFixtures.feeTag(INDEX, 251)[2], agreed.recipient)
    }

    /** §8.4's MUST NOT: a fee-less bid is dropped, so it cannot be one of the two points compared. */
    @Test
    @JsName("a_fee_less_bid_beside_one_proposal_sighting_is_not_comparable")
    fun `a fee-less bid beside one proposal sighting is not comparable`() {
        val feeLess = FeeTermSighting.onBid(BidFixtures.decode(BidFixtures.minimalBid()))
        assertEquals(FeeTermPoint.BID, feeLess.point)
        assertNull(feeLess.term, "a bid carrying no `fee` tag is a tagless optional sighting")
        val proposal = FeeTermSighting.onProposal(ProposalFixtures.proposal(ProposalFixtures.proposalTags(INDEX)))
        val refused = assertFailsWith<SettlementException> { FeeTermAgreement.across(listOf(feeLess, proposal)) }
        assertEquals(SettlementRejection.FEE_TERM_NOT_COMPARABLE, refused.reason)
    }

    /** [BidFixtures.minimalBid] with a `fee` tag at [basisPoints], naming the proposal fixture's recipient. */
    private fun bidWithFee(basisPoints: Int): List<List<String>> {
        val tag = ProposalFixtures.feeTag(INDEX, basisPoints)
        if (tag.size != 3) fail("a control above zero names a recipient")
        return BidFixtures.with(BidFixtures.minimalBid(), tag)
    }
}
