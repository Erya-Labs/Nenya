package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.bid.DeadlineRefusal
import dev.eryalabs.nenya.channel.ListingOrderBuild
import dev.eryalabs.nenya.channel.ListingOrderWriter
import dev.eryalabs.nenya.channel.RumorEnvelope
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T61's crossing, [buildProposalForBid], held equal to `ListingOrderWriter.proposalForBid` field by
 * field, under the same clock handed in once as a `NenyaClock` and once as the page's decimal-string
 * function, over both sides of decision **H**, the right and the wrong parties, and every `acceptBy`
 * shape.
 */
class JsProposalForBidEqualityTest {

    private companion object {

        const val DEADLINE: Long = ListingFixtures.CREATED_AT + 10_000L
        const val BID_DEADLINE: Long = DEADLINE - 2_000L
        const val DELIVER_BY: Long = DEADLINE + 50_000L

        const val LISTING_AUTHOR_INDEX: Int = 1
        const val BIDDER_INDEX: Int = 2

        val LISTING_AUTHOR: String = TagFixtures.pubkeyFor(LISTING_AUTHOR_INDEX)
        val BIDDER: String = TagFixtures.pubkeyFor(BIDDER_INDEX)

        val ORDER_HEX: String = RumorWriterFixtures.orderFor(0).toHex()

        /** Readings either side of each deadline, `null` being the page that supplied no clock. */
        val READINGS: List<Long?> = listOf(null, -1L, BID_DEADLINE - 1, BID_DEADLINE, DEADLINE)

        /** No deadline, a legal one, one at `deliver_by`, and a negative one. */
        val ACCEPT_BY: List<Long?> = listOf(null, BID_DEADLINE + 1, DELIVER_BY, -1L)

        fun listing(kind: Int): Listing {
            val tags = ListingFixtures.minimal(kind)
            tags += listOf(listOf("expiration", DEADLINE.toString()))
            return ListingFixtures.decode(tags, kind, index = LISTING_AUTHOR_INDEX)
        }

        fun bid(on: Listing): Bid {
            val tags = BidFixtures.minimalBid(on.kind, LISTING_AUTHOR_INDEX, on.dValue)
            tags += listOf(listOf("fee", "250", TagFixtures.pubkeyFor(7)))
            tags += listOf(listOf("deliver_by", DELIVER_BY.toString()))
            tags += listOf(listOf("expiration", BID_DEADLINE.toString()))
            return BidFixtures.decode(tags, index = BIDDER_INDEX)
        }

        /** Decision H's envelope on [listing], and the one with the two parties swapped. */
        fun envelopes(listing: Listing): List<RumorEnvelope> {
            val (buyer, provider) =
                if (listing.kind == NenyaKind.REQUEST) LISTING_AUTHOR to BIDDER else BIDDER to LISTING_AUTHOR
            return listOf(
                RumorEnvelope(buyer, BID_DEADLINE - 1, listOf(PubkeyRef(provider))),
                RumorEnvelope(provider, BID_DEADLINE - 1, listOf(PubkeyRef(buyer))),
            )
        }

        fun kotlinClock(reading: Long?): NenyaClock = if (reading == null) NenyaClock.FAIL_CLOSED else FakeClock(reading)

        fun environment(reading: Long?): JsEnvironment =
            if (reading == null) JsEnvironment() else JsEnvironment(nowSeconds = { reading.toString() })
    }

