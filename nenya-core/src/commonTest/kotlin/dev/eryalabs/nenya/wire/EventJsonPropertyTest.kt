package dev.eryalabs.nenya.wire

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.utf8Bytes
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for §7.1's object form: ten thousand seeded events written and read back,
 * and the same ten thousand written again with every **legal** non-canonical escape a conformant
 * peer might use.
 *
 * The second half is the one worth reading this file for. A round trip through this library's own
 * writer proves that the reader undoes what the writer does, and a reader that shared a defect with
 * the writer would pass it every time. So [reEncoded] emits each string a different, legal way — `\/`
 * for a slash, `A` for an `A`, uppercase hex digits, the six-character form for characters §4.1
 * says MUST use a shortcut, an escaped surrogate pair for an astral character, and escaped **keys** —
 * none of which this library ever writes, all of which RFC 8259 permits and `nak`, `nostr-tools` and
 * a hand-rolled serialiser at the other end of a relay will each produce some of. Every one must
 * read back to the same event with the same id, because §4.1's id is over the *event*, not over the
 * spelling somebody chose for it.
 *
 * The corpus is [WireFixtures]', whose alphabet is every character §4.1's MUST NOTs are written
 * about: all 32 control characters, the quote, the backslash, `/`, `0x7f`, two non-BMP code points
 * and the empty string.
 */
class EventJsonPropertyTest {

    private companion object {

        const val SAMPLES: Int = 10_000

        /** Pinned so a failure is reproducible; a reviewer may change it and re-run. */
        const val SEED: Long = 20_260_924L

        val corpus: List<WireEvent> by lazy { WireFixtures.events(SAMPLES) }

        /**
         * A distinct 128-character signature per event, computed rather than typed: the SHA-256 of
         * a per-index label, twice over with different labels, in lowercase hex.
         */
        fun signatureFor(index: Int): String =
            WireFixtures.lowerHex(WireFixtures.sha256("nenya-json-sig-r-$index".utf8Bytes())) +
                WireFixtures.lowerHex(WireFixtures.sha256("nenya-json-sig-s-$index".utf8Bytes()))
    }

    @JsName("every_written_event_reads_back_field_for_field_with_the_same_id")
    @Test
    fun `every written event reads back field for field with the same id`() {
        var checked = 0
        for ((index, event) in corpus.withIndex()) {
            val id = EventId.of(event)
            val signature = signatureFor(index)

            val read = EventJson.read(EventJson.writeSigned(event, id, signature))

            assertEquals(event.pubkey, read.event.pubkey, "pubkey did not survive event $index")
            assertEquals(event.createdAt, read.event.createdAt, "created_at did not survive event $index")
            assertEquals(event.kind, read.event.kind, "kind did not survive event $index")
            assertEquals(event.tags, read.event.tags, "a tag did not survive event $index")
            assertEquals(event.content, read.event.content, "content did not survive event $index")
            assertEquals(id.toHex(), read.claimedIdHex, "the claimed id did not survive event $index")
            assertEquals(signature, read.signatureHex, "the signature did not survive event $index")
            assertEquals(id, EventId.of(read.event), "event $index reads back to a different id")
            checked++
        }
        assertEquals(SAMPLES, checked, "the corpus must actually have been walked")
    }

    @JsName("the_unsigned_form_round_trips_with_no_signature_at_all")
    @Test
    fun `the unsigned form round-trips with no signature at all`() {
        var unsigned = 0
        for (event in corpus) {
            val id = EventId.of(event)

            val read = EventJson.read(EventJson.writeUnsigned(event, id))

            assertEquals(null, read.signatureHex, "§7.1 step 1: a rumor has no `sig` key at all")
            assertEquals(event.content, read.event.content)
            assertEquals(id, EventId.of(read.event))
            unsigned++
        }
        assertEquals(SAMPLES, unsigned)
    }

