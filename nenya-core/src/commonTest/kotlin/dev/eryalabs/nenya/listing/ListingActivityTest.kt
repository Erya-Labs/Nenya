package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * §5.6's expiration rule, evaluated against §4.6's injected clock and against nothing else.
 *
 * Three answers rather than two, and the third is the fail-closed rule of T5 reaching a decoder:
 * with no clock injected this library will not say whether a deadline has passed, and it does not
 * round that to "active". §17 is the reason — an implementation MUST NOT report unverified things
 * as verified, and "not expired" is a claim about a deadline nobody evaluated.
 */
class ListingActivityTest {

    private companion object {

        /** A deadline in the fixture's own units, well clear of the fixed `created_at`. */
        const val DEADLINE: Long = ListingFixtures.CREATED_AT + 10_000L

        fun clockAt(unixSeconds: Long): NenyaClock = FakeClock(unixSeconds)

        /** A listing of [kind] carrying [expiration], published at [createdAt]. */
        fun listing(
            expiration: Long?,
            kind: Int = NenyaKind.OFFER,
            createdAt: Long = ListingFixtures.CREATED_AT,
        ): Listing {
            val tags = ListingFixtures.minimal(kind).toMutableList()
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            return Listing.decode(ListingFixtures.checkedAt(tags, kind, createdAt))
        }
    }

    @JsName("an_expired_listing_is_inactive_even_though_the_relay_served_it")
    @Test
    fun `an expired listing is inactive even though the relay served it`() {
        val listing = listing(DEADLINE)

        assertEquals(
            ListingActivity.EXPIRED,
            listing.activity(clockAt(DEADLINE + 1)),
            "§5.6: relays SHOULD honour NIP-40 and many do not, so an implementation MUST treat an " +
                "event whose expiration has passed as inactive regardless of the relay serving it",
        )
        assertEquals(ListingActivity.ACTIVE, listing.activity(clockAt(DEADLINE - 1)))
    }

    @JsName("the_named_second_is_itself_expired")
    @Test
    fun `the named second is itself expired`() {
        val listing = listing(DEADLINE)

        assertEquals(ListingActivity.EXPIRED, listing.activity(clockAt(DEADLINE)))
        assertEquals(ListingActivity.ACTIVE, listing.activity(clockAt(DEADLINE - 1)))
    }

    @JsName("with_no_clock_the_answer_is_cannot_say_and_never_active")
    @Test
    fun `with no clock the answer is cannot say, and never active`() {
        val listing = listing(DEADLINE)

        val answer = listing.activity(NenyaClock.FAIL_CLOSED)

        assertEquals(ListingActivity.CANNOT_SAY, answer)
        assertNotEquals(
            ListingActivity.ACTIVE,
            answer,
            "collapsing the silent clock to `active` would report a deadline as unexpired that " +
                "this library never evaluated — the §17 over-claim the whole seam design exists " +
                "to prevent",
        )
        assertEquals(
            ListingActivity.CANNOT_SAY,
            listing.activity(),
            "and the default is the fail-closed seam, so a caller that injects nothing gets the " +
                "same answer rather than a friendlier one",
        )
    }

    /**
     * A clock answering before 1970 is broken (§4.3 has no negative timestamp), and a broken clock
     * is not compared with a deadline in either direction. Before this rule `-1` was used as given
     * and every listing with an `expiration` read [ListingActivity.ACTIVE] under it — a deadline
     * reported unexpired on a reading nobody could trust. Zero is a real reading and is used.
     */
    @JsName("a_clock_reading_before_1970_cannot_say_and_never_active_or_expired")
    @Test
    fun `a clock reading before 1970 cannot say, and never active or expired`() {
        for (reading in listOf(-1L, Long.MIN_VALUE)) {
            for (expiration in listOf(0L, DEADLINE)) {
                assertEquals(
                    ListingActivity.CANNOT_SAY,
                    listing(expiration).activity(clockAt(reading)),
                    "expiration $expiration, reading $reading",
                )
            }
        }
        assertEquals(ListingActivity.EXPIRED, listing(0L).activity(clockAt(0L)))
        assertEquals(ListingActivity.ACTIVE, listing(DEADLINE).activity(clockAt(0L)))
        assertEquals(ListingActivity.ACTIVE, listing(expiration = null).activity(clockAt(-1L)))
    }

    /**
     * The stated narrowing: a listing carrying no `expiration` has no deadline to evaluate, so the
     * clock is not consulted and the answer is [ListingActivity.ACTIVE] even when it is silent.
     * §5.6's rule is about an event "whose `expiration` has passed"; there is no such field here,
     * and answering "cannot say" would make every listing unusable to a client that injected no
     * clock for a question that was never asked.
     */
    @JsName("a_listing_with_no_expiration_is_active_without_the_clock_being_consulted")
    @Test
    fun `a listing with no expiration is active without the clock being consulted`() {
        val listing = listing(expiration = null)

        assertEquals(ListingActivity.ACTIVE, listing.activity(NenyaClock.FAIL_CLOSED))
        assertEquals(ListingActivity.ACTIVE, listing.activity(clockAt(DEADLINE + 1_000_000)))
    }

    /**
     * §4.6's control, built so that an implementation reading the event's own `created_at` instead
     * of the injected clock gets the **opposite** answer in both directions. One direction alone
     * would survive the mutation half the time.
     */
    @JsName("a_counterparty_s_created_at_decides_nothing")
    @Test
    fun `a counterparty's created_at decides nothing`() {
        // The author's clock is ahead of ours: `created_at` is past the deadline and the injected
        // clock is not. §4.6 makes ours authoritative, so the listing is live.
        val ahead = listing(DEADLINE, createdAt = DEADLINE + 5_000L)
        assertEquals(
            ListingActivity.ACTIVE,
            ahead.activity(clockAt(DEADLINE - 5_000L)),
            "a listing whose own created_at is later than the clock reading is not thereby " +
                "expired; a counterparty's created_at is a claim",
        )

        // And behind: `created_at` is well before the deadline while our clock has passed it.
        val behind = listing(DEADLINE, createdAt = DEADLINE - 9_000L)
        assertEquals(
            ListingActivity.EXPIRED,
            behind.activity(clockAt(DEADLINE + 5_000L)),
        )
    }

    @JsName("activity_is_about_the_deadline_and_the_status_tag_is_a_separate_axis")
    @Test
    fun `activity is about the deadline and the status tag is a separate axis`() {
        val sold = ListingFixtures.with(ListingFixtures.minimalOffer(), listOf("status", "sold"))
        val listing = Listing.decode(ListingFixtures.checked(sold, NenyaKind.OFFER))

        assertEquals(ListingStatus.SOLD, listing.status)
        assertEquals(
            ListingActivity.ACTIVE,
            listing.activity(clockAt(ListingFixtures.CREATED_AT)),
            "§5.6 makes expiration the consumer's rule and §5.1 makes `status` the publisher's " +
                "statement; a sold offer with no expiration has not expired, and conflating the " +
                "two would report an un-lit takedown as a deadline",
        )
    }
}
