package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.bid.DeadlineRefusal
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.settlement.FeeTermAgreement
import dev.eryalabs.nenya.settlement.FeeTermSighting
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagFixtures
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T61 — §7.5's proposal built from the public bid the buyer chose, [ListingOrderWriter.proposalForBid].
 *
 * Three things are proved: on a request and on an offer the built proposal decodes with the bid's own
 * price, `fee`, fee recipient and `deliver_by`, and its `BID` and `ORDER_PROPOSAL` fee sightings agree;
 * decision **H**'s parties are the only ones it builds for; and every deadline — the listing's, the
 * bid's, and (T62) the bid's `deliver_by` and the `expiration` asked for, each against the clock —
 * refuses as a value and builds nothing.
 */
class ProposalForBidTest {

    private companion object {

        /** The listing's deadline, well clear of the fixtures' fixed `created_at`. */
        const val DEADLINE: Long = ListingFixtures.CREATED_AT + 10_000L

        /** The bid's own deadline, earlier than the listing's so the two can be told apart. */
        const val BID_DEADLINE: Long = DEADLINE - 2_000L

        /** The bid's `deliver_by`, after both. */
        const val DELIVER_BY: Long = DEADLINE + 50_000L

        /** A reading before every deadline above. */
        const val BEFORE: Long = BID_DEADLINE - 1

        const val LISTING_AUTHOR_INDEX: Int = 1
        const val BIDDER_INDEX: Int = 2
        const val STRANGER_INDEX: Int = 3

        val LISTING_AUTHOR: String = TagFixtures.pubkeyFor(LISTING_AUTHOR_INDEX)
        val BIDDER: String = TagFixtures.pubkeyFor(BIDDER_INDEX)
        val STRANGER: String = TagFixtures.pubkeyFor(STRANGER_INDEX)
        val FEE_RECIPIENT: String = TagFixtures.pubkeyFor(7)

        val ORDER: OrderId = RumorWriterFixtures.orderFor(0)

        /** A request and an offer, the two sides §6 lets a bid be made against. */
        val SIDES: List<Int> = listOf(NenyaKind.REQUEST, NenyaKind.OFFER)

        /** A listing of [kind] by [LISTING_AUTHOR], carrying [expiration] or none. */
        fun listing(kind: Int, expiration: Long? = DEADLINE): Listing {
            val tags = ListingFixtures.minimal(kind)
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            return ListingFixtures.decode(tags, kind, index = LISTING_AUTHOR_INDEX)
        }

        /** A bid by [BIDDER] on [on], carrying the optional terms given. */
        fun bid(
            on: Listing,
            fee: List<String>? = listOf("fee", "250", FEE_RECIPIENT),
            deliverBy: Long? = DELIVER_BY,
            expiration: Long? = BID_DEADLINE,
        ): Bid {
            val tags = BidFixtures.minimalBid(on.kind, LISTING_AUTHOR_INDEX, on.dValue)
            if (fee != null) tags += listOf(fee)
            if (deliverBy != null) tags += listOf(listOf("deliver_by", deliverBy.toString()))
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            return BidFixtures.decode(tags, index = BIDDER_INDEX)
        }

        /** Decision H: the buyer on a request is its author, on an offer the bidder. */
        fun buyerOn(listing: Listing): String = if (listing.kind == NenyaKind.REQUEST) LISTING_AUTHOR else BIDDER

        fun providerOn(listing: Listing): String = if (listing.kind == NenyaKind.REQUEST) BIDDER else LISTING_AUTHOR

        fun envelope(author: String, vararg counterparties: String): RumorEnvelope =
            RumorEnvelope(author, BEFORE, counterparties.map { PubkeyRef(it) })

        /** The envelope decision H permits on [listing]: the buyer, to the provider. */
        fun buyerEnvelope(listing: Listing): RumorEnvelope = envelope(buyerOn(listing), providerOn(listing))

        fun build(
            listing: Listing,
            bid: Bid,
            clock: NenyaClock = FakeClock(BEFORE),
            envelope: RumorEnvelope = buyerEnvelope(listing),
            acceptBy: Long? = BEFORE + 1_000L,
        ): ListingOrderBuild = ListingOrderWriter.proposalForBid(listing, bid, clock, envelope, ORDER, acceptBy)

        fun built(answer: ListingOrderBuild, what: String): ListingOrderBuild.Built =
            answer as? ListingOrderBuild.Built ?: fail("$what: expected a build, got $answer")

        fun decoded(answer: ListingOrderBuild, what: String): OrderProposal =
            OrderProposal.decode(RumorWriterFixtures.bound(built(answer, what).event))
    }