    @JsName("every_legal_non_canonical_re_encoding_reads_back_to_the_same_event_and_id")
    @Test
    fun `every legal non-canonical re-encoding reads back to the same event and id`() {
        val random = JdkRandom(SEED)
        var differed = 0
        for ((index, event) in corpus.withIndex()) {
            val id = EventId.of(event)
            val signature = signatureFor(index)
            val canonical = EventJson.writeSigned(event, id, signature)
            val alternative = reEncoded(event, id, signature, random)

            val read = EventJson.read(alternative)

            assertEquals(event.pubkey, read.event.pubkey, "pubkey did not survive re-encoding $index")
            assertEquals(event.createdAt, read.event.createdAt, "created_at did not survive re-encoding $index")
            assertEquals(event.kind, read.event.kind, "kind did not survive re-encoding $index")
            assertEquals(event.tags, read.event.tags, "a tag did not survive re-encoding $index")
            assertEquals(event.content, read.event.content, "content did not survive re-encoding $index")
            assertEquals(
                id,
                EventId.of(read.event),
                "re-encoding $index reads back to a different id, so this library computes an id " +
                    "from the spelling of an event rather than from the event",
            )
            assertEquals(
                id.toHex(),
                read.claimedIdHex.lowercase(),
                "the claimed id did not survive re-encoding $index",
            )
            assertEquals(
                signature,
                read.signatureHex?.lowercase(),
                "the signature did not survive re-encoding $index",
            )
            if (alternative != canonical) differed++
        }

        // The floor that stops this being a second copy of the round-trip test above: if the
        // generator happened to emit §4.1's own spelling every time, every assertion here would be
        // satisfied by a reader that understood nothing but canonical output.
        assertTrue(
            differed > SAMPLES / 2,
            "only $differed of $SAMPLES re-encodings differed from the canonical form, so this " +
                "test is asserting the canonical round trip a second time",
        )
    }

