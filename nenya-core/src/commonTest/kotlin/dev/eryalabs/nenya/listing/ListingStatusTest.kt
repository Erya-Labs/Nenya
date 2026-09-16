package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * §5.1's and §5.2's `status` vocabulary, and §11.1's rule that one codec MUST NOT serve both it
 * and the order-state vocabulary.
 *
 * T6 made that rule executable in the direction it could reach — the listing tokens land in
 * `OrderState.UNKNOWN` — and this file closes the other direction. The token that matters is
 * `cancelled`: it is the one both vocabularies name, with different meanings, and therefore the
 * one where a single shared codec looks correct and passes a careless test.
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as `ListingStatusTest`
 * on each target, so every test keeps its `ListingStatusTest` name. The one test that asserts the two
 * types have no assignability relationship reads `java.lang.Class.isAssignableFrom`, and is in the JVM
 * `ListingStatusTest` (`src/jvmTest`).
 */
abstract class PortableListingStatusTest {

    private companion object {

        /** §5.2's request extension, as the document prints it. */
        val REQUEST_TOKENS: List<String> = listOf("active", "awarded", "fulfilled", "cancelled")

        /** §5.1's offer pair: "`active` or `sold`, and nothing else". */
        val OFFER_TOKENS: List<String> = listOf("active", "sold")
    }

    @JsName("each_kind_s_vocabulary_is_the_one_its_section_gives_and_the_two_differ")
    @Test
    fun `each kind's vocabulary is the one its section gives, and the two differ`() {
        assertEquals(
            REQUEST_TOKENS.toSet(),
            ListingStatusCodec.vocabulary(NenyaKind.REQUEST).mapNotNull { it.token }.toSet(),
        )
        for (offerKind in listOf(NenyaKind.OFFER, NenyaKind.OFFER_DRAFT)) {
            assertEquals(
                OFFER_TOKENS.toSet(),
                ListingStatusCodec.vocabulary(offerKind).mapNotNull { it.token }.toSet(),
            )
        }
        assertNotEquals(
            ListingStatusCodec.vocabulary(NenyaKind.REQUEST),
            ListingStatusCodec.vocabulary(NenyaKind.OFFER),
        )
        assertFailsWith<ListingException> { ListingStatusCodec.vocabulary(NenyaKind.PUBLIC_BID) }
    }

    /**
     * The pair that proves the two vocabularies are not one. Either assertion alone passes over a
     * codec carrying a single union of all five tokens.
     */
    @JsName("awarded_is_refused_on_an_offer_and_accepted_on_a_request")
    @Test
    fun `awarded is refused on an offer and accepted on a request`() {
        val offer = ListingFixtures.with(ListingFixtures.minimalOffer(), listOf("status", "awarded"))
        val refused = assertFailsWith<ListingException> {
            Listing.decode(ListingFixtures.checked(offer, NenyaKind.OFFER))
        }
        assertEquals(
            ListingRejection.STATUS_OUTSIDE_VOCABULARY,
            refused.reason,
            "§5.1: an offer's status MUST be `active` or `sold` and nothing else, because a third " +
                "value makes the event non-conformant in every existing NIP-99 client",
        )
        assertEquals("status", refused.tag)

        val request = ListingFixtures.with(ListingFixtures.minimalRequest(), listOf("status", "awarded"))
        val accepted = Listing.decode(ListingFixtures.checked(request, NenyaKind.REQUEST))
        assertEquals(ListingStatus.AWARDED, accepted.status)
        assertEquals("awarded", accepted.statusToken)
    }

