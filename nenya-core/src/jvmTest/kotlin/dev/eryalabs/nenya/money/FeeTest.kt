package dev.eryalabs.nenya.money

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The §8.3 fee tests that need the JVM, on top of the common ones in [PortableFeeTest].
 *
 * Both take their expected fee from `java.math.BigInteger` — a second, arbitrary-precision
 * algorithm — which common Kotlin does not have.
 */
class FeeTest : PortableFeeTest() {

    @Test
    fun `at the supply cap and 10000 bps the naive 64-bit product overflows and this one does not`() {
        val price = Msat.SUPPLY_CAP
        assertEquals(2_100_000_000_000_000_000L, price.millisatoshis)

        val split = FeeTerm.of(FeeTerm.MAX_BASIS_POINTS).splitOn(price)

        // §8.3's boundary example. 10 000 bps is 100%, so the fee equals the price exactly.
        assertEquals(2_100_000_000_000_000_000L, split.fee.millisatoshis)
        assertEquals(4_200_000_000_000_000_000L, split.total.millisatoshis)
        assertTrue(split.total.millisatoshis < Long.MAX_VALUE, "§8.3's total is still below 2^63")
        // Confirmed by a second, independent algorithm rather than by restating the literal.
        assertEquals(
            BigInteger.valueOf(price.millisatoshis)
                .multiply(BigInteger.valueOf(10_000L))
                .divide(BigInteger.valueOf(10_000L)),
            BigInteger.valueOf(split.fee.millisatoshis),
        )

        // Now the bug this is a control for, computed here rather than assumed. The true
        // product is 2.1 × 10^22, well above 2^63, so `price * bps` wraps.
        val naive = (price.millisatoshis * 10_000L) / 10_000L
        assertEquals(
            760_524_411_853_026L,
            naive,
            "the wrapped value is a fact about two's complement; if this moved, the pinned figure below is stale",
        )
        // It is *positive*, which is precisely why this bug survives a casual sanity check
        // and why "assert it did not go negative" is not a test of anything here.
        assertTrue(naive > 0L, "the wrapped result is positive, which is what makes it dangerous")
        assertNotEquals(
            split.fee.millisatoshis,
            naive,
            "the decomposition must not reproduce the naive product's wrapped answer",
        )
        // Pinned both ways round: the correct answer is roughly 2 760 times the wrapped one.
        assertTrue(split.fee.millisatoshis > naive * 2_000L, "the two answers are not close")
    }

    @Test
    fun `an in-range fee is never refused for being large`() {
        // §8.1: NENYA-1 imposes no ceiling below 10 000, and an implementation MUST NOT
        // reject a value in 0..10000 on the ground that it considers it too high. A
        // client's own lower policy limit is a separate, named refusal taken above this
        // library — never a refusal here, and never reported as a malformed term.
        val price = Msat.ofSat(100_000L)
        for (bps in listOf(0, 1, 250, 500, 501, 1_000, 5_000, 9_000, 9_999, 10_000)) {
            val split = FeeTerm.of(bps).splitOn(price)
            val expected = BigInteger.valueOf(price.millisatoshis)
                .multiply(BigInteger.valueOf(bps.toLong()))
                .divide(BigInteger.valueOf(10_000L))
                .toLong()
            assertEquals(expected, split.fee.millisatoshis, "$bps bps must be accepted and computed")
        }
    }
}
