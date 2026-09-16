package dev.eryalabs.nenya.money

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The §4.4 money rules, case by case.
 *
 * Every negative control asserts the *reason* rather than merely that something failed.
 * "It threw" is not evidence: a parser that rejects everything passes that assertion, and
 * so does one that rejects a sub-millisatoshi value as gibberish when the caller needed
 * to be told the amount was simply too fine to express.
 *
 * No value here is an encoded string somebody typed out. The BTC inputs are plain decimal
 * amounts named by the specification, and every expected millisatoshi figure is either
 * pinned as an arithmetic fact of §4.4 or computed by `java.math.BigInteger` in the test itself.
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as `MsatTest` on each
 * target, so every test keeps its `MsatTest` name. The one test that establishes a genuine 64-bit
 * overflow with `java.math.BigInteger` is in the JVM `MsatTest` (`src/jvmTest`): common Kotlin has no
 * arbitrary-precision integer to establish it with.
 */
abstract class PortableMsatTest {

    // ---------------------------------------------------------------- unit facts

    @JsName("the_specification_s_unit_relations_are_pinned")
    @Test
    fun `the specification's unit relations are pinned`() {
        assertEquals(1_000L, Msat.MSAT_PER_SAT)
        assertEquals(100_000_000_000L, Msat.MSAT_PER_BTC)
        assertEquals(2_100_000_000_000_000_000L, Msat.SUPPLY_CAP_MSAT)
        assertEquals(11, Msat.MAX_BTC_FRACTIONAL_DIGITS)
        // 1 BTC = 100 000 000 SAT = 100 000 000 000 msat, stated the other way round so a
        // typo in one constant cannot hide behind a matching typo in the other.
        assertEquals(Msat.MSAT_PER_BTC, 100_000_000L * Msat.MSAT_PER_SAT)
        // The cap is 21 000 000 BTC exactly.
        assertEquals(Msat.SUPPLY_CAP_MSAT, 21_000_000L * Msat.MSAT_PER_BTC)
    }

    // ---------------------------------------------------------------- BTC parsing, positive

    @JsName("one_hundred_billionth_of_a_btc_is_exactly_one_millisatoshi")
    @Test
    fun `one hundred-billionth of a BTC is exactly one millisatoshi`() {
        assertEquals(1L, Msat.parseBtc("0.00000000001").millisatoshis)
    }

    @JsName("one_btc_is_one_hundred_billion_millisatoshis")
    @Test
    fun `one BTC is one hundred billion millisatoshis`() {
        assertEquals(100_000_000_000L, Msat.parseBtc("1").millisatoshis)
        assertEquals(100_000_000_000L, Msat.parseBtc("1.0").millisatoshis)
        assertEquals(100_000_000_000L, Msat.parseBtc("1.00000000000").millisatoshis)
    }

    @JsName("twenty_one_million_btc_is_exactly_the_supply_cap")
    @Test
    fun `twenty-one million BTC is exactly the supply cap`() {
        val cap = Msat.parseBtc("21000000")
        assertEquals(2_100_000_000_000_000_000L, cap.millisatoshis)
        assertEquals(Msat.SUPPLY_CAP, cap)
        assertTrue(cap.isWithinSupply())
    }

    @JsName("a_fractional_part_is_scaled_by_position_not_by_digit_count")
    @Test
    fun `a fractional part is scaled by position, not by digit count`() {
        // 0.5 BTC = 50 000 000 000 msat. A parser that read the fraction as an integer
        // without positional scaling would answer 5.
        assertEquals(50_000_000_000L, Msat.parseBtc("0.5").millisatoshis)
        assertEquals(5_000_000_000L, Msat.parseBtc("0.05").millisatoshis)
        assertEquals(100_000_000_500L, Msat.parseBtc("1.000000005").millisatoshis)
    }

    @JsName("zero_parses_and_leading_zeros_are_accepted_on_read")
    @Test
    fun `zero parses and leading zeros are accepted on read`() {
        assertEquals(Msat.ZERO, Msat.parseBtc("0"))
        assertEquals(Msat.ZERO, Msat.parseBtc("0.00000000000"))
        assertEquals(Msat.ZERO, Msat.parseBtc("00000"))
        // §4.4 is strict on write and permissive on read; this is a read.
        assertEquals(100_000_000_000L, Msat.parseBtc("0000000001").millisatoshis)
    }

