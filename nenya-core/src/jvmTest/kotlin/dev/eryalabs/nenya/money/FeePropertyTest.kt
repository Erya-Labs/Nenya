package dev.eryalabs.nenya.money

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The independent oracle for §8.3, and the real content of this file.
 *
 * §8.3 offers two conformant routes to `fee_msat`: arbitrary-precision integers, or the
 * exact 64-bit decomposition. [FeeTerm.feeOn] implements the decomposition, because that
 * is the one a reference implementation should demonstrate — every language has 64-bit
 * integers and not every language has `BigInteger`. This file runs the *other* route over
 * sampled pairs and requires the two to agree.
 *
 * That is the whole argument. Two different algorithms agreeing is evidence; one algorithm
 * agreeing with itself is not, and a test that recomputed the decomposition and compared it
 * to itself would pass against any decomposition, correct or not. [BigInteger] is in the
 * JDK, so this costs no dependency.
 *
 * Every fixture is generated here from a pinned seed. Nothing is typed out and nothing is
 * read from a file; a reviewer can change [SEED] and re-run, and every property must hold.
 *
 * ### Why the sampler is stratified
 *
 * A uniform draw over `[0, cap]` is a terrible sampler for this function. The
 * decomposition has two terms, `(price / 10000) * bps` and `((price % 10000) * bps) / 10000`,
 * and a uniform draw over a range whose top is 2.1 × 10^18 essentially never produces a
 * price below `10000` — so the `q = 0` branch, where the whole answer comes from the second
 * term, would go untested about once in every 2 × 10^14 draws. The strata below reach it
 * deliberately.
 *
 * The counters that guard this are deliberately counted on the **condition**, never on the
 * stratum index. `i % STRATA` gives each stratum exactly `SAMPLES / STRATA` draws for every
 * seed and every implementation, so an assertion on that count would be a compile-time
 * truth dressed up as a check — it would still pass if stratum 1's *body* were changed to
 * draw across the whole range. Asserting that some sampled price really was below `10000`
 * is what actually holds the branch open.
 */
class FeePropertyTest {

    private companion object {
        /** Pinned so a failure is reproducible; there is no clock and no entropy here. */
        const val SEED: Int = 20260910

        /** T2's floor on this file: at least ten thousand sampled `(price, bps)` pairs. */
        const val SAMPLES: Int = 10_000

        val TEN_THOUSAND: BigInteger = BigInteger.valueOf(10_000L)

        /** How many price strata [FeePropertyTest.samplePrice] draws from. */
        const val STRATA: Int = 5

        /**
         * §8.3's other conformant route, written out in full so it is visibly a different
         * algorithm from the one under test: multiply exactly, then divide exactly. No
         * decomposition, no 64-bit intermediate, no shared helper.
         */
        fun oracle(priceMsat: Long, bps: Int): Long =
            BigInteger.valueOf(priceMsat)
                .multiply(BigInteger.valueOf(bps.toLong()))
                .divide(TEN_THOUSAND)
                .toLong()
    }

    /**
     * A price in `[0, cap]`, drawn from the stratum that exercises a distinct branch of the
     * decomposition. Deliberately not uniform: see the class note.
     */
    private fun samplePrice(random: Random, stratum: Int): Long =
        when (stratum) {
            // Uniform across the whole legal range: the general case.
            0 -> random.nextLong(0L, Msat.SUPPLY_CAP_MSAT + 1L)
            // Below 10 000, where `price / 10000` is 0 and the entire fee comes out of the
            // remainder term. Unreachable in practice from stratum 0.
            1 -> random.nextLong(0L, FeeTerm.BASIS_POINT_DIVISOR)
            // An exact multiple of 10 000, where the remainder term is 0 and the entire fee
            // comes out of the whole term — the opposite branch to stratum 1.
            2 -> random.nextLong(0L, Msat.SUPPLY_CAP_MSAT / FeeTerm.BASIS_POINT_DIVISOR + 1L) *
                FeeTerm.BASIS_POINT_DIVISOR
            // Hard against the cap, where the naive product overflows by the widest margin.
            3 -> Msat.SUPPLY_CAP_MSAT - random.nextLong(0L, 1_000_000L)
            // Small but not tiny: the everyday order sizes, a few sats to a few BTC.
            else -> random.nextLong(0L, 100_000_000_000L)
        }

    /** Basis points in `0..10000`, with the boundary values drawn far more often than uniform. */
    private fun sampleBps(random: Random): Int = when (random.nextInt(0, 8)) {
        0 -> 0
        1 -> 1
        2 -> FeeTerm.MAX_BASIS_POINTS
        3 -> FeeTerm.MAX_BASIS_POINTS - 1
        else -> random.nextInt(0, FeeTerm.MAX_BASIS_POINTS + 1)
    }

