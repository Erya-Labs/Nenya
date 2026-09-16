package dev.eryalabs.nenya.wire

import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.latin1Bytes
import dev.eryalabs.nenya.oracleSha256
import dev.eryalabs.nenya.utf8Bytes
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §4.1's id rule: the SHA-256 of the **UTF-8 bytes** of the canonical serialisation, recomputed
 * here and compared against what the relay claimed — before any other processing.
 *
 * No id and no digest is typed anywhere. Every expected value in this file is computed by
 * the platform's own SHA-256 in the test ([oracleSha256]: `MessageDigest` on the JVM), over bytes
 * the test encodes itself, so a serialiser and a hasher
 * that agreed with each other and with nothing else would still have to agree with an
 * independent encoding of the same rule.
 */
class EventIdTest {

    private companion object {

        fun sha256Hex(bytes: ByteArray): String =
            WireFixtures.lowerHex(oracleSha256(bytes))

        fun refusal(block: () -> Unit): WireRejection = assertFailsWith<WireException> { block() }.reason
    }

    @JsName("the_id_is_the_sha_256_of_the_serialisation_s_utf_8_bytes")
    @Test
    fun `the id is the SHA-256 of the serialisation's UTF-8 bytes`() {
        val event = WireFixtures.eventWith(
            tags = listOf(listOf("t", "nenya"), listOf("price", "50000", "SAT")),
            content = "a thirty-second animation",
        )

        val serialisation = event.canonicalSerialisation()

        assertEquals(
            sha256Hex(serialisation.utf8Bytes()),
            EventId.of(event).toHex(),
        )
    }

    /**
     * The charset is load-bearing, and this is the control that says so. For an event whose
     * content carries a non-BMP character the two encodings differ, so an implementation that
     * hashed the platform's default bytes — or ISO-8859-1, the JDK's cheapest wrong answer —
     * computes an id nobody else computes for exactly the events that carry an emoji.
     */
    @JsName("the_id_is_over_utf_8_bytes_and_differs_from_the_same_string_s_iso_8859_1_bytes")
    @Test
    fun `the id is over UTF-8 bytes and differs from the same string's ISO-8859-1 bytes`() {
        val event = WireFixtures.eventWith(
            tags = emptyList(),
            content = TestText.codePoint(0x1F600),
        )
        val serialisation = event.canonicalSerialisation()

        val utf8 = sha256Hex(serialisation.utf8Bytes())
        val latin1 = sha256Hex(serialisation.latin1Bytes())

        assertEquals(utf8, EventId.of(event).toHex())
        assertNotEquals(
            latin1,
            utf8,
            "the two encodings must actually differ for this fixture, or the control is vacuous",
        )
        assertNotEquals(latin1, EventId.of(event).toHex())
    }

    @JsName("a_matching_claim_yields_a_checked_event_carrying_the_id_the_event_and_the_bounds")
    @Test
    fun `a matching claim yields a checked event carrying the id, the event and the bounds`() {
        val event = WireFixtures.event()
        val claimed = EventId.of(event).toHex()

        val checked = CheckedEvent.checkEventId(claimed, event)

        assertEquals(claimed, checked.id.toHex())
        assertEquals(event, checked.event)
        assertEquals(
            WireLimits.DEFAULT.maxSerialisedEventBytes,
            checked.limits.maxSerialisedEventBytes,
            "§17 forbids reporting an unverified thing as verified, and \"bounded, by a bound that " +
                "bounds nothing\" is that; the bounds this check ran under are published for a " +
                "consumer that has to tell the two apart",
        )
    }

    @JsName("an_uppercase_claimed_id_is_accepted_and_normalised")
    @Test
    fun `an uppercase claimed id is accepted and normalised`() {
        val event = WireFixtures.event()
        val claimed = EventId.of(event).toHex()

        val checked = CheckedEvent.checkEventId(claimed.uppercase(), event)

        assertEquals(
            claimed,
            checked.id.toHex(),
            "§4.3's no-normalisation exception list has two entries — the BOLT-11 string and the " +
                "preimage — and states that it is exhaustive, so an event id follows the general " +
                "accept-and-normalise rule. Refusing a permissive peer here would be a bug.",
        )
        assertEquals(claimed.lowercase(), checked.id.toHex())
    }