    // ---------------------------------------------------------------- BTC parsing, negative controls

    @JsName("a_twelfth_fractional_digit_is_refused_as_finer_than_a_millisatoshi_not_as_malformed")
    @Test
    fun `a twelfth fractional digit is refused as finer than a millisatoshi, not as malformed`() {
        // 0.000000000001 BTC is 0.1 msat. §4.4 says a read that would lose precision MUST
        // fail loudly and MUST NOT round, and the caller has to be able to tell this apart
        // from gibberish, so the reason is the assertion.
        val refused = assertFailsWith<MoneyException> { Msat.parseBtc("0.000000000001") }
        assertEquals(MoneyRejection.SUB_MILLISATOSHI, refused.reason)
        assertNotEquals(MoneyRejection.MALFORMED, refused.reason)
        assertTrue(
            refused.message!!.contains("millisatoshi"),
            "the refusal must name the unit it could not express, was: ${refused.message}",
        )
    }

    @JsName("a_twelve_digit_fraction_is_refused_even_when_the_digits_are_all_zero")
    @Test
    fun `a twelve-digit fraction is refused even when the digits are all zero`() {
        // The rule §4.4 states is about decimal places, not about whether this particular
        // value happened to round-trip. An implementation that checked the value instead
        // of the digit count would accept this and then accept 0.000000000001 tomorrow.
        val refused = assertFailsWith<MoneyException> { Msat.parseBtc("0.000000000000") }
        assertEquals(MoneyRejection.SUB_MILLISATOSHI, refused.reason)
    }

    @JsName("the_length_bound_does_not_shadow_the_sub_millisatoshi_reason")
    @Test
    fun `the length bound does not shadow the sub-millisatoshi reason`() {
        // §4.3's size bound sits in front of the digit-count check, so a long-but-too-fine
        // amount could come back MALFORMED and lose §4.4's distinguishability. Twenty-one
        // characters with a twelfth fractional digit: inside the bound, still too fine.
        val refused = assertFailsWith<MoneyException> { Msat.parseBtc("21000000.000000000000") }
        assertEquals(MoneyRejection.SUB_MILLISATOSHI, refused.reason)
    }

    @JsName("one_millisatoshi_above_twenty_one_million_btc_is_refused_as_above_supply")
    @Test
    fun `one millisatoshi above twenty-one million BTC is refused as above supply`() {
        val refused = assertFailsWith<MoneyException> { Msat.parseBtc("21000000.00000000001") }
        assertEquals(MoneyRejection.ABOVE_SUPPLY, refused.reason)
        assertTrue(
            refused.message!!.contains("supply"),
            "the refusal must name the supply bound, was: ${refused.message}",
        )
    }

    @JsName("a_whole_part_too_large_for_a_long_is_refused_as_above_supply_not_as_malformed")
    @Test
    fun `a whole part too large for a Long is refused as above supply, not as malformed`() {
        // Nine digits of BTC, and then twenty. Both are numerically above the cap; the
        // second would also blow up a naive toLong(). Neither is malformed.
        assertEquals(
            MoneyRejection.ABOVE_SUPPLY,
            assertFailsWith<MoneyException> { Msat.parseBtc("100000000") }.reason,
        )
        assertEquals(
            MoneyRejection.ABOVE_SUPPLY,
            assertFailsWith<MoneyException> { Msat.parseBtc("99999999999999999999") }.reason,
        )
        // ...and the leading zeros in front of an in-range value are not a length problem.
        assertEquals(100_000_000_000L, Msat.parseBtc("000000000000000000001").millisatoshis)
    }

    @JsName("non_decimal_input_is_malformed")
    @Test
    fun `non-decimal input is malformed`() {
        val malformed = listOf(
            "",            // empty
            ".",           // no digits at all
            "1.",          // trailing point
            ".5",          // no whole part
            "1.2.3",       // two points
            "-1",          // sign
            "+1",          // sign
            "1e5",         // exponent
            " 1",          // leading space
            "1 ",          // trailing space
            "1_000",       // separator
            "0x1",         // radix prefix
            "abc",         // not a number
            "1,5",         // comma as decimal point
            "１",      // FULLWIDTH DIGIT ONE — Char.isDigit() says true, we must not
            "٣",      // ARABIC-INDIC DIGIT THREE — likewise
        )
        for ((index, input) in malformed.withIndex()) {
            // Identified by position in the list above, so a red run names the exact case
            // without echoing input this class is deliberately not echoing.
            val refused = assertFailsWith<MoneyException>("expected a refusal for malformed input #$index") {
                Msat.parseBtc(input)
            }
            assertEquals(MoneyRejection.MALFORMED, refused.reason, "wrong reason for malformed input #$index")
        }
    }

