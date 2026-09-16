package dev.eryalabs.nenya.wire

import java.security.MessageDigest
import java.util.Random

/**
 * The generator behind every event fixture in this package.
 *
 * This is not a scratch file: the queue's Definition of done forbids an encoded value appearing
 * in a test as something somebody typed out. No event id, no digest and no pubkey here is
 * typed — the pubkeys are SHA-256 digests of a per-index label, the strings are assembled from
 * an alphabet by a `java.util.Random` pinned to [SEED], and every id under test is computed by
 * `MessageDigest`. A reviewer can change [SEED], re-run the suite, and every property must still
 * hold.
 *
 * ### The alphabet is the point
 *
 * §4.1 is an escaping rule, so a corpus of ordinary ASCII proves nothing at all: it exercises
 * rule 3 ten thousand times and rules 1 and 2 never. [ALPHABET] therefore carries every one of
 * the 32 control characters, the quote, the backslash, `/`, `0x7f` (DEL), two non-BMP characters
 * and the empty string — the exact set §4.1's MUST NOTs are written about — and the first
 * [ALPHABET]`.size` events carry one entry each in `content` so coverage is deterministic rather
 * than a matter of luck with a seed. `WirePropertyTest` asserts that coverage rather than
 * assuming it.
 *
 * ### Distinctness is by construction
 *
 * Every event's pubkey is the digest of its own index, so no two generated events are equal.
 * That is load-bearing for the "distinct events produce distinct ids" property: over a corpus
 * with duplicates in it, a serialiser that emitted a constant would still be caught, but the
 * assertion would be about the generator rather than about the serialiser.
 */
internal object WireFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random` rather than `kotlin.random` is
     * deliberate: its algorithm is specified by the JDK, so this file produces the same runs on
     * any JVM a reviewer re-runs it on.
     */
    const val SEED: Long = 20260910L

    /** Every character §4.1's rules and MUST NOTs are written about, plus ordinary text. */
    val ALPHABET: List<String> = buildList {
        add("")
        // The 32 control characters: five of them rule 1 names, 27 of them rule 2 covers.
        for (code in 0x00..0x1F) add(code.toChar().toString())
        // Rule 1's other two, and the two characters §4.1 names as MUST NOT escape / MUST NOT
        // escape-above-0x20: `/` and 0x7f (DEL).
        for (code in listOf(0x22, 0x5C, 0x2F, 0x7F)) add(code.toChar().toString())
        // Multi-byte UTF-8, including two non-BMP code points that travel as surrogate pairs.
        add("é")
        add("中")
        add(String(Character.toChars(0x1F600)))
        add(String(Character.toChars(0x10348)))
        // Ordinary values, so the corpus is not pathological end to end.
        add("nenya")
        add("wss://relay.example.invalid")
        add("30402:d-value:with:colons")
    }

    /** [count] events, provably distinct, drawn from one seeded run. */
    fun events(count: Int): List<WireEvent> {
        val random = Random(SEED)
        return List(count) { index ->
            val tags = List(random.nextInt(5)) {
                List(1 + random.nextInt(3)) { hostileString(random, 3) }
            }
            WireEvent(
                pubkey = pubkeyFor(index),
                createdAt = (random.nextInt(Int.MAX_VALUE)).toLong(),
                kind = random.nextInt(65_536),
                tags = tags,
                content = if (index < ALPHABET.size) ALPHABET[index] else hostileString(random, 6),
            )
        }
    }

    /** One event, drawn from the same seeded run so a single-case test and a property agree. */
    fun event(): WireEvent = events(1).single()

    /** An event with exactly these tags and content, and a stable generated pubkey. */
    fun eventWith(tags: List<List<String>>, content: String = "", index: Int = 0): WireEvent =
        WireEvent(
            pubkey = pubkeyFor(index),
            createdAt = CREATED_AT,
            kind = KIND,
            tags = tags,
            content = content,
        )

    /** A fixed, non-round timestamp, so a serialiser that emitted a constant is visible. */
    const val CREATED_AT: Long = 1_767_225_600L

    /** §5.2's request kind, used only as a number here; this package knows no kind vocabulary. */
    const val KIND: Int = 30_404

    /** A generated 64-character lowercase-hex pubkey, unique per [index] and never typed. */
    fun pubkeyFor(index: Int): String = lowerHex(sha256("nenya-wire-fixture-$index".toByteArray()))

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** §4.3's canonical form: lowercase, unpadded. Written here so no test transcribes hex. */
    fun lowerHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }

    private fun hostileString(random: Random, maxPieces: Int): String {
        val out = StringBuilder()
        repeat(random.nextInt(maxPieces + 1)) {
            out.append(ALPHABET[random.nextInt(ALPHABET.size)])
        }
        return out.toString()
    }
}