    @JsName("a_claimed_id_of_63_characters_is_refused_as_the_wrong_length_never_padded")
    @Test
    fun `a claimed id of 63 characters is refused as the wrong length, never padded`() {
        val event = WireFixtures.event()
        val claimed = EventId.of(event).toHex()

        assertEquals(WireRejection.WRONG_LENGTH, refusal { CheckedEvent.checkEventId(claimed.drop(1), event) })
        assertEquals(WireRejection.WRONG_LENGTH, refusal { CheckedEvent.checkEventId(claimed + "0", event) })
        assertEquals(WireRejection.WRONG_LENGTH, refusal { EventId.ofHex("") })
        assertEquals(
            WireRejection.NOT_HEX,
            refusal { CheckedEvent.checkEventId(claimed.dropLast(1) + "g", event) },
        )
    }

    @JsName("a_claim_that_does_not_match_produces_no_checked_event_at_all")
    @Test
    fun `a claim that does not match produces no checked event at all`() {
        val event = WireFixtures.event()
        val other = WireFixtures.events(2).last()

        assertEquals(
            WireRejection.ID_MISMATCH,
            refusal { CheckedEvent.checkEventId(EventId.of(other).toHex(), event) },
            "§4.1: an implementation MUST reject a received event whose id does not match its " +
                "recomputed value, before any other processing. Producing nothing is how that is " +
                "enforced — every later decoder takes a CheckedEvent.",
        )
        assertNotEquals(EventId.of(other), EventId.of(event))
    }

    @JsName("one_changed_byte_anywhere_changes_the_id")
    @Test
    fun `one changed byte anywhere changes the id`() {
        val base = WireFixtures.eventWith(tags = listOf(listOf("t", "nenya")), content = "hello")
        val id = EventId.of(base)

        val variants = listOf(
            WireFixtures.eventWith(tags = listOf(listOf("t", "nenya")), content = "hellp"),
            WireFixtures.eventWith(tags = listOf(listOf("t", "nenyb")), content = "hello"),
            WireFixtures.eventWith(tags = listOf(listOf("t", "nenya"), listOf("x")), content = "hello"),
            WireFixtures.eventWith(tags = listOf(listOf("t", "nenya")), content = "hello", index = 1),
            WireEvent(WireFixtures.pubkeyFor(0), WireFixtures.CREATED_AT + 1, WireFixtures.KIND, listOf(listOf("t", "nenya")), "hello"),
            WireEvent(WireFixtures.pubkeyFor(0), WireFixtures.CREATED_AT, WireFixtures.KIND + 1, listOf(listOf("t", "nenya")), "hello"),
        )

        for (variant in variants) {
            assertNotEquals(id, EventId.of(variant), "all five id-bearing fields must reach the digest")
        }
    }

    @JsName("an_event_id_reads_back_from_its_own_hex_in_either_case")
    @Test
    fun `an event id reads back from its own hex, in either case`() {
        val id = EventId.of(WireFixtures.event())

        assertEquals(id, EventId.ofHex(id.toHex()))
        assertEquals(id, EventId.ofHex(id.toHex().uppercase()))
        assertEquals(id.toHex(), EventId.ofHex(id.toHex().uppercase()).toHex())
        assertEquals(EventId.BYTE_LENGTH, id.bytes().size)
    }

    @JsName("an_event_id_copies_its_bytes_on_the_way_out")
    @Test
    fun `an event id copies its bytes on the way out`() {
        val id = EventId.of(WireFixtures.event())
        val before = id.toHex()

        id.bytes().fill(0)

        assertEquals(before, id.toHex())
    }