    @JsName("an_over_long_input_is_refused_before_it_is_parsed_and_never_truncated")
    @Test
    fun `an over-long input is refused before it is parsed, and never truncated`() {
        // §4.3: an implementation MUST bound the size of anything it parses and MUST reject
        // rather than truncate. A relay is entitled to send a megabyte of digits.
        val hostile = "1".repeat(100_000)
        val refused = assertFailsWith<MoneyException> { Msat.parseBtc(hostile) }
        assertEquals(MoneyRejection.MALFORMED, refused.reason)
        // The bound must not have swallowed a legitimate amount: the longest well-formed
        // BTC value in Nenya is the cap at one-millisatoshi precision, twenty characters.
        assertEquals(Msat.SUPPLY_CAP_MSAT, Msat.parseBtc("21000000.00000000000").millisatoshis)
    }

    @JsName("no_floating_point_can_be_involved_in_the_parse")
    @Test
    fun `no floating point can be involved in the parse`() {
        // 0.1 BTC is not representable in binary floating point. A parser routed through a
        // Double would land on 10 000 000 000.000000149... and answer either side of the
        // true value; this asserts the exact integer.
        assertEquals(10_000_000_000L, Msat.parseBtc("0.1").millisatoshis)
        // And a value above 2^53, where a Double cannot represent consecutive integers at
        // all: the cap minus one millisatoshi must come back exactly.
        assertEquals(
            Msat.SUPPLY_CAP_MSAT - 1L,
            Msat.parseBtc("20999999.99999999999").millisatoshis,
        )
        assertTrue(Msat.SUPPLY_CAP_MSAT > (1L shl 53), "the interesting range must be beyond Double's integers")
    }

    // ---------------------------------------------------------------- construction and the supply bound

    @JsName("a_negative_amount_is_refused_at_construction")
    @Test
    fun `a negative amount is refused at construction`() {
        assertEquals(
            MoneyRejection.NEGATIVE,
            assertFailsWith<MoneyException> { Msat.ofMsat(-1L) }.reason,
        )
        assertEquals(
            MoneyRejection.NEGATIVE,
            assertFailsWith<MoneyException> { Msat.ofSat(-1L) }.reason,
        )
        assertEquals(
            MoneyRejection.NEGATIVE,
            assertFailsWith<MoneyException> { Msat.ofMsat(Long.MIN_VALUE) }.reason,
        )
    }

    @JsName("one_millisatoshi_above_the_cap_is_refused_as_above_supply_naming_that_reason")
    @Test
    fun `one millisatoshi above the cap is refused as above supply, naming that reason`() {
        // Note: cap + 1 does NOT overflow a Long — the cap is 2.1e18 against a maximum of
        // 9.22e18 — so "assert it did not go negative" would be true even of a broken
        // implementation. The reason code is the whole assertion.
        assertTrue(Msat.SUPPLY_CAP_MSAT + 1L > 0L, "cap + 1 must not be an overflow case")
        val refused = assertFailsWith<MoneyException> { Msat.ofMsat(Msat.SUPPLY_CAP_MSAT + 1L) }
        assertEquals(MoneyRejection.ABOVE_SUPPLY, refused.reason)
        assertTrue(refused.message!!.contains("supply"), "was: ${refused.message}")

        // The cap itself is accepted, so the bound is inclusive and the test above is not
        // passing because everything is refused.
        assertEquals(Msat.SUPPLY_CAP_MSAT, Msat.ofMsat(Msat.SUPPLY_CAP_MSAT).millisatoshis)
    }

