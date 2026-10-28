package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingActivity
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §4.6's bid deadline, and §5.6's refusal to act against an expired listing with it.
 *
 * `Bid.activity` is held to the rules `ListingActivityTest` holds `Listing.activity` to, one for one;
 * [DeadlineGate.check] is held to "permitted only when both are ACTIVE, naming which side refused,
 * never on CANNOT_SAY", with the listing's refusal taking precedence because §5.6's MUST is the one
 * the document states.
 */
class BidDeadlineTest {

    private companion object {

        /** A deadline well clear of the fixtures' fixed `created_at`. */
        const val DEADLINE: Long = BidFixtures.CREATED_AT + 10_000L

        /** A second deadline, later than [DEADLINE], so the two sides can disagree. */
        const val LATER: Long = DEADLINE + 5_000L

        fun clockAt(unixSeconds: Long): NenyaClock = FakeClock(unixSeconds)

        /** [BidFixtures.minimalBid]'s bid carrying [expiration], published at [createdAt]. */
        fun bid(expiration: Long?, createdAt: Long = BidFixtures.CREATED_AT): Bid {
            val tags = BidFixtures.minimalBid().toMutableList()
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            val event = WireEvent(TagFixtures.pubkeyFor(0), createdAt, NenyaKind.PUBLIC_BID, tags, "")
            return Bid.decode(CheckedEvent.checkEventId(EventId.of(event).toHex(), event))
        }

        /** The §5.2 request [bid] is scoped to — same kind, same author, same `d` — carrying [expiration]. */
        fun listing(expiration: Long?, dValue: String? = null): Listing {
            val tags = ListingFixtures.minimalRequest()
            if (dValue != null) tags[tags.indexOfFirst { it[0] == "d" }] = listOf("d", dValue)
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            return Listing.decode(
                ListingFixtures.checkedAt(tags, NenyaKind.REQUEST, ListingFixtures.CREATED_AT, index = 1),
            )
        }

        /** Every clock the controls below are run under: before, at and after each deadline, and silent. */
        val CLOCKS: List<NenyaClock> = listOf(
            NenyaClock.FAIL_CLOSED, clockAt(-1L), clockAt(Long.MIN_VALUE), clockAt(0L),
            clockAt(DEADLINE - 1), clockAt(DEADLINE), clockAt(LATER - 1), clockAt(LATER), clockAt(Long.MAX_VALUE),
        )

        val EXPIRATIONS: List<Long?> = listOf(null, 0L, DEADLINE, LATER)
    }

    /** A clock that answers [readings] in turn and counts how often it was asked. */
    private class SteppingClock(private vararg val readings: Long) : NenyaClock {
        var reads: Int = 0
        override fun now(): SeamAnswer<Long> = SeamAnswer.Provided(readings[minOf(reads++, readings.size - 1)])
    }

    // ---------------------------------------------------------------------------------------------
    // Bid.activity — §4.6's "`expiration` … on a bid"
    // ---------------------------------------------------------------------------------------------

    @JsName("the_fixture_bid_is_scoped_to_the_fixture_listing")
    @Test
    fun `the fixture bid is scoped to the fixture listing`() {
        // Every gate control below rests on this; a fixture drift would make them refuse for the
        // wrong reason and pass.
        assertEquals(listing(null).coordinate, bid(null).listing)
        assertEquals(DeadlineGate.Permitted, DeadlineGate.check(listing(null), bid(null), clockAt(DEADLINE)))
    }

    @JsName("a_bid_is_expired_after_its_deadline_and_active_before_it")
    @Test
    fun `a bid is expired after its deadline and active before it`() {
        val bid = bid(DEADLINE)

        assertEquals(ListingActivity.EXPIRED, bid.activity(clockAt(DEADLINE + 1)))
        assertEquals(ListingActivity.EXPIRED, bid.activity(clockAt(DEADLINE)), "the named second is expired")
        assertEquals(ListingActivity.ACTIVE, bid.activity(clockAt(DEADLINE - 1)))
    }

    @JsName("a_bid_under_a_silent_clock_cannot_say_and_never_active")
    @Test
    fun `a bid under a silent clock cannot say, and never active`() {
        val bid = bid(DEADLINE)

        assertEquals(ListingActivity.CANNOT_SAY, bid.activity(NenyaClock.FAIL_CLOSED))
        assertEquals(ListingActivity.CANNOT_SAY, bid.activity(), "the default is the fail-closed seam")
        for (reading in listOf(-1L, Long.MIN_VALUE)) {
            assertEquals(ListingActivity.CANNOT_SAY, bid.activity(clockAt(reading)), "reading $reading")
        }
    }

