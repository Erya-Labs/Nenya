package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.wire.EventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for this task: every generated bid decodes and re-encodes to the
 * **identical event id**.
 *
 * Asserting that a tag list came back equal to itself would prove nothing about the rule §4.3
 * actually states. §4.3 gives the consequence — dropping a tag an implementation does not
 * understand "silently strips extensions **and changes the event id**" — so the property is
 * asserted through T8's recomputation, over ten thousand bids carrying unknown tags, control
 * characters and non-BMP characters. A codec that dropped or reordered one fails at scale rather
 * than on the single fixture somebody remembered to write.
 *
 * A codec that rejected everything fails the first assertion here; one that accepted everything
 * fails `BidCodecTest`. Neither can pass both.
 */
class BidPropertyTest {

    private companion object {

        /** The queue's floor for this task. */
        const val SAMPLES: Int = 10_000
    }

    private val corpus = BidFixtures.bids(SAMPLES)

    @Test
    fun `every generated bid decodes and re-encodes to the identical event id`() {
        assertEquals(SAMPLES, corpus.size)

        var unknownTagsSeen = 0
        for (fixture in corpus) {
            val checked = BidFixtures.checked(fixture.tags, fixture.content, fixture.index)
            val bid = Bid.decode(checked)
            unknownTagsSeen += bid.unknownTags.size

            val encoded = bid.encode()
            assertEquals(
                fixture.tags,
                encoded.tags,
                "§4.1 forbids reordering or normalising tags and §4.3 forbids dropping unknown " +
                    "ones; fixture ${fixture.index}",
            )
            assertEquals(
                checked.id,
                EventId.of(encoded),
                "the re-encoded bid must hash to the id the original did; fixture ${fixture.index}",
            )

            // Decode-then-encode is stable under repetition: a codec that normalised on the second
            // pass would converge on a different event than the one it was handed.
            val again = Bid.decode(
                BidFixtures.checked(encoded.tags, fixture.content, fixture.index),
            )
            assertEquals(encoded.tags, again.encode().tags, "fixture ${fixture.index}")
        }

        assertTrue(
            unknownTagsSeen >= SAMPLES,
            "the corpus must actually carry unknown tags for the id property to be about " +
                "anything; it carried $unknownTagsSeen across $SAMPLES bids",
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
        val whole = BidFixtures.checked(fixture.tags, fixture.content, fixture.index)
        val stripped = BidFixtures.checked(
            fixture.tags.filter { NenyaTags.byName(it[0]) != null },
            fixture.content,
            fixture.index,
        )

        assertNotEquals(whole.id, stripped.id, "§4.3's own stated consequence")
    }

    /**
     * Every scope tag in the corpus agrees with the coordinate it names, across ten thousand bids
     * whose pubkeys are spelled in both cases (§4.3) and whose `d` values contain colons (§4.2).
     *
     * A codec that compared coordinate **strings** rather than parsed coordinates fails on the
     * re-cased half; one that split the coordinate without a limit of three fails on the colons.
     */
    @Test
    fun `every bid in the corpus resolves to the listing its scope tags name`() {
        for (fixture in corpus) {
            val bid = BidFixtures.decode(fixture)
            assertEquals(fixture.listingKind, bid.listingKind, "fixture ${fixture.index}")
            assertEquals(
                bid.listing.pubkey,
                bid.listingAuthorPubkey,
                "fixture ${fixture.index}",
            )
            assertEquals(
                bid.listing.pubkey,
                bid.pubkeyRefs.single().pubkey,
                "§6: `p` names the listing author; fixture ${fixture.index}",
            )
            assertNotEquals(
                bid.bidderPubkey,
                bid.listingAuthorPubkey,
                "the corpus must keep the two parties distinct, or a codec confusing them passes",
            )
        }
    }

    /**
     * The corpus's own coverage, asserted rather than hoped for. A seeded generator that quietly
     * stopped producing open-ended bids, or colons in `d`, or re-cased pubkeys, would leave every
     * property above green over a corpus that exercised one branch ten thousand times.
     */
    @Test
    fun `the corpus covers all three listing kinds, both fee arities and §6's open-ended bid`() {
        val decoded = corpus.map { BidFixtures.decode(it) }
        val tags = corpus.flatMap { it.tags }

        assertEquals(BidFixtures.LISTING_KINDS.toSet(), decoded.map { it.listingKind }.toSet())
        assertTrue(
            decoded.count { it.openEnded } > SAMPLES / 10,
            "§6 makes `expiration` a SHOULD, so the open-ended shape must be well represented; " +
                "the corpus carried ${decoded.count { it.openEnded }}",
        )
        assertTrue(
            decoded.count { !it.openEnded } > SAMPLES / 10,
            "and the bounded shape too, or the flag is never anything but true",
        )
        assertTrue(
            decoded.any { it.fee == FeeTerm.Absent } && decoded.any { it.fee != FeeTerm.Absent },
            "§8.1's `Absent` and a stated term are different values and both must appear",
        )
        assertEquals(setOf(2, 3), tags.filter { it[0] == "fee" }.map { it.size }.toSet())
        assertEquals(
            setOf(2, 3),
            tags.filter { it[0] == "A" }.map { it.size }.toSet(),
            "NIP-22's relay hint is optional, so both arities of a scope tag must be generated",
        )
        assertEquals(
            setOf("SAT", "sat", "sats", "SATS", "msat", "MSAT", "BTC", "btc"),
            tags.filter { it[0] == "price" }.map { it[2] }.toSet(),
            "§4.4's permissive-on-read list, all eight",
        )
        assertTrue(
            decoded.count { ':' in it.listing.dValue } > 0,
            "§5.3 makes `d` opaque, so a colon inside one is legal and §4.2's split has a limit " +
                "for exactly that; the corpus carried ${decoded.count { ':' in it.listing.dValue }}",
        )
        val recased = corpus.count { fixture ->
            val root = fixture.tags.first { it[0] == "A" }[1]
            val parent = fixture.tags.first { it[0] == "a" }[1]
            root != parent && root.lowercase() == parent.lowercase()
        }
        assertTrue(
            recased > 0,
            "§4.3's accept-and-normalise rule needs the same coordinate spelled two ways, or a " +
                "codec comparing scope tags as raw strings passes; the corpus carried $recased",
        )
        assertTrue(
            tags.any { tag -> tag.drop(1).any { value -> value.any { it.code < 0x20 } } },
            "the corpus must carry control characters inside tag values, or the id it round-trips " +
                "through never exercised §4.1's escaping rules",
        )
    }
}
