package dev.eryalabs.nenya.money

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The §8.3 fee arithmetic, case by case.
 *
 * §8.3 is the one piece of arithmetic every integrating client depends on and the one
 * place a rounding disagreement is wire-visible: the buyer's total differs between two
 * implementations and neither side can tell which of them is wrong. So the specification's
 * own worked examples are reproduced here exactly, and every negative control asserts the
 * *reason* rather than merely that something failed.
 *
 * Nothing here is an encoded value anybody typed out — every figure is either a worked
 * example transcribed from §8.3 with its arithmetic restated, or computed in the test by
 * `java.math.BigInteger`, which is a second algorithm and therefore evidence.
 *
 * ### Where these tests run
 *
 * This class holds the tests that are common Kotlin; it is abstract, and runs as `FeeTest` on each
 * target, so every test keeps its `FeeTest` name. The two tests whose oracle is `java.math.BigInteger`
 * are in the JVM `FeeTest` (`src/jvmTest`): common Kotlin has no arbitrary-precision integer, and
 * replacing the oracle with 64-bit arithmetic would be checking the decomposition against the
 * bug it exists to avoid.
 */
abstract class PortableFeeTest {

    // ---------------------------------------------------------------- specification facts

    @JsName("the_specification_s_basis_point_constants_are_pinned")
    @Test
    fun `the specification's basis-point constants are pinned`() {
        assertEquals(10_000L, FeeTerm.BASIS_POINT_DIVISOR)
        assertEquals(10_000, FeeTerm.MAX_BASIS_POINTS)
        // 1 bps is 0.01%, so 10 000 bps is 100%. Stated as the relation rather than as the
        // literal again, so a typo in the constant cannot hide behind a matching typo here.
        assertEquals(FeeTerm.MAX_BASIS_POINTS.toLong(), FeeTerm.BASIS_POINT_DIVISOR)
    }

    // ---------------------------------------------------------------- the worked examples

    @JsName("the_50_000_sat_at_250_bps_example_reproduces_exactly")
    @Test
    fun `the 50 000 SAT at 250 bps example reproduces exactly`() {
        // §8.3: price = 50 000 SAT, bps = 250 (2.5%).
        val price = Msat.ofSat(50_000L)
        assertEquals(50_000_000L, price.millisatoshis)

        val split = FeeTerm.of(250).splitOn(price)

        // floor(50 000 000 × 250 / 10 000) = 1 250 000 msat = 1 250 SAT
        assertEquals(1_250_000L, split.fee.millisatoshis)
        assertEquals(1_250L, split.fee.toSatoshis())
        // total = 51 250 000 msat = 51 250 SAT
        assertEquals(51_250_000L, split.total.millisatoshis)
        assertEquals(51_250L, split.total.toSatoshis())
        assertTrue(split.feePayeeRequired, "a 1 250 000 msat fee is required and must be invoiced")
    }

    @JsName("the_1_001_sat_at_137_bps_rounding_example_reproduces_exactly_rounding_down")
    @Test
    fun `the 1 001 SAT at 137 bps rounding example reproduces exactly, rounding down`() {
        // §8.3: price = 1 001 SAT, bps = 137.
        val price = Msat.ofSat(1_001L)
        assertEquals(1_001_000L, price.millisatoshis)

        val fee = FeeTerm.of(137).feeOn(price)

        // 1 001 000 × 137 = 137 137 000, and 137 137 000 / 10 000 = 13 713.7 exactly.
        assertEquals(137_137_000L, 1_001_000L * 137L)
        assertEquals(13_713L, fee.millisatoshis)
        // Floor, never ceiling: ceiling would be 13 714, and this is the assertion that
        // tells the two apart. §8.3 says the division truncates toward zero and both
        // operands are non-negative, so truncation is floor.
        assertNotEquals(13_714L, fee.millisatoshis, "§8.3 rounds down; 13 714 is the ceiling")

        // The fee is not a whole number of satoshis, and §4.4 refuses rather than rounds.
        // §8.3's satoshi rounding is a property of a satoshi-only rail and is dormant in
        // v1, so nothing here rounds it to the 13 SAT §8.3 mentions.
        val refused = assertFailsWith<MoneyException> { fee.toSatoshis() }
        assertEquals(MoneyRejection.NOT_WHOLE_SATOSHIS, refused.reason)
    }

    // ---------------------------------------------------------------- the zero-fee case

