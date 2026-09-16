package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.wire.EventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §5.3's Card. and Requirement columns applied where §5.3 says they belong, and §5.1's and §5.2's
 * own constraints on top of them.
 *
 * The required set is **derived** from T9's vocabulary — which `TagVocabularyTest` holds equal to
 * §5.3's table parsed out of the document at test time — and this file walks it, removing each
 * REQUIRED tag in turn. So a §5.3 revision that changes the Requirement column reaches this
 * decoder and this test together, and neither can go stale while the other passes.
 */
class ListingCodecTest {

    private companion object {

        /** §5.1's two offer kinds and §5.2's request, which is every kind this codec reads. */
        val LISTING_KINDS: List<Int> = ListingFixtures.KINDS
    }

    @Test
    fun `the derived required set is the one §5_3 marks MUST, and differs across the two kinds`() {
        val onRequests = Listing.requiredTags(NenyaKind.REQUEST)
        val onOffers = Listing.requiredTags(NenyaKind.OFFER)

        assertTrue(onRequests.isNotEmpty(), "the per-tag removal loop cannot iterate over nothing")
        assertEquals(setOf("d", "title", "price", "t", "nenya", "alt"), onRequests.toSet())
        assertEquals(setOf("d", "title", "price", "t", "nenya"), onOffers.toSet())
        assertEquals(
            onOffers.toSet() + "alt",
            onRequests.toSet(),
            "§5.3's one conditional row: `alt` is MUST on requests and SHOULD on offers, because " +
                "30404 is an unregistered kind and a generic client renders whatever `alt` says",
        )
        assertEquals(
            Listing.requiredTags(NenyaKind.OFFER),
            Listing.requiredTags(NenyaKind.OFFER_DRAFT),
            "§5.1: a draft is identical in structure to an offer",
        )
        assertEquals(listOf("item", "g", "location"), Listing.forbiddenTags(NenyaKind.REQUEST))
    }

    @Test
    fun `removing each REQUIRED tag in turn is refused naming that tag`() {
        for (kind in LISTING_KINDS) {
            val whole = ListingFixtures.minimal(kind)
            Listing.decode(ListingFixtures.checked(whole, kind)) // the paired positive control

            val required = Listing.requiredTags(kind)
            assertTrue(required.isNotEmpty(), "kind:$kind derived no required tags")
            for (name in required) {
                val without = ListingFixtures.without(whole, name)
                val refused = assertFailsWith<ListingException>("kind:$kind without `$name`") {
                    Listing.decode(ListingFixtures.checked(without, kind))
                }
                assertEquals(ListingRejection.MISSING_REQUIRED_TAG, refused.reason, "kind:$kind, `$name`")
                assertEquals(
                    name,
                    refused.tag,
                    "the refusal must name the tag that is missing, as data rather than as wording",
                )
            }
        }
    }

    @Test
    fun `a request with no alt is refused and an offer with no alt is accepted`() {
        val request = ListingFixtures.without(ListingFixtures.minimalRequest(), "alt")
        val refused = assertFailsWith<ListingException> {
            Listing.decode(ListingFixtures.checked(request, NenyaKind.REQUEST))
        }
        assertEquals(ListingRejection.MISSING_REQUIRED_TAG, refused.reason)
        assertEquals("alt", refused.tag)

        // §5.1 leaves `alt` a SHOULD on a NIP-99 offer, and an implementation applying one rule to
        // both kinds is wrong in one direction whichever rule it picks.
        val offer = Listing.decode(ListingFixtures.checked(ListingFixtures.minimalOffer(), NenyaKind.OFFER))
        assertNull(offer.alt)
    }

