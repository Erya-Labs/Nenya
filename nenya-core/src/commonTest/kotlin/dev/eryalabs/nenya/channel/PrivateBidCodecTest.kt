package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.listing.ListingActivity
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T59: §6.1's `kind:16` `type=6` private bid read back. `RumorWriter.privateBid` writes one and
 * [PrivateBid.decode] reads it, so every bid the library writes is a bid it can read.
 *
 * Every event here comes from [RumorWriterFixtures]' seeded corpus or is one of its events with a tag
 * removed or replaced; no encoded value is typed.
 */
class PrivateBidCodecTest {

    private companion object {

        /** Enough of the corpus that every `PRIVATE_BID` fee shape and deadline shape is reached. */
        const val CORPUS: Int = 8 * 24
    }

    /** The seeded corpus's private-bid cases. */
    private fun bidCases(): List<RumorWriterFixtures.Case> =
        RumorWriterFixtures.cases(CORPUS).filter { it.shape == RumorWriterFixtures.Shape.PRIVATE_BID }

    @Test
    @JsName("every_written_private_bid_decodes_to_the_values_it_was_written_from")
    fun `every written private bid decodes to the values it was written from`() {
        var feeAbsent = 0
        var withExpiration = 0
        var withDeliverBy = 0
        for (case in bidCases()) {
            val event = RumorWriterFixtures.build(case)
            val bid = PrivateBid.decode(RumorWriterFixtures.bound(event))
            val terms = assertNotNull(case.terms, "$case carries terms")
            val item = assertNotNull(case.item, "$case carries an item")

            assertEquals(case.envelope.authorPubkey, bid.bidder, "$case: §7.2's author is the bidder")
            assertEquals(item.coordinate.toTagValue(), bid.item.coordinate.toTagValue(), "$case: item")
            assertEquals(item.quantity, bid.item.quantity, "$case: item quantity")
            assertEquals(terms.split.price, bid.price, "$case: price")
            assertEquals(terms.split.price, bid.split.price, "$case: the split's price")
            assertEquals(terms.split.term, bid.fee, "$case: §8.1's fee term")
            assertEquals(terms.split.fee, bid.split.fee, "$case: §8.3's fee")
            assertEquals(terms.split.total, bid.split.total, "$case: §8.3's total")
            assertEquals(terms.expiration, bid.expiration, "$case: expiration")
            assertEquals(terms.deliverBy, bid.deliverBy, "$case: deliver_by")
            assertEquals(case.feeRecipient, bid.feeRecipient, "$case: §8.1's fee recipient")
            assertEquals(
                case.envelope.counterparties.map { it.pubkey to it.relay },
                bid.counterparties.map { it.pubkey to it.relay },
                "$case: §7.4's `p`",
            )
            assertEquals(case.envelope.createdAt, bid.createdAt, "$case: created_at")
            RumorWriterFixtures.assertSameBytes(event, bid.encode(), case)

            if (terms.split.term == FeeTerm.Absent) feeAbsent++
            if (terms.expiration != null) withExpiration++
            if (terms.deliverBy != null) withDeliverBy++
        }
        assertTrue(feeAbsent > 0, "the corpus must reach a bid with no `fee` tag (§8.1)")
        assertTrue(withExpiration > 0, "the corpus must reach a bid with an `expiration`")
        assertTrue(withDeliverBy > 0, "the corpus must reach a bid with a `deliver_by`")
    }

    @Test
    @JsName("a_type_1_proposal_is_refused_as_not_a_private_bid")
    fun `a type 1 proposal is refused as not a private bid`() {
        val fixture = ProposalFixtures.pairs(1).single()
        val proposal = ProposalFixtures.bound(fixture.proposalTags, fixture.index)
        // The rumor is a good proposal, so the refusal below is about the type and nothing else.
        OrderProposal.decode(proposal)

        val refused = assertFailsWith<ChannelException> { PrivateBid.decode(proposal) }
        assertEquals(ChannelRejection.NOT_A_PRIVATE_BID, refused.reason)
        assertEquals(ChannelVocabulary.TYPE, refused.tag)
    }