    @JsName("a_non_zero_bps_can_compute_to_a_zero_fee_and_then_no_fee_payee_is_required")
    @Test
    fun `a non-zero bps can compute to a zero fee, and then no fee payee is required`() {
        // §8.3's own example of the deadlock case: bps = 1, price_msat = 3000.
        val term = FeeTerm.of(1)
        val split = term.splitOn(Msat.ofMsat(3_000L))

        // floor(3000 × 1 / 10 000) = floor(0.3) = 0
        assertEquals(0L, split.fee.millisatoshis)
        assertEquals(Msat.ZERO, split.fee)
        assertEquals(3_000L, split.total.millisatoshis)

        // The term names a recipient — §8.1 requires one above zero bps — and yet no fee
        // invoice may exist. §9.2 computes "required" from the amount, not from the term;
        // an implementation that awaits an invoice per named payee expires every such order.
        assertTrue(term.namesRecipient, "1 bps is above zero, so §8.1 requires a recipient")
        assertFalse(split.feePayeeRequired, "a computed fee of 0 requires no invoice and no receipt")
    }

    @JsName("a_free_order_levies_no_fee_even_at_a_hundred_per_cent_and_requires_no_fee_invoice")
    @Test
    fun `a free order levies no fee even at a hundred per cent, and requires no fee invoice`() {
        // A counterparty can propose a zero price — §8.3 does not forbid one and
        // Msat.ofMsat(0) is legal — and the sampler in FeePropertyTest essentially never
        // draws it. This is §8.3's zero-fee deadlock arriving from the other direction:
        // there the fee rounded away, here there is nothing to take a fee of. The order
        // must still be completable, so no fee invoice may be awaited.
        val split = FeeTerm.of(FeeTerm.MAX_BASIS_POINTS).splitOn(Msat.ZERO)

        assertEquals(0L, split.fee.millisatoshis)
        assertEquals(0L, split.total.millisatoshis)
        assertTrue(split.term.namesRecipient, "10 000 bps is above zero, so §8.1 requires a recipient")
        assertFalse(split.feePayeeRequired, "a free order has no fee to invoice and must not await one")
    }

    @JsName("a_stated_zero_fee_term_is_distinguishable_from_no_fee_term_at_all")
    @Test
    fun `a stated zero-fee term is distinguishable from no fee term at all`() {
        val price = Msat.ofSat(10_000L)

        val absent = FeeTerm.Absent.splitOn(price)
        val statedZero = FeeTerm.of(0).splitOn(price)
        val statedOneOnThreeThousand = FeeTerm.of(1).splitOn(Msat.ofMsat(3_000L))

        // All three levy nothing.
        assertEquals(Msat.ZERO, absent.fee)
        assertEquals(Msat.ZERO, statedZero.fee)
        assertEquals(Msat.ZERO, statedOneOnThreeThousand.fee)

        // And all three are distinguishable, which is the point. §8.1's read rule makes a
        // missing tag zero fee; §8.1 also says a proposal SHOULD carry an explicit
        // ["fee", "0"] so the absence is itself a signed statement, and §8.4 requires the
        // pair be reproduced byte-identically at four later points. A bare 0 loses that.
        assertEquals(FeeTerm.Absent, absent.term)
        assertNotEquals<FeeTerm>(FeeTerm.Absent, statedZero.term)
        assertEquals(0, statedZero.term.basisPoints)
        assertEquals(1, statedOneOnThreeThousand.term.basisPoints)

        // Neither zero-bps form names a recipient — §8.1 requires it be OMITTED at 0 bps —
        // while the 1 bps form does, and still requires no invoice.
        assertFalse(absent.term.namesRecipient)
        assertFalse(statedZero.term.namesRecipient)
        assertTrue(statedOneOnThreeThousand.term.namesRecipient)
        assertFalse(absent.feePayeeRequired)
        assertFalse(statedZero.feePayeeRequired)
        assertFalse(statedOneOnThreeThousand.feePayeeRequired)
    }

    // ---------------------------------------------------------------- the overflow control

    // ---------------------------------------------------------------- the basis-point range

    @JsName("t_10000_bps_is_accepted_and_10001_is_rejected_as_above_the_maximum")
    @Test
    fun `10000 bps is accepted and 10001 is rejected as above the maximum`() {
        assertEquals(10_000, FeeTerm.of(10_000).basisPoints)

        val refused = assertFailsWith<MoneyException> { FeeTerm.of(10_001) }
        assertEquals(MoneyRejection.BPS_ABOVE_MAXIMUM, refused.reason)

        val wayOver = assertFailsWith<MoneyException> { FeeTerm.of(Int.MAX_VALUE) }
        assertEquals(MoneyRejection.BPS_ABOVE_MAXIMUM, wayOver.reason)
    }