    @JsName("a_satoshi_amount_above_the_cap_is_refused_before_its_multiply_can_overflow")
    @Test
    fun `a satoshi amount above the cap is refused before its multiply can overflow`() {
        val maxSat = Msat.SUPPLY_CAP_MSAT / Msat.MSAT_PER_SAT
        assertEquals(Msat.SUPPLY_CAP, Msat.ofSat(maxSat))
        assertEquals(
            MoneyRejection.ABOVE_SUPPLY,
            assertFailsWith<MoneyException> { Msat.ofSat(maxSat + 1L) }.reason,
        )
        // Long.MAX_VALUE satoshis multiplied by 1000 first wraps to exactly -1000: the
        // product is 1000·2^63 - 1000, and 1000·2^63 is 500·2^64, which is 0 modulo 2^64.
        // So an implementation that converted before bounding would not merely be wrong,
        // it would hand back a *negative* amount from a positive input. The bound has to
        // be checked before the conversion.
        assertEquals(-1_000L, Long.MAX_VALUE * Msat.MSAT_PER_SAT, "the naive route wraps to -1000 here")
        val refused = assertFailsWith<MoneyException> { Msat.ofSat(Long.MAX_VALUE) }
        assertEquals(MoneyRejection.ABOVE_SUPPLY, refused.reason)
    }

    @JsName("an_above_supply_amount_cannot_leave_through_the_satoshi_door_either")
    @Test
    fun `an above-supply amount cannot leave through the satoshi door either`() {
        // §4.4 bounds a price by the supply cap, and toSatoshis() is the export side of
        // that field. An above-cap amount is reachable by arithmetic (§8.3's boundary
        // total), so the door has to refuse it rather than emit 8 400 000 000 000 000 sat.
        val aboveSupply = Msat.SUPPLY_CAP * 4L
        assertTrue(!aboveSupply.isWithinSupply())
        assertEquals(0L, aboveSupply.millisatoshis % Msat.MSAT_PER_SAT, "it is whole satoshis, so only the bound can refuse it")
        val refused = assertFailsWith<MoneyException> { aboveSupply.toSatoshis() }
        assertEquals(MoneyRejection.ABOVE_SUPPLY, refused.reason)
        // And the cap itself still passes through, so this is a bound and not a blockade.
        assertEquals(Msat.SUPPLY_CAP_MSAT / Msat.MSAT_PER_SAT, Msat.SUPPLY_CAP.toSatoshis())

        // When both refusals apply, the bound wins: an amount that may not exist at all is
        // not worth reporting a precision problem about. Pinned so the order is a decision.
        val bothWrong = Msat.SUPPLY_CAP * 4L + Msat.ofMsat(1L)
        assertEquals(1L, bothWrong.millisatoshis % Msat.MSAT_PER_SAT)
        assertEquals(
            MoneyRejection.ABOVE_SUPPLY,
            assertFailsWith<MoneyException> { bothWrong.toSatoshis() }.reason,
        )
    }

    @JsName("requirewithinsupply_restates_the_bound_for_an_amount_that_arrived_by_arithmetic")
    @Test
    fun `requireWithinSupply restates the bound for an amount that arrived by arithmetic`() {
        val total = Msat.SUPPLY_CAP + Msat.SUPPLY_CAP
        assertEquals(4_200_000_000_000_000_000L, total.millisatoshis)
        assertTrue(!total.isWithinSupply())
        assertEquals(
            MoneyRejection.ABOVE_SUPPLY,
            assertFailsWith<MoneyException> { total.requireWithinSupply() }.reason,
        )
        assertEquals(Msat.SUPPLY_CAP, Msat.SUPPLY_CAP.requireWithinSupply())
    }

    // ---------------------------------------------------------------- satoshi conversion

    @JsName("whole_satoshis_convert_both_ways")
    @Test
    fun `whole satoshis convert both ways`() {
        assertEquals(1_000L, Msat.ofSat(1L).millisatoshis)
        assertEquals(1L, Msat.ofSat(1L).toSatoshis())
        assertEquals(0L, Msat.ZERO.toSatoshis())
        assertEquals(90_000_000L, Msat.ofSat(90_000L).millisatoshis)
    }

