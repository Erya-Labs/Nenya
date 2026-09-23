package dev.eryalabs.nenya.envelope

import dev.eryalabs.nenya.wire.WireLimits
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §4.3's envelope bounds, derived from NIP-44's own vectors and held equal to what the
 * specification publishes and to what [EnvelopeLimits] declares.
 *
 * Nothing is encrypted here and no seam is called. Every number in this file is either read out
 * of an externally-authored vector file, read out of `spec/NENYA-1.md`, or computed from those
 * by [Section71] — which is the whole point: three places had to agree about 65 535, 87 472 and
 * 40 960, and a test that transcribed any of them would have proved only that it agreed with
 * itself.
 */
class Section71Test {

    private val vectors = Section71.calcPaddedLenVectors()

    private val spec = Section71.specificationLines()

    // ------------------------------------------------------------ (a) NIP-44's padding function

    @JsName("the_padding_function_reproduces_every_vendored_calc_padded_len_vector")
    @Test
    fun `the padding function reproduces every vendored calc_padded_len vector`() {
        for ((unpadded, padded) in vectors) {
            assertEquals(
                padded,
                Section71.calcPaddedLen(unpadded),
                "NIP-44's calc_padded_len($unpadded) is $padded in ${Section71.vectorsPath()}",
            )
        }
    }

    /**
     * The non-vacuity floor for (a), written as coverage rather than as a count.
     *
     * A transcribed count would go stale the day upstream added a vector. What must hold is that
     * the parse reached both branches of the function: the `unpadded_len <= 32` short circuit and
     * the `next_power > 256` branch where the chunk stops being 32. A parse that found one vector,
     * or only small ones, satisfies neither.
     */
    @JsName("the_vector_parse_is_non_empty_and_reaches_both_branches_of_the_function")
    @Test
    fun `the vector parse is non-empty and reaches both branches of the function`() {
        assertTrue(vectors.size > 1, "only ${vectors.size} calc_padded_len vector(s) parsed")
        assertTrue(
            vectors.any { it.first <= 32 },
            "no vector reaches the unpadded_len <= 32 short circuit: $vectors",
        )
        assertTrue(
            vectors.any { Section71.nextPowerOfTwoAtLeast(it.first) > 256 },
            "no vector reaches the branch where the chunk is next_power / 8: $vectors",
        )
        assertTrue(
            vectors.all { it.second >= it.first },
            "a vector pads to less than its own plaintext, which is not this file: $vectors",
        )
    }

    /**
     * **Mutation control.** Rounding up to the next power of two, with NIP-44's chunking removed,
     * must fail (a).
     *
     * Without this, "the padding function reproduces every vector" would also be true of a
     * function that happened to agree on the vectors it was shown.
     *
     * The disagreement is required **in the chunking branch** — `next_power > 256`, where the
     * chunk stops being 32 — and not merely somewhere. A bare "the sets differ" assertion is
     * satisfied by the `[16, 32]` vector, which disagrees because of the `unpadded_len <= 32`
     * short circuit and says nothing about chunking at all, so this control would keep passing
     * over a padding function whose chunking was removed entirely.
     */
    @JsName("padding_without_nip_44_chunking_fails_the_vectors")
    @Test
    fun `padding without NIP-44's chunking fails the vectors`() {
        val disagreements = vectors.filter { (unpadded, padded) ->
            Section71.nextPowerOfTwoAtLeast(unpadded) != padded
        }
        assertTrue(
            disagreements.any { Section71.nextPowerOfTwoAtLeast(it.first) > 256 },
            "rounding to the next power of two must disagree with NIP-44's chunked padding on at " +
                "least one vendored vector *in the chunking branch*, or (a) is proving nothing; " +
                "the disagreements found were $disagreements",
        )
    }

    /** **Negative control.** An off-by-one in the chunk count must fail (a) too. */
    @JsName("an_off_by_one_padding_function_fails_the_vectors")
    @Test
    fun `an off-by-one padding function fails the vectors`() {
        val disagreements = vectors.filter { (unpadded, padded) -> offByOnePadding(unpadded) != padded }
        assertTrue(
            disagreements.isNotEmpty(),
            "an off-by-one calc_padded_len must disagree with at least one vendored vector",
        )
    }

    // ------------------------------------------------------------- (b) the plaintext ceiling

