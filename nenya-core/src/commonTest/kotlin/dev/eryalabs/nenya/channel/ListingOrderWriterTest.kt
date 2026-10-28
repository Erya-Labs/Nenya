package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.bid.DeadlineGate
import dev.eryalabs.nenya.bid.DeadlineRefusal
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.tag.ItemRef
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T51 — §5.6's "MUST NOT … propose an order … against an expired listing", held over
 * [ListingOrderWriter.proposal].
 *
 * Three things are proved: a permitted build is byte for byte `RumorWriter.proposal` with `item` read
 * off the listing; every answer is the one `DeadlineGate.check` gives for the same listing and clock;
 * and a refusal builds nothing, whatever the envelope or the terms say.
 */
class ListingOrderWriterTest {

    private companion object {

        /** A deadline well clear of the fixtures' fixed `created_at`. */
        const val DEADLINE: Long = ListingFixtures.CREATED_AT + 10_000L

        val PROPOSALS: List<RumorWriterFixtures.Case> =
            RumorWriterFixtures.cases(160).filter { it.shape == RumorWriterFixtures.Shape.PROPOSAL }

        val EXPIRATIONS: List<Long?> = listOf(null, 0L, DEADLINE)

        /** Every clock the grid runs under: silent, broken, and either side of each deadline. */
        val CLOCKS: List<NenyaClock> = listOf(
            NenyaClock.FAIL_CLOSED, FakeClock(-1L), FakeClock(Long.MIN_VALUE), FakeClock(0L),
            FakeClock(DEADLINE - 1), FakeClock(DEADLINE), FakeClock(DEADLINE + 1), FakeClock(Long.MAX_VALUE),
        )

        /** A listing of [kind] carrying [expiration], or none. */
        fun listing(kind: Int, expiration: Long?): Listing {
            val tags = ListingFixtures.minimal(kind)
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            return ListingFixtures.decode(tags, kind, index = 1)
        }

        fun order(case: RumorWriterFixtures.Case): OrderId = case.order ?: fail("$case must carry an order id")

        fun terms(case: RumorWriterFixtures.Case): OrderTerms = case.terms ?: fail("$case must carry terms")

        fun build(listing: Listing, clock: NenyaClock, case: RumorWriterFixtures.Case): ListingOrderBuild =
            ListingOrderWriter.proposal(
                listing, clock, case.envelope, order(case), terms(case), case.feeRecipient, case.amountSat,
            )

        /** What `RumorWriter.proposal` builds for [case] with `item` set to [listing]'s coordinate. */
        fun direct(listing: Listing, case: RumorWriterFixtures.Case): RumorBuild = RumorWriter.proposal(
            case.envelope, order(case), ItemRef(listing.coordinate), terms(case), case.feeRecipient, case.amountSat,
        )

        fun built(answer: ListingOrderBuild, what: String): ListingOrderBuild.Built =
            answer as? ListingOrderBuild.Built ?: fail("$what: expected a build, got $answer")
    }

    /** A clock that answers [reading] and counts how often it was asked. */
    private class CountingClock(private val reading: Long) : NenyaClock {
        var reads: Int = 0
        override fun now(): SeamAnswer<Long> {
            reads++
            return SeamAnswer.Provided(reading)
        }
    }

    @JsName("the_proposal_corpus_is_the_one_this_test_is_about")
    @Test
    fun `the proposal corpus is the one this test is about`() {
        assertTrue(PROPOSALS.size >= 15, "${PROPOSALS.size} proposal case(s) is not a corpus")
        assertTrue(PROPOSALS.any { it.amountSat != null }, "§7.5's `amount` must be drawn")
        assertTrue(PROPOSALS.any { it.feeRecipient != null }, "§8.1's fee recipient must be drawn")
    }

    @JsName("a_permitted_build_is_RumorWriter_proposal_with_the_listing_s_coordinate")
    @Test
    fun `a permitted build is RumorWriter proposal with the listing's coordinate`() {
        var builds = 0
        for (kind in ListingFixtures.KINDS) {
            val listing = listing(kind, DEADLINE)
            for (case in PROPOSALS) {
                val what = "$case against kind $kind"
                val answer = build(listing, FakeClock(DEADLINE - 1), case)
                when (val expected = direct(listing, case)) {
                    is RumorBuild.Built -> {
                        RumorWriterFixtures.assertSameBytes(built(answer, what).event, expected.event, what)
                        builds++
                    }
                    is RumorBuild.Refused -> {
                        val refused = assertIs<ListingOrderBuild.Refused>(answer, what)
                        assertEquals(expected.reason, refused.refusal.reason, what)
                        assertEquals(expected.tag, refused.refusal.tag, what)
                        assertEquals(expected.detail, refused.refusal.detail, what)
                    }
                }
            }
        }
        assertTrue(builds > 0, "no case built, so the byte equality was never exercised")
    }

    @JsName("a_permitted_build_decodes_with_item_equal_to_the_listing_s_coordinate")
    @Test
    fun `a permitted build decodes with item equal to the listing's coordinate`() {
        for (kind in ListingFixtures.KINDS) {
            val listing = listing(kind, DEADLINE)
            for (case in PROPOSALS) {
                val what = "$case against kind $kind"
                val answer = build(listing, FakeClock(DEADLINE - 1), case)
                if (answer !is ListingOrderBuild.Built) continue
                val proposal = OrderProposal.decode(RumorWriterFixtures.bound(answer.event))
                assertEquals(listing.coordinate, proposal.item.coordinate, what)
                assertEquals(ItemRef.CANONICAL_QUANTITY, proposal.item.quantity, what)
                assertEquals(order(case), proposal.order, what)
            }
        }
    }

