package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.DeadlineRefusal
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ListingOrderBuild
import dev.eryalabs.nenya.channel.ListingOrderWriter
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.ProposalDeadlineRefusal
import dev.eryalabs.nenya.channel.RumorEnvelope
import dev.eryalabs.nenya.channel.RumorWriter
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.tag.PubkeyRef
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T52's crossing, [buildAgreementAgainst], held equal to `ListingOrderWriter.agreement` field by field,
 * under the same clock handed in once as a `NenyaClock` and once as the page's decimal-string function,
 * with the proposal crossing as the [JsRumor] `open` hands a page.
 */
class JsListingAgreementEqualityTest {

    private companion object {

        const val DEADLINE: Long = ListingFixtures.CREATED_AT + 10_000L

        const val PROVIDER_OFFSET: Int = 10_000

        val PROPOSALS: List<RumorWriterFixtures.Case> =
            RumorWriterFixtures.cases(80).filter { it.shape == RumorWriterFixtures.Shape.PROPOSAL }

        val EXPIRATIONS: List<Long?> = listOf(null, DEADLINE)

        /**
         * Readings either side of the deadline, `null` being the page that supplied no clock.
         * `CREATED_AT` is before every proposal's own `expiration` (`RumorWriterFixtures.orderTerms`
         * draws them after it), and `DEADLINE - 1` at or past every one, so both sides of T57's
         * deadline for acceptance are drawn too.
         */
        val READINGS: List<Long?> = listOf(null, -1L, ListingFixtures.CREATED_AT, DEADLINE - 1, DEADLINE, DEADLINE + 1)

        fun listing(kind: Int, expiration: Long?, index: Int = 1): Listing {
            val tags = ListingFixtures.minimal(kind)
            if (expiration != null) tags += listOf(listOf("expiration", expiration.toString()))
            return ListingFixtures.decode(tags, kind, index = index)
        }

        fun kotlinClock(reading: Long?): NenyaClock = if (reading == null) NenyaClock.FAIL_CLOSED else FakeClock(reading)

        fun environment(reading: Long?): JsEnvironment =
            if (reading == null) JsEnvironment() else JsEnvironment(nowSeconds = { reading.toString() })

        /** [case]'s proposal built by T51's writer against [listing], as the rumor `open` would hand back. */
        fun proposalRumor(listing: Listing, case: RumorWriterFixtures.Case): JsRumor? {
            val built = ListingOrderWriter.proposal(
                listing, FakeClock(DEADLINE - 1), case.envelope, case.order!!, case.terms!!,
                case.feeRecipient, case.amountSat,
            )
            return (built as? ListingOrderBuild.Built)?.let { JsRumor(RumorWriterFixtures.attribute(it.event)) }
        }

        fun providerEnvelope(case: RumorWriterFixtures.Case, buyer: String): RumorEnvelope = RumorEnvelope(
            RumorWriterFixtures.pubkeyFor(case.index + PROVIDER_OFFSET),
            case.envelope.createdAt + 1,
            listOf(PubkeyRef(buyer)),
        )

        fun buyerOf(rumor: JsRumor): String = rumor.rumor.author

        fun crossed(listing: Listing, reading: Long?, case: RumorWriterFixtures.Case, rumor: JsRumor): JsBuildResult =
            buildAgreementAgainst(
                listing = JsListing(listing),
                environment = environment(reading),
                envelope = JsBoundaryFixtures.crossed(providerEnvelope(case, buyerOf(rumor))),
                proposalRumor = rumor,
            )
    }