    @Test
    fun `the 64-bit decomposition agrees with an arbitrary-precision oracle on every sampled pair`() {
        val random = Random(SEED)
        var nonZeroFees = 0
        var remainderTermContributed = 0
        var wholeTermWasZero = 0
        var remainderWasZero = 0

        repeat(SAMPLES) { i ->
            val priceMsat = samplePrice(random, i % STRATA)
            val bps = sampleBps(random)

            val price = Msat.ofMsat(priceMsat) // also asserts the sampler stayed within §4.4
            val split = FeeTerm.of(bps).splitOn(price)

            val expected = oracle(priceMsat, bps)
            assertEquals(expected, split.fee.millisatoshis, "§8.3 disagreement at price=$priceMsat bps=$bps")

            // The other two amounts §8.3 defines, checked against the same oracle's inputs.
            assertEquals(priceMsat, split.price.millisatoshis, "§8.2: the price must pass through untouched")
            assertEquals(priceMsat + expected, split.total.millisatoshis, "total_msat = price_msat + fee_msat")
            assertTrue(split.fee <= price, "a fee of at most 100% can never exceed the price")

            // §9.2's "required" rule, pinned across every sampled pair rather than only at
            // the three hand-picked points in FeeTest: a payee is required when the terms
            // name it AND its expected amount is non-zero. Roughly an eighth of these pairs
            // are the dangerous shape — a named recipient whose computed fee is 0 — which
            // §8.3 says deadlocks an order into `expired` if an invoice is awaited for it.
            assertEquals(
                bps > 0 && expected > 0L,
                split.feePayeeRequired,
                "§9.2 required-payee rule at price=$priceMsat bps=$bps",
            )

            if (expected > 0L) nonZeroFees++
            // Did the second term of the decomposition actually carry any of the answer?
            if (((priceMsat % 10_000L) * bps) / 10_000L > 0L) remainderTermContributed++
            // The two branches the strata exist to reach, counted on the condition itself.
            if (priceMsat < FeeTerm.BASIS_POINT_DIVISOR) wholeTermWasZero++
            if (priceMsat % FeeTerm.BASIS_POINT_DIVISOR == 0L) remainderWasZero++
        }

        // Non-vacuity. An implementation returning 0 for everything must not be able to
        // pass, and neither must one that only ever exercises the first term of the split.
        assertTrue(nonZeroFees > SAMPLES / 4, "only $nonZeroFees of $SAMPLES pairs levied a fee at all")
        assertTrue(
            remainderTermContributed > SAMPLES / 20,
            "the remainder term carried part of the answer only $remainderTermContributed times; " +
                "half the decomposition is going untested",
        )
        // And both edge branches of the decomposition were actually reached. Counted on the
        // price itself, so changing a stratum's body to draw uniformly would fail here.
        assertTrue(
            wholeTermWasZero > SAMPLES / 10,
            "only $wholeTermWasZero prices were below ${FeeTerm.BASIS_POINT_DIVISOR}; the q = 0 branch is untested",
        )
        assertTrue(
            remainderWasZero > SAMPLES / 10,
            "only $remainderWasZero prices divided exactly; the r = 0 branch is untested",
        )
    }

    @Test
    fun `rounding is floor everywhere, never ceiling and never nearest`() {
        val random = Random(SEED + 1)
        var inexact = 0

        repeat(SAMPLES) { i ->
            val priceMsat = samplePrice(random, i % STRATA)
            // 0 and 10 000 bps both divide exactly, so they can never distinguish floor
            // from ceiling. Draw strictly inside the range, where the quotient can have a
            // remainder at all.
            val bps = random.nextInt(1, FeeTerm.MAX_BASIS_POINTS)
            val fee = FeeTerm.of(bps).feeOn(Msat.ofMsat(priceMsat)).millisatoshis

            val product = BigInteger.valueOf(priceMsat).multiply(BigInteger.valueOf(bps.toLong()))
            val (quotient, remainder) = product.divideAndRemainder(TEN_THOUSAND)
            assertEquals(quotient.toLong(), fee, "not floor at price=$priceMsat bps=$bps")

            if (remainder.signum() != 0) {
                inexact++
                // Floor stated as its defining inequality — fee × 10000 < product <
                // (fee + 1) × 10000 whenever the division is inexact — rather than only as
                // an equality with the oracle's own quotient. All in BigInteger: §4.4
                // forbids floating point anywhere in a money path, including in a check.
                val feeBig = BigInteger.valueOf(fee)
                assertTrue(
                    feeBig.multiply(TEN_THOUSAND) < product,
                    "rounded up at price=$priceMsat bps=$bps",
                )
                assertTrue(
                    feeBig.add(BigInteger.ONE).multiply(TEN_THOUSAND) > product,
                    "rounded down by more than one unit at price=$priceMsat bps=$bps",
                )
            }
        }

        // If the division always came out exact, this test proved nothing about rounding.
        assertTrue(inexact > SAMPLES / 4, "only $inexact of $SAMPLES pairs had a remainder to round")
    }
}
