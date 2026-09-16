package dev.eryalabs.nenya.wire

import dev.eryalabs.nenya.SpecAnchor
import dev.eryalabs.nenya.utf8Bytes
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §4.3's structural and resource rules: reject, never truncate — and the four bounds pinned to
 * the numbers §4.3 itself publishes, parsed at test time.
 *
 * Every bound gets a pair: the value **at** the bound accepted, and the value one unit over
 * refused **naming which bound**. A test that only checked the refusal would pass over an
 * implementation whose bound was off by one in the strict direction, which refuses conformant
 * peers and looks careful doing it.
 */
class WireBoundsTest {

    private companion object {

        /** Everything raised out of the way, for measuring a serialisation before bounding it. */
        val UNBOUNDED = WireLimits(
            maxSerialisedEventBytes = Int.MAX_VALUE,
            maxTagsPerEvent = Int.MAX_VALUE,
            maxTagValueBytes = Int.MAX_VALUE,
            maxContentBytes = Int.MAX_VALUE,
        )

        /**
         * §4.3's published defaults, parsed out of the specification's own sentence.
         *
         * Transcribing them would prove that the code agrees with whoever wrote this test. The
         * fifth bound §4.3 names — 64 `image` tags — is deliberately absent from [WireLimits]:
         * it names a tag from §5.3's vocabulary, which this package knows nothing about.
         */
        fun publishedBounds(): Map<String, Int> {
            val text = SpecAnchor.specFile().readLines().joinToString(" ")
            val patterns = mapOf(
                "serialised event" to Regex("""(\d+) KiB serialised event"""),
                "tags per event" to Regex("""(\d+) tags per event"""),
                "bytes per tag value" to Regex("""(\d+) bytes per tag value"""),
                "content" to Regex("""(\d+) KiB `content`"""),
            )
            val found = patterns.mapValues { (what, pattern) ->
                val match = pattern.find(text)
                    ?: fail("§4.3 publishes no bound matching \"$what\" in ${SpecAnchor.specFile().location}")
                match.groupValues[1].toInt()
            }
            assertEquals(4, found.size, "§4.3's four generic bounds must all have been found")
            return found
        }

        /** An event whose canonical serialisation is exactly [target] ASCII bytes long. */
        fun eventOfSerialisedLength(target: Int): WireEvent {
            val tags = mutableListOf<List<String>>()
            while (true) {
                val measured = WireFixtures.eventWith(tags = tags + listOf(listOf("")))
                val deficit = target - measured.canonicalSerialisation(UNBOUNDED).length
                check(deficit >= 0) { "cannot shrink to $target bytes; overshot by ${-deficit}" }
                if (deficit <= WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES) {
                    return WireFixtures.eventWith(tags = tags + listOf(listOf("a".repeat(deficit))))
                }
                tags += listOf("a".repeat(1000))
            }
        }

        fun refusal(block: () -> Unit): WireRejection = assertFailsWith<WireException> { block() }.reason
    }

    @JsName("the_four_defaults_are_the_four_numbers_the_specification_publishes")
    @Test
    fun `the four defaults are the four numbers the specification publishes`() {
        val published = publishedBounds()

        assertEquals(published.getValue("serialised event") * 1024, WireLimits.DEFAULT_MAX_SERIALISED_EVENT_BYTES)
        assertEquals(published.getValue("tags per event"), WireLimits.DEFAULT_MAX_TAGS_PER_EVENT)
        assertEquals(published.getValue("bytes per tag value"), WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES)
        assertEquals(published.getValue("content") * 1024, WireLimits.DEFAULT_MAX_CONTENT_BYTES)
        assertEquals(
            WireLimits.DEFAULT_MAX_SERIALISED_EVENT_BYTES,
            WireLimits.DEFAULT.maxSerialisedEventBytes,
            "and WireLimits.DEFAULT must actually be built from them",
        )
    }

    @JsName("an_empty_tag_array_is_rejected")
    @Test
    fun `an empty tag array is rejected`() {
        assertEquals(
            WireRejection.EMPTY_TAG,
            refusal { WireFixtures.eventWith(tags = listOf(listOf("t", "nenya"), emptyList())) },
            "§4.3: a tag is an array of one or more strings, and an event carrying an empty tag " +
                "array MUST be rejected",
        )
        WireFixtures.eventWith(tags = listOf(listOf("")))
    }

