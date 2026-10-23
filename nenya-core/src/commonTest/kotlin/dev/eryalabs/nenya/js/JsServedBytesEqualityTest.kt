package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliveryFixtures
import dev.eryalabs.nenya.delivery.DeliveryRejection
import dev.eryalabs.nenya.delivery.ServedBytesVerified
import dev.eryalabs.nenya.wire.encodeLowerHex
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T44 gap (3) across the JavaScript boundary: [verifyServedBytes] answers exactly what
 * `ServedBytesVerified.verifyServedBytes` answers, and can be asked **before** anything is
 * decrypted.
 *
 * ### What the gap actually was
 *
 * §10.4 fixes an order and gives step 1 a prohibition: hash the served bytes against `x`, and if
 * they do not match, the buyer "MUST NOT decrypt it". The only delivery check T43 exported is
 * [stepOrder]'s `delivery_verified`, which takes the served bytes **and** the decrypted bytes
 * together — so a page using it has already done step 2 by the time Nenya makes step 1, which is
 * the prohibition inverted by the crossing's own shape. The whole content of this entry point is
 * that there is now a call a page can make with the one byte string it has.
 *
 * ### The corpus is real blobs and real digests
 *
 * `DeliveryFixtures` generates the bytes and computes `x` and `ox` from them with the platform
 * SHA-256; nothing here is typed. The mismatch control swaps one blob for another's commitment,
 * so the refusal is a genuinely wrong digest rather than a malformed value.
 */
class JsServedBytesEqualityTest {

    private companion object {

        /** How many generated blobs the corpus draws. Each has its own length and its own digests. */
        const val CORPUS: Int = 24
    }

    /** §10.4 step 1, both ways, over a corpus of real blobs — the positive case. */
    @Test
    @JsName("verifyServedBytes_crosses_section_10_4_step_1")
    fun `verifyServedBytes crosses §10_4 step 1`() {
        for ((index, blob) in DeliveryFixtures.blobs(CORPUS).withIndex()) {
            val crossed = bothWays("blob $index", blob.commitment, blob.served)
            assertTrue(crossed.ok, "a blob whose SHA-256 is the commitment's `x` must verify")
            assertEquals(
                blob.served.size.toString(),
                crossed.servedBytesLength,
                "the length crosses as an exact decimal string, so a blob above 2^53 bytes is exact",
            )
            assertEquals(
                ServedBytesVerified.DEFAULT_MAX_SERVED_BYTES.toString(),
                crossed.maxServedBytes,
                "§17: the bound the verification was performed under is published, because " +
                    "`bounded, by a bound that bounds nothing` is not bounded",
            )
        }
    }

    /**
     * §10.4's three refusals, returned as values and equal to the exceptions a JVM caller gets.
     *
     * `SERVED_BYTES_MISMATCH` is the one the entry point exists for: it is the answer that tells a
     * page **not to decrypt**, and before this call there was no way for the page to be told it
     * before decrypting.
     */
    @Test
    @JsName("the_served_bytes_check_returns_section_10_4_s_refusals_as_values")
    fun `the served bytes check returns §10_4's refusals as values`() {
        val blobs = DeliveryFixtures.blobs(2)
        val first = blobs[0]
        val second = blobs[1]

        // The wrong blob: its digest is not this commitment's `x`, and §10.4 says MUST NOT decrypt.
        val wrongBlob = bothWaysForBytes(
            "another blob served against this commitment",
            // The other blob's bytes, padded or trimmed to the declared length so that the size
            // rules pass and the **digest** is the only thing that can refuse it.
            first.commitment,
            resize(second.served, first.served.size),
        )
        assertFalse(wrongBlob.ok, "a blob whose digest is not `x` is the wrong or a tampered one")
        assertEquals(
            DeliveryRejection.SERVED_BYTES_MISMATCH.name,
            wrongBlob.reason,
            "§10.4 step 1, and the answer a page must have before it decrypts",
        )

        // §10.4: a blob whose length disagrees with `size` is refused before any digest matters.
        val wrongLength = bothWaysForBytes(
            "a blob a byte short of its declared size",
            first.commitment,
            first.served.copyOf(first.served.size - 1),
        )
        assertFalse(wrongLength.ok, "§10.4 requires refusing a blob whose length disagrees with `size`")
        assertEquals(DeliveryRejection.SIZE_DISAGREEMENT.name, wrongLength.reason, "§10.4")

        // §4.3: a declared size above the caller's bound is refused rather than truncated, and
        // nothing is hashed. The bound crosses as a decimal string like every other byte count.
        val overBound = bothWaysForBytes(
            "a commitment declaring more than this page will hash",
            first.commitment,
            first.served,
            maxServedBytes = first.commitment.sizeBytes - 1L,
        )
        assertFalse(overBound.ok, "§4.3 requires refusing rather than truncating")
        assertEquals(DeliveryRejection.SIZE_LIMIT_EXCEEDED.name, overBound.reason, "§4.3, §10.4")
    }