    @JsName("buildAgreementAgainst_answers_what_ListingOrderWriter_agreement_answers")
    @Test
    fun `buildAgreementAgainst answers what ListingOrderWriter agreement answers`() {
        var built = 0
        var deadline = 0
        var elsewhere = 0
        var proposalDeadline = 0
        for (kind in ListingFixtures.KINDS) {
            for (expiration in EXPIRATIONS) {
                val proposedAgainst = listing(kind, expiration)
                // The listing the proposal names, and one by another author that it does not.
                for (handedIn in listOf(proposedAgainst, listing(kind, expiration, index = 2))) {
                    for (reading in READINGS) {
                        for (case in PROPOSALS) {
                            val rumor = proposalRumor(proposedAgainst, case) ?: continue
                            val what = "$case, kind $kind, expiration $expiration, reading $reading"
                            val answer = crossed(handedIn, reading, case, rumor)
                            val proposal = OrderProposal.decode(RumorWriterFixtures.bound(rumor.rumor.encode()))
                            when (
                                val expected = ListingOrderWriter.agreement(
                                    handedIn, kotlinClock(reading), providerEnvelope(case, buyerOf(rumor)), proposal,
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
                                is ListingOrderBuild.Refused -> JsBoundaryFixtures.assertSameAnswer(
                                    answer,
                                    JsBoundaryFixtures.answerOf(expected.refusal),
                                    what,
                                )
                                is ListingOrderBuild.DeadlineRefused -> {
                                    assertFalse(answer.ok, what)
                                    assertNull(answer.event, "$what: a deadline refusal carries no event")
                                    assertEquals(expected.reason.name, answer.reason, what)
                                    assertEquals("DeadlineRefusal", answer.reasonVocabulary, what)
                                    assertNull(answer.tag, what)
                                    deadline++
                                }
                                is ListingOrderBuild.ProposalDeadlineRefused -> {
                                    assertFalse(answer.ok, what)
                                    assertEquals("refused", answer.kind, what)
                                    assertNull(answer.event, "$what: a proposal deadline refusal carries no event")
                                    assertEquals(expected.reason.name, answer.reason, what)
                                    assertEquals("ProposalDeadlineRefusal", answer.reasonVocabulary, what)
                                    assertNull(answer.tag, what)
                                    assertNotNull(answer.detail, what)
                                    proposalDeadline++
                                }
                                ListingOrderBuild.ForAnotherListing -> {
                                    assertFalse(answer.ok, what)
                                    assertEquals("refused", answer.kind, what)
                                    assertNull(answer.event, "$what: another listing's proposal carries no event")
                                    assertEquals("FOR_ANOTHER_LISTING", answer.reason, what)
                                    assertEquals("ListingOrderBuild", answer.reasonVocabulary, what)
                                    assertEquals("item", answer.tag, what)
                                    assertNull(answer.tagReason, what)
                                    assertNotNull(answer.detail, what)
                                    elsewhere++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(
            built > 0 && deadline > 0 && elsewhere > 0 && proposalDeadline > 0,
            "$built build(s), $deadline deadline refusal(s), $elsewhere for another listing and " +
                "$proposalDeadline proposal deadline refusal(s): all must be drawn",
        )
    }

    @JsName("an_expired_listing_crosses_as_LISTING_EXPIRED_and_another_listing_as_FOR_ANOTHER_LISTING")
    @Test
    fun `an expired listing crosses as LISTING_EXPIRED and another listing as FOR_ANOTHER_LISTING`() {
        val kind = ListingFixtures.KINDS[0]
        val expiring = listing(kind, DEADLINE)
        val (case, rumor) = PROPOSALS.firstNotNullOf { case -> proposalRumor(expiring, case)?.let { case to it } }

        val expired = crossed(expiring, DEADLINE + 1, case, rumor)
        assertEquals(DeadlineRefusal.LISTING_EXPIRED.name, expired.reason)
        assertEquals("DeadlineRefusal", expired.reasonVocabulary)
        assertNull(expired.event)

        val silent = crossed(expiring, null, case, rumor)
        assertEquals(DeadlineRefusal.LISTING_CANNOT_SAY.name, silent.reason)
        assertNull(silent.event)

        // Another author's listing, itself expired: the item is compared first, so the answer names
        // the listing mismatch and not the deadline.
        val other = crossed(listing(kind, 0L, index = 2), DEADLINE + 1, case, rumor)
        assertEquals("FOR_ANOTHER_LISTING", other.reason)
        assertNull(other.event)

        // Before the proposal's own `expiration` too, which `RumorWriterFixtures` draws after `CREATED_AT`.
        assertTrue(crossed(expiring, ListingFixtures.CREATED_AT, case, rumor).ok, "a live listing agrees")
    }

    @JsName("a_live_listing_past_the_proposal_s_expiration_crosses_as_PROPOSAL_EXPIRED")
    @Test
    fun `a live listing past the proposal's expiration crosses as PROPOSAL_EXPIRED`() {
        val open = listing(ListingFixtures.KINDS[0], null)
        val (case, rumor) = PROPOSALS.firstNotNullOf { case ->
            if (case.terms?.expiration == null) null else proposalRumor(open, case)?.let { case to it }
        }
        val expiration = case.terms!!.expiration!!

        val expired = crossed(open, expiration, case, rumor)
        assertEquals(ProposalDeadlineRefusal.PROPOSAL_EXPIRED.name, expired.reason)
        assertEquals("ProposalDeadlineRefusal", expired.reasonVocabulary)
        assertNull(expired.event)

        val silent = crossed(open, null, case, rumor)
        assertEquals(ProposalDeadlineRefusal.PROPOSAL_CANNOT_SAY.name, silent.reason)
        assertNull(silent.event)

        assertTrue(crossed(open, expiration - 1, case, rumor).ok, "one second before the deadline agrees")
    }

    @JsName("a_rumor_that_is_not_a_proposal_is_refused_as_a_value")
    @Test
    fun `a rumor that is not a proposal is refused as a value`() {
        val listing = listing(ListingFixtures.KINDS[0], null)
        val case = PROPOSALS.first()
        val envelope = providerEnvelope(case, case.envelope.authorPubkey)

        val chat = RumorWriterFixtures.built(RumorWriter.chat(case.envelope, case.order), "chat")
        val asChat = buildAgreementAgainst(
            JsListing(listing), environment(DEADLINE - 1), JsBoundaryFixtures.crossed(envelope),
            JsRumor(RumorWriterFixtures.attribute(chat)),
        )
        assertEquals(JsCrossing.NOT_A_BOUND_RUMOR, asChat.reason)
        assertEquals(JsCrossing.VOCABULARY, asChat.reasonVocabulary)

        val update = RumorWriterFixtures.built(
            RumorWriter.statusUpdate(case.envelope, case.order!!, OrderState.CANCELLED),
            "status update",
        )
        val asUpdate = buildAgreementAgainst(
            JsListing(listing), environment(DEADLINE - 1), JsBoundaryFixtures.crossed(envelope),
            JsRumor(RumorWriterFixtures.attribute(update)),
        )
        assertEquals(ChannelRejection.NOT_A_PROPOSAL.name, asUpdate.reason)
        assertEquals("ChannelRejection", asUpdate.reasonVocabulary)
        assertNull(asUpdate.event)
    }
}