    /** A clock that answers [reading] and counts how often it was asked. */
    private class CountingClock(private val reading: Long) : NenyaClock {
        var reads: Int = 0
        override fun now(): SeamAnswer<Long> {
            reads++
            return SeamAnswer.Provided(reading)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The terms are the bid's
    // ---------------------------------------------------------------------------------------------

    @JsName("on_a_request_and_an_offer_the_proposal_decodes_with_the_bid_s_terms")
    @Test
    fun `on a request and an offer the proposal decodes with the bid's terms`() {
        for (kind in SIDES) {
            val listing = listing(kind)
            val bid = bid(listing)
            val acceptBy = BEFORE + 1_000L
            val proposal = decoded(build(listing, bid, acceptBy = acceptBy), "kind $kind")
            assertEquals(listing.coordinate, proposal.item.coordinate, "kind $kind: item is the listing")
            assertEquals(ORDER, proposal.order, "kind $kind")
            assertEquals(bid.price, proposal.price, "kind $kind: the bid's price")
            assertEquals(bid.fee, proposal.terms.split.term, "kind $kind: the bid's fee term")
            assertEquals(FEE_RECIPIENT, proposal.feeRecipient, "kind $kind: the bid's fee recipient")
            assertEquals(DELIVER_BY, proposal.terms.deliverBy, "kind $kind: the bid's deliver_by")
            assertEquals(acceptBy, proposal.terms.expiration, "kind $kind: acceptBy, never the bid's expiration")
            assertEquals(buyerOn(listing), proposal.buyer, "kind $kind: decision H's buyer sent it")
            assertEquals(listOf(providerOn(listing)), proposal.counterparties.map { it.pubkey }, "kind $kind")

            // §8.4: the `BID` point T60 reads off the bid and the `ORDER_PROPOSAL` point read off this
            // proposal are the same fee row, so the order the bid became cannot have diverged.
            val atBid = FeeTermSighting.onBid(bid)
            val atProposal = FeeTermSighting.onProposal(proposal)
            assertEquals(atBid.term, atProposal.term, "kind $kind: the two sightings' fee rows")
            val agreed = assertIs<FeeTermAgreement.Agreed>(FeeTermAgreement.across(listOf(atBid, atProposal)))
            assertEquals(FEE_RECIPIENT, agreed.recipient, "kind $kind")
        }
    }

    @JsName("the_corpus_of_generated_bids_each_proposes_its_own_terms")
    @Test
    fun `the corpus of generated bids each proposes its own terms`() {
        var builds = 0
        val kinds = mutableSetOf<Int>()
        for (fixture in BidFixtures.bids(150)) {
            val bid = BidFixtures.decode(fixture)
            // The listing the bid is scoped to: the same kind, author and `d`, open-ended, so the bid's
            // own deadline is the only one in play.
            val tags = ListingFixtures.minimal(bid.listingKind)
            tags[tags.indexOfFirst { it[0] == "d" }] = listOf("d", bid.listing.dValue)
            val authorIndex = (0..fixture.index + 1).first { TagFixtures.pubkeyFor(it) == bid.listing.pubkey }
            val listing = ListingFixtures.decode(tags, bid.listingKind, index = authorIndex)
            assertEquals(bid.listing, listing.coordinate, "bid ${fixture.index}: the paired listing")

            val bidder = bid.bidderPubkey.lowercase()
            val (buyer, provider) =
                if (bid.listingKind == NenyaKind.REQUEST) listing.coordinate.pubkey to bidder
                else bidder to listing.coordinate.pubkey
            val acceptBy = bid.deliverBy?.let { if (it == 0L) null else it - 1 }
            // T62: below the bid's own deadline and below `acceptBy` — and so below `deliver_by`, which
            // `acceptBy` is strictly before — since every one of them is now judged against this reading.
            val reading = (listOfNotNull(bid.expiration, acceptBy).minOrNull() ?: (BidFixtures.CREATED_AT + 1)) - 1
            val answer = ListingOrderWriter.proposalForBid(
                listing, bid, FakeClock(reading), RumorEnvelope(buyer, BEFORE, listOf(PubkeyRef(provider))), ORDER, acceptBy,
            )
            if (reading < 0L) {
                val expected =
                    if (bid.expiration != null) ListingOrderBuild.DeadlineRefused(DeadlineRefusal.BID_CANNOT_SAY)
                    else ListingOrderBuild.ProposalDeadlineRefused(ProposalDeadlineRefusal.PROPOSAL_CANNOT_SAY)
                assertEquals(expected, answer, "bid ${fixture.index}")
                continue
            }
            val proposal = decoded(answer, "bid ${fixture.index}")
            assertEquals(bid.price, proposal.price, "bid ${fixture.index}")
            assertEquals(bid.fee, proposal.terms.split.term, "bid ${fixture.index}")
            assertEquals(bid.feeRecipient, proposal.feeRecipient, "bid ${fixture.index}")
            assertEquals(bid.deliverBy, proposal.terms.deliverBy, "bid ${fixture.index}")
            assertEquals(acceptBy, proposal.terms.expiration, "bid ${fixture.index}")
            assertEquals(FeeTermSighting.onBid(bid).term, FeeTermSighting.onProposal(proposal).term, "bid ${fixture.index}")
            builds++
            kinds += bid.listingKind
        }
        assertTrue(builds >= 100, "only $builds of the corpus built, which is not a corpus")
        assertEquals(setOf(NenyaKind.REQUEST, NenyaKind.OFFER, NenyaKind.OFFER_DRAFT), kinds, "every side a bid scopes to")
    }

    @JsName("a_fee_less_bid_proposes_no_fee_and_the_bid_s_expiration_is_never_copied")
    @Test
    fun `a fee-less bid proposes no fee and the bid's expiration is never copied`() {
        val listing = listing(NenyaKind.REQUEST)
        val bid = bid(listing, fee = null, deliverBy = null)
        val proposal = decoded(build(listing, bid, acceptBy = null), "fee-less")
        assertEquals(bid.fee, proposal.terms.split.term)
        assertNull(proposal.feeRecipient)
        assertNull(proposal.terms.deliverBy, "the bid carried no deliver_by")
        assertNull(proposal.terms.expiration, "no acceptBy, and the bid's own BID_DEADLINE is not a proposal's")
        assertTrue(
            built(build(listing, bid, acceptBy = null), "fee-less").event.tags.none { it[0] == "fee" },
            "§8.1's absent term has no wire form",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Decision H's parties
    // ---------------------------------------------------------------------------------------------

    @JsName("only_the_buyer_sending_to_the_provider_builds_on_either_side")
    @Test
    fun `only the buyer sending to the provider builds on either side`() {
        for (kind in SIDES) {
            val listing = listing(kind)
            val bid = bid(listing)
            val buyer = buyerOn(listing)
            val provider = providerOn(listing)
            built(build(listing, bid, envelope = envelope(buyer, provider)), "kind $kind: the buyer to the provider")
            // §4.3's case rule for a key this client authors: an uppercase spelling is the same key.
            built(build(listing, bid, envelope = envelope(buyer.uppercase(), provider)), "kind $kind: uppercase buyer")

            val wrong = mapOf(
                "the provider as author" to envelope(provider, buyer),
                "the provider to itself" to envelope(provider, provider),
                "the buyer to itself" to envelope(buyer, buyer),
                "a stranger as author" to envelope(STRANGER, provider),
                "the buyer to a stranger" to envelope(buyer, STRANGER),
                "the buyer to nobody" to envelope(buyer),
                "the buyer to the provider and a stranger" to envelope(buyer, provider, STRANGER),
                "the buyer to the provider twice" to envelope(buyer, provider, provider),
            )
            for ((what, envelope) in wrong) {
                val counting = CountingClock(BEFORE)
                assertEquals(
                    ListingOrderBuild.WrongParties,
                    build(listing, bid, clock = counting, envelope = envelope),
                    "kind $kind: $what",
                )
                assertEquals(0, counting.reads, "kind $kind: $what is refused before the clock is read")
            }
        }
    }

    @JsName("a_bid_by_the_listing_s_own_author_has_no_two_parties")
    @Test
    fun `a bid by the listing's own author has no two parties`() {
        for (kind in SIDES) {
            val listing = listing(kind)
            val tags = BidFixtures.minimalBid(kind, LISTING_AUTHOR_INDEX, listing.dValue)
            val selfBid = BidFixtures.decode(tags, index = LISTING_AUTHOR_INDEX)
            assertEquals(
                ListingOrderBuild.WrongParties,
                build(listing, selfBid, envelope = envelope(LISTING_AUTHOR, LISTING_AUTHOR)),
                "kind $kind",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Deadlines
    // ---------------------------------------------------------------------------------------------

    @JsName("an_expired_bid_refuses_as_BID_EXPIRED_and_builds_nothing")
    @Test
    fun `an expired bid refuses as BID_EXPIRED and builds nothing`() {
        for (kind in SIDES) {
            val listing = listing(kind)
            val bid = bid(listing)
            built(build(listing, bid, clock = FakeClock(BID_DEADLINE - 1)), "kind $kind before the bid's deadline")
            for (reading in listOf(BID_DEADLINE, BID_DEADLINE + 1, DEADLINE - 1)) {
                assertEquals(
                    ListingOrderBuild.DeadlineRefused(DeadlineRefusal.BID_EXPIRED),
                    build(listing, bid, clock = FakeClock(reading)),
                    "kind $kind at $reading",
                )
            }
            // The listing's deadline is answered first, as §5.6's MUST is the one the document states.
            assertEquals(
                ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_EXPIRED),
                build(listing, bid, clock = FakeClock(DEADLINE)),
                "kind $kind at the listing's deadline",
            )
        }
    }

    @JsName("a_silent_clock_refuses_whichever_side_carries_a_deadline")
    @Test
    fun `a silent clock refuses whichever side carries a deadline`() {
        val expiring = listing(NenyaKind.REQUEST)
        val openEnded = listing(NenyaKind.REQUEST, expiration = null)
        for (clock in listOf(NenyaClock.FAIL_CLOSED, FakeClock(-1L), FakeClock(Long.MIN_VALUE))) {
            assertEquals(
                ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_CANNOT_SAY),
                build(expiring, bid(expiring), clock = clock),
            )
            assertEquals(
                ListingOrderBuild.DeadlineRefused(DeadlineRefusal.BID_CANNOT_SAY),
                build(openEnded, bid(openEnded), clock = clock),
            )
        }
        // Neither side carrying a deadline never consults the clock, so builds under FAIL_CLOSED — and,
        // since T62, nor may the proposal: no `deliver_by` on the bid and no `acceptBy`.
        val counting = CountingClock(Long.MAX_VALUE)
        val deadlineFree = bid(openEnded, deliverBy = null, expiration = null)
        built(build(openEnded, deadlineFree, clock = counting, acceptBy = null), "open-ended both sides")
        assertEquals(0, counting.reads)
        built(build(openEnded, deadlineFree, clock = NenyaClock.FAIL_CLOSED, acceptBy = null), "FAIL_CLOSED")
    }

    @JsName("a_bid_on_another_listing_refuses_before_the_parties_or_the_clock")
    @Test
    fun `a bid on another listing refuses before the parties or the clock`() {
        val listing = listing(NenyaKind.REQUEST)
        val elsewhere = BidFixtures.decode(
            BidFixtures.minimalBid(NenyaKind.REQUEST, LISTING_AUTHOR_INDEX, "some-other-listing"),
            index = BIDDER_INDEX,
        )
        // With the right parties and with wrong ones alike: the other listing is the answer, because the
        // parties read off this listing say nothing about a bid on some other one.
        for (envelope in listOf(buyerEnvelope(listing), envelope(STRANGER, BIDDER))) {
            val counting = CountingClock(BEFORE)
            assertEquals(
                ListingOrderBuild.DeadlineRefused(DeadlineRefusal.BID_FOR_ANOTHER_LISTING),
                build(listing, elsewhere, clock = counting, envelope = envelope),
            )
            assertEquals(0, counting.reads)
        }
    }

    @JsName("the_clock_is_read_once_for_both_deadlines")
    @Test
    fun `the clock is read once for both deadlines`() {
        val listing = listing(NenyaKind.OFFER)
        val counting = CountingClock(BEFORE)
        built(build(listing, bid(listing), clock = counting), "both expiring")
        assertEquals(1, counting.reads)
    }

    @JsName("acceptBy_at_deliver_by_refuses_as_DEADLINES_INVERTED_and_a_negative_one_as_MALFORMED_TAG")
    @Test
    fun `acceptBy at deliver_by refuses as DEADLINES_INVERTED and a negative one as MALFORMED_TAG`() {
        for (kind in SIDES) {
            val listing = listing(kind)
            val bid = bid(listing)
            built(build(listing, bid, acceptBy = DELIVER_BY - 1), "kind $kind: one second before deliver_by")
            for (acceptBy in listOf(DELIVER_BY, DELIVER_BY + 1, Long.MAX_VALUE)) {
                val refused = assertIs<ListingOrderBuild.Refused>(build(listing, bid, acceptBy = acceptBy), "kind $kind at $acceptBy")
                assertEquals(ChannelRejection.DEADLINES_INVERTED, refused.refusal.reason, "kind $kind at $acceptBy")
                assertEquals(ChannelVocabulary.EXPIRATION, refused.refusal.tag, "kind $kind at $acceptBy")
            }
            for (acceptBy in listOf(-1L, Long.MIN_VALUE)) {
                val refused = assertIs<ListingOrderBuild.Refused>(build(listing, bid, acceptBy = acceptBy), "kind $kind at $acceptBy")
                assertEquals(ChannelRejection.MALFORMED_TAG, refused.refusal.reason, "kind $kind at $acceptBy")
                assertEquals(ChannelVocabulary.EXPIRATION, refused.refusal.tag, "kind $kind at $acceptBy")
            }
            // A bid with no deliver_by puts no ceiling on acceptBy.
            val open = bid(listing, deliverBy = null)
            assertEquals(Long.MAX_VALUE, decoded(build(listing, open, acceptBy = Long.MAX_VALUE), "kind $kind").terms.expiration)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // T62: the deadlines the proposal would carry, §6's `deliver_by` and §7.5's `expiration`
    // ---------------------------------------------------------------------------------------------

    @JsName("it_builds_one_second_before_deliver_by_and_one_second_before_acceptBy")
    @Test
    fun `it builds one second before deliver_by and one second before acceptBy`() {
        val acceptBy = DELIVER_BY - 1_000L
        for (kind in SIDES) {
            // Open-ended listing and bid, so the bid's `deliver_by` and `acceptBy` are the only deadlines.
            val listing = listing(kind, expiration = null)
            val bid = bid(listing, expiration = null)
            val atDelivery = decoded(build(listing, bid, clock = FakeClock(DELIVER_BY - 1), acceptBy = null), "kind $kind")
            assertEquals(DELIVER_BY, atDelivery.terms.deliverBy, "kind $kind: the bid's deliver_by")
            assertNull(atDelivery.terms.expiration, "kind $kind")
            val atAccept = decoded(build(listing, bid, clock = FakeClock(acceptBy - 1), acceptBy = acceptBy), "kind $kind")
            assertEquals(DELIVER_BY, atAccept.terms.deliverBy, "kind $kind")
            assertEquals(acceptBy, atAccept.terms.expiration, "kind $kind: acceptBy")
        }
    }

    @JsName("a_reading_at_deliver_by_refuses_as_DELIVERY_PASSED_and_one_at_acceptBy_as_PROPOSAL_EXPIRED")
    @Test
    fun `a reading at deliver_by refuses as DELIVERY_PASSED and one at acceptBy as PROPOSAL_EXPIRED`() {
        val acceptBy = DELIVER_BY - 1_000L
        for (kind in SIDES) {
            val listing = listing(kind, expiration = null)
            val bid = bid(listing, expiration = null)
            for (reading in listOf(DELIVER_BY, DELIVER_BY + 1, Long.MAX_VALUE)) {
                // §6's `deliver_by` is answered first: with `acceptBy` strictly before it, a reading past
                // `deliver_by` is past both, and the bid's own deadline is the one the proposal cannot meet.
                for (accept in listOf(null, acceptBy)) {
                    assertEquals(
                        ListingOrderBuild.ProposalDeadlineRefused(ProposalDeadlineRefusal.DELIVERY_PASSED),
                        build(listing, bid, clock = FakeClock(reading), acceptBy = accept),
                        "kind $kind at $reading, acceptBy $accept",
                    )
                }
            }
            for (reading in listOf(acceptBy, acceptBy + 1, DELIVER_BY - 1)) {
                assertEquals(
                    ListingOrderBuild.ProposalDeadlineRefused(ProposalDeadlineRefusal.PROPOSAL_EXPIRED),
                    build(listing, bid, clock = FakeClock(reading), acceptBy = acceptBy),
                    "kind $kind at $reading",
                )
            }
            // `acceptBy` alone, on a bid with no `deliver_by`, is judged the same way.
            val open = bid(listing, deliverBy = null, expiration = null)
            assertEquals(
                ListingOrderBuild.ProposalDeadlineRefused(ProposalDeadlineRefusal.PROPOSAL_EXPIRED),
                build(listing, open, clock = FakeClock(acceptBy), acceptBy = acceptBy),
                "kind $kind: acceptBy with no deliver_by",
            )
        }
    }

    @JsName("a_silent_clock_refuses_a_deliver_by_or_acceptBy_as_PROPOSAL_CANNOT_SAY")
    @Test
    fun `a silent clock refuses a deliver_by or acceptBy as PROPOSAL_CANNOT_SAY`() {
        val listing = listing(NenyaKind.REQUEST, expiration = null)
        val withDelivery = bid(listing, expiration = null)
        val withoutDelivery = bid(listing, deliverBy = null, expiration = null)
        for (clock in listOf(NenyaClock.FAIL_CLOSED, FakeClock(-1L), FakeClock(Long.MIN_VALUE))) {
            assertEquals(
                ListingOrderBuild.ProposalDeadlineRefused(ProposalDeadlineRefusal.PROPOSAL_CANNOT_SAY),
                build(listing, withDelivery, clock = clock, acceptBy = null),
                "a bid with deliver_by and no expiration, under $clock",
            )
            assertEquals(
                ListingOrderBuild.ProposalDeadlineRefused(ProposalDeadlineRefusal.PROPOSAL_CANNOT_SAY),
                build(listing, withoutDelivery, clock = clock, acceptBy = DELIVER_BY),
                "acceptBy on a bid with no deliver_by, under $clock",
            )
        }
    }

    @JsName("every_combination_of_deadlines_reads_the_clock_at_most_once_and_none_never")
    @Test
    fun `every combination of deadlines reads the clock at most once and none never`() {
        for (kind in SIDES) {
            for (listingExpiration in listOf(null, DEADLINE)) {
                for (bidExpiration in listOf(null, BID_DEADLINE)) {
                    for (deliverBy in listOf(null, DELIVER_BY)) {
                        for (acceptBy in listOf(null, BEFORE + 1_000L)) {
                            for (reading in listOf(BEFORE, DELIVER_BY)) {
                                val listing = listing(kind, expiration = listingExpiration)
                                val bid = bid(listing, deliverBy = deliverBy, expiration = bidExpiration)
                                val counting = CountingClock(reading)
                                build(listing, bid, clock = counting, acceptBy = acceptBy)
                                val what = "kind $kind: $listingExpiration/$bidExpiration/$deliverBy/$acceptBy at $reading"
                                assertTrue(counting.reads <= 1, "$what read the clock ${counting.reads} times")
                                val anyDeadline = listOf(listingExpiration, bidExpiration, deliverBy, acceptBy).any { it != null }
                                assertEquals(if (anyDeadline) 1 else 0, counting.reads, what)
                            }
                        }
                    }
                }
            }
        }
    }

    @JsName("a_refusal_names_no_key_and_no_value")
    @Test
    fun `a refusal names no key and no value`() {
        val listing = listing(NenyaKind.REQUEST)
        val bid = bid(listing)
        val open = listing(NenyaKind.REQUEST, expiration = null)
        val late = bid(open, expiration = null)
        val answers = listOf(
            build(listing, bid, envelope = envelope(STRANGER, BIDDER)),
            build(listing, bid, clock = FakeClock(BID_DEADLINE)),
            build(listing, bid, acceptBy = DELIVER_BY),
            build(open, late, clock = FakeClock(DELIVER_BY), acceptBy = null),
            build(open, late, clock = FakeClock(DELIVER_BY - 1), acceptBy = DELIVER_BY - 1),
            build(open, late, clock = NenyaClock.FAIL_CLOSED, acceptBy = null),
        )
        for (answer in answers) {
            val text = answer.toString() + ((answer as? ListingOrderBuild.Refused)?.refusal?.detail ?: "")
            for (secret in listOf(LISTING_AUTHOR, BIDDER, STRANGER, FEE_RECIPIENT, DELIVER_BY.toString(), ORDER.toHex())) {
                assertTrue(secret !in text, "$answer must not carry a key, a deadline or the order id")
            }
        }
    }
}
