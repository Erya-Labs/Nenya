package dev.eryalabs.nenya.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §4.1's three emission rules, checked against §4.1 itself rather than against an implementer's
 * reading of NIP-01.
 *
 * The escape table is **parsed out of the specification at test time** ([Section41]) and compared
 * against what the serialiser actually emits, discovered by probing it one character at a time.
 * The comparison is a **set equality in both directions**, which is the point: containment would
 * pass over a serialiser that emitted an eighth shortcut of its own — say `\/`, which is legal
 * JSON and which §4.1 forbids by name — and containment the other way would pass over one that
 * dropped a shortcut in favour of the six-character form.
 */
class CanonicalSerialisationTest {

    private companion object {

        /**
         * Every control character, the two characters at or above `0x20` that still have to be
         * escaped (`"` and `\`), and the two §4.1 forbids escaping by name — `/` and `0x7f`.
         *
         * The last two are here so the set equality below catches an **eighth** shortcut of the
         * serialiser's own rather than only a missing one. A mutation pass confirmed it: escaping
         * `/` is the shortcut a JSON library adds for free, and with the probe stopping at `\`
         * only the ten-thousand-event corpus noticed.
         */
        val PROBED_CODE_POINTS: List<Int> = (0x00..0x1F).toList() + listOf(0x22, 0x5C, 0x2F, 0x7F)

        /** The raw body of `content` after the serialiser has escaped one probe character. */
        fun rawContentFor(code: Int): String {
            val event = WireFixtures.eventWith(tags = emptyList(), content = code.toChar().toString())
            return CanonicalReader.read(event.canonicalSerialisation()).rawContent
        }

        fun lowerHexDigits(code: Int): String {
            val digits = "0123456789abcdef"
            return "${digits[code ushr 4]}${digits[code and 0x0f]}"
        }
    }

    @Test
    fun `the seven shortcut escapes are exactly the seven the specification names`() {
        val published = Section41.shortcutEscapes()

        val emitted = mutableMapOf<String, Int>()
        for (code in PROBED_CODE_POINTS) {
            val raw = rawContentFor(code)
            if (raw.length == 2 && raw[0] == '\\') emitted[raw] = code
        }

        assertEquals(
            published,
            emitted,
            "§4.1 rule 1 fixes seven shortcut escapes and says \"only these seven\". This is a set " +
                "equality in both directions on purpose: an eighth of the serialiser's own — an " +
                "escaped `/`, say — fails it just as a missing one does.",
        )
        assertTrue(published.isNotEmpty(), "the parse must not be empty, or this equality is vacuous")
    }

    @Test
    fun `every other control character is the four-hex-digit form, with lowercase digits`() {
        val shortcutCodes = Section41.shortcutEscapes().values.toSet()
        val others = (0x00..0x1F).filter { it !in shortcutCodes }

        assertEquals(
            27,
            others.size,
            "five of §4.1's seven shortcuts are below 0x20, so 27 of the 32 control characters fall " +
                "to rule 2; the parse implies ${others.size}",
        )
        for (code in others) {
            assertEquals(
                "\\u00" + lowerHexDigits(code),
                rawContentFor(code),
                "§4.1 rule 2: every other character below 0x20 is emitted as a four-hex-digit escape " +
                    "with lowercase digits. The expected digits here are derived from the code point " +
                    "in the test rather than transcribed.",
            )
        }
    }

    @Test
    fun `the six elements are the ones the template names, in that order`() {
        val template = Section41.templateElements()
        val event = WireFixtures.eventWith(
            tags = listOf(listOf("t", "nenya")),
            content = "a request for a thing",
        )

        val parsed = CanonicalReader.read(event.canonicalSerialisation())

        val expectedKinds = template.mapIndexed { index, element ->
            when {
                index == 0 -> CanonicalReader.Kind.NUMBER
                element.contains("number") -> CanonicalReader.Kind.NUMBER
                element.contains("tags") -> CanonicalReader.Kind.ARRAY
                else -> CanonicalReader.Kind.STRING
            }
        }
        assertEquals(
            expectedKinds,
            parsed.elementKinds,
            "the shape of each element is derived from §4.1's own template line $template, so a " +
                "quoted created_at or a reordered field is caught by the document rather than by " +
                "the implementer's memory",
        )
        assertEquals(0L, parsed.prefix, "§4.1's first element is the literal 0")
        assertEquals(event.pubkey, parsed.pubkey)
        assertEquals(event.createdAt, parsed.createdAt)
        assertEquals(event.kind.toLong(), parsed.kind)
        assertEquals(event.content, parsed.content)
        assertEquals(
            listOf("0", event.createdAt.toString(), event.kind.toString()),
            parsed.numberTokens,
            "the three numbers are bare JSON numbers — never quoted, never padded, never signed",
        )
    }

    @Test
    fun `a solidus is emitted verbatim and never escaped`() {
        val event = WireFixtures.eventWith(tags = listOf(listOf("r", "https://relay.example.invalid")))

        val serialisation = event.canonicalSerialisation()

        assertFalse(
            serialisation.contains("\\/"),
            "§4.1: an implementation MUST NOT escape `/`. It is legal JSON and it changes the id.",
        )
        assertTrue(serialisation.contains("https://relay.example.invalid"))
    }

