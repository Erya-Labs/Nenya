package dev.eryalabs.nenya.wire

import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.text.isWellFormedUtf16
import dev.eryalabs.nenya.text.strictUtf8OrNull
import dev.eryalabs.nenya.utf8Bytes
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §4.1: text with no UTF-8 encoding is **rejected**, never hashed with a substitute character.
 *
 * The reason is interoperability, not tidiness. The JVM's UTF-8 encoder writes `?` (0x3F) for an
 * unpaired surrogate and JavaScript's writes U+FFFD (EF BF BD), so a library that encoded leniently
 * would give one event two ids depending on which build computed it. Every refusal below asserts
 * [WireRejection.UNPAIRED_SURROGATE] itself, not merely that something was thrown: a test that
 * accepted any [WireException] would pass over an event refused for the wrong reason.
 *
 * No expected id is typed. The accepted case builds the expected UTF-8 bytes itself, with the
 * astral character's four bytes written out, and hashes them with the platform's SHA-256
 * (`MessageDigest` on the JVM).
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as
 * `UnpairedSurrogateTest` on each target, so every test keeps its `UnpairedSurrogateTest` name, and
 * the refusal is proved on JavaScript, whose encoder substitutes U+FFFD, as well as on the JVM. The
 * one test that also asserts the JVM encoder's own `?` substitution through `Charsets.UTF_8` is in
 * the JVM `UnpairedSurrogateTest` (`src/jvmTest`); its assertions about the common encoder are
 * repeated here, against [TestText.utf8], by
 * `the common encoder refuses every ill-formed string and encodes every well-formed one`.
 */
abstract class PortableUnpairedSurrogateTest {

    protected companion object {

        const val HIGH: Char = '\uD83C'
        const val LOW: Char = '\uDFA8'

        /** U+1F3A8 ARTIST PALETTE — HIGH then LOW — and its UTF-8 encoding. */
        val PALETTE: String = "$HIGH$LOW"
        val PALETTE_UTF8: ByteArray = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x8E.toByte(), 0xA8.toByte())

        fun refusal(block: () -> Unit): WireRejection = assertFailsWith<WireException> { block() }.reason

        /** Every public door through which a string reaches the id hash, for one event. */
        fun everyDoorRefuses(event: WireEvent, label: String) {
            assertEquals(
                WireRejection.UNPAIRED_SURROGATE,
                refusal { event.canonicalSerialisation() },
                "$label: canonicalSerialisation",
            )
            assertEquals(WireRejection.UNPAIRED_SURROGATE, refusal { EventId.of(event) }, "$label: EventId.of")
            assertEquals(
                WireRejection.UNPAIRED_SURROGATE,
                refusal { CheckedEvent.checkEventId("0".repeat(EventId.HEX_LENGTH), event) },
                "$label: checkEventId must refuse for the surrogate, not report an id mismatch",
            )
        }