    /**
     * Decision **F**, taken from the vectors rather than from NIP-44's prose: 65 536 is refused
     * and 65 535 is not, so 65 535 is the largest plaintext.
     *
     * The vendored `nip44.md` describes a six-byte extended-length prefix that would permit
     * 65 536 and beyond. It postdates these vectors, no deployed application emits it, and §4.3
     * follows the vectors — see `spec/reference/README.md`.
     */
    @JsName("the_vectors_refuse_65536_and_do_not_refuse_65535")
    @Test
    fun `the vectors refuse 65536 and do not refuse 65535`() {
        val refused = Section71.invalidEncryptMsgLengths()
        val ceiling = EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES

        assertTrue(
            ceiling + 1 in refused,
            "${ceiling + 1} must be among the plaintext lengths NIP-44 refuses, in " +
                "${Section71.vectorsPath()}; they are $refused",
        )
        assertFalse(
            ceiling in refused,
            "$ceiling must NOT be refused, or it is not the ceiling; ${Section71.vectorsPath()} " +
                "lists $refused",
        )
        assertTrue(0 in refused, "NIP-44 refuses an empty plaintext, and the vectors say so: $refused")

        // The two assertions above are each satisfied by almost any integer — 99 999 is not
        // listed as refused either — so on their own they pin nothing. The ceiling is the
        // smallest *positive* refused length minus one, which is one value and not a range.
        assertEquals(
            refused.filter { it > 0 }.min() - 1,
            ceiling,
            "decision F fixes the plaintext ceiling one below the smallest positive length " +
                "${Section71.vectorsPath()} refuses; they are $refused",
        )
    }

    // --------------------------------------------------- (c) the derivation, three ways equal

    /**
     * The whole task in one assertion: what NIP-44 permits, what §4.3 publishes and what
     * [EnvelopeLimits] declares are the same three numbers.
     */
    @JsName("the_derived_bounds_equal_both_the_published_ones_and_the_declared_ones")
    @Test
    fun `the derived bounds equal both the published ones and the declared ones`() {
        val derived = Section71.derive(EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES, Section71::calcPaddedLen)
        val published = Section71.published(spec)

        assertEquals(published.maxPlaintextBytes, derived.maxPlaintextBytes, "§4.3's plaintext bound")
        assertEquals(published.maxWrapContentChars, derived.maxWrapContentChars, "§4.3's wrap content bound")
        assertEquals(published.maxRumorJsonBytes, derived.maxRumorJsonBytes, "§4.3's rumor JSON bound")

        // The plaintext ceiling is compared against the specification rather than against
        // `derived`, which returns it unchanged: `derive` takes it as a parameter, so asserting
        // it against its own argument would compare the constant with itself. What has to hold
        // is that §4.3 publishes the value the vectors pin (see the decision-F test above).
        assertEquals(EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES, published.maxPlaintextBytes)
        assertEquals(EnvelopeLimits.MAX_WRAP_CONTENT_CHARS, derived.maxWrapContentChars)
        assertEquals(EnvelopeLimits.MAX_RUMOR_JSON_BYTES, derived.maxRumorJsonBytes)
    }

    /**
     * The rumor bound is a **boundary**, not merely a number the scan happened to stop at: one
     * byte more produces a seal NIP-44 cannot carry.
     *
     * Asserting only the maximum would pass over a derivation that was off by a whole padding
     * bucket in the strict direction, which refuses conformant messages and looks careful doing
     * it.
     */
    @JsName("one_byte_past_the_rumor_bound_produces_a_seal_nip_44_cannot_carry")
    @Test
    fun `one byte past the rumor bound produces a seal NIP-44 cannot carry`() {
        val ceiling = EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES

        assertTrue(sealJsonBytes(EnvelopeLimits.MAX_RUMOR_JSON_BYTES) <= ceiling)
        assertTrue(
            sealJsonBytes(EnvelopeLimits.MAX_RUMOR_JSON_BYTES + 1) > ceiling,
            "a rumor one byte over the bound must not fit, or the bound is not where the cliff is",
        )
    }

    /**
     * Why §4.3 needed new numbers at all: under the 16 KiB `content` default a wrap carrying a
     * rumor of more than 7 168 bytes of JSON must be refused.
     *
     * The 7 168 is computed here from `WireLimits`' own default, not transcribed, so this stays
     * true of whatever §4.3 publishes as the ordinary `content` bound.
     */
    @JsName("the_ordinary_content_default_would_refuse_an_ordinary_chat_message")
    @Test
    fun `the ordinary content default would refuse an ordinary chat message`() {
        val ordinary = WireLimits.DEFAULT_MAX_CONTENT_BYTES
        val largestUnderOrdinary = (1..EnvelopeLimits.MAX_RUMOR_JSON_BYTES)
            .last { wrapContentChars(it) <= ordinary }

        assertTrue(
            largestUnderOrdinary < 10_000,
            "the ordinary $ordinary-byte content default must refuse a 10 KB chat message, which " +
                "is the reason §4.3 states separate seal and wrap bounds; it allowed " +
                "$largestUnderOrdinary bytes of rumor JSON",
        )
        assertTrue(
            wrapContentChars(largestUnderOrdinary + 1) > ordinary,
            "…and one byte more must exceed it, or this is not the boundary",
        )
        assertEquals(
            Section71.rumorBytesTheOrdinaryDefaultAllowed(spec),
            largestUnderOrdinary,
            "§4.3 publishes this figure as its entire justification for the three bounds above, " +
                "so it has to be the one the arithmetic actually produces",
        )
    }

