package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.wire.EventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for this task: every generated listing decodes and re-encodes to the
 * **identical event id**.
 *
 * Asserting that a tag list came back equal to itself would prove nothing about the rule §4.3
 * actually states. §4.3 gives the consequence — dropping a tag an implementation does not
 * understand "silently strips extensions **and changes the event id**" — so the property is
 * asserted through T8's recomputation, over ten thousand listings carrying unknown tags, control
 * characters and non-BMP characters. A codec that dropped or reordered one fails at scale rather
 * than on the single fixture somebody remembered to write.
 *
 * A codec that rejected everything fails the first assertion here; one that accepted everything
 * fails `ListingCodecTest` and `ListingStatusTest`. Neither can pass both.
 */
class ListingPropertyTest {

    private companion object {

        /** The queue's floor for this task. */
        const val SAMPLES: Int = 10_000
    }

    private val corpus = ListingFixtures.listings(SAMPLES)

    @Test
    fun `every generated listing decodes and re-encodes to the identical event id`() {
        assertEquals(SAMPLES, corpus.size)

        var unknownTagsSeen = 0
        for (fixture in corpus) {
            val checked = ListingFixtures.checked(
                fixture.tags,
                fixture.kind,
                fixture.content,
                fixture.index,
            )
            val listing = Listing.decode(checked)
            unknownTagsSeen += listing.unknownTags.size

            val encoded = listing.encode()
            assertEquals(
                fixture.tags,
                encoded.tags,
                "§4.1 forbids reordering or normalising tags and §4.3 forbids dropping unknown " +
                    "ones; fixture ${fixture.index}",
            )
            assertEquals(
                checked.id,
                EventId.of(encoded),
                "the re-encoded listing must hash to the id the original did; fixture ${fixture.index}",
            )

            // Decode-then-encode is stable under repetition: a codec that normalised on the second
            // pass would converge on a different event than the one it was handed.
            val again = Listing.decode(
                ListingFixtures.checked(encoded.tags, fixture.kind, fixture.content, fixture.index),
            )
            assertEquals(encoded.tags, again.encode().tags, "fixture ${fixture.index}")
        }

        assertTrue(
            unknownTagsSeen >= SAMPLES,
            "the corpus must actually carry unknown tags for the id property to be about " +
                "anything; it carried $unknownTagsSeen across $SAMPLES listings",
        )
    }

    /**
     * The teeth of the property above, shown rather than assumed: dropping the unknown tags **does**
     * change the id. Without this, "the id was unchanged" would be a fact about a codec that had
     * nothing to drop.
     */
    @Test
    fun `dropping an unknown tag changes the event id`() {
        val fixture = corpus.first { it.tags.any { tag -> NenyaTags.byName(tag[0]) == null } }
        val whole = ListingFixtures.checked(fixture.tags, fixture.kind, fixture.content, fixture.index)
        val stripped = ListingFixtures.checked(
            fixture.tags.filter { NenyaTags.byName(it[0]) != null },
            fixture.kind,
            fixture.content,
            fixture.index,
        )

        assertNotEquals(whole.id, stripped.id, "§4.3's own stated consequence")
    }

    /**
     * The corpus's own coverage, asserted rather than hoped for. A seeded generator that quietly
     * stopped producing requests, or `status` tags, or `expiration` tags, would leave every
     * property above green over a corpus that exercised one branch ten thousand times.
     */
    @Test
    fun `the corpus covers both listing sides, every status case and §5_6's deadline`() {
        val decoded = corpus.map { ListingFixtures.decode(it) }
        val tags = corpus.flatMap { it.tags }

        assertEquals(ListingFixtures.KINDS.toSet(), decoded.map { it.kind }.toSet())
        assertEquals(
            setOf(ListingSide.WANT_TO_BUY, ListingSide.WANT_TO_SELL),
            decoded.map { it.side }.toSet(),
        )
        assertEquals(
            ListingStatus.entries.toSet(),
            decoded.map { it.status }.toSet(),
            "every constant of the vocabulary, including UNKNOWN for §5.2's unrecognised token " +
                "and ACTIVE for the absent tag",
        )
        assertTrue(
            decoded.any { it.statusToken == null } && decoded.any { it.statusToken != null },
            "both the absent and the present `status` cases must be generated",
        )
        assertTrue(
            decoded.count { it.expiration != null } > SAMPLES / 4,
            "§5.6's rule needs a deadline to evaluate; the corpus carried " +
                "${decoded.count { it.expiration != null }}",
        )
        assertEquals(
            setOf("SAT", "sat", "sats", "SATS", "msat", "MSAT", "BTC", "btc"),
            tags.filter { it[0] == "price" }.map { it[2] }.toSet(),
            "§4.4's permissive-on-read list, all eight",
        )
        assertEquals(setOf(2, 3), tags.filter { it[0] == "fee" }.map { it.size }.toSet())
        assertEquals(setOf(2, 3), tags.filter { it[0] == "image" }.map { it.size }.toSet())
        assertTrue(
            tags.any { tag -> tag.drop(1).any { value -> value.any { it.code < 0x20 } } },
            "the corpus must carry control characters inside tag values, or the id it round-trips " +
                "through never exercised §4.1's escaping rules",
        )
    }

    /**
     * §5.6 over the whole corpus: every listing carrying a deadline answers `expired` after it and
     * `active` before it, and **none** of them answers `active` when the clock is silent.
     */
    @Test
    fun `every deadline in the corpus is evaluated against the injected clock and never without one`() {
        var withDeadline = 0
        for (fixture in corpus) {
            val listing = ListingFixtures.decode(fixture)
            val deadline = listing.expiration ?: continue
            withDeadline++

            assertEquals(
                ListingActivity.EXPIRED,
                listing.activity(FakeClock(deadline + 1)),
                "fixture ${fixture.index}",
            )
            assertEquals(
                ListingActivity.ACTIVE,
                listing.activity(FakeClock(deadline - 1)),
                "fixture ${fixture.index}",
            )
            assertEquals(
                ListingActivity.CANNOT_SAY,
                listing.activity(NenyaClock.FAIL_CLOSED),
                "fixture ${fixture.index}",
            )
        }
        assertTrue(withDeadline > 0, "no fixture carried an `expiration`, so this proved nothing")
    }
}