    @JsName("a_listing_without_expiration_builds_under_FAIL_CLOSED_and_never_reads_the_clock")
    @Test
    fun `a listing without expiration builds under FAIL_CLOSED and never reads the clock`() {
        val case = PROPOSALS.first { direct(listing(ListingFixtures.KINDS[0], null), it) is RumorBuild.Built }
        for (kind in ListingFixtures.KINDS) {
            val listing = listing(kind, null)
            built(build(listing, NenyaClock.FAIL_CLOSED, case), "kind $kind under FAIL_CLOSED")
            val counting = CountingClock(Long.MAX_VALUE)
            built(build(listing, counting, case), "kind $kind")
            assertEquals(0, counting.reads, "kind $kind: an open-ended listing must not consult the clock")
        }
    }

    @JsName("clock_at_expiration_refuses_as_LISTING_EXPIRED_and_builds_nothing")
    @Test
    fun `clock at expiration refuses as LISTING_EXPIRED and builds nothing`() {
        for (kind in ListingFixtures.KINDS) {
            val listing = listing(kind, DEADLINE)
            for (case in PROPOSALS) {
                for (reading in listOf(DEADLINE, DEADLINE + 1, Long.MAX_VALUE)) {
                    val answer = build(listing, FakeClock(reading), case)
                    assertEquals(
                        ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_EXPIRED),
                        answer,
                        "$case against kind $kind at $reading",
                    )
                }
            }
        }
    }

    @JsName("an_expiring_listing_under_FAIL_CLOSED_refuses_as_LISTING_CANNOT_SAY")
    @Test
    fun `an expiring listing under FAIL_CLOSED refuses as LISTING_CANNOT_SAY`() {
        for (kind in ListingFixtures.KINDS) {
            for (expiration in EXPIRATIONS.filterNotNull()) {
                val listing = listing(kind, expiration)
                for (clock in listOf(NenyaClock.FAIL_CLOSED, FakeClock(-1L), FakeClock(Long.MIN_VALUE))) {
                    for (case in PROPOSALS) {
                        assertEquals(
                            ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_CANNOT_SAY),
                            build(listing, clock, case),
                            "$case against kind $kind expiring $expiration",
                        )
                    }
                }
            }
        }
    }

    @JsName("every_answer_is_the_deadline_gate_s_and_then_the_writer_s")
    @Test
    fun `every answer is the deadline gate's and then the writer's`() {
        var refusals = 0
        var permits = 0
        for (kind in ListingFixtures.KINDS) {
            for (expiration in EXPIRATIONS) {
                val listing = listing(kind, expiration)
                for (clock in CLOCKS) {
                    for (case in PROPOSALS) {
                        val what = "$case, kind $kind, expiration $expiration, clock $clock"
                        val answer = build(listing, clock, case)
                        when (val gate = DeadlineGate.check(listing, null, clock)) {
                            is DeadlineGate.Refused -> {
                                assertEquals(ListingOrderBuild.DeadlineRefused(gate.reason), answer, what)
                                assertEquals(gate.reason.side.name, "LISTING", what)
                                refusals++
                            }
                            DeadlineGate.Permitted -> {
                                when (val expected = direct(listing, case)) {
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
        }
        assertTrue(refusals > 0 && permits > 0, "$refusals refusal(s) and $permits permit(s): both must be drawn")
    }

    @JsName("the_deadline_is_checked_before_the_message_and_never_against_created_at")
    @Test
    fun `the deadline is checked before the message and never against created_at`() {
        val case = PROPOSALS.first { direct(listing(ListingFixtures.KINDS[0], DEADLINE), it) is RumorBuild.Built }
        // An author key the writer refuses as MALFORMED_EVENT_FIELD: against a live listing that is the
        // answer, and against an expired one the deadline is, because it is checked first.
        val broken = RumorEnvelope("not hex", case.envelope.createdAt, case.envelope.counterparties)
        val live = listing(ListingFixtures.KINDS[0], DEADLINE)
        val proposal = { clock: NenyaClock ->
            ListingOrderWriter.proposal(live, clock, broken, order(case), terms(case), case.feeRecipient, case.amountSat)
        }
        val refused = assertIs<ListingOrderBuild.Refused>(proposal(FakeClock(DEADLINE - 1)))
        assertEquals(ChannelRejection.MALFORMED_EVENT_FIELD, refused.refusal.reason)
        assertEquals(ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_EXPIRED), proposal(FakeClock(DEADLINE)))

        // A `created_at` before the deadline decides nothing once the clock has passed it, and one after
        // it decides nothing while the clock has not.
        for (createdAt in listOf(0L, DEADLINE - 1, DEADLINE + 1_000_000L)) {
            val envelope = RumorEnvelope(case.envelope.authorPubkey, createdAt, case.envelope.counterparties)
            val at = { reading: Long ->
                ListingOrderWriter.proposal(live, FakeClock(reading), envelope, order(case), terms(case), case.feeRecipient, case.amountSat)
            }
            assertEquals(
                ListingOrderBuild.DeadlineRefused(DeadlineRefusal.LISTING_EXPIRED),
                at(DEADLINE),
                "created_at $createdAt",
            )
            assertIs<ListingOrderBuild.Built>(at(DEADLINE - 1), "created_at $createdAt")
        }
    }

    @JsName("the_clock_is_read_once_per_proposal_against_an_expiring_listing")
    @Test
    fun `the clock is read once per proposal against an expiring listing`() {
        val counting = CountingClock(DEADLINE - 1)
        build(listing(ListingFixtures.KINDS[0], DEADLINE), counting, PROPOSALS.first())
        assertEquals(1, counting.reads)
    }
}
