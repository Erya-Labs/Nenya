package dev.eryalabs.nenya.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for §10.4: a verifier that always accepts and a verifier that always
 * refuses must both fail this file.
 *
 * Two properties do that, and they are split because "every other" and "ten thousand" pull in
 * opposite directions:
 *
 * - [SAMPLES] blobs, each verifying against **its own** commitment and refused against a
 *   neighbour's. That kills always-refuse (the positive half) and always-accept (the negative
 *   half) at the sample size the queue's floor asks for;
 * - a **full cross-product** over [CROSS] blobs, where every blob is refused against every
 *   other blob's commitment. That is "refused against every other's" literally rather than
 *   against one representative, at a size where `n²` verifications are still cheap.
 *
 * A third property closes the direction the first two cannot reach on their own: the `x` and
 * `ox` operands are never interchangeable, so a verifier that used one where §10.4 says the
 * other is refused on every sample rather than on the ones where the two happen to differ.
 */
class DeliveryPropertyTest {

    private companion object {

        /** The queue's floor. Blobs are tens of bytes, so this runs in well under a second. */
        const val SAMPLES: Int = 10_000

        /** `n²` verifications, both steps, each way — 120 is ~29 000 hash comparisons. */
        const val CROSS: Int = 120
    }

    @Test
    fun `every generated blob verifies through both steps against its own commitment`() {
        val blobs = DeliveryFixtures.blobs(SAMPLES)
        assertEquals(SAMPLES, blobs.size, "the generator must actually produce the sample")

        var verified = 0
        for (blob in blobs) {
            val served = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)
            val evidence = DeliveryEvidence.verifyPlaintextBytes(served, blob.plaintext)
            assertEquals(blob.served.size.toLong(), evidence.servedBytesLength)
            assertEquals(blob.plaintext.size.toLong(), evidence.plaintextBytesLength)
            verified++
        }

