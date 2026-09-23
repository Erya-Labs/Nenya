package dev.eryalabs.nenya.wire

import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.utf8Bytes
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §7.1's object form, both directions: the four worked envelope examples §18 names, a hostile
 * corpus in which every row is refused by its own name, and §4.3's bounds.
 *
 * ### Where the positive fixtures come from
 *
 * Nobody here typed an id, a pubkey or a signature. The externally-authored half comes from the
 * vendored NIP-59 and NIP-17 documents, parsed at test time ([NipExamples]) — §18's gift-wrap row
 * says exactly what can be checked against them with no cryptography at all, which is that they
 * parse under §7.1's object rules and that each one's `id` recomputes from §4.1's serialisation.
 * The generated half comes from [WireFixtures], whose pubkeys are SHA-256 digests of a per-index
 * label and whose ids are computed by the platform's own SHA-256.
 *
 * ### Where the hostile fixtures come from
 *
 * Every row of [hostileCorpus] is a **mutation of a JSON this library just emitted**, so the row
 * differs from an accepted event in exactly the one way its name says, and the mutation itself is
 * asserted to have changed the text. A table of hand-written broken JSON would leave a row that
 * was broken twice — or not broken at all — indistinguishable from one that tested what it claims.
 */
class EventJsonTest {

    private companion object {

        /** A tag whose two values are one character each, so a mutation can replace them by name. */
        val BASE_TAGS: List<List<String>> = listOf(listOf("p", "q"))

        /** Likewise `content`: short, ordinary, and nothing a mutation could collide with. */
        const val BASE_CONTENT: String = "hello"

        /** The one event every mutation below is a mutation of. */
        val baseEvent: WireEvent by lazy { WireFixtures.eventWith(BASE_TAGS, BASE_CONTENT) }

        val baseId: EventId by lazy { EventId.of(baseEvent) }

        /**
         * A 128-character signature **computed**, never typed: two SHA-256 digests of per-purpose
         * labels, lower-hex, concatenated. Nothing verifies it and nothing claims to (§17); what it
         * has to be is the right shape, and the right shape has to be derived like everything else.
         */
        val baseSignature: String by lazy {
            WireFixtures.lowerHex(WireFixtures.sha256("nenya-event-json-sig-r".utf8Bytes())) +
                WireFixtures.lowerHex(WireFixtures.sha256("nenya-event-json-sig-s".utf8Bytes()))
        }

        val baseJson: String by lazy { EventJson.writeSigned(baseEvent, baseId, baseSignature) }

        /** §4.1's seven object keys, in the order [EventJson] emits them. */
        val KEY_ORDER: List<String> =
            listOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig")
    }

    // -----------------------------------------------------------------------------------------
    // (1) The four worked examples §18 names.
    // -----------------------------------------------------------------------------------------

    @JsName("the_NIP59_worked_rumor_reads_and_its_id_recomputes")
    @Test
    fun `the NIP-59 worked rumor reads and its id recomputes`() {
        val read = EventJson.read(NipExamples.nip59Rumor())

        assertEquals(1, read.event.kind, "NIP-59's worked rumor is a kind:1")
        assertNull(
            read.signatureHex,
            "NIP-59 says in as many words \"Do not sign the event\", and §7.1 step 1 requires a " +
                "rumor with no `sig` key at all. A reader that could not tell an absent key from " +
                "an empty one could not enforce §7.1 step 8.",
        )
        assertIdRecomputes(read, "NIP-59's worked rumor")
    }

    @JsName("the_NIP59_worked_seal_reads_and_its_id_recomputes")
    @Test
    fun `the NIP-59 worked seal reads and its id recomputes`() {
        val read = EventJson.read(NipExamples.nip59Seal())

        assertEquals(13, read.event.kind, "a seal is a kind:13 (§7.1 step 2)")
        assertEquals(
            emptyList(),
            read.event.tags,
            "§7.1 step 2 requires a seal's tags be empty",
        )
        assertEquals(
            EventJson.SIGNATURE_HEX_LENGTH,
            read.signatureHex?.length,
            "a seal is signed by the sender's real key, so the example carries a `sig`",
        )
        assertIdRecomputes(read, "NIP-59's worked seal")
    }