    /**
     * [EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES] clears the widest conformant wrap, and clears
     * it by less than two KiB — so the 88 is a fit and not a number somebody rounded up to.
     *
     * "Widest" is the largest `content` §4.3 permits plus the wrap's own fields, with the relay
     * hint in §7.1's single `p` tag at §4.3's tag-value bound. Both halves matter: a bound that
     * did not clear it would refuse conformant wraps, and one that cleared it by a wide margin
     * would not be bounding the thing it names.
     */
    @JsName("the_default_wrap_json_bound_clears_the_widest_conformant_wrap_and_little_else")
    @Test
    fun `the default wrap JSON bound clears the widest conformant wrap, and little else`() {
        val widest = Section71.wrapSkeleton(WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES).length +
            EnvelopeLimits.MAX_WRAP_CONTENT_CHARS

        assertTrue(
            widest <= EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES,
            "the widest conformant wrap is $widest bytes and the default bound is " +
                "${EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES}, which would refuse it",
        )
        assertTrue(
            widest > EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES - 2 * 1024,
            "the default bound must be a fit; $widest bytes leaves more than two KiB spare under " +
                "${EnvelopeLimits.DEFAULT_MAX_WRAP_JSON_BYTES}",
        )
    }

    /**
     * **Negative control.** A specification saying 49 152 where it says 40 960 must fail the
     * derivation equality.
     *
     * 49 152 is not an arbitrary wrong number: it is the padded size a 40 961-byte rumor reaches,
     * so it is exactly the mistake an implementer makes by reading the padding bucket as the
     * bound. The mutation is asserted to have landed before its consequence is asserted, so a
     * `replace` that matched nothing cannot make this pass.
     */
    @JsName("a_specification_stating_the_padding_bucket_as_the_rumor_bound_fails_the_derivation")
    @Test
    fun `a specification stating the padding bucket as the rumor bound fails the derivation`() {
        val mutated = spec.map { it.replace("40 960", "49 152") }
        val published = Section71.published(mutated)
        val derived = Section71.derive(EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES, Section71::calcPaddedLen)

        assertEquals(49_152, published.maxRumorJsonBytes, "the mutation must actually reach §4.3")
        assertNotEquals(
            published.maxRumorJsonBytes,
            derived.maxRumorJsonBytes,
            "the derivation must refuse a specification that publishes the padding bucket as the " +
                "rumor bound",
        )
        assertEquals(
            Section71.published(spec).maxRumorJsonBytes,
            derived.maxRumorJsonBytes,
            "…while the real specification still agrees, so the control is measuring the mutation",
        )
    }

    /** **Negative control.** An off-by-one padding function must move the derived bounds. */
    @JsName("an_off_by_one_padding_function_moves_the_derived_bounds")
    @Test
    fun `an off-by-one padding function moves the derived bounds`() {
        val honest = Section71.derive(EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES, Section71::calcPaddedLen)
        val wrong = Section71.derive(EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES, ::offByOnePadding)

        assertNotEquals(honest, wrong, "the derivation must be sensitive to the padding function it is given")
    }

    // ------------------------------------------------------------ (d) §7.1's two-day window

    @JsName("section_7_1_randomises_created_at_over_exactly_two_days")
    @Test
    fun `section 7_1 randomises created_at over exactly two days`() {
        assertEquals(
            2 * 24 * 60 * 60,
            Section71.randomisationWindowSeconds(spec),
            "§7.1 draws every seal and wrap timestamp uniformly from a two-day window into the past",
        )
    }

    // ------------------------------------------------------------------------------ helpers

    /** The seal JSON a rumor of [rumorJsonBytes] produces, under NIP-44's real padding. */
    private fun sealJsonBytes(rumorJsonBytes: Int): Int =
        Section71.SEAL_JSON_FIXED_CHARS +
            Section71.base64Chars(Section71.payloadBytes(rumorJsonBytes, Section71::calcPaddedLen))

    /** The wrap `content` a rumor of [rumorJsonBytes] produces, both NIP-44 layers applied. */
    private fun wrapContentChars(rumorJsonBytes: Int): Int =
        Section71.base64Chars(Section71.payloadBytes(sealJsonBytes(rumorJsonBytes), Section71::calcPaddedLen))

    /**
     * NIP-44's padding with the `- 1` dropped from the chunk count: one chunk too many for every
     * plaintext that lands exactly on a chunk boundary, and correct everywhere else — which is
     * what makes it a useful control rather than an obviously broken function.
     */
    private fun offByOnePadding(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32
        val nextPower = Section71.nextPowerOfTwoAtLeast(unpaddedLen)
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * (unpaddedLen / chunk + 1)
    }
}