    @Test
    fun `the t row needs nenya and exactly one side token`() {
        val request = ListingFixtures.minimalRequest()

        val both = ListingFixtures.with(request, listOf("t", ListingSide.WANT_TO_SELL.token))
        assertEquals(
            ListingRejection.MISSING_TOPIC,
            assertFailsWith<ListingException> {
                Listing.decode(ListingFixtures.checked(both, NenyaKind.REQUEST))
            }.reason,
            "§5.3 requires *exactly* one of `wtb` / `wts`; a listing on both sides of the board " +
                "cannot be rendered by §5.5's filters or acted on by a counterparty",
        )

        val neither = ListingFixtures.without(request, "t") +
            listOf(listOf("t", "nenya"), listOf("t", "ai-video"))
        assertEquals(
            ListingRejection.MISSING_TOPIC,
            assertFailsWith<ListingException> {
                Listing.decode(ListingFixtures.checked(neither, NenyaKind.REQUEST))
            }.reason,
        )

        // One `t` tag is below §5.3's `≥2` cardinality, which is a different refusal from "the
        // side token is missing" and is reported as one.
        val single = ListingFixtures.without(request, "t") + listOf(listOf("t", "nenya"))
        val tooFew = assertFailsWith<ListingException> {
            Listing.decode(ListingFixtures.checked(single, NenyaKind.REQUEST))
        }
        assertEquals(ListingRejection.TOO_FEW_OCCURRENCES, tooFew.reason)
        assertEquals("t", tooFew.tag)
    }

    @Test
    fun `a listing whose side token disagrees with its kind is refused`() {
        // Legal at the tag layer — §5.3 asks only for exactly one of the pair — and a §5.2
        // violation here: `wtb` is REQUIRED on a request and `wts` on an offer.
        val request = ListingFixtures.without(ListingFixtures.minimalRequest(), "t") +
            listOf(listOf("t", "nenya"), listOf("t", ListingSide.WANT_TO_SELL.token))
        val refused = assertFailsWith<ListingException> {
            Listing.decode(ListingFixtures.checked(request, NenyaKind.REQUEST))
        }
        assertEquals(ListingRejection.SIDE_MISMATCH, refused.reason)
        assertEquals("t", refused.tag)

        val offer = ListingFixtures.without(ListingFixtures.minimalOffer(), "t") +
            listOf(listOf("t", "nenya"), listOf("t", ListingSide.WANT_TO_BUY.token))
        assertEquals(
            ListingRejection.SIDE_MISMATCH,
            assertFailsWith<ListingException> {
                Listing.decode(ListingFixtures.checked(offer, NenyaKind.OFFER))
            }.reason,
        )
    }

    @Test
    fun `an item tag on a listing is refused as a self-reference and not as an unknown tag`() {
        val tags = ListingFixtures.with(
            ListingFixtures.minimalRequest(),
            listOf("item", TagFixtures.coordinate(), "1"),
        )

        val refused = assertFailsWith<ListingException> {
            Listing.decode(ListingFixtures.checked(tags, NenyaKind.REQUEST))
        }

        assertEquals(
            ListingRejection.SELF_REFERENCE,
            refused.reason,
            "§5.3 gives the reason itself — a listing cannot reference itself — and reporting it " +
                "as a tag this codec does not understand would hide that somebody published one " +
                "that does",
        )
        assertEquals("item", refused.tag)
        assertEquals(TagRejection.FORBIDDEN_TAG, (refused.cause as TagException).reason)
    }

    /**
     * The `item` row is the one §5.3 gives a special duplicate clause — "more than one is a
     * rejection everywhere" — and the tag codec acts on it **before** the listing rules. So two
     * `item` tags arrive as a duplicate rather than as a forbidden tag, and a diagnosis that
     * looked only at single-occurrence rows would name no tag at all: the stranger who published
     * the event would be choosing whether this library could say what was wrong with it.
     */
    @Test
    fun `two item tags on a listing are still refused as a self-reference, naming the tag`() {
        val tags = ListingFixtures.minimalOffer() + List(2) {
            listOf("item", TagFixtures.coordinate(index = it), "1")
        }

        val refused = assertFailsWith<ListingException> {
            Listing.decode(ListingFixtures.checked(tags, NenyaKind.OFFER))
        }

        assertEquals(ListingRejection.SELF_REFERENCE, refused.reason)
        assertEquals("item", refused.tag)
        assertFalse(
            refused.message.orEmpty().contains("null"),
            "a refusal message must never interpolate a tag name the diagnosis did not find",
        )
    }

