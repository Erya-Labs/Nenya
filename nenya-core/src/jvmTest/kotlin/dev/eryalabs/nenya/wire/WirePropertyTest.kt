package dev.eryalabs.nenya.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The non-vacuity floor: ten thousand seeded events, each round-tripped through an **independent**
 * JSON string reader ([CanonicalReader]) and checked against three properties a broken serialiser
 * fails.
 *
 * A serialiser is only ever wrong on the characters nobody thought about, so the corpus is built
 * from the characters §4.1's MUST NOTs are written about — every control character, the quote,
 * the backslash, `/`, DEL and two non-BMP code points — and the coverage is asserted rather than
 * hoped for.
 */
class WirePropertyTest {

    private companion object {

        const val SAMPLES: Int = 10_000

        val corpus: List<WireEvent> by lazy { WireFixtures.events(SAMPLES) }
    }

    @Test
    fun `every event round-trips through an independently written unescaper`() {
        var checked = 0
        for (event in corpus) {
            val parsed = CanonicalReader.read(event.canonicalSerialisation())

            assertEquals(event.pubkey, parsed.pubkey, "pubkey did not survive")
            assertEquals(event.createdAt, parsed.createdAt, "created_at did not survive")
            assertEquals(event.kind.toLong(), parsed.kind, "kind did not survive")
            assertEquals(event.tags, parsed.tags, "a tag did not survive")
            assertEquals(event.content, parsed.content, "content did not survive")
            checked++
        }
        assertEquals(SAMPLES, checked, "the corpus must actually have been walked")
    }

    @Test
    fun `no serialisation carries a byte below 0x20`() {
        for (event in corpus) {
            val bytes = event.canonicalSerialisation().toByteArray(Charsets.UTF_8)
            val offending = bytes.indexOfFirst { (it.toInt() and 0xff) < 0x20 }

            assertEquals(
                -1,
                offending,
                "RFC 8259 forbids a raw control character inside a JSON string, and emitting one is " +
                    "the fatal property of NIP-01's literal reading that §4.1 exists to avoid: the " +
                    "relay's parser rejects the event, and every other implementation computes a " +
                    "different id for it",
            )
        }
    }

    @Test
    fun `every backslash begins one of exactly the eight legal escape forms`() {
        val legal = Section41.shortcutEscapes().keys + "\\u"
        assertEquals(8, legal.size, "seven shortcuts plus the four-hex-digit form")

        val observed = mutableSetOf<String>()
        for (event in corpus) {
            observed += CanonicalReader.read(event.canonicalSerialisation()).escapeForms
        }

        assertEquals(
            legal,
            observed,
            "a set equality in both directions: an escape form outside §4.1's eight — an escaped " +
                "`/` above all — fails it, and so does a corpus that never exercised one of them, " +
                "which would make this assertion an opinion rather than a measurement",
        )
    }

    @Test
    fun `no serialisation carries whitespace outside a string value`() {
        for (event in corpus) {
            val parsed = CanonicalReader.read(event.canonicalSerialisation())

            assertEquals(
                0,
                parsed.whitespaceOutsideStrings,
                "§4.1: no insignificant whitespace anywhere. RFC 8259 permits it between structural " +
                    "tokens, so only a reader that knows where the strings end can tell the two " +
                    "apart — which is why the oracle counts it rather than rejecting it.",
            )
        }
    }

    @Test
    fun `distinct events produce distinct ids`() {
        val ids = mutableSetOf<String>()
        for (event in corpus) ids += EventId.of(event).toHex()

        assertEquals(
            SAMPLES,
            ids.size,
            "a serialiser that emitted a constant satisfies every property above and fails this one",
        )
    }

    /**
     * The charset rule at corpus scale. `EventIdTest` pins it on one hand-built non-BMP fixture,
     * and a mutation pass showed that fixture was the **only** thing in the suite that caught a
     * digest taken over ISO-8859-1 bytes — every other id assertion used ASCII, where the two
     * encodings agree. Ten thousand events, dozens of them carrying an emoji, is the floor that
     * does not depend on one test remembering.
     */
    @Test
    fun `every id is the SHA-256 of the serialisation's UTF-8 bytes, recomputed here`() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (event in corpus) {
            val bytes = event.canonicalSerialisation().toByteArray(Charsets.UTF_8)

            assertEquals(WireFixtures.lowerHex(digest.digest(bytes)), EventId.of(event).toHex())
        }
    }

    @Test
    fun `an id survives a round trip through its own hex, for every event in the corpus`() {
        for (event in corpus) {
            val id = EventId.of(event)

            assertEquals(id, EventId.ofHex(id.toHex()))
            assertEquals(id, CheckedEvent.checkEventId(id.toHex().uppercase(), event).id)
        }
    }

    /**
     * The corpus's own control. Every property above is trivially true of a corpus of ordinary
     * ASCII, and a generator that quietly produced one would make this whole file green and
     * meaningless.
     */
    @Test
    fun `the corpus actually exercises every character the escaping rules are about`() {
        val everything = corpus.joinToString("") { event ->
            event.content + event.tags.flatten().joinToString("")
        }

        for (code in 0x00..0x1F) {
            assertTrue(
                everything.contains(code.toChar()),
                "no event in the corpus carries 0x${code.toString(16)}, so rule 1 or rule 2 went " +
                    "unexercised for it",
            )
        }
        for (code in listOf(0x22, 0x5C, 0x2F, 0x7F)) {
            assertTrue(everything.contains(code.toChar()), "the corpus carries no 0x${code.toString(16)}")
        }
        assertTrue(
            everything.contains(String(Character.toChars(0x1F600))),
            "the corpus carries no non-BMP character, so the UTF-8 rule went unexercised",
        )
        for (text in listOf("é", "中")) {
            assertTrue(
                everything.contains(text),
                "the corpus carries no non-ASCII BMP character, which §4.1's MUST NOT sentence " +
                    "names first and which an ensure_ascii-style serialiser escapes",
            )
        }
        assertTrue(corpus.any { it.content.isEmpty() }, "the corpus carries no empty content")
        assertTrue(corpus.any { it.tags.isEmpty() }, "the corpus carries no event without tags")
        assertTrue(
            corpus.any { event -> event.tags.any { tag -> tag.any { it.isEmpty() } } },
            "the corpus carries no empty tag value",
        )
        assertTrue(
            corpus.any { it.tags.size >= 3 },
            "the corpus carries no event with enough tags to catch a reordering",
        )
    }
}
