package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.wire.EventId
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for this task: every generated rumor attributes and re-encodes to the
 * **identical event id**.
 *
 * Asserting that a tag list came back equal to itself would prove nothing about the rule §4.3
 * actually states. §4.3 gives the consequence — dropping a tag an implementation does not
 * understand "silently strips extensions **and changes the event id**" — so the property is
 * asserted through T8's recomputation, over ten thousand rumors carrying unknown tags, `subject`
 * tags, control characters and non-BMP characters, across all four rumor kinds and all six `type`
 * values. A codec that dropped or reordered one fails at scale rather than on the single fixture
 * somebody remembered to write.
 *
 * A codec that rejected everything fails the first assertion here; one that accepted everything
 * fails `ChannelCodecTest`. Neither can pass both.
 */
class ChannelPropertyTest {

    private companion object {

        /** The queue's floor for this task. */
        const val SAMPLES: Int = 10_000
    }

    private val corpus = ChannelFixtures.rumors(SAMPLES)

    @JsName("every_generated_rumor_attributes_and_re_encodes_to_the_identical_event_id")
    @Test
    fun `every generated rumor attributes and re-encodes to the identical event id`() {
        assertEquals(SAMPLES, corpus.size)

        var unknownTagsSeen = 0
        for (fixture in corpus) {
            val checked = ChannelFixtures.checked(
                fixture.rumorPubkey,
                fixture.shape.kind,
                fixture.tags,
                fixture.content,
            )
            val rumor = AttributedRumor.attribute(fixture.sealPubkey, checked)
            unknownTagsSeen += rumor.unknownTags.size

            val encoded = rumor.encode()
            assertEquals(
                fixture.tags,
                encoded.tags,
                "§4.1 forbids reordering or normalising tags and §4.3 forbids dropping unknown " +
                    "ones; fixture ${fixture.index}",
            )
            assertEquals(
                fixture.rumorPubkey,
                encoded.pubkey,
                "§4.1's id is computed over the bytes the rumor carried, so the pubkey is " +
                    "re-emitted in its own spelling; fixture ${fixture.index}",
            )
            assertEquals(
                checked.id,
                EventId.of(encoded),
                "the re-encoded rumor must hash to the id the original did; fixture ${fixture.index}",
            )

            // Decode-then-encode is stable under repetition: a codec that normalised on the second
            // pass would converge on a different event than the one it was handed.
            val again = AttributedRumor.attribute(
                fixture.sealPubkey,
                ChannelFixtures.checked(
                    encoded.pubkey,
                    fixture.shape.kind,
                    encoded.tags,
                    fixture.content,
                ),
            )
            assertEquals(encoded.tags, again.encode().tags, "fixture ${fixture.index}")
        }

        assertTrue(
            unknownTagsSeen >= SAMPLES,
            "the corpus must actually carry unknown tags for the id property to be about " +
                "anything; it carried $unknownTagsSeen across $SAMPLES rumors",
        )
    }

    /**
     * The teeth of the property above, shown rather than assumed: dropping the unknown tags **does**
     * change the id. Without this, "the id was unchanged" would be a fact about a codec that had
     * nothing to drop.
     */
    @JsName("dropping_an_unknown_tag_changes_the_event_id")
    @Test
    fun `dropping an unknown tag changes the event id`() {
        val fixture = corpus.first { it.tags.any { tag -> NenyaTags.byName(tag[0]) == null } }
        val whole = ChannelFixtures.checked(
            fixture.rumorPubkey,
            fixture.shape.kind,
            fixture.tags,
            fixture.content,
        )
        val stripped = ChannelFixtures.checked(
            fixture.rumorPubkey,
            fixture.shape.kind,
            fixture.tags.filter { NenyaTags.byName(it[0]) != null },
            fixture.content,
        )

        assertNotEquals(whole.id, stripped.id, "§4.3's own stated consequence")
    }

    /**
     * §7.2 across the whole corpus: every rumor is attributed to the seal's key, in §4.3's
     * canonical lowercase, whichever of the two spellings either side arrived in.
     */
    @JsName("every_rumor_in_the_corpus_is_attributed_to_the_seal_s_key")
    @Test
    fun `every rumor in the corpus is attributed to the seal's key`() {
        for (fixture in corpus) {
            val rumor = ChannelFixtures.attribute(fixture)

            assertEquals(
                fixture.sealPubkey.lowercase(),
                rumor.author,
                "§7.2 attributes to the seal's pubkey; fixture ${fixture.index}",
            )
            assertEquals(Attribution.AUTHENTICATED_BY_DECRYPTION, rumor.attribution)
            assertEquals(AttributedRumor.NOT_PERFORMED_HERE, rumor.notPerformedHere)
        }
    }