    /**
     * The generator itself is held to its claim: over the whole of §4.1's alphabet it must produce
     * every escape form JSON defines, including the three §4.1 forbids this library from writing.
     *
     * Without this the test above could pass with a generator that only ever emitted verbatim
     * characters, and "reads every legal escape" would be an unchecked sentence in a KDoc.
     */
    @JsName("the_re_encoder_really_emits_the_escape_forms_it_claims_to")
    @Test
    fun `the re-encoder really emits the escape forms it claims to`() {
        val random = JdkRandom(SEED)
        val seen = mutableSetOf<String>()
        var slashEscapes = 0
        var uppercaseHex = 0
        var longFormShortcut = 0

        for (value in WireFixtures.ALPHABET + listOf("/A" + TestText.codePoint(0x1F600))) {
            repeat(64) {
                val text = reEncodedString(value, random)
                var at = 0
                while (at < text.length) {
                    if (text[at] != '\\') {
                        at++
                        continue
                    }
                    val form = text.substring(at, at + 2)
                    seen += form
                    if (form == "\\/") slashEscapes++
                    if (form == "\\u") {
                        val digits = text.substring(at + 2, at + 6)
                        if (digits.any { it in 'A'..'F' }) uppercaseHex++
                        if (digits.lowercase() in SHORTCUT_CODE_POINTS) longFormShortcut++
                        at += 6
                    } else {
                        at += 2
                    }
                }
            }
        }

        assertEquals(
            setOf("\\\"", "\\\\", "\\/", "\\b", "\\f", "\\n", "\\r", "\\t", "\\u"),
            seen,
            "the generator must emit all nine escape forms JSON defines — the eight two-character " +
                "ones and `\\u` — or the reader is not being asked about the ones it claims to read",
        )
        assertTrue(slashEscapes > 0, "`\\/` is legal JSON and §4.1 forbids emitting it; the reader must accept it")
        assertTrue(uppercaseHex > 0, "uppercase hex in a `\\u` escape is legal JSON and forbidden output")
        assertTrue(
            longFormShortcut > 0,
            "the six-character form for a character §4.1 says MUST use a shortcut is legal JSON, " +
                "forbidden output, and exactly what a foreign serialiser emits",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The equivalence generator.
    // -----------------------------------------------------------------------------------------

    /**
     * The same event as §7.1's object, spelled a different legal way each time.
     *
     * Keys are escaped too, half the time: `{"id":…}` is the same member as `{"id":…}`, and a
     * reader that matched key bytes before unescaping them would read it as an unknown eighth field.
     */
    private fun reEncoded(event: WireEvent, id: EventId, sig: String, random: JdkRandom): String {
        val out = StringBuilder()
        out.append('{')
        appendMember(out, "id", reEncodedString(id.toHex(), random), random, first = true)
        appendMember(out, "pubkey", reEncodedString(event.pubkey, random), random, first = false)
        appendMember(out, "created_at", event.createdAt.toString(), random, first = false)
        appendMember(out, "kind", event.kind.toString(), random, first = false)

        val tags = StringBuilder("[")
        for ((index, tag) in event.tags.withIndex()) {
            if (index > 0) tags.append(',')
            tags.append('[')
            for ((position, value) in tag.withIndex()) {
                if (position > 0) tags.append(',')
                tags.append(reEncodedString(value, random))
            }
            tags.append(']')
        }
        tags.append(']')
        appendMember(out, "tags", tags.toString(), random, first = false)
        appendMember(out, "content", reEncodedString(event.content, random), random, first = false)
        appendMember(out, "sig", reEncodedString(sig, random), random, first = false)
        out.append('}')
        return out.toString()
    }

    /** One member, its key spelled verbatim or escaped, and RFC 8259 whitespace around the colon. */
    private fun appendMember(
        out: StringBuilder,
        key: String,
        rawValue: String,
        random: JdkRandom,
        first: Boolean,
    ) {
        if (!first) out.append(',')
        out.append(if (random.nextBoolean()) "\"$key\"" else reEncodedString(key, random))
        // RFC 8259 permits whitespace between tokens; §4.1 forbids it in the *canonical* form, which
        // is a rule about writing. A reader that refused it would refuse conformant peers.
        out.append(whitespace(random)).append(':').append(whitespace(random))
        out.append(rawValue)
    }

    private fun whitespace(random: JdkRandom): String = when (random.nextInt(8)) {
        0 -> " "
        1 -> "\t"
        2 -> "\n"
        3 -> "\r\n"
        else -> ""
    }

    /**
     * [value] as a JSON string literal, each character spelled one of the legal ways at random.
     *
     * A surrogate **pair** is treated as one unit — both units verbatim, or both escaped — because
     * splitting it would put an unpaired surrogate in the text itself, which is not ill-formed JSON
     * but is text with no UTF-8 encoding, and §4.1 requires refusing that. That is a different rule
     * with its own test; this generator is about escapes.
     */
    private fun reEncodedString(value: String, random: JdkRandom): String {
        val out = StringBuilder("\"")
        var index = 0
        while (index < value.length) {
            val character = value[index]
            val low = if (index + 1 < value.length) value[index + 1] else null
            if (character.isHighSurrogate() && low != null && low.isLowSurrogate()) {
                if (random.nextBoolean()) {
                    out.append(character).append(low)
                } else {
                    val upper = random.nextBoolean()
                    out.append(unicodeEscape(character.code, upper)).append(unicodeEscape(low.code, upper))
                }
                index += 2
                continue
            }
            out.append(spelled(character, random))
            index++
        }
        return out.append('"').toString()
    }

    /** One BMP character, spelled verbatim where JSON allows it and escaped where it does not. */
    private fun spelled(character: Char, random: JdkRandom): String {
        val code = character.code
        val choice = random.nextInt(3)
        val shortcut = SHORTCUTS[code]
        return when {
            // Must be escaped somehow: the quote, the backslash and everything below 0x20.
            shortcut != null || code < 0x20 ->
                if (choice == 0 && shortcut != null) shortcut else unicodeEscape(code, choice == 2)
            // Legal both ways, and §4.1 forbids the escaped spelling on write.
            code == SLASH && choice == 0 -> "\\/"
            choice == 1 -> unicodeEscape(code, random.nextBoolean())
            else -> character.toString()
        }
    }

    private fun unicodeEscape(code: Int, upper: Boolean): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder("\\u")
        for (shift in listOf(12, 8, 4, 0)) {
            val digit = digits[(code shr shift) and 0xF]
            out.append(if (upper) digit.uppercaseChar() else digit)
        }
        return out.toString()
    }
}

/** JSON's seven two-character escapes that are not `\/`, keyed by the code point each spells. */
private val SHORTCUTS: Map<Int, String> = mapOf(
    0x08 to "\\b",
    0x09 to "\\t",
    0x0A to "\\n",
    0x0C to "\\f",
    0x0D to "\\r",
    0x22 to "\\\"",
    0x5C to "\\\\",
)

/** The same code points as four lowercase hex digits, for recognising a long-form shortcut. */
private val SHORTCUT_CODE_POINTS: Set<String> = SHORTCUTS.keys
    .map { code -> "0123456789abcdef".let { d -> "00" + d[(code shr 4) and 0xF] + d[code and 0xF] } }
    .toSet()

private const val SLASH: Int = 0x2F
