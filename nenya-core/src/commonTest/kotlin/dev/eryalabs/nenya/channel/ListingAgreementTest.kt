package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.bid.DeadlineGate
import dev.eryalabs.nenya.bid.DeadlineRefusal
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T52 — §5.6's "MUST NOT … advance an order against an expired listing", on the provider's side:
 * [ListingOrderWriter.agreement] builds §7.6's acceptance only for a proposal naming this listing, and
 * only while this listing's deadline permits. T57 — and only before the proposal's own `expiration`,
 * §7.5's deadline for acceptance.
 *
 * Every proposal here is one T51's [ListingOrderWriter.proposal] built, so the agreement is to a
 * proposal this library wrote — the case the task's proof names — and the provider is a key distinct
 * from the buyer, because `OrderProposal.accepts` refuses a buyer accepting its own order.
 */
class ListingAgreementTest {

    private companion object {

        /** A deadline well clear of the fixtures' fixed `created_at`. */
        const val DEADLINE: Long = ListingFixtures.CREATED_AT + 10_000L

        /** Offsets the provider's fixture key clear of every key `RumorWriterFixtures` draws for a case. */
        const val PROVIDER_OFFSET: Int = 10_000

        val PROPOSALS: List<RumorWriterFixtures.Case> =
            RumorWriterFixtures.cases(160).filter { it.shape == RumorWriterFixtures.Shape.PROPOSAL }

        val EXPIRATIONS: List<Long?> = listOf(null, 0L, DEADLINE)

        /** Every clock the grid runs under: silent, broken, and either side of each deadline. */
        val CLOCKS: List<NenyaClock> = listOf(
            NenyaClock.FAIL_CLOSED, FakeClock(-1L), FakeClock(Long.MIN_VALUE), FakeClock(0L),
            FakeClock(DEADLINE - 1), FakeClock(DEADLINE), FakeClock(DEADLINE + 1), FakeClock(Long.MAX_VALUE),
        )

        /** A listing of [kind] carrying [expiration], or none, authored by fixture key [index]. */
        fun listing(kind: Int, expiration: Long?, index: Int = 1, dValue: String? = null): Listing {
            var tags: List<List<String>> = ListingFixtures.minimal(kind)
            if (dValue != null) tags = ListingFixtures.with(ListingFixtures.without(tags, "d"), listOf("d", dValue))
            if (expiration != null) tags = ListingFixtures.with(tags, listOf("expiration", expiration.toString()))
            return ListingFixtures.decode(tags, kind, index = index)
        }

        /**
         * [case]'s proposal against [listing], built by T51's writer under a clock the listing permits,
         * and decoded as the provider receives it.
         */
        fun proposalAgainst(listing: Listing, case: RumorWriterFixtures.Case): OrderProposal? {
            val built = ListingOrderWriter.proposal(
                listing, FakeClock(DEADLINE - 1), case.envelope,
                case.order ?: fail("$case must carry an order id"),
                case.terms ?: fail("$case must carry terms"),
                case.feeRecipient, case.amountSat,
            )
            return (built as? ListingOrderBuild.Built)?.let { OrderProposal.decode(RumorWriterFixtures.bound(it.event)) }
        }

        fun provider(case: RumorWriterFixtures.Case): String = RumorWriterFixtures.pubkeyFor(case.index + PROVIDER_OFFSET)

        /** The provider's envelope: its own key, the buyer as `p`. */
        fun providerEnvelope(case: RumorWriterFixtures.Case, proposal: OrderProposal): RumorEnvelope =
            RumorEnvelope(provider(case), case.envelope.createdAt + 1, listOf(PubkeyRef(proposal.buyer)))

        fun agree(listing: Listing, clock: NenyaClock, case: RumorWriterFixtures.Case, proposal: OrderProposal) =
            ListingOrderWriter.agreement(listing, clock, providerEnvelope(case, proposal), proposal)

        /** What `RumorWriter.statusUpdate` builds for `accepted` with [proposal]'s own terms. */
        fun direct(case: RumorWriterFixtures.Case, proposal: OrderProposal): RumorBuild = RumorWriter.statusUpdate(
            providerEnvelope(case, proposal), proposal.order, OrderState.ACCEPTED, proposal.item, proposal.terms,
            proposal.feeRecipient,
        )

        /**
         * A reading before both deadlines: the proposal's own `expiration` when it carries one —
         * `RumorWriterFixtures.orderTerms` draws every one before [DEADLINE] — else the listing's.
         */
        fun before(proposal: OrderProposal): Long = proposal.terms.expiration?.minus(1) ?: (DEADLINE - 1)

        /**
         * §7.5's deadline for acceptance, judged here from [clock] and the decoded terms rather than
         * through the writer under test: `null` when it does not refuse.
         */
        fun proposalDeadline(proposal: OrderProposal, clock: NenyaClock): ProposalDeadlineRefusal? {
            val expiration = proposal.terms.expiration ?: return null
            return when (val reading = clock.now()) {
                is SeamAnswer.Unavailable -> ProposalDeadlineRefusal.PROPOSAL_CANNOT_SAY
                is SeamAnswer.Provided -> when {
                    reading.value < 0L -> ProposalDeadlineRefusal.PROPOSAL_CANNOT_SAY
                    reading.value >= expiration -> ProposalDeadlineRefusal.PROPOSAL_EXPIRED
                    else -> null
                }
            }
        }

        fun built(answer: ListingOrderBuild, what: String): ListingOrderBuild.Built =
            answer as? ListingOrderBuild.Built ?: fail("$what: expected a build, got $answer")

        /** Every (listing, proposal) pair this suite runs, the listing expiring at [expiration]. */
        fun pairs(expiration: Long?): List<Triple<Listing, RumorWriterFixtures.Case, OrderProposal>> = buildList {
            for (kind in ListingFixtures.KINDS) {
                val listing = listing(kind, expiration)
                for (case in PROPOSALS) proposalAgainst(listing, case)?.let { add(Triple(listing, case, it)) }
            }
        }
    }