    @JsName("both_NIP17_worked_gift_wraps_read_and_their_ids_recompute")
    @Test
    fun `both NIP-17 worked gift wraps read and their ids recompute`() {
        val wraps = NipExamples.nip17Wraps()
        assertEquals(2, wraps.size, "NIP-17 publishes one wrap to the receiver and one to the sender")

        val ids = mutableSetOf<String>()
        for ((index, text) in wraps.withIndex()) {
            val read = EventJson.read(text)

            assertEquals(1059, read.event.kind, "wrap $index is a kind:1059 (§7.1 step 3)")
            assertEquals(
                1,
                read.event.tags.count { it.firstOrNull() == "p" },
                "§7.1 step 3 requires exactly one `p` tag on a wrap",
            )
            assertIdRecomputes(read, "NIP-17's worked wrap $index")
            ids += read.claimedIdHex
        }
        assertEquals(
            2,
            ids.size,
            "the two wraps are addressed to different keys and must not be the same event",
        )
    }

    // -----------------------------------------------------------------------------------------
    // (2) NIP-59's worked wrap: §18's named negative control, and the same text repaired.
    // -----------------------------------------------------------------------------------------

    @JsName("the_NIP59_worked_wrap_is_refused_for_its_trailing_comma")
    @Test
    fun `the NIP-59 worked wrap is refused for its trailing comma`() {
        assertEquals(
            JsonRejection.TRAILING_COMMA,
            refusalOf(NipExamples.nip59Wrap()),
            "§18: NIP-59's worked wrap is a documentation example and not a vector, and a parser " +
                "that accepts it is a parser that accepts malformed input from a relay",
        )
    }

    @JsName("the_NIP59_worked_wrap_reads_once_its_one_trailing_comma_is_removed")
    @Test
    fun `the NIP-59 worked wrap reads once its one trailing comma is removed`() {
        val repaired = NipExamples.nip59WrapWithoutItsTrailingComma()
        assertNotEquals(NipExamples.nip59Wrap(), repaired, "the repair must have changed something")

        val read = EventJson.read(repaired)

        assertEquals(1059, read.event.kind)
        assertIdRecomputes(read, "NIP-59's worked wrap, repaired")
    }

    // -----------------------------------------------------------------------------------------
    // (5) The hostile corpus: one named rejection per row.
    // -----------------------------------------------------------------------------------------

    /** One hostile row: a name a human reads, the text, and the reason it must be refused by. */
    private class Row(val name: String, val text: String, val expected: Enum<*>)

    private fun hostileCorpus(): List<Row> = listOf(
        Row(
            "a duplicate top-level key",
            inserted(",\"kind\":1"),
            JsonRejection.DUPLICATE_KEY,
        ),
        Row(
            "an eighth top-level key",
            inserted(",\"proof\":\"x\""),
            JsonRejection.UNKNOWN_KEY,
        ),
        Row(
            "no pubkey at all",
            mutated("\"pubkey\":\"${baseEvent.pubkey}\",", ""),
            JsonRejection.MISSING_KEY,
        ),
        Row(
            "created_at quoted as a string",
            mutated(createdAtMember(), "\"created_at\":\"${baseEvent.createdAt}\""),
            JsonRejection.NUMBER_EXPECTED,
        ),
        Row(
            "created_at with a leading zero",
            mutated(createdAtMember(), "\"created_at\":0${baseEvent.createdAt}"),
            JsonRejection.LEADING_ZERO,
        ),
        Row(
            "created_at in exponential form",
            mutated(createdAtMember(), "\"created_at\":1e3"),
            JsonRejection.FRACTIONAL_OR_EXPONENTIAL,
        ),
        Row(
            "a negative created_at",
            mutated(createdAtMember(), "\"created_at\":-1"),
            JsonRejection.SIGNED_NUMBER,
        ),
        Row(
            "a kind one past NIP-01's 16-bit range",
            mutated(kindMember(), "\"kind\":${EventJson.MAX_KIND + 1}"),
            JsonRejection.KIND_OUT_OF_RANGE,
        ),
        Row(
            "a lone escaped high surrogate in content",
            mutated(contentMember(), "\"content\":\"\\uD800\""),
            WireRejection.UNPAIRED_SURROGATE,
        ),
        Row(
            "a raw 0x01 in content",
            mutated(contentMember(), "\"content\":\"${1.toChar()}\""),
            JsonRejection.RAW_CONTROL_CHARACTER,
        ),
        Row(
            "a backslash escape JSON does not define",
            mutated(contentMember(), "\"content\":\"\\x41\""),
            JsonRejection.BAD_ESCAPE,
        ),
        Row(
            "a null tag element",
            mutated(tagsMember(), "\"tags\":[[\"p\",null]]"),
            WireRejection.NULL_TAG_ELEMENT,
        ),
        Row(
            "a number tag element",
            mutated(tagsMember(), "\"tags\":[[\"p\",1]]"),
            JsonRejection.TAG_ELEMENT_NOT_A_STRING,
        ),
        Row(
            "an array where a tag value belongs",
            mutated(tagsMember(), "\"tags\":[[\"p\",[\"q\"]]]"),
            JsonRejection.NESTED_ARRAY,
        ),
        Row(
            "a trailing comma",
            inserted(","),
            JsonRejection.TRAILING_COMMA,
        ),
        Row(
            "a second object after the first",
            baseJson + "{}",
            JsonRejection.TRAILING_DATA,
        ),
        Row(
            "a leading byte-order mark",
            TestText.codePoint(0xFEFF) + baseJson,
            JsonRejection.BYTE_ORDER_MARK,
        ),
        Row(
            "a JavaScript comment",
            "// an event\n" + baseJson,
            JsonRejection.COMMENT,
        ),
        Row(
            "nothing at all",
            "",
            JsonRejection.EMPTY_INPUT,
        ),
    )