    @JsName("the_tag_count_bound_accepts_512_and_refuses_513")
    @Test
    fun `the tag-count bound accepts 512 and refuses 513`() {
        val bound = WireLimits.DEFAULT_MAX_TAGS_PER_EVENT
        val tags = List(bound) { listOf("t") }

        WireFixtures.eventWith(tags = tags).canonicalSerialisation()

        assertEquals(
            WireRejection.TOO_MANY_TAGS,
            refusal { WireFixtures.eventWith(tags = tags + listOf(listOf("t"))).canonicalSerialisation() },
        )
    }

    @JsName("the_tag_value_bound_accepts_1024_bytes_and_refuses_1025")
    @Test
    fun `the tag-value bound accepts 1024 bytes and refuses 1025`() {
        val bound = WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES

        WireFixtures.eventWith(tags = listOf(listOf("a".repeat(bound)))).canonicalSerialisation()

        assertEquals(
            WireRejection.TAG_VALUE_TOO_LONG,
            refusal {
                WireFixtures.eventWith(tags = listOf(listOf("a".repeat(bound + 1)))).canonicalSerialisation()
            },
        )
    }

    /**
     * The control a char-counting implementation fails and nothing else catches: 1024 characters
     * that are 3072 bytes. §4.3 says **bytes** per tag value, and every other bound test here
     * uses ASCII, where the two measures are the same number.
     */
    @JsName("the_tag_value_bound_is_measured_in_utf_8_bytes_and_not_in_characters")
    @Test
    fun `the tag-value bound is measured in UTF-8 bytes and not in characters`() {
        val value = "中".repeat(WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES)

        assertEquals(
            WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES,
            value.length,
            "the fixture must be exactly at the bound by character count, or it proves nothing",
        )
        assertEquals(3 * WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES, value.utf8Bytes().size)
        assertEquals(
            WireRejection.TAG_VALUE_TOO_LONG,
            refusal { WireFixtures.eventWith(tags = listOf(listOf(value))).canonicalSerialisation() },
        )
    }

    /**
     * A `null` tag element, which §4.3 names in the same sentence as the empty tag array.
     *
     * Unreachable from Kotlin and entirely reachable from Java, where `List<List<String>>` is that
     * type by annotation only — so the cast below is not a contrivance, it is the JVM signature
     * this library publishes. Before the check existed this constructed successfully and then
     * threw a bare `NullPointerException` out of the serialiser, which is a denial of service in
     * the buyer's client rather than a rejection.
     */
    @JsName("a_null_tag_element_is_refused_and_never_reaches_the_serialiser")
    @Test
    fun `a null tag element is refused, and never reaches the serialiser`() {
        @Suppress("UNCHECKED_CAST")
        val hostile = listOf(listOf("e", null)) as List<List<String>>

        assertEquals(
            WireRejection.NULL_TAG_ELEMENT,
            refusal { WireFixtures.eventWith(tags = hostile) },
            "§4.3: an implementation MUST reject an event containing a non-string or null tag element",
        )
    }

    /**
     * The other half of the same sentence, and the one that was handled the opposite way.
     *
     * A relay's `"tags":[null]` parses to a `null` **tag**, not to a null element, and it is the
     * same Java caller reaching the same constructor. It reached the emptiness check and threw a
     * bare `NullPointerException` — the denial of service the element check exists to prevent,
     * one nesting level out.
     */
    @JsName("a_null_tag_is_refused_for_the_same_reason_a_null_element_is")
    @Test
    fun `a null tag is refused for the same reason a null element is`() {
        @Suppress("UNCHECKED_CAST")
        val hostile = listOf(listOf("e", "x"), null) as List<List<String>>

        assertEquals(
            WireRejection.NULL_TAG_ELEMENT,
            refusal { WireFixtures.eventWith(tags = hostile) },
            "§4.3: an implementation MUST reject an event containing a non-string or null tag",
        )
    }