    /** A clock that answers [reading] and counts how often it was asked. */
    private class CountingClock(private val reading: Long) : NenyaClock {
        var reads: Int = 0
        override fun now(): SeamAnswer<Long> {
            reads++
            return SeamAnswer.Provided(reading)
        }
    }

    @JsName("the_agreement_corpus_is_the_one_this_test_is_about")
    @Test
    fun `the agreement corpus is the one this test is about`() {
        val pairs = pairs(DEADLINE)
        assertTrue(pairs.size >= 30, "${pairs.size} built proposal(s) is not a corpus")
        assertTrue(pairs.any { it.third.feeRecipient != null }, "§8.1's fee recipient must be drawn")
        assertTrue(pairs.any { it.third.terms.deliverBy != null }, "§7.5's `deliver_by` must be drawn")
        assertTrue(pairs.all { it.third.buyer != provider(it.second) }, "the provider must not be the buyer")
        assertTrue(pairs.any { it.third.terms.expiration != null }, "§7.5's `expiration` must be drawn")
        assertTrue(pairs.any { it.third.terms.expiration == null }, "a proposal without one must be drawn too")
        assertTrue(
            pairs.all { val e = it.third.terms.expiration; e == null || e in (ListingFixtures.CREATED_AT + 1) until DEADLINE },
            "every proposal's own deadline must fall after `CREATED_AT` and before the listing's",
        )
    }

    @JsName("an_agreement_to_a_T51_proposal_decodes_and_accepts_answers_Accepted")
    @Test
    fun `an agreement to a T51 proposal decodes and accepts answers Accepted`() {
        var accepted = 0
        for ((listing, case, proposal) in pairs(DEADLINE)) {
            val what = "$case against kind ${listing.kind}"
            val event = built(agree(listing, FakeClock(before(proposal)), case, proposal), what).event
            val update = OrderStatusMessage.decode(RumorWriterFixtures.bound(event))
            assertEquals(OrderState.ACCEPTED, update.status, what)
            assertEquals(proposal.order, update.order, what)
            val answer = assertIs<Acceptance.Accepted>(proposal.accepts(update, provider(case)), what)
            assertEquals(proposal.order, answer.order, what)
            assertTrue(answer.terms.namesTheSameDealAs(proposal.terms), what)
            accepted++
        }
        assertTrue(accepted > 0, "no agreement was accepted, so §7.6 was never exercised")
    }

    @JsName("a_permitted_agreement_is_RumorWriter_statusUpdate_with_the_proposal_s_terms")
    @Test
    fun `a permitted agreement is RumorWriter statusUpdate with the proposal's terms`() {
        for ((listing, case, proposal) in pairs(DEADLINE)) {
            val what = "$case against kind ${listing.kind}"
            val answer = agree(listing, FakeClock(before(proposal)), case, proposal)
            when (val expected = direct(case, proposal)) {
                is RumorBuild.Built ->
                    RumorWriterFixtures.assertSameBytes(built(answer, what).event, expected.event, what)
                is RumorBuild.Refused -> {
                    val refused = assertIs<ListingOrderBuild.Refused>(answer, what)
                    assertEquals(expected.reason, refused.refusal.reason, what)
                    assertEquals(expected.tag, refused.refusal.tag, what)
                }
            }
        }
    }