    @JsName("every_hostile_row_is_refused_by_its_own_name")
    @Test
    fun `every hostile row is refused by its own name`() {
        val rows = hostileCorpus()

        // The control first: the text every row is a mutation of must itself be accepted, or a row
        // could be "refused" for a defect it shares with the base and the table would prove nothing.
        EventJson.read(baseJson)

        for (row in rows) {
            assertEquals(
                row.expected,
                refusalOf(row.text),
                "${row.name} must be refused as ${row.expected.name}",
            )
        }

        // Non-vacuity: a table whose rows all report one generic reason is a table that proves only
        // that something went wrong. Every row here names a different rule.
        assertEquals(
            rows.size,
            rows.map { it.expected }.toSet().size,
            "two rows report the same reason, so one of them is not discriminating: " +
                rows.groupBy { it.expected }.filterValues { it.size > 1 }.mapValues { it.value.map(Row::name) },
        )
        assertTrue(rows.size >= 19, "§18's negative controls are 19 rows; this table has ${rows.size}")
    }

    /**
     * The rest of the refusals the reader owes, which share a reason with a row above and so are
     * checked here rather than there — the distinctness assertion above is about the named table.
     */
    @JsName("the_remaining_malformed_shapes_are_refused_too")
    @Test
    fun `the remaining malformed shapes are refused too`() {
        val more = listOf(
            "NaN" to mutated(createdAtMember(), "\"created_at\":NaN"),
            "Infinity" to mutated(createdAtMember(), "\"created_at\":Infinity"),
            "a null created_at" to mutated(createdAtMember(), "\"created_at\":null"),
            "a fractional created_at" to mutated(createdAtMember(), "\"created_at\":1.5"),
        )
        for ((name, text) in more) {
            val reason = refusalOf(text)
            assertTrue(
                reason == JsonRejection.NUMBER_EXPECTED || reason == JsonRejection.FRACTIONAL_OR_EXPONENTIAL,
                "$name must be refused as a number problem; it was refused as $reason",
            )
        }

        // The guard on the only Long this reader builds, and it carries a timestamp. Without it a
        // nineteen-digit value wraps to a *negative* one — which would surface as
        // NEGATIVE_TIMESTAMP, a different reason — and a twenty-digit one can wrap back to a
        // positive value and be accepted outright. Both directions, plus the control that the
        // largest Long is still read, so this is a bound rather than a blanket refusal.
        EventJson.read(mutated(createdAtMember(), "\"created_at\":${Long.MAX_VALUE}"))
        assertEquals(
            JsonRejection.NUMBER_TOO_LARGE,
            refusalOf(mutated(createdAtMember(), "\"created_at\":9223372036854775808")),
            "one past Long.MAX_VALUE, in nineteen digits: the accumulator must refuse rather than wrap",
        )
        assertEquals(
            JsonRejection.NUMBER_TOO_LARGE,
            refusalOf(mutated(createdAtMember(), "\"created_at\":${"9".repeat(20)}")),
            "twenty digits: refused on the digit count, before the accumulator is reached at all",
        )

        assertEquals(
            JsonRejection.NOT_AN_OBJECT,
            refusalOf("[0,\"a\"]"),
            "§4.1's array serialisation is not §7.1's payload, and reading one as the other is how " +
                "a layer confusion starts",
        )
        assertEquals(JsonRejection.UNTERMINATED, refusalOf(baseJson.dropLast(1)))
        assertEquals(
            JsonRejection.VALUE_NOT_A_STRING,
            refusalOf(mutated(contentMember(), "\"content\":7")),
        )
        assertEquals(
            JsonRejection.TAGS_NOT_AN_ARRAY,
            refusalOf(mutated(tagsMember(), "\"tags\":\"\"")),
        )
        assertEquals(
            JsonRejection.TAG_NOT_AN_ARRAY,
            refusalOf(mutated(tagsMember(), "\"tags\":[\"p\"]")),
        )
        assertEquals(
            WireRejection.NULL_TAG_ELEMENT,
            refusalOf(mutated(tagsMember(), "\"tags\":[null]")),
            "a null *tag* is the same door, the same Java caller and the same denial of service as " +
                "a null tag element; WireRejection.NULL_TAG_ELEMENT's own KDoc says so",
        )
        assertEquals(
            WireRejection.EMPTY_TAG,
            refusalOf(mutated(tagsMember(), "\"tags\":[[]]")),
            "§4.3: a tag is an array of ONE or more strings",
        )
        // §7.1 step 8 turns on telling an absent `sig` from a present one carrying "any value at
        // all, including an empty string or null". Both of those are refused here, by their own
        // names, so a `signatureHex` of null upstream can only ever mean the key was absent.
        assertEquals(
            JsonRejection.VALUE_NOT_A_STRING,
            refusalOf(mutated(signatureMember(), "\"sig\":null")),
        )
        assertEquals(
            JsonRejection.SIGNATURE_MALFORMED,
            refusalOf(mutated(signatureMember(), "\"sig\":\"\"")),
        )
        assertEquals(
            JsonRejection.KEY_NOT_A_STRING,
            refusalOf("{1:2}"),
        )
        assertEquals(
            JsonRejection.UNEXPECTED_CHARACTER,
            refusalOf(mutated("\"content\":", "\"content\"=")),
        )
        assertEquals(
            WireRejection.WRONG_LENGTH,
            refusalOf(mutated("\"id\":\"${baseId.toHex()}\"", "\"id\":\"abc\"")),
            "§4.3 requires rejecting a hex value of the wrong length rather than padding it",
        )
        assertEquals(
            WireRejection.NOT_HEX,
            refusalOf(mutated("\"id\":\"${baseId.toHex()}\"", "\"id\":\"${"z".repeat(EventId.HEX_LENGTH)}\"")),
        )
    }

