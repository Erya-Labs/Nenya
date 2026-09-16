package dev.eryalabs.nenya.tag

import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for this task: §4.3's round-trip rule, proved the way §4.3 states its own
 * consequence.
 *
 * §4.3 says unknown tags MUST be preserved verbatim when an implementation round-trips an event it
 * did not author, and gives the reason in the same sentence — dropping one "silently strips
 * extensions **and changes the event id**". So this does not assert that a list came back equal to
 * itself. It feeds the re-published tags back through T8 and asserts the **event id is
 * unchanged**, which is the property the rule is actually about and the one a relay and a
 * counterparty will check.
 *
 * A codec that accepts everything fails `TagCodecTest`; one that rejects everything fails the
 * first assertion here. Neither can pass both.
 */
class TagPropertyTest {

    private companion object {

        /** The queue's floor for this task. */
        const val SAMPLES: Int = 10_000
    }

    private val corpus = TagFixtures.fixtures(SAMPLES)

    @Test
    fun `every generated tag set reads, re-publishes byte-identically and keeps its event id`() {
        assertEquals(SAMPLES, corpus.size)

        var unknownTagsSeen = 0
        for (fixture in corpus) {
            val original = WireEvent(
                pubkey = TagFixtures.pubkeyFor(fixture.index),
                createdAt = TagFixtures.CREATED_AT,
                kind = fixture.context.kind,
                tags = fixture.tags,
                content = fixture.content,
            )
            val checked = TagFixtures.checked(
                fixture.tags,
                fixture.context.kind,
                fixture.content,
                fixture.index,
            )
            val read = TagSet.read(checked, fixture.context)
            unknownTagsSeen += read.unknownTags.size

            val republished = read.republish()
            assertEquals(
                fixture.tags,
                republished,
                "§4.1 forbids reordering or normalising tags, and §4.3 forbids dropping unknown " +
                    "ones; fixture ${fixture.index}",
            )

            val rebuilt = WireEvent(
                pubkey = original.pubkey,
                createdAt = original.createdAt,
                kind = original.kind,
                tags = republished,
                content = original.content,
            )
            assertEquals(
                EventId.of(original),
                EventId.of(rebuilt),
                "the re-published event must hash to the id the original did; fixture ${fixture.index}",
            )

            // Read-then-write is stable under repetition too: a codec that normalised on the
            // second pass would converge on a different event than the one it was handed.
            val reread = TagSet.read(
                TagFixtures.checked(republished, fixture.context.kind, fixture.content, fixture.index),
                fixture.context,
            )
            assertEquals(republished, reread.republish(), "fixture ${fixture.index}")
        }

        assertTrue(
            unknownTagsSeen >= SAMPLES,
            "the corpus must actually carry unknown tags for the round-trip property to be about " +
                "anything; it carried $unknownTagsSeen across $SAMPLES fixtures",
        )
    }

    /**
     * The teeth of the property above, shown rather than assumed: dropping the unknown tags
     * **does** change the id. Without this, "the id was unchanged" would be a fact about a codec
     * that had nothing to drop.
     */
    @Test
    fun `dropping an unknown tag changes the event id`() {
        val fixture = corpus.first { it.tags.any { tag -> NenyaTags.byName(tag[0]) == null } }
        val pubkey = TagFixtures.pubkeyFor(fixture.index)

        val whole = WireEvent(pubkey, TagFixtures.CREATED_AT, fixture.context.kind, fixture.tags, fixture.content)
        val stripped = WireEvent(
            pubkey,
            TagFixtures.CREATED_AT,
            fixture.context.kind,
            fixture.tags.filter { NenyaTags.byName(it[0]) != null },
            fixture.content,
        )

        assertNotEquals(
            EventId.of(whole),
            EventId.of(stripped),
            "§4.3's stated consequence: dropping a tag an implementation does not understand " +
                "changes the event id",
        )
    }

    /**
     * The corpus's own coverage, asserted rather than hoped for.
     *
     * A seeded generator that quietly stopped producing `BTC` prices, or `fee` tags at zero basis
     * points, would leave the property above green over a corpus that exercised one branch ten
     * thousand times.
     */
    @Test
    fun `the corpus covers every context, both fee arities and all eight unit tokens`() {
        val tags = corpus.flatMap { it.tags }

        assertEquals(
            setOf(NenyaKind.REQUEST, NenyaKind.OFFER, NenyaKind.OFFER_DRAFT, NenyaKind.PUBLIC_BID),
            corpus.map { it.context.kind }.toSet(),
        )
        assertEquals(
            setOf("SAT", "sat", "sats", "SATS", "msat", "MSAT", "BTC", "btc"),
            tags.filter { it[0] == "price" }.map { it[2] }.toSet(),
            "§4.4's permissive-on-read list, all eight",
        )
        val feeArities = tags.filter { it[0] == "fee" }.map { it.size }.toSet()
        assertEquals(setOf(2, 3), feeArities, "§8.1's two legal arities, both generated")
        assertTrue(
            tags.any { it[0] == "image" } && tags.any { it[0] == "p" },
            "the `0–n` rows must actually repeat somewhere in the corpus",
        )
        assertEquals(
            setOf(2, 3),
            tags.filter { it[0] == "image" }.map { it.size }.toSet(),
            "§5.3 prints three elements and NIP-99 makes the dimensions optional, so both arities " +
                "must be generated — a corpus of three-element `image` tags never executes the " +
                "narrowing `ImageRef` documents",
        )
        assertTrue(
            tags.any { tag -> tag.drop(1).any { value -> value.any { it.code < 0x20 } } },
            "the corpus must carry control characters inside tag values, or the id it round-trips " +
                "through never exercised §4.1's escaping rules",
        )
    }
}