    @JsName("fail_closed_never_answers_active_or_expired_for_a_bid_with_an_expiration")
    @Test
    fun `fail closed never answers active or expired for a bid with an expiration`() {
        for (expiration in EXPIRATIONS.filterNotNull()) {
            val answer = bid(expiration).activity(NenyaClock.FAIL_CLOSED)
            assertNotEquals(ListingActivity.ACTIVE, answer, "expiration $expiration")
            assertNotEquals(ListingActivity.EXPIRED, answer, "expiration $expiration")
        }
    }

    @JsName("an_open_ended_bid_is_active_without_the_clock_being_consulted")
    @Test
    fun `an open-ended bid is active without the clock being consulted`() {
        val clock = SteppingClock(Long.MAX_VALUE)

        assertEquals(ListingActivity.ACTIVE, bid(null).activity(clock))
        assertEquals(ListingActivity.ACTIVE, bid(null).activity(NenyaClock.FAIL_CLOSED))
        assertEquals(0, clock.reads, "§6's open-ended bid has no deadline for a clock to evaluate")
    }

    /** §4.6: built so that reading the bid's own `created_at` gets the opposite answer both ways. */
    @JsName("a_bidder_s_created_at_decides_nothing")
    @Test
    fun `a bidder's created_at decides nothing`() {
        assertEquals(ListingActivity.ACTIVE, bid(DEADLINE, createdAt = DEADLINE + 5_000L).activity(clockAt(DEADLINE - 5_000L)))
        assertEquals(ListingActivity.EXPIRED, bid(DEADLINE, createdAt = DEADLINE - 9_000L).activity(clockAt(DEADLINE + 5_000L)))
    }