    // -----------------------------------------------------------------------------------------
    // (6) Bounds.
    // -----------------------------------------------------------------------------------------

    @JsName("exactly_the_byte_bound_is_accepted_and_one_byte_over_is_refused_before_the_scan")
    @Test
    fun `exactly the byte bound is accepted and one byte over is refused before the scan`() {
        val exact = baseJson.utf8Bytes().size
        assertTrue(exact > 0, "the base event must actually serialise to something")

        // Exactly the bound: accepted. The bound is a maximum, not a strict one.
        EventJson.read(baseJson, WireLimits(maxSerialisedEventBytes = exact))

        assertEquals(
            JsonRejection.TOO_LARGE,
            refusalOf(baseJson, WireLimits(maxSerialisedEventBytes = exact - 1)),
            "§4.3 requires rejecting rather than truncating",
        )

        // The bound is checked BEFORE the scanner runs, which is the property that makes a 100 MiB
        // "event" cheap to refuse. Proved by handing it something that is over the bound AND is not
        // an object at all: a reader that scanned first would answer NOT_AN_OBJECT.
        assertEquals(
            JsonRejection.TOO_LARGE,
            refusalOf("x".repeat(exact + 1), WireLimits(maxSerialisedEventBytes = exact)),
            "the bound must be checked before the scan, or a hostile relay chooses how much work " +
                "this library does before refusing",
        )
    }

    @JsName("the_byte_bound_is_measured_in_UTF8_bytes_and_not_in_characters")
    @Test
    fun `the byte bound is measured in UTF-8 bytes and not in characters`() {
        // One astral character is two UTF-16 units and four UTF-8 bytes, so a character-counting
        // bound accepts a text §4.3 says is over.
        val wide = WireFixtures.eventWith(BASE_TAGS, TestText.codePoint(0x1F3A8).repeat(64))
        val json = EventJson.writeUnsigned(wide, EventId.of(wide))
        val characters = json.length
        val bytes = json.utf8Bytes().size
        assertTrue(bytes > characters, "the fixture must actually be wider in bytes than in units")

        assertEquals(
            JsonRejection.TOO_LARGE,
            refusalOf(json, WireLimits(maxSerialisedEventBytes = bytes - 1)),
        )
        EventJson.read(json, WireLimits(maxSerialisedEventBytes = bytes))
    }