        fun inContentAndInATag(text: String, label: String) {
            everyDoorRefuses(WireFixtures.eventWith(tags = emptyList(), content = text), "$label in content")
            everyDoorRefuses(
                WireFixtures.eventWith(tags = listOf(listOf("t", "nenya"), listOf("alt", text))),
                "$label in a tag value",
            )
        }
    }

    @JsName("a_lone_high_surrogate_is_rejected_as_an_unpaired_surrogate")
    @Test
    fun `a lone high surrogate is rejected as an unpaired surrogate`() {
        inContentAndInATag("a${HIGH}b", "a high surrogate mid-string")
        inContentAndInATag("ab$HIGH", "a high surrogate at the very end")
        inContentAndInATag("$HIGH$HIGH$LOW", "a high surrogate followed by a valid pair")
    }

    @JsName("a_lone_low_surrogate_is_rejected_as_an_unpaired_surrogate")
    @Test
    fun `a lone low surrogate is rejected as an unpaired surrogate`() {
        inContentAndInATag("a${LOW}b", "a low surrogate mid-string")
        inContentAndInATag("${LOW}ab", "a low surrogate at the very start")
        inContentAndInATag("$HIGH$LOW$LOW", "a valid pair followed by a low surrogate")
    }

    @JsName("a_reversed_surrogate_pair_is_rejected_as_an_unpaired_surrogate")
    @Test
    fun `a reversed surrogate pair is rejected as an unpaired surrogate`() {
        inContentAndInATag("a$LOW${HIGH}b", "a low-then-high pair")
    }

    @JsName("a_valid_astral_character_is_accepted_and_hashed_over_its_four_utf_8_bytes")
    @Test
    fun `a valid astral character is accepted and hashed over its four UTF-8 bytes`() {
        val pubkey = WireFixtures.pubkeyFor(0)
        val event = WireFixtures.eventWith(tags = listOf(listOf("t", PALETTE)), content = "a${PALETTE}b")

        val expectedBytes = ArrayList<Byte>().apply {
            // US-ASCII written out by hand: every character here is below 0x80, and one that is not
            // fails rather than being encoded, so no multi-byte sequence can arrive by this route.
            fun ascii(text: String) = text.forEach { char ->
                assertTrue(char.code < 0x80, "not ASCII: ${char.code}")
                add(char.code.toByte())
            }
            fun write(bytes: ByteArray) = bytes.forEach { add(it) }
            ascii("[0,\"$pubkey\",${WireFixtures.CREATED_AT},${WireFixtures.KIND},[[\"t\",\"")
            write(PALETTE_UTF8)
            ascii("\"]],\"a")
            write(PALETTE_UTF8)
            ascii("b\"]")
        }.toByteArray()

        assertContentEquals(expectedBytes, event.canonicalSerialisation().utf8Bytes())
        val id = EventId.of(event)
        assertEquals(WireFixtures.lowerHex(WireFixtures.sha256(expectedBytes)), id.toHex())
        assertEquals(id, CheckedEvent.checkEventId(id.toHex(), event).id, "and the id check accepts it")
    }

    /**
     * The common encoder the wire package routes through, held to an encoder that is not the
     * library's: [TestText.utf8], written independently and checked against the JDK in
     * `CommonTestOraclesTest`. These are the assertions of the JVM-only
     * `the common encoder refuses exactly what the JVM encoder silently substituted` that need no
     * `Charsets`, so the rule that the JVM and JavaScript agree is checked on JavaScript too, where
     * the platform encoder would have written U+FFFD rather than `?`.
     */
    @JsName("the_common_encoder_refuses_every_ill_formed_string_and_encodes_every_well_formed_one")
    @Test
    fun `the common encoder refuses every ill-formed string and encodes every well-formed one`() {
        for (text in listOf("a${HIGH}b", "a${LOW}b", "a$LOW${HIGH}b", "ab$HIGH", "$LOW", "$HIGH")) {
            val units = text.map { it.code.toString(16) }
            assertFalse(isWellFormedUtf16(text), "ill-formed: $units")
            assertNull(strictUtf8OrNull(text), "never encoded: $units")
            assertFailsWith<IllegalArgumentException>("the independent encoder must agree it has no UTF-8: $units") {
                TestText.utf8(text)
            }
        }
        for (text in listOf("", "plain", "a${PALETTE}b", "\u00e9\u4e2d$PALETTE$PALETTE")) {
            assertTrue(isWellFormedUtf16(text), "well-formed: ${text.map { it.code.toString(16) }}")
            assertContentEquals(TestText.utf8(text), assertNotNull(strictUtf8OrNull(text)))
        }
        assertContentEquals(PALETTE_UTF8, strictUtf8OrNull(PALETTE))
    }

    /**
     * An unpaired surrogate has no byte length, so it is refused before any byte bound is
     * measured: a hostile value that is both too long and ill-formed is refused for the surrogate.
     * And, as for every other refusal in this package, the message names the field and never the
     * text (§12 items 2 and 11).
     */
    @JsName("the_refusal_comes_before_the_byte_bound_and_never_echoes_the_text")
    @Test
    fun `the refusal comes before the byte bound and never echoes the text`() {
        val secretish = "the buyer's shipping address"
        val overlong = "x".repeat(WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES + 1) + HIGH
        val tagged = WireFixtures.eventWith(tags = listOf(listOf("alt", overlong)))
        assertEquals(WireRejection.UNPAIRED_SURROGATE, refusal { tagged.canonicalSerialisation() })

        val inContent = WireFixtures.eventWith(tags = emptyList(), content = "$secretish$LOW")
        val inTag = WireFixtures.eventWith(tags = listOf(listOf("t", "nenya"), listOf("alt", "$secretish$HIGH")))
        val contentMessage = assertFailsWith<WireException> { EventId.of(inContent) }.message.orEmpty()
        val tagMessage = assertFailsWith<WireException> { EventId.of(inTag) }.message.orEmpty()

        assertTrue(contentMessage.startsWith("content "), contentMessage)
        assertTrue(tagMessage.startsWith("a value of tag 1 "), tagMessage)
        for (message in listOf(contentMessage, tagMessage)) {
            assertFalse(message.contains(secretish), "the text reached a message: $message")
            assertFalse(message.contains(HIGH) || message.contains(LOW), "a surrogate reached a message")
        }
    }
}