    @Test
    fun `g and location are refused, naming the tag`() {
        for (name in listOf("g", "location")) {
            val tags = ListingFixtures.with(ListingFixtures.minimalOffer(), listOf(name, "u4pruyd"))
            val refused = assertFailsWith<ListingException> {
                Listing.decode(ListingFixtures.checked(tags, NenyaKind.OFFER))
            }
            assertEquals(ListingRejection.FORBIDDEN_TAG, refused.reason, name)
            assertEquals(name, refused.tag)
        }
    }

    @Test
    fun `a duplicated single-occurrence tag is refused naming duplication, in both orderings`() {
        for (pair in listOf(listOf("50000", "60000"), listOf("60000", "50000"))) {
            val tags = ListingFixtures.without(ListingFixtures.minimalOffer(), "price") +
                pair.map { listOf("price", it, "SAT") }
            val refused = assertFailsWith<ListingException> {
                Listing.decode(ListingFixtures.checked(tags, NenyaKind.OFFER))
            }
            assertEquals(ListingRejection.DUPLICATE_TAG, refused.reason)
            assertEquals(
                "price",
                refused.tag,
                "§4.3 requires rejection rather than first-wins or last-wins: two conformant " +
                    "implementations would otherwise disagree about the price of one signed event",
            )
        }
    }

    @Test
    fun `an Encoding-column refusal keeps the §5_3 reason on the cause`() {
        val malformed = ListingFixtures.without(ListingFixtures.minimalOffer(), "price") +
            listOf(listOf("price", "50000", "DOGE"))
        val refused = assertFailsWith<ListingException> {
            Listing.decode(ListingFixtures.checked(malformed, NenyaKind.OFFER))
        }
        assertEquals(ListingRejection.MALFORMED_TAG, refused.reason)
        assertEquals(
            TagRejection.UNKNOWN_UNIT,
            (refused.cause as TagException).reason,
            "nothing is flattened away: a caller that wants §5.3's own reason still has it",
        )

        val dataUri = ListingFixtures.with(
            ListingFixtures.minimalOffer(),
            listOf("image", "data:image/png;base64,iVBORw0KGgo="),
        )
        assertEquals(
            TagRejection.NOT_HTTPS,
            (
                assertFailsWith<ListingException> {
                    Listing.decode(ListingFixtures.checked(dataUri, NenyaKind.OFFER))
                }.cause as TagException
                ).reason,
        )
    }

    /**
     * §4.4's third answer, carried up rather than flattened: a recurring NIP-99 price is a
     * well-formed event this version does not implement, and calling it malformed would tell the
     * user a conformant NIP-99 client is broken.
     */
    @Test
    fun `a recurring NIP-99 price is refused as unsupported and not as malformed`() {
        val recurring = ListingFixtures.without(ListingFixtures.minimalOffer(), "price") +
            listOf(listOf("price", "50000", "SAT", "monthly"))

        val refused = assertFailsWith<ListingException> {
            Listing.decode(ListingFixtures.checked(recurring, NenyaKind.OFFER))
        }

        assertEquals(ListingRejection.UNSUPPORTED, refused.reason)
        assertEquals("price", refused.tag)
        assertEquals(TagRejection.UNSUPPORTED, (refused.cause as TagException).reason)
    }

    @Test
    fun `§4_3's fifth bound is injected and enforced as reject-never-truncate`() {
        val images = List(3) { listOf("image", "https://example.invalid/$it.png") }
        val tags = ListingFixtures.minimalOffer() + images

        val accepted = Listing.decode(
            ListingFixtures.checked(tags, NenyaKind.OFFER),
            TagLimits(maxImageTags = 3),
        )
        assertEquals(3, accepted.images.size)

        val refused = assertFailsWith<ListingException> {
            Listing.decode(ListingFixtures.checked(tags, NenyaKind.OFFER), TagLimits(maxImageTags = 2))
        }
        assertEquals(ListingRejection.LIMIT_EXCEEDED, refused.reason)
        assertEquals("image", refused.tag)
        assertEquals(TagRejection.LIMIT_EXCEEDED, (refused.cause as TagException).reason)
    }

