package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.DeadlineRefusal
import dev.eryalabs.nenya.channel.ListingOrderBuild
import dev.eryalabs.nenya.channel.ListingOrderWriter
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T51's crossing, [buildProposalAgainst], held equal to `ListingOrderWriter.proposal` field by field,
 * under the same clock handed in once as a `NenyaClock` and once as the page's decimal-string function.
 */
class JsListingOrderEqualityTest {

    private companion object {

        const val DEADLINE: Long = ListingFixtures.CREATED_AT + 10_000L

        val PROPOSALS: List<RumorWriterFixtures.Case> =
            RumorWriterFixtures.cases(80).filter { it.shape == RumorWriterFixtures.Shape.PROPOSAL }

        val EXPIRATIONS: List<Long?> = listOf(null, DEADLINE)

        /** Readings either side of the deadline, `null` being the page that supplied no clock. */
        val READINGS: List<Long?> = listOf(null, -1L, DEADLINE - 1, DEADLINE, DEADLINE + 1)

        fun listing(kind: Int, expiration: Long?): Listing {
            val tags = ListingFixtures.minimal(kind)
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            return ListingFixtures.decode(tags, kind, index = 1)
        }

        fun kotlinClock(reading: Long?): NenyaClock = if (reading == null) NenyaClock.FAIL_CLOSED else FakeClock(reading)

        fun environment(reading: Long?): JsEnvironment =
            if (reading == null) JsEnvironment() else JsEnvironment(nowSeconds = { reading.toString() })

        fun crossed(listing: Listing, reading: Long?, case: RumorWriterFixtures.Case): JsBuildResult =
            buildProposalAgainst(
                listing = JsListing(listing),
                environment = environment(reading),
                envelope = JsBoundaryFixtures.crossed(case.envelope),
                orderIdHex = (case.order ?: fail("$case must carry an order id")).toHex(),
                terms = JsBoundaryFixtures.crossed(case.terms ?: fail("$case must carry terms")),
                feeRecipient = case.feeRecipient,
                amountSat = JsBoundaryFixtures.decimalOrNull(case.amountSat),
            )
    }

    @JsName("buildProposalAgainst_answers_what_ListingOrderWriter_proposal_answers")
    @Test
    fun `buildProposalAgainst answers what ListingOrderWriter proposal answers`() {
        var built = 0
        var deadline = 0
        for (kind in ListingFixtures.KINDS) {
            for (expiration in EXPIRATIONS) {
                val listing = listing(kind, expiration)
                for (reading in READINGS) {
                    for (case in PROPOSALS) {
                        val what = "$case, kind $kind, expiration $expiration, reading $reading"
                        val answer = crossed(listing, reading, case)
                        when (
                            val expected = ListingOrderWriter.proposal(
                                listing, kotlinClock(reading), case.envelope, case.order!!, case.terms!!,
                                case.feeRecipient, case.amountSat,
                            )
                        ) {
                            is ListingOrderBuild.Built -> {
                                JsBoundaryFixtures.assertSameAnswer(
                                    answer,
                                    JsBoundaryFixtures.Answer(true, expected.event, null, null, null, null, null),
                                    what,
                                )
                                built++
                            }
                            ListingOrderBuild.ForAnotherListing ->
                                fail("$what: `proposal` reads its item off the listing and has no other to name")
                            is ListingOrderBuild.Refused -> JsBoundaryFixtures.assertSameAnswer(
                                answer,
                                JsBoundaryFixtures.answerOf(expected.refusal),
                                what,
                            )
                            is ListingOrderBuild.DeadlineRefused -> {
                                assertFalse(answer.ok, what)
                                assertEquals("refused", answer.kind, what)
                                assertNull(answer.event, "$what: a deadline refusal carries no event")
                                assertEquals(expected.reason.name, answer.reason, what)
                                assertEquals("DeadlineRefusal", answer.reasonVocabulary, what)
                                assertNull(answer.tag, what)
                                assertNull(answer.tagReason, what)
                                assertNotNull(answer.detail, what)
                                deadline++
                            }
                        }
                    }
                }
            }
        }
        assertTrue(built > 0 && deadline > 0, "$built build(s) and $deadline deadline refusal(s): both must be drawn")
    }

    @JsName("an_expired_listing_crosses_as_LISTING_EXPIRED_and_a_silent_page_as_LISTING_CANNOT_SAY")
    @Test
    fun `an expired listing crosses as LISTING_EXPIRED and a silent page as LISTING_CANNOT_SAY`() {
        val case = PROPOSALS.first()
        val expiring = listing(ListingFixtures.KINDS[0], DEADLINE)

        val expired = crossed(expiring, DEADLINE, case)
        assertEquals(DeadlineRefusal.LISTING_EXPIRED.name, expired.reason)
        assertNull(expired.event)

        for (silent in listOf(JsEnvironment(), JsEnvironment(nowSeconds = { null }), JsEnvironment(nowSeconds = { "-1" }))) {
            val answer = buildProposalAgainst(
                JsListing(expiring), silent, JsBoundaryFixtures.crossed(case.envelope), case.order!!.toHex(),
                JsBoundaryFixtures.crossed(case.terms!!), case.feeRecipient, JsBoundaryFixtures.decimalOrNull(case.amountSat),
            )
            assertEquals(DeadlineRefusal.LISTING_CANNOT_SAY.name, answer.reason)
            assertEquals("DeadlineRefusal", answer.reasonVocabulary)
            assertNull(answer.event)
        }
    }

    @JsName("a_built_crossing_names_the_listing_s_coordinate_as_item")
    @Test
    fun `a built crossing names the listing's coordinate as item`() {
        var seen = 0
        for (kind in ListingFixtures.KINDS) {
            val listing = listing(kind, null)
            for (case in PROPOSALS) {
                val event = crossed(listing, null, case).event ?: continue
                val items = event.tags.filter { it[0] == "item" }
                assertEquals(1, items.size, "$case: exactly one item")
                assertEquals(listing.coordinate.toTagValue(), items.single()[1], "$case against kind $kind")
                seen++
            }
        }
        assertTrue(seen > 0, "no crossing built")
    }
}