    /**
     * The other direction is **not** the same rule, and reading it as one would be this codec
     * inventing a MUST. §5.1 closes the offer vocabulary itself — "and nothing else" — while §5.2
     * gives requests no such clause and does give the unknown-treatment MUST. So `sold` on a
     * request is a peer this implementation does not understand, and the listing still decodes:
     * refusing it would throw away the `d`, the `title` and the `price` too.
     */
    @JsName("sold_on_a_request_reads_as_unknown_rather_than_refusing_the_listing")
    @Test
    fun `sold on a request reads as unknown rather than refusing the listing`() {
        val request = ListingFixtures.with(ListingFixtures.minimalRequest(), listOf("status", "sold"))

        val listing = Listing.decode(ListingFixtures.checked(request, NenyaKind.REQUEST))

        assertEquals(ListingStatus.UNKNOWN, listing.status)
        assertNotEquals(ListingStatus.ACTIVE, listing.status, "§5.2's other half")
        assertNotEquals(ListingStatus.SOLD, listing.status)
        assertEquals("sold", listing.statusToken)
        assertEquals("lighthouse-loop-2026-09", listing.dValue, "the rest of the listing survives")

        // And the writer stays strict, which is §4.4's read/write asymmetry applied to a
        // vocabulary: this library emits only what §5.2 gives a request.
        assertEquals(
            ListingRejection.STATUS_OUTSIDE_VOCABULARY,
            assertFailsWith<ListingException> {
                ListingStatusCodec.write(ListingStatus.SOLD, NenyaKind.REQUEST)
            }.reason,
        )
    }

    /**
     * The narrowing on the offer side is as small as the rule that licenses it: §5.1's "nothing
     * else" refuses the three tokens **this document defines** for the other kind, and a token no
     * section defines is still §5.2's `unknown` treatment on an offer as well.
     */
    @JsName("an_offer_refuses_a_defined_request_token_and_shrugs_at_an_undefined_one")
    @Test
    fun `an offer refuses a defined request token and shrugs at an undefined one`() {
        // Both offer kinds, because §5.1 says a draft "has identical structure" and a rule
        // asserted on `30402` alone would not catch a codec that forgot the other one.
        for (kind in listOf(NenyaKind.OFFER, NenyaKind.OFFER_DRAFT)) {
            for (token in listOf("awarded", "fulfilled", "cancelled")) {
                assertEquals(
                    ListingRejection.STATUS_OUTSIDE_VOCABULARY,
                    assertFailsWith<ListingException>("kind:$kind, `$token`") {
                        ListingStatusCodec.read(token, kind)
                    }.reason,
                )
            }
            assertEquals(
                ListingStatus.UNKNOWN,
                ListingStatusCodec.read(ListingFixtures.UNRECOGNISED_STATUS, kind),
                "a peer speaking a dialect Nenya has not written yet is exactly what §5.2's " +
                    "unknown-treatment clause is for",
            )
        }
    }

    /**
     * §4.3 requires unknown tags be ignored rather than refused, and the same reading applies to an
     * element nobody asked for: §5.3's Encoding cell is `["status", "<value>"]` and NIP-01 tags are
     * open-ended arrays, so a third element is a peer's extension and not a malformed listing. The
     * value is read from element 1 either way, and the tag round-trips verbatim, so nothing about
     * the event id or the re-published bytes changes.
     */
    @JsName("an_extra_element_on_a_status_tag_is_ignored_rather_than_refused")
    @Test
    fun `an extra element on a status tag is ignored rather than refused`() {
        val tags = ListingFixtures.with(
            ListingFixtures.minimalOffer(),
            listOf("status", "sold", "an element this codec does not know"),
        )

        val listing = Listing.decode(ListingFixtures.checked(tags, NenyaKind.OFFER))

        assertEquals(ListingStatus.SOLD, listing.status)
        assertEquals(tags, listing.encode().tags)
    }

    @JsName("an_absent_status_means_active_and_an_unrecognised_one_means_unknown")
    @Test
    fun `an absent status means active and an unrecognised one means unknown`() {
        val absent = Listing.decode(ListingFixtures.checked(ListingFixtures.minimalOffer(), NenyaKind.OFFER))
        assertEquals(ListingStatus.ACTIVE, absent.status, "§5.3: absent means `active`")

        val unrecognised = ListingFixtures.with(
            ListingFixtures.minimalOffer(),
            listOf("status", ListingFixtures.UNRECOGNISED_STATUS),
        )
        val read = Listing.decode(ListingFixtures.checked(unrecognised, NenyaKind.OFFER))
        assertEquals(
            ListingStatus.UNKNOWN,
            read.status,
            "§5.2: an unrecognised status MUST be treated as `unknown` and MUST NOT be treated as " +
                "`active`",
        )
        assertNotEquals(ListingStatus.ACTIVE, read.status)
        assertEquals(
            ListingFixtures.UNRECOGNISED_STATUS,
            read.statusToken,
            "the value is *treated* as unknown, not discarded: a client may still show the user " +
                "what the publisher wrote, which is different from acting on it",
        )
    }