    @JsName("a_negative_fee_term_is_refused_at_construction")
    @Test
    fun `a negative fee term is refused at construction`() {
        for (bps in listOf(-1, -250, Int.MIN_VALUE)) {
            val refused = assertFailsWith<MoneyException>("$bps bps must be refused") { FeeTerm.of(bps) }
            assertEquals(MoneyRejection.NEGATIVE, refused.reason, "wrong reason for $bps bps")
        }
    }

    @JsName("the_basis_point_range_is_enforced_by_the_type_not_only_by_the_factory")
    @Test
    fun `the basis-point range is enforced by the type, not only by the factory`() {
        // FeeTerm.Stated's constructor is internal, so this is a door only the library
        // itself can reach — and the library will reach it, when the tag codec arrives.
        // "of() is the only caller" is a property of today's code; §8.1's range has to be a
        // property of the type. An out-of-range Stated would levy more than the price.
        val over = assertFailsWith<MoneyException> { FeeTerm.Stated(10_001) }
        assertEquals(MoneyRejection.BPS_ABOVE_MAXIMUM, over.reason)

        val under = assertFailsWith<MoneyException> { FeeTerm.Stated(-1) }
        assertEquals(MoneyRejection.NEGATIVE, under.reason)

        assertEquals(10_000, FeeTerm.Stated(10_000).basisPoints)
    }

    @JsName("a_split_derives_its_fee_from_its_own_terms_and_cannot_be_told_otherwise")
    @Test
    fun `a split derives its fee from its own terms and cannot be told otherwise`() {
        // FeeSplit takes (price, term) and computes the fee; there is no constructor that
        // accepts a fee. §8.4 aborts an order over a fee term that diverges from the signed
        // one, and the same divergence inside a single split is not worth making possible.
        val price = Msat.ofSat(50_000L)
        val term = FeeTerm.of(250)
        assertEquals(term.feeOn(price), FeeSplit(price, term).fee)
        assertEquals(term.splitOn(price), FeeSplit(price, term))
    }

    // ---------------------------------------------------------------- the price bound

    @JsName("a_price_above_the_supply_cap_is_refused_as_above_supply")
    @Test
    fun `a price above the supply cap is refused as above supply`() {
        // §4.4 bounds price_msat by the supply cap precisely so §8.3 is expressible without
        // overflow, so a price above it is not a price. Such an amount is unreachable
        // through Msat's read paths and reachable by arithmetic, which is how it is built
        // here — it is what an §8.3 total looks like, and a total is not a price.
        val aboveCap = Msat.SUPPLY_CAP + Msat.ofMsat(1L)
        assertEquals(2_100_000_000_000_000_001L, aboveCap.millisatoshis)

        val refused = assertFailsWith<MoneyException> { FeeTerm.of(250).feeOn(aboveCap) }
        assertEquals(MoneyRejection.ABOVE_SUPPLY, refused.reason)

        // Including at zero bps, where the answer would have been 0 and the bad price would
        // have gone unnoticed.
        val atZero = assertFailsWith<MoneyException> { FeeTerm.Absent.feeOn(aboveCap) }
        assertEquals(MoneyRejection.ABOVE_SUPPLY, atZero.reason)
    }

    // ---------------------------------------------------------------- shape

    @JsName("a_fee_is_never_more_than_the_price_and_the_total_is_always_their_sum")
    @Test
    fun `a fee is never more than the price, and the total is always their sum`() {
        val price = Msat.ofSat(1_000_000L)
        for (bps in 0..FeeTerm.MAX_BASIS_POINTS step 137) {
            val split = FeeTerm.of(bps).splitOn(price)
            assertTrue(split.fee <= price, "$bps bps levied more than the price")
            assertEquals(price.millisatoshis + split.fee.millisatoshis, split.total.millisatoshis)
            assertEquals(price, split.price, "§8.2: the price is what the provider receives, untouched")
        }
    }

    @JsName("two_terms_stating_the_same_basis_points_are_equal_and_absent_is_its_own_thing")
    @Test
    fun `two terms stating the same basis points are equal, and absent is its own thing`() {
        assertEquals(FeeTerm.of(250), FeeTerm.of(250))
        assertEquals(FeeTerm.of(250).hashCode(), FeeTerm.of(250).hashCode())
        assertNotEquals(FeeTerm.of(250), FeeTerm.of(251))
        assertNotEquals<FeeTerm>(FeeTerm.of(0), FeeTerm.Absent)

        val price = Msat.ofSat(7L)
        assertEquals(FeeTerm.of(250).splitOn(price), FeeTerm.of(250).splitOn(price))
        assertNotEquals(FeeTerm.of(250).splitOn(price), FeeTerm.of(251).splitOn(price))
    }
}
