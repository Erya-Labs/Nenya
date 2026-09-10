package dev.eryalabs.nenya.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for §9.2 check 3.
 *
 * Two directions, and both are needed. A verifier that returns success unconditionally passes
 * the first and fails the second; one that refuses everything fails the first. Neither
 * direction alone rules out the other implementation, which is why "10 000 preimages verify"
 * would be a weak floor on its own.
 *
 * Every fixture comes from [PaymentFixtures] — a `java.util.Random` on a pinned seed, hashed
 * with `MessageDigest`. Nothing here is typed.
 */
class PaymentPropertyTest {

    private companion object {
        /** The queue's floor for this task. */
        const val SAMPLES: Int = 10_000
    }

    @Test
    fun `every sampled preimage verifies against its own payment hash`() {
        val preimages = PaymentFixtures.preimages(SAMPLES)
        assertEquals(SAMPLES, preimages.size, "the generator must actually produce $SAMPLES preimages")
        assertEquals(SAMPLES, preimages.toSet().size, "the sampled preimages must be distinct")

        var verified = 0
        for ((index, preimage) in preimages.withIndex()) {
            // Alternating the payee so the capability record is exercised on both branches
            // across the whole sample rather than only in the hand-written controls.
            val payee = if (index % 2 == 0) Payee.PROVIDER else Payee.FEE
            val evidence = VerifiedPayment.verify(payee, PaymentFixtures.paymentHashOf(preimage), preimage)

            assertEquals(preimage, evidence.preimage)
            assertEquals(payee, evidence.payee)
            assertTrue(
                PaymentCheck.PREIMAGE_HASH_COMPARISON in evidence.checksPerformed,
                "check 3 is what this library performed",
            )
            assertTrue(
                PaymentCheck.INVOICE_AMOUNT in evidence.checksNotPerformedHere,
                "check 4 is what it did not, and §17 requires it say so",
            )
            verified++
        }
        assertEquals(SAMPLES, verified)
    }

    @Test
    fun `every sampled preimage is refused against a different fixture's payment hash`() {
        val preimages = PaymentFixtures.preimages(SAMPLES)
        val hashes = preimages.map(PaymentFixtures::paymentHashOf)
        assertEquals(SAMPLES, hashes.toSet().size, "the sampled payment hashes must be distinct")

        var refused = 0
        for (index in preimages.indices) {
            val other = hashes[(index + 1) % SAMPLES]
            val failure = assertFailsWith<PaymentException> {
                VerifiedPayment.verify(Payee.PROVIDER, other, preimages[index])
            }
            assertEquals(PaymentRejection.PREIMAGE_MISMATCH, failure.reason)
            refused++
        }
        assertEquals(SAMPLES, refused, "a verifier that accepted anything would not reach this count")
    }

    @Test
    fun `every sampled preimage is refused in uppercase and accepted in lowercase`() {
        val hexes = PaymentFixtures.preimageHex(SAMPLES)

        var uppercaseRejected = 0
        var caseInvariant = 0
        for (hex in hexes) {
            val uppercase = hex.uppercase()
            if (uppercase == hex) {
                // An all-digit run carries no letter to mis-case; it is not a control.
                caseInvariant++
                continue
            }
            val failure = assertFailsWith<PaymentException> { Preimage.ofHex(uppercase) }
            assertEquals(PaymentRejection.UPPERCASE_NOT_PERMITTED, failure.reason)
            // §4.3 puts the payment hash under the opposite rule, and the two must not drift.
            assertEquals(hex, PaymentHash.ofHex(uppercase).toHex())
            uppercaseRejected++
        }
        assertTrue(
            uppercaseRejected > SAMPLES / 2,
            "far more than half of 32-byte random hex runs carry a letter; only $uppercaseRejected did, " +
                "with $caseInvariant case-invariant, which means the sampler is not sampling",
        )
    }
}