    @Test
    fun `a bid and an order rumor are not listings`() {
        for (kind in listOf(NenyaKind.PUBLIC_BID, NenyaKind.ORDER_MESSAGE, NenyaKind.CHAT)) {
            val refused = assertFailsWith<ListingException>("kind:$kind") {
                Listing.decode(ListingFixtures.checked(ListingFixtures.minimalOffer(), kind))
            }
            assertEquals(
                ListingRejection.NOT_A_LISTING_KIND,
                refused.reason,
                "§5.3's Card. and Requirement columns MUST NOT be applied to a bid or an order rumor",
            )
            assertFailsWith<ListingException> { Listing.requiredTags(kind) }
            assertFailsWith<ListingException> { Listing.forbiddenTags(kind) }
        }
    }

    @Test
    fun `a decoded listing reports §5_3's values, its coordinate and its unknown tags`() {
        val feeRecipient = TagFixtures.pubkeyFor(9)
        val tags = ListingFixtures.minimalRequest() + listOf(
            listOf("summary", "Seamless loop, 1080p, no watermark"),
            listOf("published_at", (ListingFixtures.CREATED_AT - 100).toString()),
            listOf("m", "video/mp4"),
            listOf("license", "cc-by-4.0"),
            listOf("deliver_by", (ListingFixtures.CREATED_AT + 86_400).toString()),
            listOf("fee", "250", feeRecipient),
            listOf("p", TagFixtures.pubkeyFor(3)),
            listOf("image", "https://example.invalid/reference.jpg", "1280x720"),
            listOf("x-nenya-experiment", "carried through verbatim"),
        )
        val checked = ListingFixtures.checked(tags, NenyaKind.REQUEST)

        val listing = Listing.decode(checked)

        // §5.3's two REQUIRED value-bearing rows, pinned on the *listing's* surface and not only
        // on the tag codec's: §8.2 makes `price` what the provider receives, and a decoder that
        // wired a constant onto it would pass every other test in this package.
        assertEquals(Msat.ofSat(120_000), listing.price)
        assertEquals("30s looping animation, lighthouse in a storm", listing.title)
        assertEquals(checked.id, listing.id)
        assertEquals(TagFixtures.pubkeyFor(0), listing.authorPubkey)
        assertEquals(ListingFixtures.CREATED_AT, listing.createdAt)
        assertEquals("Seamless loop, 1080p, no watermark", listing.summary)
        assertEquals(ListingFixtures.CREATED_AT - 100, listing.publishedAt)
        assertEquals(ListingFixtures.CREATED_AT + 86_400, listing.deliverBy)
        assertEquals(FeeTerm.of(250), listing.fee)
        assertEquals(feeRecipient, listing.feeRecipient)
        assertEquals(listOf(TagFixtures.pubkeyFor(3)), listing.pubkeyRefs.map { it.pubkey })
        assertEquals(
            listOf("https://example.invalid/reference.jpg"),
            listing.images.map { it.url },
        )
        assertEquals(listOf("nenya", "wtb"), listing.topics)
        assertEquals(1, listing.nenyaVersion)
        assertNull(listing.expiration, "this fixture carries no deadline for §5.6 to evaluate")

        assertEquals(NenyaKind.REQUEST, listing.kind)
        assertEquals(ListingSide.WANT_TO_BUY, listing.side)
        assertEquals(ListingStatus.ACTIVE, listing.status, "§5.3: an absent `status` means active")
        assertNull(listing.statusToken)
        assertEquals("lighthouse-loop-2026-09", listing.dValue)
        assertEquals(TagFixtures.pubkeyFor(0), listing.coordinate.pubkey)
        assertEquals(NenyaKind.REQUEST, listing.coordinate.kind)
        assertEquals(
            "${NenyaKind.REQUEST}:${TagFixtures.pubkeyFor(0)}:lighthouse-loop-2026-09",
            listing.coordinate.toTagValue(),
            "§4.2: everything that refers to a listing refers to it by coordinate, never by id",
        )
        assertEquals("video/mp4", listing.mimeType)
        assertEquals("cc-by-4.0", listing.license)
        assertEquals(listOf(listOf("x-nenya-experiment", "carried through verbatim")), listing.unknownTags)
        assertNotNull(listing.alt)
    }