    @Test
    @JsName("a_type_6_without_price_is_refused_naming_price")
    fun `a type 6 without price is refused naming price`() {
        val event = RumorWriterFixtures.build(bidCases().first())
        val withoutPrice = event.tags.filter { it[0] != ChannelVocabulary.PRICE }
        assertTrue(withoutPrice.size < event.tags.size, "the built bid must carry a `price` to remove")
        // Through the proposal fixtures' own door, so the rumor reaching the decoder is a well-formed
        // §7.4 type=6 that is missing §6.1's `price` and nothing else.
        val rumor = ProposalFixtures.bound(withoutPrice)
        assertEquals(OrderMessageKind.PRIVATE_BID, rumor.type)

        val refused = assertFailsWith<ChannelException> { PrivateBid.decode(rumor) }
        assertEquals(ChannelRejection.MISSING_REQUIRED_TAG, refused.reason)
        assertEquals(ChannelVocabulary.PRICE, refused.tag)
    }

    /**
     * §6's worked example bids with an `expiration` later than its `deliver_by` — a bid open for days
     * that promises delivery within hours of the order — and §6.1 applies §6's term rules unchanged.
     * §7.5's strictly-earlier rule is about a proposal's deadline for acceptance, not a bid's own
     * deadline, so the private bid decodes and its `expiration` still decides [PrivateBid.activity].
     */
    @Test
    @JsName("a_bid_whose_expiration_is_at_or_after_its_deliver_by_decodes_as_section_6_s_example_does")
    fun `a bid whose expiration is at or after its deliver_by decodes, as §6's example does`() {
        val case = bidCases().first { it.terms?.deliverBy != null }
        val event = RumorWriterFixtures.build(case)
        val deliverBy = assertNotNull(case.terms?.deliverBy)
        for (expiration in listOf(deliverBy, deliverBy + 1, deliverBy + 2 * 24 * 60 * 60)) {
            val tags = event.tags.filter { it[0] != ChannelVocabulary.EXPIRATION } +
                listOf(listOf(ChannelVocabulary.EXPIRATION, expiration.toString()))
            val bid = PrivateBid.decode(ProposalFixtures.bound(tags))
            assertEquals(expiration, bid.expiration)
            assertEquals(deliverBy, bid.deliverBy)
            assertEquals(ListingActivity.ACTIVE, bid.activity(FakeClock(expiration - 1)))
            assertEquals(ListingActivity.EXPIRED, bid.activity(FakeClock(expiration)))
        }
    }

    @Test
    @JsName("a_private_bid_s_activity_is_its_own_expiration_against_the_injected_clock")
    fun `a private bid's activity is its own expiration against the injected clock`() {
        val bid = expiring()
        val expiration = assertNotNull(bid.expiration)

        assertEquals(ListingActivity.ACTIVE, bid.activity(FakeClock(expiration - 1)))
        assertEquals(ListingActivity.EXPIRED, bid.activity(FakeClock(expiration)))
        assertEquals(ListingActivity.EXPIRED, bid.activity(FakeClock(expiration + 1)))
        assertEquals(ListingActivity.CANNOT_SAY, bid.activity(NenyaClock.FAIL_CLOSED))
        assertEquals(ListingActivity.CANNOT_SAY, bid.activity(), "the default clock is FAIL_CLOSED")
        assertEquals(ListingActivity.CANNOT_SAY, bid.activity(FakeClock(-1)))
    }

    @Test
    @JsName("an_open_ended_private_bid_is_active_without_reading_the_clock")
    fun `an open-ended private bid is active without reading the clock`() {
        val bid = bidCases()
            .map { PrivateBid.decode(RumorWriterFixtures.bound(RumorWriterFixtures.build(it))) }
            .first { it.expiration == null }
        var reads = 0
        val counting = object : NenyaClock {
            override fun now(): SeamAnswer<Long> {
                reads++
                return NenyaClock.FAIL_CLOSED.now()
            }
        }
        assertEquals(ListingActivity.ACTIVE, bid.activity(counting))
        assertEquals(0, reads, "no deadline, so nothing to read the clock for")
    }

    @Test
    @JsName("a_private_bid_names_nothing_when_printed")
    fun `a private bid names nothing when printed`() {
        val bid = expiring()
        val printed = bid.toString()
        assertEquals("PrivateBid(redacted)", printed)
        for (secret in listOf(bid.bidder, bid.item.coordinate.toTagValue(), bid.price.millisatoshis.toString())) {
            assertTrue(secret !in printed, "§12: a printed bid must not carry `$secret`")
        }
    }

    /** A corpus bid carrying an `expiration`, decoded. */
    private fun expiring(): PrivateBid {
        val event: WireEvent = RumorWriterFixtures.build(bidCases().first { it.terms?.expiration != null })
        return PrivateBid.decode(RumorWriterFixtures.bound(event))
    }
}