    @JsName("the_content_bound_accepts_16_kib_and_refuses_one_byte_more")
    @Test
    fun `the content bound accepts 16 KiB and refuses one byte more`() {
        val bound = WireLimits.DEFAULT_MAX_CONTENT_BYTES

        WireFixtures.eventWith(tags = emptyList(), content = "a".repeat(bound)).canonicalSerialisation()

        assertEquals(
            WireRejection.CONTENT_TOO_LONG,
            refusal {
                WireFixtures.eventWith(tags = emptyList(), content = "a".repeat(bound + 1))
                    .canonicalSerialisation()
            },
        )
    }

    @JsName("the_serialised_event_bound_accepts_64_kib_and_refuses_one_byte_more")
    @Test
    fun `the serialised-event bound accepts 64 KiB and refuses one byte more`() {
        val bound = WireLimits.DEFAULT_MAX_SERIALISED_EVENT_BYTES

        val atBound = eventOfSerialisedLength(bound)
        assertEquals(bound, atBound.canonicalSerialisation().utf8Bytes().size)

        assertEquals(
            WireRejection.SERIALISED_EVENT_TOO_LARGE,
            refusal { eventOfSerialisedLength(bound + 1).canonicalSerialisation() },
        )
    }

    /**
     * The same char-versus-byte control as above, for the other two byte-measured bounds.
     *
     * T8 asked for it on the tag value, where the trap is easiest to see. A review pass then
     * mutated `content` and the whole-event bound to count characters and the entire suite stayed
     * green — the identical failure mode, unguarded, one field over. §4.3 writes both bounds in
     * KiB, which is a byte unit, so 16 384 CJK characters is 49 152 bytes and MUST be refused.
     */
    @JsName("the_content_and_whole_event_bounds_are_measured_in_utf_8_bytes_too")
    @Test
    fun `the content and whole-event bounds are measured in UTF-8 bytes too`() {
        val content = "中".repeat(WireLimits.DEFAULT_MAX_CONTENT_BYTES)
        assertEquals(WireLimits.DEFAULT_MAX_CONTENT_BYTES, content.length, "at the bound by characters")

        assertEquals(
            WireRejection.CONTENT_TOO_LONG,
            refusal { WireFixtures.eventWith(tags = emptyList(), content = content).canonicalSerialisation() },
        )

        // 100 tags of 340 CJK characters: 34 000 characters, 102 000 bytes. Every other bound is
        // satisfied — 100 tags of 1020 bytes each, no content — so only the whole-event bound can
        // refuse it, and a character-counting one would not.
        val wide = WireFixtures.eventWith(tags = List(100) { listOf("中".repeat(340)) })
        val serialisation = wide.canonicalSerialisation(UNBOUNDED)
        assertTrue(
            serialisation.length < WireLimits.DEFAULT_MAX_SERIALISED_EVENT_BYTES,
            "the fixture must be under the bound by characters, or it proves nothing",
        )
        assertTrue(
            serialisation.utf8Bytes().size > WireLimits.DEFAULT_MAX_SERIALISED_EVENT_BYTES,
            "…and over it by bytes",
        )
        assertEquals(WireRejection.SERIALISED_EVENT_TOO_LARGE, refusal { wide.canonicalSerialisation() })
    }

    @JsName("nothing_is_truncated_to_fit")
    @Test
    fun `nothing is truncated to fit`() {
        val tooLong = "a".repeat(WireLimits.DEFAULT_MAX_CONTENT_BYTES + 1)
        val event = WireFixtures.eventWith(tags = emptyList(), content = tooLong)

        assertFailsWith<WireException> { event.canonicalSerialisation() }

        assertEquals(
            tooLong.length,
            event.content.length,
            "§4.3 requires rejecting rather than truncating, and a value the refusal quietly " +
                "shortened would be neither",
        )
    }