    @JsName("buildProposalForBid_answers_what_ListingOrderWriter_proposalForBid_answers")
    @Test
    fun `buildProposalForBid answers what ListingOrderWriter proposalForBid answers`() {
        val drawn = mutableSetOf<String>()
        for (kind in listOf(NenyaKind.REQUEST, NenyaKind.OFFER)) {
            val listing = listing(kind)
            val bid = bid(listing)
            for (envelope in envelopes(listing)) {
                for (reading in READINGS) {
                    for (acceptBy in ACCEPT_BY) {
                        val what = "kind $kind, author ${envelope.authorPubkey.take(8)}, reading $reading, acceptBy $acceptBy"
                        val answer = buildProposalForBid(
                            JsListing(listing), JsBid(bid), environment(reading),
                            JsBoundaryFixtures.crossed(envelope), ORDER_HEX, JsBoundaryFixtures.decimalOrNull(acceptBy),
                        )
                        when (
                            val expected = ListingOrderWriter.proposalForBid(
                                listing, bid, kotlinClock(reading), envelope, RumorWriterFixtures.orderFor(0), acceptBy,
                            )
                        ) {
                            is ListingOrderBuild.Built -> {
                                JsBoundaryFixtures.assertSameAnswer(
                                    answer,
                                    JsBoundaryFixtures.Answer(true, expected.event, null, null, null, null, null),
                                    what,
                                )
                                drawn += "built"
                            }
                            is ListingOrderBuild.Refused -> {
                                JsBoundaryFixtures.assertSameAnswer(answer, JsBoundaryFixtures.answerOf(expected.refusal), what)
                                drawn += expected.refusal.reason.name
                            }
                            is ListingOrderBuild.DeadlineRefused -> {
                                assertFalse(answer.ok, what)
                                assertEquals("refused", answer.kind, what)
                                assertNull(answer.event, "$what: a deadline refusal carries no event")
                                assertEquals(expected.reason.name, answer.reason, what)
                                assertEquals("DeadlineRefusal", answer.reasonVocabulary, what)
                                assertNull(answer.tag, what)
                                assertNotNull(answer.detail, what)
                                drawn += expected.reason.name
                            }
                            ListingOrderBuild.WrongParties -> {
                                assertFalse(answer.ok, what)
                                assertEquals("refused", answer.kind, what)
                                assertNull(answer.event, "$what: a proposal between the wrong keys carries no event")
                                assertEquals("WRONG_PARTIES", answer.reason, what)
                                assertEquals("ListingOrderBuild", answer.reasonVocabulary, what)
                                assertNull(answer.tag, what)
                                assertNull(answer.tagReason, what)
                                assertNotNull(answer.detail, what)
                                drawn += "WRONG_PARTIES"
                            }
                            ListingOrderBuild.ForAnotherListing ->
                                fail("$what: `proposalForBid` reads its item off the listing and has no other to name")
                            is ListingOrderBuild.ProposalDeadlineRefused ->
                                fail("$what: `proposalForBid` writes a deadline for acceptance and answers none")
                        }
                    }
                }
            }
        }
        assertEquals(
            setOf(
                "built", "WRONG_PARTIES", "DEADLINES_INVERTED", "MALFORMED_TAG",
                DeadlineRefusal.LISTING_CANNOT_SAY.name, DeadlineRefusal.LISTING_EXPIRED.name, DeadlineRefusal.BID_EXPIRED.name,
            ),
            drawn,
            "every answer the grid is written to reach",
        )
    }

    @JsName("a_bid_on_another_listing_crosses_as_BID_FOR_ANOTHER_LISTING")
    @Test
    fun `a bid on another listing crosses as BID_FOR_ANOTHER_LISTING`() {
        val listing = listing(NenyaKind.REQUEST)
        val elsewhere = BidFixtures.decode(
            BidFixtures.minimalBid(NenyaKind.REQUEST, LISTING_AUTHOR_INDEX, "some-other-listing"),
            index = BIDDER_INDEX,
        )
        val answer = buildProposalForBid(
            JsListing(listing), JsBid(elsewhere), environment(BID_DEADLINE - 1),
            JsBoundaryFixtures.crossed(envelopes(listing).first()), ORDER_HEX,
        )
        assertEquals(DeadlineRefusal.BID_FOR_ANOTHER_LISTING.name, answer.reason)
        assertEquals("DeadlineRefusal", answer.reasonVocabulary)
        assertNull(answer.event)
        assertTrue(answer.detail!!.contains("bid"), "the bid side's detail, not the listing's: ${answer.detail}")
    }
}