    @JsName("bid_activity_agrees_with_listing_activity_on_the_same_deadline_under_every_clock")
    @Test
    fun `bid activity agrees with listing activity on the same deadline under every clock`() {
        for (expiration in EXPIRATIONS) {
            for (clock in CLOCKS) {
                assertEquals(
                    listing(expiration).activity(clock),
                    bid(expiration).activity(clock),
                    "expiration $expiration, clock $clock",
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DeadlineGate.check — §5.6's listing arm and §4.6's bid arm
    // ---------------------------------------------------------------------------------------------

    @JsName("permitted_when_both_deadlines_are_ahead_of_the_clock")
    @Test
    fun `permitted when both deadlines are ahead of the clock`() {
        assertEquals(DeadlineGate.Permitted, DeadlineGate.check(listing(LATER), bid(DEADLINE), clockAt(DEADLINE - 1)))
        assertEquals(DeadlineGate.Permitted, DeadlineGate.check(listing(LATER), null, clockAt(LATER - 1)))
    }

    @JsName("an_expired_listing_with_an_unexpired_bid_is_refused_naming_the_listing")
    @Test
    fun `an expired listing with an unexpired bid is refused naming the listing`() {
        val answer = DeadlineGate.check(listing(DEADLINE), bid(LATER), clockAt(DEADLINE))

        assertIs<DeadlineGate.Refused>(answer)
        assertEquals(DeadlineRefusal.LISTING_EXPIRED, answer.reason)
        assertEquals(DeadlineSide.LISTING, answer.side, "§5.6: MUST NOT accept a bid against an expired listing")
        assertEquals(
            DeadlineRefusal.LISTING_EXPIRED,
            (DeadlineGate.check(listing(DEADLINE), null, clockAt(DEADLINE)) as DeadlineGate.Refused).reason,
            "and with no bid at all — proposing or advancing an order against the listing",
        )
    }

    @JsName("an_expired_bid_on_a_live_listing_is_refused_naming_the_bid")
    @Test
    fun `an expired bid on a live listing is refused naming the bid`() {
        val answer = DeadlineGate.check(listing(LATER), bid(DEADLINE), clockAt(DEADLINE))

        assertIs<DeadlineGate.Refused>(answer)
        assertEquals(DeadlineRefusal.BID_EXPIRED, answer.reason)
        assertEquals(DeadlineSide.BID, answer.side)
    }

    @JsName("cannot_say_on_either_side_refuses_with_its_own_constant")
    @Test
    fun `cannot say on either side refuses with its own constant`() {
        val listingSilent = DeadlineGate.check(listing(DEADLINE), bid(null), NenyaClock.FAIL_CLOSED)
        assertEquals(DeadlineRefusal.LISTING_CANNOT_SAY, (listingSilent as DeadlineGate.Refused).reason)

        val bidSilent = DeadlineGate.check(listing(null), bid(DEADLINE), clockAt(-1L))
        assertEquals(DeadlineRefusal.BID_CANNOT_SAY, (bidSilent as DeadlineGate.Refused).reason)
        assertEquals(DeadlineSide.BID, bidSilent.side)
    }

    /**
     * The negative control the task names, run as a sweep: under `FAIL_CLOSED` no pair carrying an
     * `expiration` on either side is permitted. A pair carrying none has no deadline to evaluate and
     * is permitted under any clock, the same narrowing `Listing.activity` states.
     */
    @JsName("fail_closed_never_permits_a_pair_carrying_any_expiration")
    @Test
    fun `fail closed never permits a pair carrying any expiration`() {
        // `null` in the bid position is "no bid at all"; the bid with no `expiration` is EXPIRATIONS' null.
        val bids: List<Bid?> = listOf(null) + EXPIRATIONS.map { bid(it) }
        for (listingExpiration in EXPIRATIONS) {
            for (bid in bids) {
                val bidExpiration = bid?.expiration
                val answer = DeadlineGate.check(listing(listingExpiration), bid, NenyaClock.FAIL_CLOSED)
                if (listingExpiration == null && bidExpiration == null) {
                    assertEquals(DeadlineGate.Permitted, answer)
                } else {
                    assertIs<DeadlineGate.Refused>(answer, "listing $listingExpiration, bid $bidExpiration")
                    assertTrue(answer.reason.name.endsWith("_CANNOT_SAY"), "${answer.reason} under a silent clock")
                }
            }
        }
    }

    /** "Permitted only when both are ACTIVE", checked over every pair and every clock above. */
    @JsName("permitted_exactly_when_both_activities_are_active")
    @Test
    fun `permitted exactly when both activities are active`() {
        var permitted = 0
        var refused = 0
        for (listingExpiration in EXPIRATIONS) {
            for (bidExpiration in EXPIRATIONS) {
                for (clock in CLOCKS) {
                    val listing = listing(listingExpiration)
                    val bid = bid(bidExpiration)
                    val both = listing.activity(clock) == ListingActivity.ACTIVE &&
                        bid.activity(clock) == ListingActivity.ACTIVE
                    val answer = DeadlineGate.check(listing, bid, clock)
                    assertEquals(both, answer == DeadlineGate.Permitted, "$listingExpiration/$bidExpiration/$clock")
                    if (answer is DeadlineGate.Refused) {
                        refused++
                        val side = if (listing.activity(clock) != ListingActivity.ACTIVE) DeadlineSide.LISTING else DeadlineSide.BID
                        assertEquals(side, answer.side, "the listing is named first, §5.6's MUST")
                    } else {
                        permitted++
                    }
                }
            }
        }
        assertTrue(permitted > 0 && refused > 0, "non-vacuity: $permitted permitted, $refused refused")
    }

    @JsName("the_clock_is_read_once_so_both_deadlines_are_judged_at_one_instant")
    @Test
    fun `the clock is read once, so both deadlines are judged at one instant`() {
        // A clock answering DEADLINE - 1 and then DEADLINE: two reads would find the listing live and
        // the bid expired, two different instants answering one question.
        val clock = SteppingClock(DEADLINE - 1, DEADLINE)

        assertEquals(DeadlineGate.Permitted, DeadlineGate.check(listing(DEADLINE), bid(DEADLINE), clock))
        assertEquals(1, clock.reads)

        val unread = SteppingClock(Long.MAX_VALUE)
        assertEquals(DeadlineGate.Permitted, DeadlineGate.check(listing(null), bid(null), unread))
        assertEquals(0, unread.reads, "no expiration on either side: nothing for the clock to evaluate")
    }

    @JsName("a_bid_scoped_to_another_listing_is_refused_naming_the_bid")
    @Test
    fun `a bid scoped to another listing is refused naming the bid`() {
        val other = listing(null, dValue = "some-other-listing")

        assertNotEquals(other.coordinate, bid(null).listing)
        val answer = DeadlineGate.check(other, bid(null), clockAt(DEADLINE - 1))
        assertIs<DeadlineGate.Refused>(answer)
        assertEquals(DeadlineRefusal.BID_FOR_ANOTHER_LISTING, answer.reason)
        assertEquals(DeadlineSide.BID, answer.side)
    }

    @JsName("a_refusal_names_the_rule_and_nothing_else")
    @Test
    fun `a refusal names the rule and nothing else`() {
        val answer = DeadlineGate.check(listing(DEADLINE), bid(null), clockAt(DEADLINE))

        assertEquals("DeadlineGate.Refused(LISTING_EXPIRED)", answer.toString())
        assertEquals("DeadlineGate.Permitted", DeadlineGate.Permitted.toString())
    }
}
