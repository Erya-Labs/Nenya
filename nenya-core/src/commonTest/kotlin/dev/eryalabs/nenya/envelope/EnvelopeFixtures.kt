package dev.eryalabs.nenya.envelope

import dev.eryalabs.nenya.seam.FakeCryptoSigner
import dev.eryalabs.nenya.seam.FakeKey
import dev.eryalabs.nenya.seam.Nip44PayloadSigner
import dev.eryalabs.nenya.seam.provided
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.EventJson
import dev.eryalabs.nenya.wire.ReadEvent
import dev.eryalabs.nenya.wire.WireEvent
import dev.eryalabs.nenya.wire.WireLimits
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The parties, the rumors and the reading half §7.1's write path is measured against.
 *
 * Nothing here is typed: every key is a [FakeKey], which is a SHA-256 digest of a pinned label, and
 * every rumor is built by this file rather than transcribed. A reviewer can change a stream, re-run
 * the suite, and every property must still hold.
 *
 * ### The reader is the test's, and that is deliberate
 *
 * `GiftWrap.open` does not exist yet, so a test that asserted the write path by calling the read
 * path would be asserting nothing at all today and would silently become circular tomorrow. So
 * this file opens a wrap the way a **counterparty** would: `EventJson.read` for the structure,
 * `CheckedEvent.checkEventId` for §4.1's recomputation, and the recipient's own
 * [FakeCryptoSigner.nip44Decrypt] for the two decryptions — all of them functions that existed
 * before this task. What is proved is therefore that somebody else's reader can open what this
 * library sealed.
 */
internal object EnvelopeFixtures {

    /** The sender's stream. Single digits are free for named parties (`FakeKeyRanges`). */
    const val SENDER_STREAM: Long = 1L

    /** The recipient's stream. */
    const val RECIPIENT_STREAM: Long = 2L

    /** A third party, for the rumor that claims somebody else's key. */
    const val STRANGER_STREAM: Long = 3L

    /**
     * The clock reading every control uses, comfortably past §7.1 step 5's two-day window so that
     * `max(0, now − 172 800)` is not the floor and the window is its full width.
     *
     * A plain number of unix seconds, chosen rather than read: §4.6 makes the injected clock
     * authoritative and a test pins it.
     */
    const val NOW: Long = 1_800_000_000L

    /** §4.3's bounds for reading back a `kind:13` seal — NIP-44's plaintext ceiling. */
    val SEAL_LIMITS: WireLimits = WireLimits(
        maxSerialisedEventBytes = EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES,
        maxTagsPerEvent = WireLimits.DEFAULT_MAX_TAGS_PER_EVENT,
        maxTagValueBytes = WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES,
        maxContentBytes = EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES,
    )

    /** §4.3's bounds for reading back a `kind:1059` wrap. */
    val WRAP_LIMITS: WireLimits = WireLimits(
        maxSerialisedEventBytes = EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES,
        maxTagsPerEvent = WireLimits.DEFAULT_MAX_TAGS_PER_EVENT,
        maxTagValueBytes = WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES,
        maxContentBytes = EnvelopeLimits.MAX_WRAP_CONTENT_CHARS,
    )

    fun senderSigner(): Nip44PayloadSigner = Nip44PayloadSigner(FakeKey(SENDER_STREAM))

    fun recipientSigner(): Nip44PayloadSigner = Nip44PayloadSigner(FakeKey(RECIPIENT_STREAM))

    fun senderKey(): String = FakeKey(SENDER_STREAM).hex

    fun recipientKey(): String = FakeKey(RECIPIENT_STREAM).hex

    fun strangerKey(): String = FakeKey(STRANGER_STREAM).hex

    /**
     * An ordinary §7.4 rumor: a `kind:16` order message carrying the tags a real one does, so the
     * redaction sweep has an order id and a counterparty pubkey to look for.
     *
     * The `created_at` is the **true** time (§7.1 step 1), which is the one timestamp in the
     * envelope that is not randomised.
     */
    fun rumor(
        pubkey: String = senderKey(),
        createdAt: Long = NOW,
        content: String = RUMOR_CONTENT,
        orderIdHex: String = ORDER_ID_HEX,
    ): WireEvent = WireEvent(
        pubkey = pubkey,
        createdAt = createdAt,
        kind = NenyaKind.ORDER_MESSAGE,
        tags = listOf(
            listOf("nenya", "1"),
            listOf("p", recipientKey()),
            listOf("order", orderIdHex),
            listOf("type", "1"),
        ),
        content = content,
    )