    @Test
    fun `DEL is emitted verbatim because it is above 0x20`() {
        val del = 0x7F.toChar().toString()
        val event = WireFixtures.eventWith(tags = emptyList(), content = del)

        val parsed = CanonicalReader.read(event.canonicalSerialisation())

        assertEquals(
            del,
            parsed.rawContent,
            "§4.1 names 0x7f explicitly: it is above 0x20, so rule 3 applies and it is emitted " +
                "verbatim. It is the one character above 0x20 an implementer reaches to escape.",
        )
    }

    /**
     * §4.1's MUST NOT sentence names *"not non-ASCII characters"* **first**, and until a review
     * pass asked for this test nothing in the suite covered it: every other emission control here
     * is about a control character, `/`, DEL or a non-BMP code point.
     *
     * The gap matters because escaping non-ASCII is not an exotic mistake — it is the
     * `ensure_ascii=True` default of the JSON stack an implementer is most likely to port from. A
     * serialiser carrying it computes a different id from the network for every event whose
     * content says "café", and the ten-thousand-event corpus would not notice: [CanonicalReader]
     * unescapes `\uXXXX` deliberately, so the round-trip still succeeds, and `\u` is already one
     * of the eight legal escape forms.
     */
    @Test
    fun `a non-ASCII BMP character is emitted verbatim and never u-escaped`() {
        for (text in listOf("é", "中", "Ω", " ", "߿", "￿")) {
            val event = WireFixtures.eventWith(tags = listOf(listOf("alt", text)), content = text)

            val parsed = CanonicalReader.read(event.canonicalSerialisation())

            assertEquals(
                text,
                parsed.rawContent,
                "§4.1 rule 3 and its MUST NOT: every character at or above 0x20 is emitted " +
                    "verbatim as UTF-8, and non-ASCII characters are named first among the ones " +
                    "an implementation MUST NOT `\\u`-escape",
            )
            assertEquals(listOf(listOf("alt", text)), parsed.tags, "…in a tag value too")
            assertFalse(
                parsed.escapeForms.contains("\\u"),
                "an ensure_ascii-style serialiser escapes this and round-trips perfectly, which is " +
                    "why only an emission control catches it",
            )
        }
    }

    @Test
    fun `a non-BMP character is emitted as UTF-8 and never as an escaped surrogate pair`() {
        val emoji = String(Character.toChars(0x1F600))
        val event = WireFixtures.eventWith(tags = emptyList(), content = emoji)

        val serialisation = event.canonicalSerialisation()
        val parsed = CanonicalReader.read(serialisation)

        assertEquals(emoji, parsed.rawContent, "rule 3: verbatim, as UTF-8 bytes")
        assertFalse(
            parsed.escapeForms.contains("\\u"),
            "§4.1 MUST NOT escape any character at or above 0x20, and a surrogate-pair escape is " +
                "how an implementation borrowed from a JSON library does it anyway",
        )
        assertEquals(
            4,
            emoji.toByteArray(Charsets.UTF_8).size,
            "the fixture must actually be non-BMP, or this control proves nothing",
        )
    }

    @Test
    fun `none of the seven is emitted in the six-character form`() {
        for ((escape, code) in Section41.shortcutEscapes()) {
            val raw = rawContentFor(code)

            assertEquals(
                escape,
                raw,
                "§4.1 MUST NOT use the six-character form for any of the seven characters rule 1 " +
                    "covers; rule 1 takes precedence over rule 2 wherever both could apply",
            )
            assertFalse(raw.startsWith("\\u"), "…and specifically not the four-hex-digit form")
        }
    }

    @Test
    fun `tag order is preserved exactly, never sorted or normalised`() {
        val tags = listOf(
            listOf("t", "nenya"),
            listOf("a", "30404:" + WireFixtures.pubkeyFor(7) + ":winter-loop"),
            listOf("alt", "a Nenya request"),
            listOf("t", "wtb"),
        )
        val event = WireFixtures.eventWith(tags = tags)

        val parsed = CanonicalReader.read(event.canonicalSerialisation())

        assertEquals(
            tags,
            parsed.tags,
            "§4.1: an implementation MUST NOT reorder or normalise tags. Sorting them is the " +
                "tempting normalisation, and it changes the id of every event that carries two.",
        )
        assertEquals(tags, event.tags, "and the event itself keeps the order it was given")
    }

    @Test
    fun `an empty tag value, an empty content and an event with no tags all serialise`() {
        val parsed = CanonicalReader.read(
            WireFixtures.eventWith(tags = listOf(listOf("")), content = "").canonicalSerialisation(),
        )

        assertEquals(listOf(listOf("")), parsed.tags)
        assertEquals("", parsed.content)
        assertEquals(
            emptyList(),
            CanonicalReader.read(
                WireFixtures.eventWith(tags = emptyList()).canonicalSerialisation(),
            ).tags,
            "§18 names empty tags and empty content as cases worth pinning; an empty tag *array* is " +
                "the one §4.3 rejects, and a tag whose single value is empty is not that",
        )
    }

    @Test
    fun `the tags are unmodifiable, so the event whose id was computed stays that event`() {
        val mutable = mutableListOf(mutableListOf("t", "nenya"))
        val event = WireEvent(WireFixtures.pubkeyFor(1), 1L, 1, mutable, "")
        val before = EventId.of(event).toHex()

        mutable[0][0] = "u"
        mutable += mutableListOf("extra")

        assertEquals(
            before,
            EventId.of(event).toHex(),
            "the caller keeps its own reference to the list it passed; an event that did not copy " +
                "on the way in could be rewritten after its id was published",
        )
        assertEquals(listOf(listOf("t", "nenya")), event.tags)
    }
}