    @JsName("a_million_tags_are_refused_as_too_many_rather_than_materialised")
    @Test
    fun `a million tags are refused as too many rather than materialised`() {
        val tags = StringBuilder("\"tags\":[")
        repeat(MILLION) {
            if (it > 0) tags.append(',')
            tags.append("[\"a\"]")
        }
        tags.append(']')
        val text = mutated(tagsMember(), tags.toString())
        // A bound wide enough to hold the whole text, so the refusal below is the tag count and not
        // the byte bound standing in for it. Every character of this fixture is ASCII, so its UTF-8
        // byte count is its length; measuring it with the test encoder would box six million Bytes.
        val roomy = WireLimits(maxSerialisedEventBytes = text.length)

        assertEquals(
            WireRejection.TOO_MANY_TAGS,
            refusalOf(text, roomy),
            "§4.3's tag-count bound is enforced during the scan, so the 512th tag is the last one " +
                "this library ever allocates",
        )
    }

    @JsName("the_tag_count_is_bounded_during_the_scan_and_not_after_it")
    @Test
    fun `the tag count is bounded during the scan and not after it`() {
        // Four tags, a bound of two, and rubbish after the fourth. A reader that materialised the
        // array and counted afterwards would reach the rubbish and answer UNEXPECTED_CHARACTER.
        val text = mutated(tagsMember(), "\"tags\":[[\"a\"],[\"b\"],[\"c\"],[\"d\"] zzz]")

        assertEquals(
            WireRejection.TOO_MANY_TAGS,
            refusalOf(text, WireLimits(maxTagsPerEvent = 2)),
        )
    }

    // -----------------------------------------------------------------------------------------
    // The writer.
    // -----------------------------------------------------------------------------------------

    @JsName("the_writer_emits_the_seven_keys_in_a_fixed_order")
    @Test
    fun `the writer emits the seven keys in a fixed order`() {
        var previous = -1
        for (key in KEY_ORDER) {
            val at = baseJson.indexOf("\"$key\":")
            assertTrue(at > previous, "`$key` is out of order in ${KEY_ORDER}")
            previous = at
        }
        assertTrue(baseJson.startsWith("{\"id\":\""), "the object opens with its id")
        assertTrue(baseJson.endsWith("\"}"), "and closes on the signature")
        assertEquals(baseJson, EventJson.writeSigned(baseEvent, baseId, baseSignature), "and is stable")
    }

    @JsName("the_unsigned_form_carries_no_sig_key_at_all")
    @Test
    fun `the unsigned form carries no sig key at all`() {
        val unsigned = EventJson.writeUnsigned(baseEvent, baseId)

        assertTrue(
            "\"sig\"" !in unsigned,
            "§7.1 step 1: a rumor has no `sig` key at all, not an empty one",
        )
        assertNull(EventJson.read(unsigned).signatureHex)
    }

    @JsName("the_writer_refuses_an_id_that_is_not_this_events_id")
    @Test
    fun `the writer refuses an id that is not this event's id`() {
        val other = WireFixtures.eventWith(BASE_TAGS, BASE_CONTENT, index = 1)
        val otherId = EventId.of(other)
        assertNotEquals(baseId, otherId, "the two fixtures must differ or this proves nothing")

        val thrown = assertFailsWith<WireException> { EventJson.writeSigned(baseEvent, otherId, baseSignature) }

        assertEquals(
            WireRejection.ID_MISMATCH,
            thrown.reason,
            "§4.1 requires every reader to reject an event whose id does not recompute; emitting " +
                "one would put this library on the wrong end of its own central rule",
        )
    }

    @JsName("the_writer_refuses_a_signature_of_the_wrong_shape")
    @Test
    fun `the writer refuses a signature of the wrong shape`() {
        assertEquals(
            JsonRejection.SIGNATURE_MALFORMED,
            assertFailsWith<JsonException> {
                EventJson.writeSigned(baseEvent, baseId, baseSignature.dropLast(1))
            }.reason,
        )
        assertEquals(
            JsonRejection.SIGNATURE_MALFORMED,
            assertFailsWith<JsonException> {
                EventJson.writeSigned(baseEvent, baseId, baseSignature.dropLast(1) + "z")
            }.reason,
        )
    }