    @JsName("case_is_not_silently_normalised_into_a_status")
    @Test
    fun `case is not silently normalised into a status`() {
        for (token in listOf("Active", "ACTIVE", "aCtIvE")) {
            assertEquals(
                ListingStatus.UNKNOWN,
                ListingStatusCodec.read(token, NenyaKind.OFFER),
                "§5.1 and §5.2 fix the tokens as lowercase, and §4.3's accept-and-normalise rule " +
                    "is about hex rather than about a controlled vocabulary; `$token` is a token " +
                    "this vocabulary does not name",
            )
        }
    }

    /**
     * §11.1's conflation trap, closed from the listing side. Nine of the ten order tokens are
     * simply unknown here; `cancelled` is the tenth and is the whole point.
     */
    @JsName("every_s11_1_order_token_read_here_lands_in_unknown_except_the_one_that_lands_elsewhere")
    @Test
    fun `every §11_1 order token read here lands in UNKNOWN, except the one that lands elsewhere`() {
        val orderTokens = OrderState.entries.mapNotNull { it.token }
        assertEquals(10, orderTokens.size, "§11.1 fixes ten canonical tokens")

        for (token in orderTokens) {
            val status = ListingStatusCodec.read(token, NenyaKind.REQUEST)
            if (token == ListingStatus.CANCELLED.token) {
                assertEquals(
                    ListingStatus.CANCELLED,
                    status,
                    "a cancelled listing was withdrawn from the board; a cancelled order ended " +
                        "before `paid`. The token is shared and the meaning is not.",
                )
                assertEquals(
                    OrderState.CANCELLED,
                    OrderStatusCodec.read(token),
                    "and the same token still reads as the order state in the order codec — two " +
                        "vocabularies, one token, two answers",
                )
            } else {
                assertEquals(ListingStatus.UNKNOWN, status, "`$token` is not a listing status")
                assertNotEquals(ListingStatus.ACTIVE, status)
            }
        }
    }

    @JsName("the_writer_is_held_to_the_same_per_kind_vocabulary_as_the_reader")
    @Test
    fun `the writer is held to the same per-kind vocabulary as the reader`() {
        assertEquals(listOf("status", "sold"), ListingStatusCodec.write(ListingStatus.SOLD, NenyaKind.OFFER))
        assertEquals(
            listOf("status", "fulfilled"),
            ListingStatusCodec.write(ListingStatus.FULFILLED, NenyaKind.REQUEST),
        )

        assertEquals(
            ListingRejection.STATUS_OUTSIDE_VOCABULARY,
            assertFailsWith<ListingException> {
                ListingStatusCodec.write(ListingStatus.AWARDED, NenyaKind.OFFER)
            }.reason,
            "§5.1's rule binds emission hardest: the third value is what breaks every existing " +
                "NIP-99 client",
        )
        assertEquals(
            ListingRejection.UNKNOWN_IS_NOT_EMITTABLE,
            assertFailsWith<ListingException> {
                ListingStatusCodec.write(ListingStatus.UNKNOWN, NenyaKind.REQUEST)
            }.reason,
            "§5.2's `unknown` is the treatment of somebody else's unrecognised token; emitting it " +
                "would republish their value as though this implementation had understood it",
        )
    }

    /** A status this codec wrote reads back as the status it was written from, on both kinds. */
    @JsName("write_then_read_round_trips_every_token_of_every_vocabulary")
    @Test
    fun `write then read round-trips every token of every vocabulary`() {
        for (kind in ListingFixtures.KINDS) {
            for (status in ListingStatusCodec.vocabulary(kind)) {
                val tag = ListingStatusCodec.write(status, kind)
                assertEquals("status", tag[0])
                assertEquals(status, ListingStatusCodec.read(tag[1], kind), "kind:$kind, $status")

                val listing = Listing.decode(
                    ListingFixtures.checked(ListingFixtures.with(ListingFixtures.minimal(kind), tag), kind),
                )
                assertEquals(status, listing.status)
            }
        }
    }
}