    /**
     * A `servedBytesHex` that is not strict lowercase hex has no `ByteArray` to be, so there is
     * nothing to hash — which is the crossing's own refusal and not a §10.4 ruling.
     *
     * Reported under a vocabulary no section of NENYA-1 uses, so a page can never read it as "the
     * blob is wrong"; the two mean entirely different things to whoever has to fix it.
     */
    @Test
    @JsName("bytes_that_are_not_hex_are_the_crossing_s_own_refusal")
    fun `bytes that are not hex are the crossing's own refusal`() {
        val blob = DeliveryFixtures.blob()
        for (bad in listOf("zz", "ABCD", encodeLowerHex(blob.served) + "a")) {
            val crossed = verifyServedBytes(JsBoundaryFixtures.crossed(blob.commitment), bad)
            assertFalse(crossed.ok, "`$bad` is not §4.3's lowercase hex of even length")
            assertEquals(JsCrossing.UNCONSTRUCTIBLE_VALUE, crossed.reason, "the crossing's own reason")
            assertEquals(JsCrossing.VOCABULARY, crossed.reasonVocabulary, "never a §10.4 ruling")
            assertTrue(
                crossed.reason in JsCrossing.REASONS,
                "every reason this boundary reports must be one of the constants it declares",
            )
        }
    }

    /** A `maxServedBytes` that is not the decimal form this boundary writes is the same refusal. */
    @Test
    @JsName("a_malformed_bound_is_the_crossing_s_own_refusal")
    fun `a malformed bound is the crossing's own refusal`() {
        val blob = DeliveryFixtures.blob()
        val crossed = verifyServedBytes(
            commitment = JsBoundaryFixtures.crossed(blob.commitment),
            servedBytesHex = encodeLowerHex(blob.served),
            maxServedBytes = "64 MiB",
        )
        assertFalse(crossed.ok, "a bound that is not a number bounds nothing")
        assertEquals(JsCrossing.MALFORMED_DECIMAL, crossed.reason, "the decimal codec's own refusal")
        assertEquals(JsCrossing.VOCABULARY, crossed.reasonVocabulary, "never a §10.4 ruling")
    }

    // -----------------------------------------------------------------------------------------
    // The call, made twice.
    // -----------------------------------------------------------------------------------------

    /** One blob through `verifyServedBytes` on both sides, at this library's default bound. */
    private fun bothWays(
        what: String,
        commitment: DeliverableCommitment,
        servedBytes: ByteArray,
    ): JsServedBytesResult = bothWaysForBytes(what, commitment, servedBytes)

    /** The same, with the bound stated — `null` meaning this library's default on both sides. */
    private fun bothWaysForBytes(
        what: String,
        commitment: DeliverableCommitment,
        servedBytes: ByteArray,
        maxServedBytes: Long? = null,
    ): JsServedBytesResult {
        val bound = maxServedBytes ?: ServedBytesVerified.DEFAULT_MAX_SERVED_BYTES
        val expected = JsFlowFixtures.servedBytesAnswerOf {
            ServedBytesVerified.verifyServedBytes(commitment, servedBytes, bound)
        }
        val crossed = verifyServedBytes(
            commitment = JsBoundaryFixtures.crossed(commitment),
            // Written here rather than through `JsHex`, so the two sides do not share the codec
            // whose correctness is part of what is being compared.
            servedBytesHex = encodeLowerHex(servedBytes),
            maxServedBytes = maxServedBytes?.toString(),
        )
        JsFlowFixtures.assertSameServedBytes(crossed, expected, what)
        return crossed
    }

    /**
     * [bytes] as exactly [length] bytes — truncated, or repeated until it is long enough.
     *
     * So that the digest control is about the **digest**: §10.4's size rules fire first for a blob
     * of the wrong length, and a control that tripped one of those would never reach step 1's hash
     * comparison at all. Repeating rather than zero-padding, so the result is not a prefix of this
     * blob with a tail a careless implementation could skip.
     */
    private fun resize(bytes: ByteArray, length: Int): ByteArray {
        if (bytes.isEmpty()) fail("a blob fixture must carry bytes to resize")
        return ByteArray(length) { bytes[it % bytes.size] }
    }
}
