package dev.eryalabs.nenya.envelope

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.fail

/**
 * §4.3's envelope bounds and §7.1's randomisation window, **derived** from the vendored NIP-44
 * vectors and **parsed** out of the specification — never transcribed.
 *
 * Three numbers in this repository have to agree: what §4.3 publishes, what [EnvelopeLimits]
 * declares, and what NIP-44 actually permits. Transcribing any of them into a test would prove
 * only that the test agrees with whoever wrote it. So this object computes them from the one
 * externally-authored artefact available — `nip44.vectors.json`, whose SHA-256 §18 publishes —
 * and reads the specification's own sentences at test time. A NIP-44 revision that changed the
 * padding, a specification revision that changed a bound, and a hand edit of [EnvelopeLimits]
 * each turn the suite red rather than leaving three copies drifting apart.
 *
 * ### Everything is a function of its inputs
 *
 * [derive] takes the padding function and the plaintext ceiling as parameters, and [published]
 * takes the specification's lines, so a control can feed either a deliberately wrong one and be
 * measured by **the** derivation rather than by a second implementation of it that could drift
 * into agreeing with whatever it was handed. That is what makes the negative controls in
 * `Section71Test` worth anything.
 *
 * Every accessor fails loudly naming what it read, so a moved heading or a renamed JSON key
 * cannot make a test pass vacuously.
 */
internal object Section71 {

    // ------------------------------------------------------------------ NIP-44's own arithmetic