    @JsName("a_millisatoshi_amount_that_is_not_whole_satoshis_is_refused_never_truncated")
    @Test
    fun `a millisatoshi amount that is not whole satoshis is refused, never truncated`() {
        val refused = assertFailsWith<MoneyException> { Msat.ofMsat(1_500L).toSatoshis() }
        assertEquals(MoneyRejection.NOT_WHOLE_SATOSHIS, refused.reason)
        // The failure mode this guards against is answering 1, or 2.
        val refusedByOne = assertFailsWith<MoneyException> { Msat.ofMsat(1L).toSatoshis() }
        assertEquals(MoneyRejection.NOT_WHOLE_SATOSHIS, refusedByOne.reason)
        assertEquals(
            MoneyRejection.NOT_WHOLE_SATOSHIS,
            assertFailsWith<MoneyException> { Msat.ofMsat(999L).toSatoshis() }.reason,
        )
    }

    // ---------------------------------------------------------------- arithmetic

    @JsName("addition_that_fits_is_exact_including_adding_nothing_and_reaching_the_limit")
    @Test
    fun `addition that fits is exact, including adding nothing and reaching the limit`() {
        // §8.3's zero-fee case is `total_msat = price_msat + 0`, which is reachable with a
        // non-zero bps (bps = 1, price = 3000). An overflow guard written one comparison
        // too strict would refuse exactly this and deadlock every zero-fee order, while
        // every overflow test in this file stayed green.
        val price = Msat.ofMsat(3_000L)
        assertEquals(price, price + Msat.ZERO)
        assertEquals(price, Msat.ZERO + price)
        assertEquals(Msat.ZERO, Msat.ZERO + Msat.ZERO)
        assertEquals(Msat.ofMsat(4_000L), price + Msat.ofMsat(1_000L))
        // A sum landing exactly on Long.MAX_VALUE fits and must not be called an overflow.
        val huge = Msat.ofMsat(1L) * (Long.MAX_VALUE - Msat.SUPPLY_CAP_MSAT)
        assertEquals(Long.MAX_VALUE, (huge + Msat.SUPPLY_CAP).millisatoshis)
    }

    @JsName("addition_reports_overflow_rather_than_wrapping")
    @Test
    fun `addition reports overflow rather than wrapping`() {
        // Built by arithmetic, because no read path can produce an amount this large.
        val huge = Msat.SUPPLY_CAP * 4L
        assertEquals(8_400_000_000_000_000_000L, huge.millisatoshis)

        val naive = huge.millisatoshis + Msat.SUPPLY_CAP_MSAT
        assertTrue(naive < 0L, "the naive sum really does wrap, and wraps negative here")

        val refused = assertFailsWith<MoneyException> { huge + Msat.SUPPLY_CAP }
        assertEquals(MoneyRejection.OVERFLOW, refused.reason)
    }

    @JsName("multiplication_that_fits_is_exact_including_at_the_boundary")
    @Test
    fun `multiplication that fits is exact, including at the boundary`() {
        assertEquals(Msat.ZERO, Msat.SUPPLY_CAP * 0L)
        assertEquals(Msat.ZERO, Msat.ZERO * Long.MAX_VALUE)
        assertEquals(Msat.SUPPLY_CAP, Msat.SUPPLY_CAP * 1L)
        // Long.MAX_VALUE itself, reached exactly, must not be mistaken for an overflow.
        assertEquals(Long.MAX_VALUE, (Msat.ofMsat(1L) * Long.MAX_VALUE).millisatoshis)
    }

    @JsName("a_negative_multiplier_is_refused")
    @Test
    fun `a negative multiplier is refused`() {
        val refused = assertFailsWith<MoneyException> { Msat.ofSat(1L) * -1L }
        assertEquals(MoneyRejection.NEGATIVE, refused.reason)
        assertEquals(
            MoneyRejection.NEGATIVE,
            assertFailsWith<MoneyException> { Msat.ZERO * Long.MIN_VALUE }.reason,
        )
    }

    @JsName("amounts_order_and_compare_by_millisatoshi_value")
    @Test
    fun `amounts order and compare by millisatoshi value`() {
        assertTrue(Msat.ofSat(1L) > Msat.ofMsat(999L))
        assertEquals(Msat.ofSat(1L), Msat.ofMsat(1_000L))
        assertEquals(listOf(Msat.ZERO, Msat.ofMsat(1L), Msat.ofSat(1L)), listOf(Msat.ofSat(1L), Msat.ZERO, Msat.ofMsat(1L)).sorted())
    }
}