    @JsName("a_caller_may_raise_or_lower_every_bound_and_a_bound_below_one_is_refused")
    @Test
    fun `a caller may raise or lower every bound, and a bound below one is refused`() {
        val tiny = WireLimits(maxTagsPerEvent = 1)

        WireFixtures.eventWith(tags = listOf(listOf("t"))).canonicalSerialisation(tiny)
        assertEquals(
            WireRejection.TOO_MANY_TAGS,
            refusal {
                WireFixtures.eventWith(tags = listOf(listOf("t"), listOf("t"))).canonicalSerialisation(tiny)
            },
            "§4.3 says the bounds SHOULD be configurable, so a client's own smaller bound must bite",
        )
        for (build in listOf<() -> WireLimits>(
            { WireLimits(maxSerialisedEventBytes = 0) },
            { WireLimits(maxTagsPerEvent = 0) },
            { WireLimits(maxTagValueBytes = -1) },
            { WireLimits(maxContentBytes = 0) },
        )) {
            assertEquals(WireRejection.NON_POSITIVE_LIMIT, refusal { build() })
        }
    }

    @JsName("a_negative_created_at_is_refused_as_a_timestamp")
    @Test
    fun `a negative created_at is refused as a timestamp`() {
        assertEquals(
            WireRejection.NEGATIVE_TIMESTAMP,
            refusal { WireEvent(WireFixtures.pubkeyFor(0), -1L, 1, emptyList(), "") },
            "§4.3 fixes a timestamp as a non-negative integer",
        )
        WireEvent(WireFixtures.pubkeyFor(0), 0L, 1, emptyList(), "")
    }

    @JsName("a_pubkey_of_the_wrong_length_is_refused_as_the_wrong_length_never_padded")
    @Test
    fun `a pubkey of the wrong length is refused as the wrong length, never padded`() {
        val pubkey = WireFixtures.pubkeyFor(3)

        assertEquals(
            WireRejection.WRONG_LENGTH,
            refusal { WireEvent(pubkey.dropLast(1), 1L, 1, emptyList(), "") },
            "§4.3: a pubkey is exactly 64 hex characters, and a value of the wrong length MUST be " +
                "rejected rather than padded or truncated",
        )
        assertEquals(
            WireRejection.NOT_HEX,
            refusal { WireEvent(pubkey.dropLast(1) + "z", 1L, 1, emptyList(), "") },
        )
    }

    /**
     * An uppercase pubkey is **accepted** (§4.3's no-normalisation list is exhaustive and a pubkey
     * is not on it) and is **not** rewritten, and the second half is the one that matters.
     *
     * The pubkey goes into the id preimage. An implementation that lowercased it on the way in
     * would hash different bytes from the ones its author hashed, and would refuse a genuine event
     * as `ID_MISMATCH` — this library calling a real event forged, which is precisely the outcome
     * §4.1's recompute-and-compare rule exists to prevent. It fails in the safe direction, which
     * is why it survived a first review; nothing but this test would ever have found it.
     */
    @JsName("an_uppercase_pubkey_is_accepted_and_its_case_survives_into_the_id_preimage")
    @Test
    fun `an uppercase pubkey is accepted and its case survives into the id preimage`() {
        val pubkey = WireFixtures.pubkeyFor(3).uppercase()
        val event = WireEvent(pubkey, WireFixtures.CREATED_AT, WireFixtures.KIND, emptyList(), "")

        assertEquals(pubkey, event.pubkey, "the bytes the author signed are not this library's to rewrite")
        assertTrue(
            event.canonicalSerialisation().contains(pubkey),
            "the serialisation carries the pubkey exactly as it arrived",
        )
        val claimed = WireFixtures.lowerHex(
            WireFixtures.sha256(event.canonicalSerialisation().utf8Bytes()),
        )
        assertEquals(
            claimed,
            CheckedEvent.checkEventId(claimed, event).id.toHex(),
            "an event whose author serialised an uppercase pubkey verifies against its own id",
        )
    }

    @JsName("the_id_check_enforces_the_bounds_too_so_nothing_large_gets_past_it")
    @Test
    fun `the id check enforces the bounds too, so nothing large gets past it`() {
        val event = WireFixtures.eventWith(tags = List(WireLimits.DEFAULT_MAX_TAGS_PER_EVENT + 1) { listOf("t") })
        val claimed = EventId.of(event, UNBOUNDED).toHex()

        assertEquals(
            WireRejection.TOO_MANY_TAGS,
            refusal { CheckedEvent.checkEventId(claimed, event) },
            "§4.1's id check is the door every later decoder goes through, so a §4.3 bound that " +
                "bound only the write path would bound nothing that matters",
        )
        assertTrue(claimed.length == EventId.HEX_LENGTH)
    }
}