    @JsName("the_writer_refuses_a_kind_outside_NIP01s_sixteen_bit_range")
    @Test
    fun `the writer refuses a kind outside NIP-01's sixteen-bit range`() {
        val wide = WireEvent(
            pubkey = WireFixtures.pubkeyFor(2),
            createdAt = WireFixtures.CREATED_AT,
            kind = EventJson.MAX_KIND + 1,
            tags = BASE_TAGS,
            content = BASE_CONTENT,
        )

        assertEquals(
            JsonRejection.KIND_OUT_OF_RANGE,
            assertFailsWith<JsonException> { EventJson.writeUnsigned(wide, EventId.of(wide)) }.reason,
            "the writer must not emit an object its own reader would refuse",
        )
    }

    /**
     * §7.1's "exactly one escaping routine": the object form's `content` is byte-identical to the
     * canonical serialisation's, over the whole of §4.1's alphabet.
     *
     * The comparison is against [CanonicalReader]'s raw reading of `canonicalSerialisation` rather
     * than against a table written here, so a change to rules 1–3 moves both forms together or
     * turns this red.
     */
    @JsName("the_object_form_escapes_strings_exactly_as_the_canonical_serialisation_does")
    @Test
    fun `the object form escapes strings exactly as the canonical serialisation does`() {
        var checked = 0
        for (value in WireFixtures.ALPHABET) {
            val event = WireFixtures.eventWith(listOf(listOf(value, "t")), value)
            val canonical = CanonicalReader.read(event.canonicalSerialisation())
            val json = EventJson.writeUnsigned(event, EventId.of(event))

            assertTrue(
                "\"content\":\"${canonical.rawContent}\"" in json,
                "the object form escaped content differently from the canonical serialisation",
            )
            assertTrue(
                "[\"${canonical.rawTagValues.single().first()}\",\"t\"]" in json,
                "the object form escaped a tag value differently from the canonical serialisation",
            )
            checked++
        }
        assertEquals(WireFixtures.ALPHABET.size, checked, "the alphabet must actually have been walked")
        assertTrue(checked > 30, "§4.1's alphabet is every control character and more; $checked is not it")
    }

    // -----------------------------------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------------------------------

    /** The reason [EventJson.read] refused [text], whichever of the two exception types carried it. */
    private fun refusalOf(text: String, limits: WireLimits = WireLimits.DEFAULT): Enum<*> =
        try {
            val read = EventJson.read(text, limits)
            fail("this text was expected to be refused, and read as $read")
        } catch (refused: JsonException) {
            refused.reason
        } catch (refused: WireException) {
            refused.reason
        }

    /** §4.1's central rule over an example nobody here authored. */
    private fun assertIdRecomputes(read: ReadEvent, what: String) {
        val checked = CheckedEvent.checkEventId(read.claimedIdHex, read.event)

        assertEquals(
            read.claimedIdHex.lowercase(),
            checked.id.toHex(),
            "$what claims an id this library's own SHA-256 does not compute from §4.1's " +
                "serialisation of its five fields",
        )
    }

    /**
     * [baseJson] with [from] replaced by [to], having first asserted that [from] occurs **exactly
     * once**.
     *
     * The assertion is the point: a mutation whose search string is absent leaves the base text
     * untouched, and a row built on it would assert that a perfectly good event is refused — or,
     * worse, silently stop testing anything the day the writer's key order changes.
     */
    private fun mutated(from: String, to: String): String {
        val occurrences = baseJson.split(from).size - 1
        assertEquals(1, occurrences, "`$from` occurs $occurrences times in the base JSON")
        return baseJson.replace(from, to)
    }

    /** [baseJson] with [text] spliced in immediately before the closing brace. */
    private fun inserted(text: String): String {
        assertTrue(baseJson.endsWith("}"), "the base JSON must end on its closing brace")
        return baseJson.dropLast(1) + text + "}"
    }

    private fun createdAtMember(): String = "\"created_at\":${baseEvent.createdAt}"

    private fun kindMember(): String = "\"kind\":${baseEvent.kind}"

    private fun contentMember(): String = "\"content\":\"$BASE_CONTENT\""

    private fun tagsMember(): String = "\"tags\":[[\"p\",\"q\"]]"

    private fun signatureMember(): String = "\"sig\":\"$baseSignature\""
}

/** A million, spelled once. §4.3's point is that the 513th tag is never allocated. */
private const val MILLION: Int = 1_000_000