    /**
     * §12 item 11 forbids a secret in "the string representation of any value the implementation
     * exposes", and STOP RULE 14 adds a counterparty pubkey and a listing id to the same
     * sentence. A default Kotlin `toString` would carry the pubkey and the whole of `content`;
     * this is the control that catches the debugging `toString` somebody adds later.
     */
    @JsName("no_tostring_here_names_a_pubkey_an_id_or_a_byte_of_content")
    @Test
    fun `no toString here names a pubkey, an id or a byte of content`() {
        val secretish = "the buyer's shipping address"
        val event = WireFixtures.eventWith(tags = listOf(listOf("t", "nenya")), content = secretish)
        val id = EventId.of(event)
        val checked = CheckedEvent.checkEventId(id.toHex(), event)

        for (rendered in listOf(event.toString(), id.toString(), checked.toString())) {
            assertFalse(rendered.contains(event.pubkey), "a pubkey reached a toString: $rendered")
            assertFalse(rendered.contains(id.toHex()), "an event id reached a toString: $rendered")
            assertFalse(rendered.contains(secretish), "content reached a toString: $rendered")
        }
        assertTrue(event.toString().contains("kind="), "…and the toString must still say something")
    }

    /**
     * The other string a client logs, and the one no control covered.
     *
     * A rejection message is written at the moment the offending value is in scope, so it is the
     * easiest place in this package to echo one by accident — `"…this one carries $value"` on the
     * `NOT_HEX` branch is a single edit away and reads like better diagnostics. §12 item 2 names
     * the counterparty pubkey among the values that MUST NOT appear outside an encrypted event
     * and item 11 forbids one in a crash report; a message may name a length, a count, a bound
     * and a reason, and nothing the caller handed in.
     */
    @JsName("no_rejection_message_echoes_a_pubkey_a_tag_value_or_a_byte_of_content")
    @Test
    fun `no rejection message echoes a pubkey, a tag value or a byte of content`() {
        val pubkey = WireFixtures.pubkeyFor(4)
        val secretish = "the buyer's shipping address"
        val tagValue = "wss://relay.example.invalid/the-buyer's-inbox"
        val event = WireFixtures.eventWith(tags = listOf(listOf("relay", tagValue)), content = secretish)

        val messages = listOf(
            { WireEvent(pubkey.dropLast(1), 1L, 1, emptyList(), secretish) },
            { WireEvent(pubkey.dropLast(1) + "z", 1L, 1, emptyList(), secretish) },
            { WireEvent(pubkey, -1L, 1, listOf(listOf(tagValue)), secretish) },
            { WireFixtures.eventWith(tags = listOf(listOf(tagValue), emptyList())) },
            { EventId.ofHex(pubkey.dropLast(1)) },
            { CheckedEvent.checkEventId(EventId.of(WireFixtures.event()).toHex(), event) },
            {
                WireEvent(pubkey, 1L, 1, listOf(listOf("a".repeat(2000))), "")
                    .canonicalSerialisation()
            },
            {
                WireEvent(pubkey, 1L, 1, emptyList(), "x".repeat(WireLimits.DEFAULT_MAX_CONTENT_BYTES + 1))
                    .canonicalSerialisation()
            },
        ).map { block -> assertFailsWith<WireException> { block() }.message.orEmpty() }

        // Every branch having thrown is enforced by the `assertFailsWith` inside the map above,
        // not here; this only pins that no branch was quietly dropped from the list.
        assertEquals(8, messages.size, "all eight branches must still be exercised")
        for (message in messages) {
            assertTrue(message.isNotEmpty(), "a rejection must still explain itself")
            assertFalse(message.contains(pubkey.take(16)), "a pubkey reached a message: $message")
            assertFalse(message.contains(secretish), "content reached a message: $message")
            assertFalse(message.contains(tagValue), "a tag value reached a message: $message")
            assertFalse(message.contains("aaaa"), "a tag value reached a message: $message")
        }
    }
}