    /**
     * The `content` every fixture rumor carries, so the redaction sweep has a distinctive string to
     * search every refusal message for.
     */
    const val RUMOR_CONTENT: String = "the-private-terms-of-this-order-must-never-reach-a-log"

    /**
     * A 32-byte order id in hex, derived from a [FakeKey] rather than typed — §12 item 11 puts an
     * order id in one sentence with key material, so the sweep looks for this too.
     */
    val ORDER_ID_HEX: String = FakeKey(stream = 99L).hex

    /**
     * A rumor whose §7.1 object form is **exactly** [target] bytes, built out of tags.
     *
     * Tags rather than `content`, because §4.3's ordinary 16 KiB `content` bound still applies to a
     * rumor — [EnvelopeLimits] says so in as many words, and nothing in this task relaxes it. A
     * rumor at §7.1 step 1's 40 960-byte ceiling is therefore a rumor with a lot of tags, which is
     * what the whole-event bound actually permits.
     *
     * The size is **measured and then closed exactly**, never assumed: the overhead of one more tag
     * is obtained by adding an empty-valued one and subtracting, so this function cannot be wrong
     * about the shape of the JSON it is counting.
     */
    fun rumorOfJsonBytes(
        target: Int,
        pubkey: String = senderKey(),
        createdAt: Long = NOW,
        limits: WireLimits = WireLimits.DEFAULT,
    ): WireEvent {
        var tags = listOf<List<String>>()
        while (true) {
            val candidate = tags + listOf(tagOf(CHUNK))
            if (jsonBytes(eventOf(candidate, pubkey, createdAt), limits) > target) break
            tags = candidate
        }
        // Close the remainder with one more tag whose value length is solved for rather than
        // guessed. If the whole-tag loop overshot the room a padded tag needs, give one chunk back.
        var padded = solve(tags, target, pubkey, createdAt, limits)
        if (padded == null && tags.isNotEmpty()) {
            tags = tags.dropLast(1)
            padded = solve(tags, target, pubkey, createdAt, limits)
        }
        val value = padded ?: fail("no rumor of exactly $target bytes can be built from tags alone")
        val event = eventOf(tags + listOf(tagOf(value)), pubkey, createdAt)
        assertEquals(
            target,
            jsonBytes(event, limits),
            "the fixture must be exactly the size it claims, or the bound it probes is not the " +
                "bound being tested",
        )
        return event
    }

    /** The JSON [GiftWrap.seal] itself would write for [event], in UTF-8 bytes (all ASCII here). */
    fun jsonBytes(event: WireEvent, limits: WireLimits = WireLimits.DEFAULT): Int =
        EventJson.writeUnsigned(event, EventId.of(event, limits), limits).length

    // -----------------------------------------------------------------------------------------
    // The reading half.
    // -----------------------------------------------------------------------------------------

    /** One wrap opened the way a counterparty opens it, every layer read back and id-checked. */
    class Opened(
        val wrap: ReadEvent,
        val wrapChecked: CheckedEvent,
        val sealJson: String,
        val seal: ReadEvent,
        val sealChecked: CheckedEvent,
        val rumorJson: String,
        val rumor: ReadEvent,
        val rumorChecked: CheckedEvent,
    )

