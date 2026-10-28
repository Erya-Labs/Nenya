package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.bid.DeadlineGate
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingActivity
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T49's three crossings — [listingActivity], [bidActivity] and [checkDeadlines] — held equal to the
 * Kotlin answers they translate, field by field, under the same clock handed in once as a
 * `NenyaClock` and once as the page's decimal-string function.
 */
class JsDeadlineEqualityTest {

    private companion object {

        const val DEADLINE: Long = BidFixtures.CREATED_AT + 10_000L
        const val LATER: Long = DEADLINE + 5_000L

        val EXPIRATIONS: List<Long?> = listOf(null, DEADLINE, LATER)

        /** Readings either side of each deadline, `null` being the page that supplied no clock. */
        val READINGS: List<Long?> = listOf(null, -1L, 0L, DEADLINE - 1, DEADLINE, LATER - 1, LATER)

        fun bid(expiration: Long?): Bid {
            val tags = BidFixtures.minimalBid()
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            return BidFixtures.decode(tags)
        }

        /** The §5.2 request [bid] is scoped to: same kind, same author index, same `d`. */
        fun listing(expiration: Long?): Listing {
            val tags = ListingFixtures.minimalRequest()
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            return ListingFixtures.decode(tags, NenyaKind.REQUEST, index = 1)
        }

        fun kotlinClock(reading: Long?): NenyaClock = if (reading == null) NenyaClock.FAIL_CLOSED else FakeClock(reading)

        fun environment(reading: Long?): JsEnvironment =
            if (reading == null) JsEnvironment() else JsEnvironment(nowSeconds = { reading.toString() })
    }

    @JsName("both_activities_cross_as_the_kotlin_answer_s_name")
    @Test
    fun `both activities cross as the Kotlin answer's name`() {
        for (expiration in EXPIRATIONS) {
            for (reading in READINGS) {
                val clock = kotlinClock(reading)
                val environment = environment(reading)
                assertEquals(
                    listing(expiration).activity(clock).name,
                    listingActivity(JsListing(listing(expiration)), environment),
                    "listing $expiration at $reading",
                )
                assertEquals(
                    bid(expiration).activity(clock).name,
                    bidActivity(JsBid(bid(expiration)), environment),
                    "bid $expiration at $reading",
                )
            }
        }
    }

    @JsName("check_deadlines_crosses_the_gate_field_by_field")
    @Test
    fun `checkDeadlines crosses the gate field by field`() {
        var refusals = 0
        var permits = 0
        // `null` in the bid position is "no bid at all"; the bid with no `expiration` is EXPIRATIONS' null.
        val bids: List<Bid?> = listOf(null) + EXPIRATIONS.map { bid(it) }
        for (listingExpiration in EXPIRATIONS) {
            for (bid in bids) {
                for (reading in READINGS) {
                    val listing = listing(listingExpiration)
                    val expected = DeadlineGate.check(listing, bid, kotlinClock(reading))
                    val crossed = checkDeadlines(JsListing(listing), bid?.let { JsBid(it) }, environment(reading))
                    val label = "listing $listingExpiration, bid ${bid?.expiration}, reading $reading"

                    assertEquals(expected == DeadlineGate.Permitted, crossed.permitted, label)
                    assertEquals(if (crossed.permitted) "permitted" else "refused", crossed.kind, label)
                    if (expected is DeadlineGate.Refused) {
                        refusals++
                        assertEquals(expected.reason.name, crossed.reason, label)
                        assertEquals(expected.side.name, crossed.side, label)
                    } else {
                        permits++
                        assertNull(crossed.reason, label)
                        assertNull(crossed.side, label)
                    }
                }
            }
        }
        assertTrue(refusals > 0 && permits > 0, "non-vacuity: $permits permitted, $refusals refused")
    }

    @JsName("a_page_with_no_clock_never_crosses_active_or_permitted_for_a_deadline")
    @Test
    fun `a page with no clock never crosses active or permitted for a deadline`() {
        val listing = JsListing(listing(DEADLINE))

        assertEquals(ListingActivity.CANNOT_SAY.name, listingActivity(listing, JsEnvironment()))
        assertEquals(ListingActivity.CANNOT_SAY.name, bidActivity(JsBid(bid(DEADLINE)), JsEnvironment()))
        val answer = checkDeadlines(listing, JsBid(bid(null)), JsEnvironment())
        assertFalse(answer.permitted)
        assertEquals("LISTING_CANNOT_SAY", answer.reason)
        assertEquals("LISTING", answer.side)
    }

    @JsName("a_clock_answering_something_other_than_a_decimal_string_cannot_say")
    @Test
    fun `a clock answering something other than a decimal string cannot say`() {
        for (text in listOf("", "soon", "1e9", " 1", "0x10")) {
            val environment = JsEnvironment(nowSeconds = { text })
            assertEquals(ListingActivity.CANNOT_SAY.name, bidActivity(JsBid(bid(DEADLINE)), environment), "'$text'")
        }
        assertEquals(
            ListingActivity.CANNOT_SAY.name,
            bidActivity(JsBid(bid(DEADLINE)), JsEnvironment(nowSeconds = { null })),
        )
    }

    @JsName("an_expired_listing_crosses_refused_naming_the_listing_beside_a_live_bid")
    @Test
    fun `an expired listing crosses refused naming the listing beside a live bid`() {
        val answer = checkDeadlines(JsListing(listing(DEADLINE)), JsBid(bid(LATER)), environment(DEADLINE))

        assertEquals("refused", answer.kind)
        assertEquals("LISTING_EXPIRED", answer.reason)
        assertEquals("LISTING", answer.side)
        assertEquals("JsDeadlineCheck(refused: LISTING_EXPIRED)", answer.toString())
    }
}
