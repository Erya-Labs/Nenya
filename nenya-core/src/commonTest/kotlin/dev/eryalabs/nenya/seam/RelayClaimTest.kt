package dev.eryalabs.nenya.seam

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The relay transport's answers, per relay — and the four shapes a single acknowledgement could not
 * tell apart.
 *
 * §5.5 wants "this relay did not accept the request" surfaced apart from "accepted but not readable
 * back", and until T35 this seam could say neither: [RelayTransport.publish] answered one
 * [RelayAcknowledgement] for a whole fan-out, with only `CLAIMS_ACCEPTED` and `CLAIMS_REJECTED` to
 * choose between. A relay that timed out, one that closed the subscription and one that sent a bare
 * `NOTICE` all had to be reported as one of those two, and each such report was false.
 *
 * ### Nothing here is evidence, and that is asserted rather than assumed
 *
 * §3: an implementation MUST NOT accept from this seam any claim that an event is valid, current or
 * complete. Every constant on this surface is named for a claim, no production code in this library
 * calls [RelayTransport] at all, and [SubscriptionEnd] is an enum precisely so that "the relay says
 * it finished" cannot be read as a `Boolean` that means it did.
 *
 * ### No socket, and no address
 *
 * STOP RULE 13. The fakes name their relays with strings that are plainly not addresses, so a
 * fixture cannot be copied into something that would dial one, and nothing here parses the name.
 */
class RelayClaimTest {

    @JsName("a_fan_out_answers_once_per_relay_rather_than_once_for_all_of_them")
    @Test
    fun `a fan-out answers once per relay rather than once for all of them`() {
        val transport = FakeRelayTransport()

        val claims = transport.publish(EVENT).provided()

        assertEquals(
            listOf(FakeRelayTransport.FIRST_RELAY, FakeRelayTransport.SECOND_RELAY),
            claims.map { it.relay },
            "one claim per relay, each naming the relay it came from — a single answer for the whole " +
                "fan-out is what §5.5 says cannot express the outcome",
        )
        assertTrue(claims.all { it.acknowledgement == RelayAcknowledgement.CLAIMS_ACCEPTED })
        assertTrue(claims.all { it.closeReason == RelayCloseReason.NONE })
    }

    /**
     * The five outcomes, told apart on one fan-out — and they disagree, which is the case that
     * cannot be described at all by a single acknowledgement.
     */
    @JsName("accepted_rejected_timed_out_closed_and_a_notice_are_five_distinguishable_answers")
    @Test
    fun `accepted, rejected, timed out, closed and a notice are five distinguishable answers`() {
        val transport = DisagreeingRelayTransport()

        val claims = transport.publish(EVENT).provided()

        assertEquals(
            listOf(
                RelayAcknowledgement.CLAIMS_ACCEPTED,
                RelayAcknowledgement.CLAIMS_REJECTED,
                RelayAcknowledgement.TIMED_OUT,
                RelayAcknowledgement.CLOSED,
                RelayAcknowledgement.NOTICE,
            ),
            claims.map { it.acknowledgement },
            "each of §5.5's outcomes is its own answer; collapsing the last three into REJECTED " +
                "would tell a caller the relay refused the event when nothing of the sort was said",
        )
        assertEquals(
            claims.size,
            claims.map { it.relay }.toSet().size,
            "and each is attributed to a relay, or 'two relays disagreed' has no subject",
        )
        assertEquals(
            mapOf(
                RelayAcknowledgement.CLAIMS_REJECTED to RelayCloseReason.RATE_LIMITED,
                RelayAcknowledgement.CLOSED to RelayCloseReason.AUTH_REQUIRED,
            ),
            claims.filter { it.closeReason != RelayCloseReason.NONE }
                .associate { it.acknowledgement to it.closeReason },
            "NIP-01's machine-readable prefix is what a client acts on: retry a rate limit, " +
                "authenticate for an auth-required, and do neither for an invalid",
        )
    }

    /**
     * Two relays disagreeing about what they hold, which is the disagreement a merged event list
     * erases.
     */
    @JsName("two_relays_disagreeing_about_what_they_served_stays_visible")
    @Test
    fun `two relays disagreeing about what they served stays visible`() {
        val transport = DisagreeingRelayTransport()

        val deliveries = transport.request(FILTER).provided()

        val served = deliveries.filter { it.events.isNotEmpty() }
        assertEquals(1, served.size, "exactly one relay served anything, and it is knowable which")
        assertEquals(RelayAcknowledgement.CLAIMS_ACCEPTED, served.single().claim.acknowledgement)
        assertEquals(listOf(FILTER), served.single().events)
        assertEquals(
            SubscriptionEnd.CLAIMS_ENDED,
            served.single().subscription,
            "and only that relay claims it sent everything it holds — a claim, never completeness",
        )
        assertTrue(
            deliveries.filterNot { it === served.single() }
                .all { it.subscription == SubscriptionEnd.CLAIMS_INCOMPLETE && it.events.isEmpty() },
            "the other four served nothing and claim nothing; a merged list would report the union " +
                "as though every relay agreed on it",
        )
    }

    /** The fail-closed default still publishes nothing and fetches nothing, per relay or otherwise. */
    @JsName("the_default_transport_reaches_no_relay_at_all")
    @Test
    fun `the default transport reaches no relay at all`() {
        assertEquals(
            SeamCapability.RELAY_PUBLISH,
            RelayTransport.FAIL_CLOSED.publish(EVENT).unavailable().capability,
        )
        assertEquals(
            SeamCapability.RELAY_REQUEST,
            RelayTransport.FAIL_CLOSED.request(FILTER).unavailable().capability,
        )
    }

    /**
     * §12 items 2 and 11 over the two new value types: what they print is the relay's name, the
     * claim and a **count**, never an event.
     *
     * A `RelayDelivery` holds serialised events straight off a relay, and a gift wrap is one of the
     * things it will hold: a `toString` that printed them would put a counterparty's ciphertext and
     * the wrap's own `pubkey` — which §7.2 forbids displaying at all — into every log line a client
     * writes while debugging its transport.
     */
    @JsName("a_relay_delivery_prints_a_count_and_never_an_event")
    @Test
    fun `a relay delivery prints a count and never an event`() {
        val delivery = RelayDelivery(
            RelayClaim("some-relay", RelayAcknowledgement.CLAIMS_ACCEPTED),
            listOf(EVENT, FILTER),
            SubscriptionEnd.CLAIMS_ENDED,
        )

        val text = delivery.toString()

        assertFalse(EVENT in text, "a serialised event MUST NOT reach a log through this type")
        assertFalse(FILTER in text)
        assertTrue("2" in text, "the count is the one thing about the events it may say")
        assertTrue("some-relay" in text, "and which relay said it, or a disagreement is unreadable")
    }

    private companion object {

        /**
         * Distinctive text in place of an event and a filter. Neither is parsed anywhere on this
         * path — the seam deals in serialised bytes and this library's codec is the caller's to
         * apply — so a value that could not be mistaken for either is the honest fixture, and it is
         * what makes "this string did not reach the log" an assertion about something.
         */
        const val EVENT: String = "not-an-event: the relay seam parses nothing, and neither does this test"

        const val FILTER: String = "not-a-filter: the relay seam parses nothing, and neither does this test"
    }
}