    /**
     * NIP-44's `calc_padded_len`, transcribed from the pseudocode in `spec/reference/nip44.md`
     * and checked against every `v2.valid.calc_padded_len` vector by `Section71Test`.
     *
     * The published form is `1 << (floor(log2(unpadded_len - 1)) + 1)`, which is the smallest
     * power of two at or above `unpadded_len`. It is written here as a doubling loop rather than
     * with a bit-count intrinsic so that what it computes is visible on the page, and so that the
     * mutation control — the same function with the chunking removed — is a one-line change to a
     * shape a reader can compare.
     */
    internal fun calcPaddedLen(unpaddedLen: Int): Int {
        require(unpaddedLen >= 1) { "NIP-44's minimum plaintext is 1 byte; asked for $unpaddedLen" }
        if (unpaddedLen <= 32) return 32
        val nextPower = nextPowerOfTwoAtLeast(unpaddedLen)
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    /**
     * The smallest power of two at or above [n], which is what NIP-44's `next_power` is.
     *
     * Bounded rather than left to overflow. The input comes from an **external** file, and a
     * vector row upstream might one day add near `Int.MAX_VALUE`: doubling past 2^30 reaches
     * `Int.MIN_VALUE` and then `0`, and the loop would never terminate — a hung suite instead of
     * a failing one, which is the worst outcome available to an unattended run.
     */
    internal fun nextPowerOfTwoAtLeast(n: Int): Int {
        require(n <= LARGEST_REPRESENTABLE_POWER_OF_TWO) {
            "$n exceeds $LARGEST_REPRESENTABLE_POWER_OF_TWO, above which no power of two fits in " +
                "an Int; NIP-44's largest vendored vector is 65 536"
        }
        var power = 1
        while (power < n) power = power shl 1
        return power
    }

    /**
     * A NIP-44 payload's size in raw bytes, for a plaintext of [plaintextBytes].
     *
     * The layout is a 1-byte version, a 32-byte nonce, the ciphertext — a 2-byte big-endian
     * length prefix plus the padded plaintext, which ChaCha20 does not resize — and a 32-byte
     * MAC. `nip44.md`'s own commentary states the two ends of the range this produces
     * ("raw payload (small): 99 … to 65603"), which is the cross-check that the 67 here is right.
     *
     * [padding] is a parameter so a control can supply a wrong one and watch the suite notice.
     */
    internal fun payloadBytes(plaintextBytes: Int, padding: (Int) -> Int): Int =
        VERSION_BYTE + NONCE_BYTES + LENGTH_PREFIX_BYTES + padding(plaintextBytes) + MAC_BYTES

    /** Standard base64 with padding: four characters per three bytes, rounded up. */
    internal fun base64Chars(bytes: Int): Int = 4 * ((bytes + 2) / 3)

    // ------------------------------------------------------------------------ the derivation

    /** What [derive] computes: §4.3's three envelope numbers, from NIP-44 and nothing else. */
    internal data class Derived(
        val maxPlaintextBytes: Int,
        val maxWrapContentChars: Int,
        val maxRumorJsonBytes: Int,
    )

    /**
     * §4.3's three numbers, from the plaintext ceiling and the padding function alone.
     *
     * - The **wrap `content`** ceiling is the base64 length of the largest payload the plaintext
     *   ceiling permits: a seal JSON of exactly [maxPlaintextBytes] is the most a wrap can carry.
     * - The **rumor JSON** ceiling is the largest rumor whose seal still fits under the plaintext
     *   ceiling, found by scanning rather than by inverting the padding function — padding is a
     *   step function and its inverse is where an off-by-one hides. The scan is over every legal
     *   plaintext length, so it cannot miss a bucket boundary.
     *
     * The seal's fixed fields are counted by [SEAL_JSON_FIXED_CHARS] from a skeleton, not by a
     * number somebody added up.
     */
    internal fun derive(maxPlaintextBytes: Int, padding: (Int) -> Int): Derived {
        var largestRumor = 0
        for (rumorJsonBytes in 1..maxPlaintextBytes) {
            val sealJsonBytes = SEAL_JSON_FIXED_CHARS + base64Chars(payloadBytes(rumorJsonBytes, padding))
            if (sealJsonBytes <= maxPlaintextBytes) largestRumor = rumorJsonBytes
        }
        if (largestRumor == 0) {
            fail(
                "no rumor JSON of any legal length produces a seal within $maxPlaintextBytes bytes; " +
                    "the derivation is measuring nothing",
            )
        }
        return Derived(
            maxPlaintextBytes = maxPlaintextBytes,
            maxWrapContentChars = base64Chars(payloadBytes(maxPlaintextBytes, padding)),
            maxRumorJsonBytes = largestRumor,
        )
    }

    /**
     * The characters a `kind:13` seal's JSON costs around an empty `content`.
     *
     * Measured from a skeleton rather than added up by hand: the seven NIP-01 fields in §7.1's
     * object form, with a 64-character id, a 64-character pubkey, a ten-digit `created_at`
     * (unix seconds have been ten digits since 2001 and stay so until 2286), `kind:13`, the
     * empty `tags` §7.1 requires of a seal, an empty `content`, and a 128-character signature.
     */
    internal val SEAL_JSON_FIXED_CHARS: Int = sealSkeleton().length

    /** The same skeleton as [SEAL_JSON_FIXED_CHARS], exposed so a failure can print it. */
    internal fun sealSkeleton(): String =
        "{\"id\":\"" + "0".repeat(64) +
            "\",\"pubkey\":\"" + "0".repeat(64) +
            "\",\"created_at\":" + "9".repeat(10) +
            ",\"kind\":13,\"tags\":[],\"content\":\"" +
            "\",\"sig\":\"" + "0".repeat(128) + "\"}"

    /**
     * The same skeleton for a `kind:1059` wrap, with the single `p` tag §7.1 requires and a
     * relay hint at §4.3's 1024-byte tag-value bound — the widest a conformant wrap can be.
     */
    internal fun wrapSkeleton(relayHintChars: Int): String =
        "{\"id\":\"" + "0".repeat(64) +
            "\",\"pubkey\":\"" + "0".repeat(64) +
            "\",\"created_at\":" + "9".repeat(10) +
            ",\"kind\":1059,\"tags\":[[\"p\",\"" + "0".repeat(64) + "\",\"" + "r".repeat(relayHintChars) +
            "\"]],\"content\":\"" +
            "\",\"sig\":\"" + "0".repeat(128) + "\"}"

    // ------------------------------------------------------------------- the vendored vectors

    /** `v2.valid.calc_padded_len`: `(unpadded, padded)` pairs, externally authored. */
    internal fun calcPaddedLenVectors(): List<Pair<Int, Int>> {
        val array = jsonArrayAfter(CALC_PADDED_LEN)
        val pairs = PAIR.findAll(array)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
        if (pairs.isEmpty()) {
            fail("\"$CALC_PADDED_LEN\" in ${vectorsPath()} parsed to no pairs at all; its array read: $array")
        }
        return pairs
    }

    /** `v2.invalid.encrypt_msg_lengths`: the plaintext lengths NIP-44 encryption MUST refuse. */
    internal fun invalidEncryptMsgLengths(): List<Int> {
        val array = jsonArrayAfter(ENCRYPT_MSG_LENGTHS)
        val lengths = INTEGER.findAll(array).map { it.value.toInt() }.toList()
        if (lengths.isEmpty()) {
            fail("\"$ENCRYPT_MSG_LENGTHS\" in ${vectorsPath()} parsed to no lengths; its array read: $array")
        }
        return lengths
    }

    internal fun vectorsPath(): String = SpecAnchor.nip44VectorsFile().location

    // --------------------------------------------------------------- the specification's text

    /** The three numbers §4.3 publishes for the envelope, as parsed from a given document. */
    internal data class Published(
        val maxPlaintextBytes: Int,
        val maxWrapContentChars: Int,
        val maxRumorJsonBytes: Int,
    )

    /**
     * §4.3's three envelope bounds, parsed out of [specificationLines].
     *
     * Scoped to §4.3 and anchored on the specification's own phrasing: each pattern must match
     * **exactly once** inside the section, so a revision that stated a bound twice — or dropped
     * one and left a similar sentence elsewhere — fails here rather than silently picking one.
     * The lines are a parameter so a control can hand this a mutated copy.
     */
    internal fun published(specificationLines: List<String>): Published = Published(
        maxPlaintextBytes = soleNumber(specificationLines, MAX_PLAINTEXT, "the NIP-44 plaintext bound"),
        maxWrapContentChars = soleNumber(specificationLines, MAX_WRAP_CONTENT, "the wrap `content` bound"),
        maxRumorJsonBytes = soleNumber(specificationLines, MAX_RUMOR_JSON, "the rumor JSON bound"),
    )

    /**
     * The rumor size §4.3 says the **ordinary** `content` default stopped at, parsed out of §4.3.
     *
     * §4.3 states this figure as the whole justification for the three bounds above, and a
     * justification nothing checks is exactly the sentence that goes stale. It is parsed and then
     * recomputed by `Section71Test` from `WireLimits`' own default.
     */
    internal fun rumorBytesTheOrdinaryDefaultAllowed(specificationLines: List<String>): Int =
        soleNumber(specificationLines, ORDINARY_DEFAULT_CEILING, "the rumor size the ordinary default allowed")

    /** §7.1's randomisation window in seconds, parsed out of its `[now − N, now]` interval. */
    internal fun randomisationWindowSeconds(specificationLines: List<String>): Int {
        val section = sectionText(specificationLines, SECTION_71, SECTION_71_END)
        val matches = RANDOMISATION_WINDOW.findAll(section).map { it.groupValues[1] }.toList()
        if (matches.size != 1) {
            fail(
                "§7.1 must state its randomisation interval exactly once as `[now − N, now]`; found " +
                    "${matches.size} in ${specPath()}",
            )
        }
        return ungroup(matches.single())
    }

    /** The specification as the tests read it, for a caller that wants to vary it. */
    internal fun specificationLines(): List<String> = SpecAnchor.specFile().readLines()

    internal fun specPath(): String = SpecAnchor.specFile().location

    // ----------------------------------------------------------------------------- internals

    /** 2^30: the largest power of two an `Int` holds, and the ceiling [nextPowerOfTwoAtLeast] takes. */
    private const val LARGEST_REPRESENTABLE_POWER_OF_TWO: Int = 1 shl 30

    private const val VERSION_BYTE: Int = 1

    private const val NONCE_BYTES: Int = 32

    private const val LENGTH_PREFIX_BYTES: Int = 2

    private const val MAC_BYTES: Int = 32

    private const val CALC_PADDED_LEN: String = "calc_padded_len"

    private const val ENCRYPT_MSG_LENGTHS: String = "encrypt_msg_lengths"

    private const val SECTION_43: String = "### 4.3 Encodings, tags and hostile input"

    private const val SECTION_43_END: String = "### 4.4 "

    private const val SECTION_71: String = "### 7.1 Envelope"

    private const val SECTION_71_END: String = "### 7.2 "

    /** An inner `[unpadded, padded]` vector. `\]` because JavaScript's `u` mode refuses a bare one. */
    private val PAIR = Regex("""\[\s*(\d+)\s*,\s*(\d+)\s*\]""")

    private val INTEGER = Regex("""\d+""")

    /**
     * §4.3's three sentences. Digits are grouped with ordinary spaces throughout this document
     * ("2 100 000 000 000 000 000" in §4.4), so each pattern captures digits and spaces and
     * [ungroup] strips the separators.
     */
    private val MAX_PLAINTEXT = Regex("""at least 1 and at most ([0-9 ]+) bytes""")

    private val MAX_WRAP_CONTENT = Regex("""at most ([0-9 ]+) base64 characters""")

    private val MAX_RUMOR_JSON = Regex("""JSON is at most ([0-9 ]+) bytes on write""")

    /** §4.3's own account of why the ordinary default was unusable: "more than 7 168 bytes of JSON". */
    private val ORDINARY_DEFAULT_CEILING = Regex("""rumor of more than ([0-9 ]+) bytes of JSON""")

    /** §7.1's interval. The separator is a Unicode minus sign, so it is matched as "not a digit". */
    private val RANDOMISATION_WINDOW = Regex("""\[now [^0-9]+([0-9 ]+), now\]""")

    private fun soleNumber(specificationLines: List<String>, pattern: Regex, what: String): Int {
        val section = sectionText(specificationLines, SECTION_43, SECTION_43_END)
        val matches = pattern.findAll(section).map { it.groupValues[1] }.toList()
        if (matches.size != 1) {
            fail(
                "§4.3 must publish $what exactly once; the pattern ${pattern.pattern} matched " +
                    "${matches.size} time(s) in ${specPath()}",
            )
        }
        return ungroup(matches.single())
    }

    /** One section of the document, from its heading to [endPrefix], joined for phrase matching. */
    private fun sectionText(specificationLines: List<String>, heading: String, endPrefix: String): String {
        val start = specificationLines.indexOfFirst { it.trim() == heading }
        if (start < 0) fail("no line reading \"$heading\" in ${specPath()}")
        val after = specificationLines.drop(start + 1).indexOfFirst { it.startsWith(endPrefix) }
        if (after < 0) fail("\"$heading\" in ${specPath()} is not followed by \"$endPrefix\"")
        return specificationLines.subList(start, start + 1 + after).joinToString(" ")
    }

    /** "65 535" as this document groups it, back to 65535. */
    private fun ungroup(grouped: String): Int =
        grouped.replace(" ", "").toIntOrNull()
            ?: fail("\"$grouped\" in ${specPath()} is not a number once its digit grouping is removed")

    /**
     * The balanced JSON array following `"<key>"`, by bracket depth.
     *
     * Depth counting rather than a regex because `calc_padded_len`'s array is nested. Neither
     * array holds a string, and that is asserted rather than assumed: a quote inside one would
     * mean this scanner is looking at a different key and counting brackets inside text.
     */
    private fun jsonArrayAfter(key: String): String {
        val json = SpecAnchor.nip44VectorsFile().text
        val keyAt = json.indexOf("\"$key\"")
        if (keyAt < 0) fail("no \"$key\" key in ${vectorsPath()}")
        val open = json.indexOf('[', keyAt)
        if (open < 0) fail("\"$key\" in ${vectorsPath()} is not followed by an array")
        var depth = 0
        for (index in open until json.length) {
            when (json[index]) {
                '[' -> depth++
                ']' -> if (--depth == 0) {
                    val array = json.substring(open, index + 1)
                    if ('"' in array) {
                        fail("the array after \"$key\" in ${vectorsPath()} holds a string: $array")
                    }
                    return array
                }
            }
        }
        fail("the array after \"$key\" in ${vectorsPath()} is never closed")
    }
}