        assertEquals(SAMPLES, verified, "a verifier that refused everything cannot pass this")
    }

    @Test
    fun `every generated blob is refused against a neighbour's commitment`() {
        val blobs = DeliveryFixtures.blobs(SAMPLES)
        val digests = blobs.map { it.commitment.x.toHex() }.toSet()
        assertEquals(
            SAMPLES,
            digests.size,
            "the generator must produce distinct served blobs, or a cross-rejection property is " +
                "asserting that a blob differs from itself",
        )

        var refused = 0
        for (index in blobs.indices) {
            val blob = blobs[index]
            val neighbour = blobs[(index + 1) % blobs.size]
            // The neighbour's commitment declares the neighbour's size, so hand it the
            // neighbour's length of this blob's bytes: the refusal must come from the digest,
            // not from the size rule that would have caught it first.
            val sized = blob.served.copyOf(neighbour.served.size)
            val rejection = assertFailsWith<DeliveryException> {
                ServedBytesVerified.verifyServedBytes(neighbour.commitment, sized)
            }
            assertEquals(DeliveryRejection.SERVED_BYTES_MISMATCH, rejection.reason)
            refused++
        }

        assertEquals(SAMPLES, refused, "a verifier that accepted everything cannot pass this")
    }

    @Test
    fun `every generated blob's plaintext is refused against a neighbour's ox`() {
        val blobs = DeliveryFixtures.blobs(SAMPLES)

        var refused = 0
        for (index in blobs.indices) {
            val blob = blobs[index]
            val neighbour = blobs[(index + 1) % blobs.size]
            // Step 1 is passed honestly, against this blob's own commitment, precisely so that
            // step 3 is what the refusal below is about. The neighbour-commitment property above
            // can never reach step 3 at all — every off-diagonal pair dies at step 1 — so without
            // this one the queue's non-vacuity floor holds for `x` and not for `ox`, and a
            // verifier whose `ox` comparison always passed would clear all 10 000 samples.
            val served = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)
            val rejection = assertFailsWith<DeliveryException> {
                DeliveryEvidence.verifyPlaintextBytes(served, neighbour.plaintext)
            }
            assertEquals(DeliveryRejection.PLAINTEXT_BYTES_MISMATCH, rejection.reason)
            refused++
        }

        assertEquals(SAMPLES, refused, "a step-3 check that accepted everything cannot pass this")
    }

    @Test
    fun `no blob verifies against any other blob's commitment, in either step`() {
        val blobs = DeliveryFixtures.blobs(CROSS)

        var acceptedServed = 0
        var refusedServed = 0
        var acceptedPlaintext = 0
        var refusedPlaintext = 0
        for (blob in blobs) {
            // Step 3's own cross-product, reached through this blob's own step 1 so that every
            // pair actually gets there. Structuring it as one chained loop would make the whole
            // off-diagonal die at step 1 and leave step 3 exercised on the diagonal alone.
            val ownServed = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)

            for (other in blobs) {
                val sized = blob.served.copyOf(other.served.size)
                try {
                    ServedBytesVerified.verifyServedBytes(other.commitment, sized)
                    assertTrue(blob === other, "a blob verified against another blob's x")
                    acceptedServed++
                } catch (refused: DeliveryException) {
                    assertEquals(DeliveryRejection.SERVED_BYTES_MISMATCH, refused.reason)
                    assertTrue(blob !== other, "a blob must verify against its own commitment")
                    refusedServed++
                }

                try {
                    DeliveryEvidence.verifyPlaintextBytes(ownServed, other.plaintext)
                    assertTrue(
                        blob === other,
                        "a blob's ox accepted another blob's plaintext, which is §10.5's whole " +
                            "guarantee failing",
                    )
                    acceptedPlaintext++
                } catch (refused: DeliveryException) {
                    assertEquals(DeliveryRejection.PLAINTEXT_BYTES_MISMATCH, refused.reason)
                    assertTrue(blob !== other, "a blob's plaintext must verify against its own ox")
                    refusedPlaintext++
                }
            }
        }

        // Both steps get the full n² independently, so neither can be carried by the other.
        assertEquals(CROSS, acceptedServed, "exactly the diagonal must be accepted at step 1")
        assertEquals(CROSS, acceptedPlaintext, "exactly the diagonal must be accepted at step 3")
        assertEquals(CROSS * CROSS - CROSS, refusedServed, "every off-diagonal pair, at step 1")
        assertEquals(CROSS * CROSS - CROSS, refusedPlaintext, "every off-diagonal pair, at step 3")
    }

    @Test
    fun `x and ox are never interchangeable`() {
        val blobs = DeliveryFixtures.blobs(SAMPLES)

        var distinct = 0
        for (blob in blobs) {
            assertTrue(
                blob.commitment.x != blob.commitment.ox,
                "a fixture whose x equals its ox cannot tell a verifier that swapped them apart",
            )
            // The commitment with its two hashes exchanged: nothing may verify against it, in
            // either step, which is the swap mutation caught on every sample rather than on the
            // ones where the two operands happen to differ.
            val swapped = DeliverableCommitment(
                x = blob.commitment.ox,
                ox = blob.commitment.x,
                mimeType = blob.commitment.mimeType,
                sizeBytes = blob.commitment.sizeBytes,
            )
            val rejection = assertFailsWith<DeliveryException> {
                ServedBytesVerified.verifyServedBytes(swapped, blob.served)
            }
            assertEquals(DeliveryRejection.SERVED_BYTES_MISMATCH, rejection.reason)
            distinct++
        }

        assertEquals(SAMPLES, distinct)
    }

    @Test
    fun `every generated release matches its own commitment on all four operands and no other`() {
        val blobs = DeliveryFixtures.blobs(CROSS)

        var matched = 0
        var diverged = 0
        for (blob in blobs) {
            for (other in blobs) {
                if (blob === other) {
                    other.commitment.checkReleaseIdentity(blob.matchingRelease())
                    matched++
                } else {
                    val refused = assertFailsWith<DeliveryException> {
                        other.commitment.checkReleaseIdentity(blob.matchingRelease())
                    }
                    assertTrue(
                        refused.reason in setOf(
                            DeliveryRejection.SERVED_HASH_MISMATCH,
                            DeliveryRejection.PLAINTEXT_HASH_MISMATCH,
                            DeliveryRejection.SIZE_MISMATCH,
                        ),
                        "a release for another commitment must diverge on a named operand, not pass",
                    )
                    diverged++
                }
            }
        }

        assertEquals(CROSS, matched)
        assertEquals(CROSS * CROSS - CROSS, diverged)
    }
}