    /**
     * The corpus's own coverage, asserted rather than hoped for. A seeded generator that quietly
     * stopped producing the reserved type, or the private bid, or a re-cased order id, would leave
     * every property above green over a corpus that exercised one branch ten thousand times.
     */
    @JsName("the_corpus_covers_all_four_rumor_kinds_and_all_six_type_values")
    @Test
    fun `the corpus covers all four rumor kinds and all six type values`() {
        val decoded = corpus.map { ChannelFixtures.attribute(it) }
        val tags = corpus.flatMap { it.tags }

        assertEquals(
            RumorKind.entries.toSet(),
            decoded.map { it.kind }.toSet(),
            "all four of §7.4's rumor kinds must be in the corpus",
        )
        for (kind in RumorKind.entries) {
            assertTrue(decoded.count { it.kind == kind } > 0, "the corpus carried no $kind")
        }
        val types = decoded.filterIsInstance<AttributedRumor.Bound>().mapNotNull { it.type }
        assertEquals(
            OrderMessageKind.entries.filter { it.type != null }.toSet(),
            types.toSet(),
            "all six of §7.4's `type` values must be in the corpus, the reserved one included",
        )
        for (message in OrderMessageKind.entries.filter { it.type != null }) {
            assertTrue(types.count { it == message } > 0, "the corpus carried no $message")
        }

        // §7.4's exception, both ways round, across the corpus.
        val bids = decoded.filterIsInstance<AttributedRumor.Bound>()
            .filter { it.type == OrderMessageKind.PRIVATE_BID }
        assertTrue(bids.isNotEmpty())
        assertTrue(bids.all { it.order == null }, "§7.4: `type=6` carries no order id")
        val bound = decoded.filterIsInstance<AttributedRumor.Bound>()
            .filter { it.type != OrderMessageKind.PRIVATE_BID }
        assertTrue(bound.isNotEmpty() && bound.all { it.order != null })

        // §7.4 puts kind:14 outside the rule, so both halves must be generated.
        val chats = decoded.filterIsInstance<AttributedRumor.Chat>()
        assertTrue(chats.any { rumor -> rumor.unknownTags.any { it[0] == ChannelTags.ORDER } })
        assertTrue(chats.any { rumor -> rumor.unknownTags.none { it[0] == ChannelTags.ORDER } })

        // §4.3's accept-and-normalise rule needs the same value spelled two ways, or a codec
        // comparing raw strings passes.
        val recasedKeys = corpus.count { it.sealPubkey != it.rumorPubkey }
        assertTrue(recasedKeys > 0, "§7.2's comparison must meet both spellings; it met $recasedKeys")
        val recasedOrders = tags.count { it[0] == ChannelTags.ORDER && it[1].any { c -> c in 'A'..'F' } }
        assertTrue(recasedOrders > 0, "§7.4's `order` read must meet an uppercase id; it met $recasedOrders")

        assertEquals(
            setOf(2, 3),
            tags.filter { it[0] == "fee" }.map { it.size }.toSet(),
            "§8.1's two arities, both generated",
        )
        assertTrue(
            tags.any { it[0] == "subject" },
            "§7.4's `subject` is display metadata that must round-trip; the corpus carried none",
        )
        assertTrue(
            tags.any { tag -> tag.drop(1).any { value -> value.any { it.code < 0x20 } } },
            "the corpus must carry control characters inside tag values, or the id it round-trips " +
                "through never exercised §4.1's escaping rules",
        )
    }

    /**
     * Both §7.4 parses and both modelled sets are non-empty, asserted here as well as in
     * `RumorVocabularyTest`, so "empty equals empty" cannot pass in the file that consumes them.
     */
    @JsName("the_s7_4_parses_and_the_modelled_sets_are_non_empty")
    @Test
    fun `the §7-4 parses and the modelled sets are non-empty`() {
        assertTrue(Section74.rumorKinds().isNotEmpty())
        assertTrue(Section74.types().isNotEmpty())
        assertTrue(Section74.requiredTagNames.isNotEmpty())
        assertTrue(RumorKind.entries.isNotEmpty())
        assertTrue(OrderMessageKind.entries.mapNotNull { it.type }.isNotEmpty())
        assertTrue(ChannelFixtures.SHAPES.count { it.kind == NenyaKind.ORDER_MESSAGE } == Section74.types().size)
    }
}