    @Test
    fun `decode then encode reproduces the identical event, unknown tags and tag order included`() {
        val tags = ListingFixtures.minimalRequest() + listOf(
            listOf("x-nenya-experiment", "an extension this codec does not know"),
            listOf("image", "https://example.invalid/reference.jpg", "1280x720"),
            listOf("expiration", (ListingFixtures.CREATED_AT + 1000).toString()),
        )
        val content = "Looking for a 30-second looping animation.\nStyle reference attached."
        val checked = ListingFixtures.checked(tags, NenyaKind.REQUEST, content)

        val encoded = Listing.decode(checked).encode()

        assertEquals(checked.event.tags, encoded.tags, "§4.1 forbids reordering or normalising tags")
        assertEquals(checked.event.content, encoded.content)
        assertEquals(checked.event.pubkey, encoded.pubkey)
        assertEquals(checked.event.createdAt, encoded.createdAt)
        assertEquals(
            checked.id,
            EventId.of(encoded),
            "§4.3 states the consequence itself: dropping a tag an implementation does not " +
                "understand changes the event id",
        )
    }

    /**
     * §12 and STOP RULE 14 as every other package in this library pins them: a rejection message
     * may name a tag, a count and a rule, and the string form of a value may name no value at all.
     * A listing is public, so this is convention rather than a leak — but the type that holds a
     * coordinate and a counterparty pubkey is the one somebody later pastes into a log, and the
     * seam, wire and delivery packages each carry this control.
     */
    @Test
    fun `nothing prints the publisher's values`() {
        val dValue = "a-d-value-nobody-should-see"
        val tags = ListingFixtures.without(ListingFixtures.minimalRequest(), "d") +
            listOf(listOf("d", dValue))
        val listing = Listing.decode(ListingFixtures.checked(tags, NenyaKind.REQUEST))

        for (printed in listOf(listing.toString(), listing.coordinate.toString())) {
            assertFalse(printed.contains(dValue), printed)
            assertFalse(printed.contains(TagFixtures.pubkeyFor(0)), printed)
            assertFalse(printed.contains(listing.title), printed)
        }

        val refused = assertFailsWith<ListingException> {
            Listing.decode(
                ListingFixtures.checked(
                    ListingFixtures.with(tags, listOf("g", "u4pruydqqvj")),
                    NenyaKind.REQUEST,
                ),
            )
        }
        val message = refused.message.orEmpty()
        assertFalse(message.contains(dValue), message)
        assertFalse(message.contains("u4pruydqqvj"), message)
        assertFalse(message.contains(TagFixtures.pubkeyFor(0)), message)
    }

    /**
     * §5.3 makes `d` opaque and requires only that it be non-empty, so a colon inside one is
     * ordinary — and a coordinate built by splitting without a limit would silently address a
     * different event.
     */
    @Test
    fun `a d value containing a colon survives into the coordinate`() {
        val dValue = "batch:2026-09:lighthouse"
        val tags = ListingFixtures.without(ListingFixtures.minimalOffer(), "d") + listOf(listOf("d", dValue))

        val listing = Listing.decode(ListingFixtures.checked(tags, NenyaKind.OFFER))

        assertEquals(dValue, listing.dValue)
        assertEquals(dValue, listing.coordinate.dValue)
    }
}