    @JsName("clock_past_the_listing_s_expiration_refuses_as_LISTING_EXPIRED_and_builds_nothing")
    @Test
    fun `clock past the listing's expiration refuses as LISTING_EXPIRED and builds nothing`() {
        var seen = 0
        for ((listing, case, proposal) in pairs(DEADLINE)) {
            for (reading in listOf(DEADLINE, DEADLINE + 1, Long.MAX_VALUE)) {
                assertEquals(
                    ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_EXPIRED),
                    agree(listing, FakeClock(reading), case, proposal),
                    "$case against kind ${listing.kind} at $reading",
                )
                seen++
            }
        }
        assertTrue(seen > 0, "no expired agreement was attempted")
    }

    @JsName("an_expiring_listing_under_a_silent_or_negative_clock_refuses_as_LISTING_CANNOT_SAY")
    @Test
    fun `an expiring listing under a silent or negative clock refuses as LISTING_CANNOT_SAY`() {
        for ((listing, case, proposal) in pairs(DEADLINE)) {
            for (clock in listOf(NenyaClock.FAIL_CLOSED, FakeClock(-1L), FakeClock(Long.MIN_VALUE))) {
                assertEquals(
                    ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_CANNOT_SAY),
                    agree(listing, clock, case, proposal),
                    "$case against kind ${listing.kind} under $clock",
                )
            }
        }
    }

    @JsName("an_open_ended_listing_agrees_under_FAIL_CLOSED_and_never_reads_the_clock")
    @Test
    fun `an open-ended listing agrees under FAIL_CLOSED and never reads the clock`() {
        // T57: only a proposal without an `expiration` of its own leaves no deadline to judge.
        val pairs = pairs(null).filter { it.third.terms.expiration == null }
        assertTrue(pairs.isNotEmpty(), "no open-ended proposal was built against an open-ended listing")
        for ((listing, case, proposal) in pairs) {
            val what = "$case against kind ${listing.kind}"
            if (direct(case, proposal) !is RumorBuild.Built) continue
            built(agree(listing, NenyaClock.FAIL_CLOSED, case, proposal), "$what under FAIL_CLOSED")
            val counting = CountingClock(Long.MAX_VALUE)
            built(agree(listing, counting, case, proposal), what)
            assertEquals(0, counting.reads, "$what: no deadline on either side must not consult the clock")
        }
    }

    @JsName("a_proposal_for_another_listing_is_ForAnotherListing_before_the_clock_is_read")
    @Test
    fun `a proposal for another listing is ForAnotherListing before the clock is read`() {
        val kind = ListingFixtures.KINDS[1]
        val against = listing(kind, null)
        // Three other listings, each differing from [against] in exactly one of §4.2's three fields —
        // and each expired, so a writer that asked the deadline first would answer LISTING_EXPIRED.
        val others = listOf(
            "another author" to listing(kind, 0L, index = 2),
            "another d" to listing(kind, 0L, dValue = "another-listing"),
            "another kind" to listing(ListingFixtures.KINDS[2], 0L),
        )
        var seen = 0
        for ((name, other) in others) {
            assertNotEquals(against.coordinate, other.coordinate, name)
            for (case in PROPOSALS) {
                val proposal = proposalAgainst(against, case) ?: continue
                for (reading in listOf(DEADLINE - 1, Long.MAX_VALUE)) {
                    val counting = CountingClock(reading)
                    assertEquals(
                        ListingOrderBuild.ForAnotherListing,
                        ListingOrderWriter.agreement(other, counting, providerEnvelope(case, proposal), proposal),
                        "$case agreed against $name at $reading",
                    )
                    assertEquals(0, counting.reads, "$case against $name: the item is compared before the clock")
                    seen++
                }
            }
        }
        assertTrue(seen > 0, "no proposal for another listing was refused")
    }

    @JsName("every_agreement_is_the_item_check_s_then_the_deadline_gate_s_then_the_writer_s")
    @Test
    fun `every agreement is the item check's, then the deadline gate's, then the writer's`() {
        // T57: between the gate and the writer, the proposal's own deadline, as [proposalDeadline] judges it.
        var refusals = 0
        var proposalRefusals = 0
        var permits = 0
        for (expiration in EXPIRATIONS) {
            for ((listing, case, proposal) in pairs(expiration)) {
                for (clock in CLOCKS) {
                    val what = "$case, kind ${listing.kind}, expiration $expiration, clock $clock"
                    val answer = agree(listing, clock, case, proposal)
                    when (val gate = DeadlineGate.check(listing, null, clock)) {
                        is DeadlineGate.Refused -> {
                            assertEquals(ListingOrderBuild.DeadlineRefused(gate.reason), answer, what)
                            refusals++
                        }
                        DeadlineGate.Permitted -> {
                            val refusal = proposalDeadline(proposal, clock)
                            if (refusal != null) {
                                assertEquals(ListingOrderBuild.ProposalDeadlineRefused(refusal), answer, what)
                                proposalRefusals++
                                continue
                            }
                            when (val expected = direct(case, proposal)) {
                                is RumorBuild.Built ->
                                    RumorWriterFixtures.assertSameBytes(built(answer, what).event, expected.event, what)
                                is RumorBuild.Refused -> assertEquals(
                                    expected.reason,
                                    assertIs<ListingOrderBuild.Refused>(answer, what).refusal.reason,
                                    what,
                                )
                            }
                            permits++
                        }
                    }
                }
            }
        }
        assertTrue(
            refusals > 0 && proposalRefusals > 0 && permits > 0,
            "$refusals listing refusal(s), $proposalRefusals proposal refusal(s) and $permits permit(s): all must be drawn",
        )
    }

    /** Every pair whose proposal carries its own `expiration`, against a listing expiring at [expiration]. */
    private fun expiringProposals(expiration: Long?) = pairs(expiration).filter { it.third.terms.expiration != null }

    @JsName("one_second_before_the_proposal_s_expiration_builds_and_accepts_answers_Accepted")
    @Test
    fun `one second before the proposal's expiration builds and accepts answers Accepted`() {
        var seen = 0
        for (listingExpiration in listOf(null, DEADLINE)) {
            for ((listing, case, proposal) in expiringProposals(listingExpiration)) {
                if (direct(case, proposal) !is RumorBuild.Built) continue
                val what = "$case, kind ${listing.kind}, listing expiration $listingExpiration"
                val at = proposal.terms.expiration!! - 1
                val event = built(agree(listing, FakeClock(at), case, proposal), what).event
                val update = OrderStatusMessage.decode(RumorWriterFixtures.bound(event))
                assertIs<Acceptance.Accepted>(proposal.accepts(update, provider(case)), what)
                seen++
            }
        }
        assertTrue(seen > 0, "no agreement just before a proposal's deadline was built")
    }

    @JsName("clock_at_the_proposal_s_expiration_with_the_listing_live_refuses_as_PROPOSAL_EXPIRED")
    @Test
    fun `clock at the proposal's expiration, with the listing live, refuses as PROPOSAL_EXPIRED`() {
        var seen = 0
        for (listingExpiration in listOf(null, DEADLINE)) {
            for ((listing, case, proposal) in expiringProposals(listingExpiration)) {
                val expiration = proposal.terms.expiration!!
                for (reading in listOf(expiration, expiration + 1, DEADLINE - 1)) {
                    val what = "$case, kind ${listing.kind}, listing expiration $listingExpiration, at $reading"
                    assertEquals(DeadlineGate.Permitted, DeadlineGate.check(listing, null, FakeClock(reading)), what)
                    assertEquals(
                        ListingOrderBuild.ProposalDeadlineRefused(ProposalDeadlineRefusal.PROPOSAL_EXPIRED),
                        agree(listing, FakeClock(reading), case, proposal),
                        what,
                    )
                    seen++
                }
            }
        }
        assertTrue(seen > 0, "no agreement past a proposal's deadline was attempted")
    }

    @JsName("an_expiring_proposal_under_a_silent_or_negative_clock_refuses_as_PROPOSAL_CANNOT_SAY")
    @Test
    fun `an expiring proposal under a silent or negative clock refuses as PROPOSAL_CANNOT_SAY`() {
        val pairs = expiringProposals(null)
        assertTrue(pairs.isNotEmpty(), "no expiring proposal was built against an open-ended listing")
        for ((listing, case, proposal) in pairs) {
            for (clock in listOf(NenyaClock.FAIL_CLOSED, FakeClock(-1L), FakeClock(Long.MIN_VALUE))) {
                assertEquals(
                    ListingOrderBuild.ProposalDeadlineRefused(ProposalDeadlineRefusal.PROPOSAL_CANNOT_SAY),
                    agree(listing, clock, case, proposal),
                    "$case against kind ${listing.kind} under $clock",
                )
            }
        }
    }

    @JsName("the_listing_s_deadline_is_answered_before_the_proposal_s")
    @Test
    fun `the listing's deadline is answered before the proposal's`() {
        val pairs = expiringProposals(DEADLINE)
        assertTrue(pairs.isNotEmpty(), "no expiring proposal was built against an expiring listing")
        for ((listing, case, proposal) in pairs) {
            // Both deadlines passed, and both unjudgeable: the listing is named each time.
            assertEquals(
                ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_EXPIRED),
                agree(listing, FakeClock(DEADLINE), case, proposal),
                "$case",
            )
            assertEquals(
                ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_CANNOT_SAY),
                agree(listing, NenyaClock.FAIL_CLOSED, case, proposal),
                "$case",
            )
        }
    }

    @JsName("both_deadlines_are_judged_against_at_most_one_clock_reading")
    @Test
    fun `both deadlines are judged against at most one clock reading`() {
        var both = 0
        for (listingExpiration in EXPIRATIONS) {
            for ((listing, case, proposal) in pairs(listingExpiration)) {
                val deadlines = listOfNotNull(listingExpiration, proposal.terms.expiration).size
                if (deadlines == 2) both++
                for (reading in listOf(0L, before(proposal), DEADLINE - 1, DEADLINE, Long.MAX_VALUE)) {
                    val counting = CountingClock(reading)
                    agree(listing, counting, case, proposal)
                    val what = "$case, kind ${listing.kind}, listing expiration $listingExpiration, at $reading"
                    assertTrue(counting.reads <= 1, "$what: the clock was read ${counting.reads} times")
                    if (deadlines == 0) assertEquals(0, counting.reads, "$what: no deadline, no read")
                    else assertEquals(1, counting.reads, "$what: a deadline to judge is one read")
                }
            }
        }
        assertTrue(both > 0, "no pair carried both deadlines, so a second read was never possible")
    }

    @JsName("the_deadline_is_never_judged_against_the_agreement_s_created_at")
    @Test
    fun `the deadline is never judged against the agreement's created_at`() {
        val (listing, case, proposal) = pairs(DEADLINE).first { direct(it.second, it.third) is RumorBuild.Built }
        for (createdAt in listOf(0L, DEADLINE - 1, DEADLINE + 1_000_000L)) {
            val envelope = RumorEnvelope(provider(case), createdAt, listOf(PubkeyRef(proposal.buyer)))
            val at = { reading: Long -> ListingOrderWriter.agreement(listing, FakeClock(reading), envelope, proposal) }
            assertEquals(
                ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_EXPIRED),
                at(DEADLINE),
                "created_at $createdAt",
            )
            assertIs<ListingOrderBuild.Built>(at(before(proposal)), "created_at $createdAt")
        }
    }

    /**
     * The closed direction the writer's KDoc names: a stranger's proposal spelling its coordinate's
     * pubkey in uppercase decodes — §4.3 accepts uppercase on read — so the agreement is built, with
     * the coordinate in this library's lowercase. §7.6 compares bytes, so `accepts` answers a
     * counter-proposal naming `item`, never `Accepted`.
     */
    @JsName("a_proposal_spelled_another_way_is_answered_as_a_counter_proposal_never_accepted")
    @Test
    fun `a proposal spelled another way is answered as a counter-proposal, never accepted`() {
        val (listing, case, proposal) = pairs(null).first { direct(it.second, it.third) is RumorBuild.Built }
        val lowercase = listing.coordinate.toTagValue()
        val uppercase = "${listing.coordinate.kind}:${listing.coordinate.pubkey.uppercase()}:${listing.coordinate.dValue}"
        assertNotEquals(lowercase, uppercase, "the fixture pubkey must carry a hex letter")

        val original = proposal.encode()
        val respelled = WireEvent(
            original.pubkey,
            original.createdAt,
            original.kind,
            original.tags.map { if (it[0] == ChannelVocabulary.ITEM) listOf(it[0], uppercase) + it.drop(2) else it },
            original.content,
        )
        val stranger = OrderProposal.decode(RumorWriterFixtures.bound(respelled))
        assertEquals(listing.coordinate, stranger.item.coordinate, "uppercase decodes to the same coordinate")

        val event = built(agree(listing, FakeClock(before(stranger)), case, stranger), "the respelled proposal").event
        assertEquals(lowercase, event.tags.single { it[0] == ChannelVocabulary.ITEM }[1])
        val answer = stranger.accepts(OrderStatusMessage.decode(RumorWriterFixtures.bound(event)), provider(case))
        assertEquals(listOf(ChannelVocabulary.ITEM), assertIs<Acceptance.CounterProposal>(answer).divergentTerms)
    }
}
