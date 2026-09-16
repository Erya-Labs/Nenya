package dev.eryalabs.nenya.money

import kotlin.js.JsName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The non-vacuity floor for §4.4: properties over sampled values, so an implementation
 * that refuses everything cannot pass by refusing everything.
 *
 * Every fixture in this file is generated here, by committed code, from a pinned seed —
 * nothing is typed out and nothing is read from a file. A reviewer can change [SEED] and
 * re-run, and the properties must still hold.
 *
 * The sampler is deliberately split in two directions, because they are not equally
 * informative:
 *
 *  - `sat → msat → sat` is `×1000` then `÷1000`. It round-trips by construction, and a
 *    broken implementation would have to be *very* broken to fail it. Its real job is to
 *    prove that every in-range value is *accepted*.
 *  - `msat → sat` on a value that is not a multiple of 1000 is the direction that can
 *    actually fail, and it is unreachable from the first sampler: multiplying satoshis by
 *    1000 cannot generate a non-multiple. §4.4's "Never lossy" rule lives entirely here.
 */
class MsatPropertyTest {

    private companion object {
        /** Pinned so a failure is reproducible; there is no clock and no entropy here. */
        const val SEED: Int = 20260910

        /** §4.4's floor on this file: at least ten thousand samples per property. */
        const val SAMPLES: Int = 10_000

        val MAX_SAT: Long = Msat.SUPPLY_CAP_MSAT / Msat.MSAT_PER_SAT
    }

    @JsName("every_satoshi_amount_at_or_below_the_cap_converts_and_round_trips_exactly")
    @Test
    fun `every satoshi amount at or below the cap converts, and round-trips exactly`() {
        val random = Random(SEED)
        // The two ends are included explicitly rather than left to a uniform draw, which
        // reaches neither.
        val samples = mutableListOf(0L, 1L, MAX_SAT - 1L, MAX_SAT)
        repeat(SAMPLES) { samples += random.nextLong(0L, MAX_SAT + 1L) }

        for (satoshis in samples) {
            val amount = Msat.ofSat(satoshis)
            assertEquals(satoshis * Msat.MSAT_PER_SAT, amount.millisatoshis, "wrong scale for $satoshis sat")
            assertEquals(satoshis, amount.toSatoshis(), "sat -> msat -> sat lost a value")
            assertTrue(amount.isWithinSupply(), "$satoshis sat is within supply and must be accepted")
        }
        assertTrue(samples.size > SAMPLES, "the sampler must actually produce $SAMPLES values")
    }

    @JsName("a_millisatoshi_amount_that_is_not_a_multiple_of_one_thousand_is_refused_never_truncated")
    @Test
    fun `a millisatoshi amount that is not a multiple of one thousand is refused, never truncated`() {
        val random = Random(SEED)
        var nonMultiples = 0

        repeat(SAMPLES) {
            // Draw a whole-satoshi base strictly below the maximum so that adding the
            // remainder below cannot push the amount over the supply cap — which would
            // refuse it for the wrong reason and hide whether this rule works at all.
            val wholeSat = random.nextLong(0L, MAX_SAT)
            val remainder = random.nextLong(1L, Msat.MSAT_PER_SAT) // 1..999, never 0
            val value = wholeSat * Msat.MSAT_PER_SAT + remainder
            assertTrue(value % Msat.MSAT_PER_SAT != 0L, "the sampler must draw a non-multiple")
            nonMultiples++

            val amount = Msat.ofMsat(value)
            assertEquals(value, amount.millisatoshis, "an in-supply millisatoshi amount must be accepted as it is")

            val refused = assertFailsWith<MoneyException>("$value msat is not whole satoshis and must be refused") {
                amount.toSatoshis()
            }
            assertEquals(MoneyRejection.NOT_WHOLE_SATOSHIS, refused.reason, "wrong reason for $value msat")
        }

        assertEquals(SAMPLES, nonMultiples, "the sampler must actually produce $SAMPLES non-multiples")
    }

    @JsName("every_millisatoshi_amount_at_or_below_the_cap_survives_a_btc_round_trip")
    @Test
    fun `every millisatoshi amount at or below the cap survives a BTC round-trip`() {
        val random = Random(SEED)
        val samples = mutableListOf(0L, 1L, Msat.MSAT_PER_BTC, Msat.SUPPLY_CAP_MSAT - 1L, Msat.SUPPLY_CAP_MSAT)
        repeat(SAMPLES) { samples += random.nextLong(0L, Msat.SUPPLY_CAP_MSAT + 1L) }

        for (millisatoshis in samples) {
            val text = btcDecimal(millisatoshis)
            assertEquals(
                millisatoshis,
                Msat.parseBtc(text).millisatoshis,
                "BTC round-trip lost a value at $millisatoshis msat (rendered $text)",
            )
        }
    }

    @JsName("a_twelfth_fractional_digit_is_refused_across_the_whole_range")
    @Test
    fun `a twelfth fractional digit is refused across the whole range`() {
        val random = Random(SEED)
        repeat(SAMPLES) {
            // Same values as above, rendered one digit finer than a millisatoshi. Every
            // one names a value Nenya cannot express, whatever the digits happen to be.
            val millisatoshis = random.nextLong(0L, Msat.SUPPLY_CAP_MSAT + 1L)
            val tooFine = btcDecimal(millisatoshis) + random.nextInt(0, 10).toString()
            val refused = assertFailsWith<MoneyException>("expected a refusal for a twelve-digit fraction") {
                Msat.parseBtc(tooFine)
            }
            assertEquals(MoneyRejection.SUB_MILLISATOSHI, refused.reason, "wrong reason for $tooFine")
        }
    }

    /**
     * Renders a millisatoshi amount as a decimal BTC string with exactly eleven fractional
     * digits, by integer arithmetic on the two halves. Deliberately not the inverse of
     * [Msat.parseBtc] sharing its code: an independent renderer is what makes the
     * round-trip evidence rather than a tautology.
     *
     * The eleven and the 10^11 are written out here rather than read from [Msat], for the
     * same reason. Borrowing [Msat.MAX_BTC_FRACTIONAL_DIGITS] would make this renderer
     * track the very constant under test — change the constant to twelve and renderer and
     * parser would move together and every property in this file would stay green, which
     * is precisely the failure a round-trip test is supposed to catch. 1 BTC is 10^11 msat
     * by §4.4, so both numbers below are specification facts, not implementation details.
     */
    private fun btcDecimal(millisatoshis: Long): String {
        val whole = millisatoshis / 100_000_000_000L
        val fraction = millisatoshis % 100_000_000_000L
        return whole.toString() + "." + fraction.toString().padStart(11, '0')
    }
}