    /**
     * §7.1's read procedure as far as this task's proof needs it, using only what existed before it.
     *
     * @param reader the addressee's own signer — the recipient's for `toRecipient`, the sender's
     *   for `toSelf`. A reader holding any other key gets nothing, which is what
     *   `FakeCrypto`'s header means by modelling confidentiality against an adversary that goes
     *   through the same fakes.
     */
    fun open(wrapJson: String, reader: FakeCryptoSigner): Opened {
        val wrap = EventJson.read(wrapJson, WRAP_LIMITS)
        val wrapChecked = CheckedEvent.checkEventId(wrap.claimedIdHex, wrap.event, WRAP_LIMITS)
        assertEquals(NenyaKind.GIFT_WRAP, wrap.event.kind, "§7.1 step 3: the kind is 1059 and only 1059")

        val sealJson = reader.nip44Decrypt(wrap.event.pubkey, wrap.event.content).provided()
        val seal = EventJson.read(sealJson, SEAL_LIMITS)
        val sealChecked = CheckedEvent.checkEventId(seal.claimedIdHex, seal.event, SEAL_LIMITS)
        assertEquals(NenyaKind.SEAL, seal.event.kind, "§7.1 step 2: the seal is a kind:13")

        val rumorJson = reader.nip44Decrypt(seal.event.pubkey, seal.event.content).provided()
        val rumor = EventJson.read(rumorJson)
        val rumorChecked = CheckedEvent.checkEventId(rumor.claimedIdHex, rumor.event)
        assertTrue(
            rumor.signatureHex == null,
            "§7.1 step 1 and step 8: a rumor carries no `sig` key at all",
        )
        return Opened(wrap, wrapChecked, sealJson, seal, sealChecked, rumorJson, rumor, rumorChecked)
    }

    /**
     * A rumor with nothing in it but §4.1's five fields, for the property tests.
     *
     * The tests that run ten thousand messages assert over the **envelope** — timestamps and
     * throwaway keys — and nothing about the rumor's contents, and every byte of a rumor is
     * encrypted twice, decrypted twice, serialised four times and hashed at every layer. A small
     * rumor is therefore not a weaker fixture there, it is the same fixture without the work; the
     * assertions that are about a rumor's contents use [rumor], which carries §7.4's real tags.
     *
     * It keeps this test suite well clear of the JavaScript runner's 30-second per-test budget,
     * which the JVM run cannot measure and which is the one failure this loop cannot debug.
     */
    fun minimalRumor(pubkey: String = senderKey(), createdAt: Long = NOW): WireEvent =
        WireEvent(pubkey, createdAt, NenyaKind.CHAT, emptyList(), "")

    /**
     * The two `created_at` values of one emitted copy — the wrap's, read off its JSON, and its
     * seal's, read after the one decryption that reaches it.
     *
     * Stops at the seal deliberately. [open] goes on to decrypt, parse and id-check the rumor, and
     * a property test over ten thousand messages that reads only two timestamps would be paying
     * for all of it eighty thousand times.
     */
    fun envelopeTimestamps(wrapJson: String, reader: FakeCryptoSigner): Pair<Long, Long> {
        val wrap = EventJson.read(wrapJson, WRAP_LIMITS)
        val sealJson = reader.nip44Decrypt(wrap.event.pubkey, wrap.event.content).provided()
        val seal = EventJson.read(sealJson, SEAL_LIMITS)
        assertEquals(NenyaKind.SEAL, seal.event.kind)
        return wrap.event.createdAt to seal.event.createdAt
    }

    /** The single `["p", …]` tag §7.1 step 3 requires of a wrap. */
    fun recipientTagOf(wrap: ReadEvent): List<String> {
        val tags = wrap.event.tags.filter { it[0] == "p" }
        assertEquals(1, tags.size, "§7.1 step 3: exactly one `p` tag, naming the addressee")
        return tags.single()
    }

    // -----------------------------------------------------------------------------------------
    // Internals of the exact-size builder.
    // -----------------------------------------------------------------------------------------

    /** 1000 characters, comfortably inside §4.3's 1024-byte tag-value bound. */
    private const val CHUNK: Int = 1000

    private const val FILLER: Char = 'a'

    private fun tagOf(length: Int): List<String> = listOf("x", FILLER.toString().repeat(length))

    private fun eventOf(tags: List<List<String>>, pubkey: String, createdAt: Long): WireEvent =
        WireEvent(pubkey, createdAt, NenyaKind.CHAT, tags, "")

    /**
     * The value length that makes one more tag land exactly on [target], or `null` when no
     * non-negative length within §4.3's tag-value bound does.
     *
     * Linear by construction — one more character of an ASCII tag value is one more byte of JSON —
     * so the overhead is measured once with an empty value rather than counted by hand.
     */
    private fun solve(
        tags: List<List<String>>,
        target: Int,
        pubkey: String,
        createdAt: Long,
        limits: WireLimits,
    ): Int? {
        val base = jsonBytes(eventOf(tags + listOf(tagOf(0)), pubkey, createdAt), limits)
        val value = target - base
        return if (value in 0..WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES) value else null
    }
}
