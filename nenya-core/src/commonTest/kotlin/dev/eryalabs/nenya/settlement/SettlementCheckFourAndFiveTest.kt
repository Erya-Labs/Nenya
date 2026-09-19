package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.PaymentException
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.payment.PaymentRejection
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.seam.FakeClock
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §9.2 check 4 (the amount) and check 5 (the expiry), on the store path.
 *
 * ### These are the two checks a dishonest counterparty is caught by and nothing else catches
 *
 * A provider who invoices ten times `price_msat`, or a fee recipient who pads its invoice with a
 * hidden margin, passes every other check in this library: the string is byte-identical to the one
 * stored (check 1), the proof is 32 bytes of lowercase hex (check 2), and the preimage hashes to
 * the payment hash (check 3's comparison) — because the buyer really did pay the invoice the
 * provider really did issue. What is wrong is the *figure*, and only check 4 looks at it. Check 5
 * is the same shape for time: an invoice already dead when it was accepted is one no wallet would
 * have paid, and a receipt for one is evidence of something other than this order settling.
 *
 * ### Nothing here is typed, and every expected amount is computed
 *
 * Every invoice is one of the 26 vendored BOLT-11 examples read through [Bolt11Examples], or one of
 * them with a single part changed by [Bolt11Composer] (decision D). Every expected millisatoshi
 * figure is computed **in the test**, from the figure the vendored document states in prose beside
 * the invoice and from Appendix C's multiplier table parsed out of `spec/NENYA-1.md` — never a
 * number written here. A wrong factor on one side cannot agree with a wrong factor on the other,
 * because the two sides are the document and the specification rather than this file twice.
 *
 * Clock readings are computed the same way: from the timestamp and expiry the document states for
 * the invoice under test, so that "one second past the deadline" is one second past *that
 * invoice's* deadline and not past a constant that happens to be near it.
 *
 * ### Each control names the reason and never merely the refusal
 *
 * A control asserting "refused" would pass with check 4 deleted and check 5 firing instead, which
 * is exactly the confusion the three constants exist to prevent — so each asserts the
 * [SettlementRejection] and, where the order of the checks is the point, asserts *which* check
 * spoke first.
 */
class SettlementCheckFourAndFiveTest {

    private companion object {

        /** Appendix C's default when an invoice carries no `x`, restated so a control can use it. */
        const val DEFAULT_EXPIRY: Long = 3_600L

        /** The two `2500u` examples state this, and the boundary controls are computed from it. */
        const val ONE_MINUTE: Long = 60L

        /** The pico example states this. Its boundary is the same rule at a very different scale. */
        const val ONE_WEEK: Long = 604_800L

        /**
         * How long after an invoice's own deadline the "verified years later" control stands.
         *
         * Check 5 takes no clock at verification time, so what this number does is make the control
         * *meaningful to a reader*: it is the moment at which a check that re-read the clock would
         * have refused, and the test asserts the settlement evidences anyway.
         */
        const val YEARS_LATER: Long = 5L * 365L * 24L * 3_600L

        /** Half of 10 000 bps, so a split's fee is exactly half its price. See [feeOf]. */
        const val HALF_IN_BASIS_POINTS: Int = 5_000

        /** How many fixtures the settlement-level property runs over, per payee and per outcome. */
        const val CORPUS: Int = 200

        /** A thirteen-group `x` is 65 bits — the only width at which the value bound can bite. */
        const val SIXTY_FIVE_BIT_GROUPS: Int = 13

        /** 2^63 = 8 × 2^60: the first value a signed `Long` cannot hold. T22 saturates it. */
        const val LEADING_GROUP_AT_TWO_TO_THE_63: Int = 8

        /** 2^64 − 1 = 15 × 2^60 + (2^60 − 1): the largest `x` Appendix C admits. */
        const val LEADING_GROUP_AT_THE_LARGEST_LEGAL_VALUE: Int = 15

        /** Every group below the leading one, all bits set. */
        const val FULL_GROUP: Int = 31

        /** The two `2500u` examples' written amount, which is also the `u`-multiplier anchor. */
        const val TWENTY_FIVE_HUNDRED_MICRO: String = "2500u"

        /** The pico example's written amount — the one fraction-of-a-msat row in use. */
        const val PICO_AMOUNT: String = "9678785340p"

        /** How many all-lowercase valid examples name no amount. Pinned so the control cannot shrink. */
        const val ANY_AMOUNT_EXAMPLES: Int = 2

        /** Four examples across three expiries: two at 60 seconds, one at a week, one at the default. */
        const val BOUNDARY_CASES: Int = 4

        /** Ten times the price, which is the over-invoicing §9.2 check 4 exists to catch. */
        const val INFLATION: Long = 10L

        /** How many `2500u`/60-second examples the pinned document publishes. */
        const val ONE_MINUTE_EXAMPLES: Int = 2
    }

    private val examples: List<Bolt11Examples.Example> by lazy { Bolt11Examples.extract() }

    // -----------------------------------------------------------------------------------------
    // Choosing the vendored examples the controls are built on, by what they state.
    // -----------------------------------------------------------------------------------------

    private fun lowercaseValid(): List<Bolt11Examples.Example> = examples.filter {
        it.group == Bolt11Examples.Group.VALID && it.invoice == it.invoice.lowercase()
    }

    /**
     * The one all-lowercase valid example stating [written] as its amount and [expiry] as its
     * expiry, failing loudly when the document stops having exactly one.
     *
     * Chosen by what the document *says*, never by position: an example picked by index would go on
     * compiling after an upstream edit moved it and would quietly be testing a different invoice.
     */
    private fun example(written: String, expiry: Long?): Bolt11Examples.Example {
        val found = lowercaseValid().filter { it.amount?.written == written && it.expirySeconds == expiry }
        if (found.size != 1) {
            fail(
                "${Bolt11Examples.PATH} must hold exactly one all-lowercase valid example stating " +
                    "an amount of '$written' and an expiry of ${expiry ?: "none"}; it holds ${found.size}",
            )
        }
        return found.single()
    }

    /**
     * The two `2500u`/60-second examples, which differ only in their description — one of them
     * carries the document's Japanese one.
     */
    private fun oneMinuteExamples(): List<Bolt11Examples.Example> {
        val found = lowercaseValid().filter {
            it.amount?.written == TWENTY_FIVE_HUNDRED_MICRO && it.expirySeconds == ONE_MINUTE
        }
        assertEquals(
            ONE_MINUTE_EXAMPLES,
            found.size,
            "${Bolt11Examples.PATH} must hold $ONE_MINUTE_EXAMPLES all-lowercase valid examples " +
                "stating $TWENTY_FIVE_HUNDRED_MICRO and a $ONE_MINUTE-second expiry",
        )
        return found
    }

    /**
     * The first of them, for the controls that want one invoice rather than the pair.
     *
     * Chosen by what the document states and then taken in document order, which is stable because
     * [oneMinuteExamples] has just pinned how many there are: an upstream edit that added a third
     * turns that assertion red rather than silently changing which invoice these controls run on.
     */
    private fun oneMinuteExample(): Bolt11Examples.Example = oneMinuteExamples().first()

    /** Every all-lowercase valid example whose human-readable part names no amount at all. */
    private fun anyAmountExamples(): List<Bolt11Examples.Example> = lowercaseValid().filter { it.amount == null }

    /**
     * The millisatoshis an example's stated amount comes to, computed from Appendix C's table.
     *
     * The same computation `Bolt11InvoiceTest` makes for the same reason: the document states a
     * figure and a unit word, the specification states what a unit is worth, and multiplying them
     * here is what stops a number being written into this file.
     */
    private fun statedMsat(example: Bolt11Examples.Example): Msat {
        val amount = example.amount ?: fail("$example states no amount, so nothing can be computed from it")
        val row = AppendixC.multipliers.single { it.letter == amount.letter.firstOrNull() }
        val figure = amount.number.toLong()
        if (figure % row.denominator != 0L) fail("$example: '${amount.written}' is not a whole number of msat")
        return Msat.ofMsat(figure / row.denominator * row.numerator)
    }

    /** The expiry the document states for an example, or Appendix C's default where it states none. */
    private fun statedExpiry(example: Bolt11Examples.Example): Long = example.expirySeconds ?: DEFAULT_EXPIRY

    // -----------------------------------------------------------------------------------------
    // The one door: an invoice stored as an accepted `type=2`, and the receipt that settles it.
    // -----------------------------------------------------------------------------------------

    /**
     * [invoice] stored for [payee] at [acceptedAt], then settled by a receipt naming it.
     *
     * Every step goes through the codecs the library ships — `PaymentRequest.decode`,
     * `AcceptedPaymentRequest.accept` against a pinned fake clock, `PaymentReceipt.decode` — so a
     * control here is about `Settlement.verify` and not about a value assembled by hand.
     *
     * The preimage and the payment hash agree, always. Checks 4 and 5 run **before** check 3, so a
     * control about the amount must not be able to pass by accident on a preimage error; the one
     * test that wants both wrong at once says so and asserts which spoke first.
     */
    private fun settle(
        invoice: String,
        acceptedAt: Long,
        split: FeeSplit,
        payee: Payee = Payee.PROVIDER,
        preimageHex: String = PaymentFixtures.preimageHex(1).single(),
        paymentHash: PaymentHash = PaymentFixtures.paymentHashOf(Preimage.ofHex(preimageHex)),
    ): Settlement {
        val order = SettlementFixtures.orderHex(0)
        val store = PaymentRequestStore.inMemory()
        AcceptedPaymentRequest.accept(
            SettlementFixtures.request(
                SettlementFixtures.requestTags(
                    order = order,
                    payment = SettlementFixtures.requestPaymentTag(invoice),
                    payeeTag = SettlementFixtures.payeeTag(payee),
                ),
                split,
            ),
            store,
            FakeClock(acceptedAt),
        )
        val receipt = SettlementFixtures.receipt(
            SettlementFixtures.receiptTags(
                order = order,
                payment = SettlementFixtures.paymentTag(invoice, preimageHex),
                payeeTag = SettlementFixtures.payeeTag(payee),
            ),
        )
        return Settlement.verify(receipt, paymentHash, store, split)
    }

    /** The reason [settle] refused with, failing loudly if it evidenced instead. */
    private fun refusalOf(
        invoice: String,
        acceptedAt: Long,
        split: FeeSplit,
        payee: Payee = Payee.PROVIDER,
    ): SettlementRejection = assertFailsWith<SettlementException> {
        settle(invoice, acceptedAt, split, payee)
    }.reason

    /** A split whose **provider** is owed exactly [amount]. Its fee is zero, so no fee payee exists. */
    private fun priceOf(amount: Msat): FeeSplit =
        SettlementFixtures.split(priceMsat = amount.millisatoshis, basisPoints = null)

    /**
     * A split whose **fee recipient** is owed exactly [amount], and whose provider is owed twice it.
     *
     * At 5 000 basis points the fee is half the price, so a price of `2 × amount` puts the fee on
     * [amount] and the provider on something that is definitely not [amount] — which is what lets
     * one invoice be right for one payee and wrong for the other under a single split.
     */
    private fun feeOf(amount: Msat): FeeSplit = SettlementFixtures.split(
        priceMsat = amount.millisatoshis * 2L,
        basisPoints = HALF_IN_BASIS_POINTS,
    )

    // -----------------------------------------------------------------------------------------
    // Check 4 — the amount.
    // -----------------------------------------------------------------------------------------

    /**
     * The headline: an invoice for exactly what the payee is owed passes, and one msat either way
     * does not.
     *
     * Both directions, because only one of them is the attack anyone pictures. An invoice for more
     * is the overcharge §9.2 names; an invoice for **less** settles the order for a figure the terms
     * never named, and a payee who accepted it holds evidence of a payment that discharges nothing.
     * §9.2 says the amount MUST *equal* the expected one, and a `>=` comparison — the mutation this
     * pair exists to catch — passes the first control and the over-invoicing one alike.
     */
    @JsName("an_invoice_for_the_expected_amount_passes_and_one_msat_either_way_is_a_mismatch")
    @Test
    fun `an invoice for the expected amount passes, and one msat either way is a mismatch`() {
        val example = oneMinuteExample()
        val expected = statedMsat(example)
        val acceptedAt = example.timestamp!! + statedExpiry(example)

        assertIs<Settlement.Evidenced>(
            settle(example.invoice, acceptedAt, priceOf(expected)),
            "$example: the invoice asks for exactly what the provider is owed",
        )

        for (delta in listOf(1L, -1L)) {
            assertEquals(
                SettlementRejection.INVOICE_AMOUNT_MISMATCH,
                refusalOf(
                    example.invoice,
                    acceptedAt,
                    priceOf(Msat.ofMsat(expected.millisatoshis + delta)),
                ),
                "$example: an expectation $delta msat from the invoice's must be a mismatch, and a " +
                    "comparison tolerating one direction would let a provider round the difference " +
                    "its own way on every order",
            )
        }
    }

    /**
     * The pico example, whose amount is not a whole number of any coarser unit.
     *
     * The one vendored invoice whose figure exercises Appendix C's fractional row — 967 878 534 msat
     * is 9 678 785 340 pico-bitcoin and not a whole number of nano — so a parser that read the `p`
     * multiplier as an integer, or that divided in the wrong order, reaches a different figure here
     * and at no other example.
     */
    @JsName("the_pico_example_passes_against_its_computed_amount")
    @Test
    fun `the pico example passes against its computed amount`() {
        val example = example(PICO_AMOUNT, ONE_WEEK)
        val expected = statedMsat(example)

        assertIs<Settlement.Evidenced>(
            settle(example.invoice, example.timestamp!! + ONE_WEEK, priceOf(expected)),
            "$example: ${expected.millisatoshis} msat, computed from the document and Appendix C",
        )
        assertEquals(
            SettlementRejection.INVOICE_AMOUNT_MISMATCH,
            refusalOf(
                example.invoice,
                example.timestamp!! + ONE_WEEK,
                priceOf(Msat.ofMsat(expected.millisatoshis + 1L)),
            ),
            "$example: and the same invoice against one msat more is a mismatch, so the pass above " +
                "is the figure and not an unconditional yes",
        )
    }

    /**
     * BOLT-11's "any amount" invoice, which §9.2 check 4 rejects on sight — as its own reason.
     *
     * Not a mismatch: an any-amount invoice never said what it wanted, so there is nothing to
     * mismatch against, and a payer handed one chooses the figure itself. Reporting it as a
     * mismatch would send a reader looking for a wrong number in an invoice that holds no number,
     * which is why the two are separate constants. The mutation the queue names — treating a
     * missing amount as a match — turns both of these red.
     */
    @JsName("both_any_amount_examples_are_refused_as_invoice_amount_missing")
    @Test
    fun `both any-amount examples are refused as INVOICE_AMOUNT_MISSING`() {
        val anyAmount = anyAmountExamples()
        assertEquals(
            ANY_AMOUNT_EXAMPLES,
            anyAmount.size,
            "the vendored document must still hold $ANY_AMOUNT_EXAMPLES all-lowercase valid " +
                "examples with no amount, or this control is about fewer invoices than it says",
        )

        for (example in anyAmount) {
            assertEquals(
                SettlementRejection.INVOICE_AMOUNT_MISSING,
                refusalOf(example.invoice, example.timestamp!! + statedExpiry(example), priceOf(Msat.ofMsat(1L))),
                "$example: an invoice naming no amount is rejected as naming none",
            )
        }
    }

    /**
     * One invoice, one split, two payees, two answers — which is check 4's whole content.
     *
     * The fee recipient is owed exactly what this invoice asks for and the provider is owed twice
     * it, so the same `(invoice, split)` pair must pass for one role and be refused for the other.
     * That is what separates a check reading §8.3's arithmetic from one comparing against whatever
     * single number was nearest to hand: comparing the provider against `total_msat`, or the fee
     * against `price_msat`, are both mutations the queue names and both turn exactly one half of
     * this test red.
     */
    @JsName("a_fee_payee_owed_the_invoices_amount_passes_where_the_provider_under_the_same_split_does_not")
    @Test
    fun `a fee payee owed the invoice's amount passes where the provider under the same split does not`() {
        val example = oneMinuteExample()
        val expected = statedMsat(example)
        val acceptedAt = example.timestamp!! + ONE_MINUTE
        val split = feeOf(expected)

        assertEquals(expected, split.fee, "the split must owe the fee recipient exactly the invoice's amount")
        assertTrue(split.price != expected, "and the provider something else, or there are not two answers here")

        assertIs<Settlement.Evidenced>(
            settle(example.invoice, acceptedAt, split, Payee.FEE),
            "$example: §8.3's `fee_msat` is what a fee receipt's invoice is held to",
        )
        assertEquals(
            SettlementRejection.INVOICE_AMOUNT_MISMATCH,
            refusalOf(example.invoice, acceptedAt, split, Payee.PROVIDER),
            "$example: and `price_msat` is what a provider receipt's invoice is held to — the same " +
                "invoice under the same split, judged against the other payee's amount",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Check 5 — the expiry.
    // -----------------------------------------------------------------------------------------

    /**
     * The boundary, on each of the three expiries the vendored examples between them state.
     *
     * `timestamp + expiry` is the last second at which the invoice is live, so acceptance **at** it
     * passes and one second later does not. Equality passing is the half an implementation gets
     * wrong by writing `>` where `>=` belongs, and it is the half that matters: an invoice paid in
     * its final second is paid.
     *
     * Three scales rather than one, and one of them is the **default**: an example carrying no `x`
     * is held to Appendix C's 3600, which is the branch an implementation forgets and the one check
     * 5 then measures against nothing at all.
     */
    @JsName("acceptance_at_timestamp_plus_expiry_passes_and_one_second_later_is_expired")
    @Test
    fun `acceptance at timestamp plus expiry passes, and one second later is expired`() {
        val cases = oneMinuteExamples() + listOf(example(PICO_AMOUNT, ONE_WEEK), noExpiryExample())
        assertEquals(
            BOUNDARY_CASES,
            cases.size,
            "the boundary must be taken at $BOUNDARY_CASES examples, or it is being taken at fewer " +
                "expiries than this test claims",
        )
        assertEquals(
            setOf(ONE_MINUTE, ONE_WEEK, DEFAULT_EXPIRY),
            cases.mapTo(HashSet()) { statedExpiry(it) },
            "and at three genuinely different expiries, one of them Appendix C's default",
        )

        for (example in cases) {
            val split = priceOf(statedMsat(example))
            val deadline = example.timestamp!! + statedExpiry(example)

            assertIs<Settlement.Evidenced>(
                settle(example.invoice, deadline, split),
                "$example: an invoice accepted in its final second was live, and `>` here would " +
                    "refuse it",
            )
            assertEquals(
                SettlementRejection.INVOICE_EXPIRED,
                refusalOf(example.invoice, deadline + 1L, split),
                "$example: and one second past the deadline it was not",
            )
        }
    }

    /**
     * §9.2's own sentence, made a control: check 5 is **not** re-evaluated when the receipt arrives.
     *
     * "An invoice that was live when the buyer paid it does not become unpaid because it has since
     * expired." The operand is the reading taken at acceptance, so an invoice accepted one second
     * into its window still evidences years afterwards — and the strongest statement of that is
     * structural rather than temporal: [Settlement.verify] takes no clock at all, and there is
     * nothing on its parameter list a re-evaluating implementation could have read one from.
     *
     * [YEARS_LATER] is therefore what the *reader* needs rather than what the call needs: it is the
     * distance past this invoice's own deadline at which a check that did re-read the clock would
     * refuse, and the assertion is that this one evidences.
     */
    @JsName("an_invoice_live_at_acceptance_still_evidences_years_after_it_expired")
    @Test
    fun `an invoice live at acceptance still evidences years after it expired`() {
        val example = oneMinuteExample()
        val timestamp = example.timestamp!!
        val acceptedAt = timestamp + 1L
        val longSinceExpired = timestamp + ONE_MINUTE + YEARS_LATER

        assertTrue(
            longSinceExpired > timestamp + ONE_MINUTE,
            "the control must stand past this invoice's own deadline, or it says nothing",
        )
        assertIs<Settlement.Evidenced>(
            settle(example.invoice, acceptedAt, priceOf(statedMsat(example))),
            "$example: accepted one second in, and judged on that reading however long ago it was",
        )
    }

    /**
     * An `x` that saturates: `2^63`, and `2^64 − 1`, accepted at `Long.MAX_VALUE`.
     *
     * T22 reads either as [Long.MAX_VALUE], because an expiry that large is one that never runs out
     * and a negative one would invert check 5's comparison and expire every such invoice instantly.
     * This is the control that says so from check 5's side.
     *
     * ### It is also the control for the arithmetic, and the timestamp is why
     *
     * The comparison is written `expiry >= acceptedAt − timestamp` rather than
     * `timestamp + expiry >= acceptedAt`, and this is the fixture that tells the two apart — but
     * only if the timestamp is **non-zero**. At `timestamp = 0` the sum `0 + Long.MAX_VALUE` does
     * not wrap and both forms agree, so the control would pass over the mutant it exists to catch.
     * With a real timestamp the sum is `Long.MIN_VALUE + (timestamp − 1)`, which is negative and
     * below any acceptance reading, so the addition form refuses an invoice that never expires.
     *
     * `Long.MAX_VALUE` as the acceptance reading is the hardest case available — no clock can read
     * higher — so an invoice that passes at it passes everywhere.
     */
    @JsName("an_x_that_saturates_at_long_max_value_is_not_expired_at_any_acceptance_reading")
    @Test
    fun `an x that saturates at Long MAX_VALUE is not expired at any acceptance reading`() {
        val amount = Msat.ofMsat(SettlementFixtures.PRICE_MSAT)
        val split = priceOf(amount)
        val timestamp = SettlementFixtures.ACCEPTED_AT - SettlementFixtures.INVOICE_AGE_SECONDS
        val leading = mapOf(
            "2^63" to LEADING_GROUP_AT_TWO_TO_THE_63,
            "2^64 − 1" to LEADING_GROUP_AT_THE_LARGEST_LEGAL_VALUE,
        )

        assertTrue(
            timestamp > 0L && timestamp + Long.MAX_VALUE < 0L,
            "the fixture's timestamp must be one that makes `timestamp + Long.MAX_VALUE` wrap " +
                "negative, or this control cannot see the difference between the subtraction the " +
                "library performs and the addition §9.2's wording invites",
        )

        for ((name, group) in leading) {
            val invoice = SettlementFixtures.invoiceWithExpiryGroups(
                PaymentFixtures.preimageHex(1).single(),
                amount,
                timestamp = timestamp,
                groups = listOf(group) + List(SIXTY_FIVE_BIT_GROUPS - 1) { FULL_GROUP },
            )

            assertIs<Settlement.Evidenced>(
                settle(invoice, Long.MAX_VALUE, split),
                "an `x` of $name saturates to Long.MAX_VALUE, which reads as an expiry that never " +
                    "runs out — and neither the parse nor check 5's comparison may throw or wrap",
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // The order of the checks, and the record they leave.
    // -----------------------------------------------------------------------------------------

    /**
     * Checks 4 and 5 speak **before** T3's check 3, and the precedence is the point.
     *
     * A receipt for an invoice with the wrong amount very often carries a perfectly good preimage —
     * the buyer really did pay the invoice the provider really did issue — so a library that ran
     * check 3 first would report `PREIMAGE_MISMATCH` on the one case where the preimage is fine and
     * the *figure* is not. Here both are wrong at once and the answer must be the amount.
     */
    @JsName("a_wrong_amount_is_reported_before_a_wrong_preimage")
    @Test
    fun `a wrong amount is reported before a wrong preimage`() {
        val example = oneMinuteExample()
        val acceptedAt = example.timestamp!! + ONE_MINUTE
        val preimages = PaymentFixtures.preimageHex(2)
        val wrongHash = PaymentFixtures.paymentHashOf(Preimage.ofHex(preimages[1]))

        // The control for the control: with the amount right, this receipt fails on the preimage.
        val onPreimage = assertFailsWith<PaymentException> {
            settle(
                example.invoice,
                acceptedAt,
                priceOf(statedMsat(example)),
                preimageHex = preimages[0],
                paymentHash = wrongHash,
            )
        }
        assertEquals(
            PaymentRejection.PREIMAGE_MISMATCH,
            onPreimage.reason,
            "the preimage really does not hash to this payment hash, or the precedence assertion " +
                "below would hold of a receipt with nothing wrong with its preimage at all",
        )

        val refused = assertFailsWith<SettlementException> {
            settle(
                example.invoice,
                acceptedAt,
                priceOf(Msat.ofMsat(statedMsat(example).millisatoshis + 1L)),
                preimageHex = preimages[0],
                paymentHash = wrongHash,
            )
        }
        assertEquals(
            SettlementRejection.INVOICE_AMOUNT_MISMATCH,
            refused.reason,
            "§9.2's order is 1, 4, 5, then 2 and 3: a receipt for a bad invoice is told what is " +
                "wrong with the invoice before anything is said about its preimage",
        )
    }

    /** The same, for check 5: an expired invoice is reported expired and not as a preimage failure. */
    @JsName("an_expired_invoice_is_reported_before_a_wrong_preimage")
    @Test
    fun `an expired invoice is reported before a wrong preimage`() {
        val example = oneMinuteExample()
        val preimages = PaymentFixtures.preimageHex(2)

        val refused = assertFailsWith<SettlementException> {
            settle(
                example.invoice,
                example.timestamp!! + ONE_MINUTE + 1L,
                priceOf(statedMsat(example)),
                preimageHex = preimages[0],
                paymentHash = PaymentFixtures.paymentHashOf(Preimage.ofHex(preimages[1])),
            )
        }
        assertEquals(SettlementRejection.INVOICE_EXPIRED, refused.reason)
    }

    /**
     * The record: a settlement that passed checks 4 and 5 says it did, and §17 requires it say so.
     *
     * Containment rather than equality, because the exact set is `SettlementCheckOneTest`'s
     * business and asserting it twice would make one of the two a copy that moves when the other
     * does. What this pins is the half that is this task's: the two constants that used to sit in
     * every result's `checksNotPerformedHere` are now on the performed side, on a real invoice.
     */
    @JsName("a_settlement_that_performed_checks_4_and_5_records_both")
    @Test
    fun `a settlement that performed checks 4 and 5 records both`() {
        val example = oneMinuteExample()
        val evidenced = assertIs<Settlement.Evidenced>(
            settle(example.invoice, example.timestamp!! + ONE_MINUTE, priceOf(statedMsat(example))),
        )

        val required = setOf(
            PaymentCheck.INVOICE_IDENTITY,
            PaymentCheck.PREIMAGE_SHAPE,
            PaymentCheck.PREIMAGE_HASH_COMPARISON,
            PaymentCheck.INVOICE_AMOUNT,
            PaymentCheck.INVOICE_EXPIRY,
        )
        assertTrue(
            evidenced.checksPerformed.containsAll(required),
            "the store path performs all five: ${evidenced.checksPerformed}",
        )
        assertEquals(
            emptySet(),
            required intersect evidenced.checksNotPerformedHere,
            "and none of them may also be on the not-performed side of the same statement",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Derived invoices: the settlement-level controls, and what a stored string that cannot be
    // parsed does.
    // -----------------------------------------------------------------------------------------

    /**
     * A derived invoice for the wrong amount is a mismatch, and the request really was re-stored.
     *
     * The composer recomputes the bech32 checksum over the changed human-readable part, so this is
     * a well-formed invoice that asks for the wrong figure rather than a corrupt string — which is
     * what makes it check 4's control and not the parser's. The pair either side of it is what says
     * so: the same derivation at the expected amount evidences.
     */
    @JsName("a_derived_invoice_for_the_wrong_amount_is_a_mismatch_and_the_right_one_evidences")
    @Test
    fun `a derived invoice for the wrong amount is a mismatch, and the right one evidences`() {
        val expected = Msat.ofMsat(SettlementFixtures.PRICE_MSAT)
        val split = priceOf(expected)
        val preimageHex = PaymentFixtures.preimageHex(1).single()

        assertIs<Settlement.Evidenced>(
            settle(
                SettlementFixtures.invoice(preimageHex, expected),
                SettlementFixtures.ACCEPTED_AT,
                split,
            ),
        )
        assertEquals(
            SettlementRejection.INVOICE_AMOUNT_MISMATCH,
            refusalOf(
                SettlementFixtures.invoice(
                    preimageHex,
                    Msat.ofMsat(expected.millisatoshis * INFLATION),
                ),
                SettlementFixtures.ACCEPTED_AT,
                split,
            ),
            "a provider who invoices $INFLATION times `price_msat` is caught by check 4 and by " +
                "nothing else in this library",
        )
    }

    /**
     * A stored invoice whose checksum does not check out surfaces T22's reason, not a settlement one.
     *
     * The recogniser stored it — it verifies no checksum — and check 1 passed, because the receipt
     * names the same string byte for byte. What fails is the parse checks 4 and 5 need, and the
     * caller is told which rule refused rather than being told its amount does not match an amount
     * nobody could read.
     *
     * Built by substituting one character **of the checksum**, so the only thing wrong with the
     * string is the checksum: a substitution in the body could change a tagged field's declared
     * length and be refused as `FIELD_TRUNCATED`, which would be the right verdict for the wrong
     * reason.
     */
    @JsName("a_stored_invoice_with_a_bad_checksum_surfaces_the_parsers_own_reason")
    @Test
    fun `a stored invoice with a bad checksum surfaces the parser's own reason`() {
        val expected = Msat.ofMsat(SettlementFixtures.PRICE_MSAT)
        val sound = SettlementFixtures.invoice(PaymentFixtures.preimageHex(1).single(), expected)
        val last = sound[sound.length - 1]
        val corrupt = sound.dropLast(1) + Bolt11Reference.BECH32_ALPHABET.first { it != last }

        assertEquals(sound.length, corrupt.length, "only one character may differ")
        assertEquals(
            SettlementRejection.CHECKSUM_INVALID,
            refusalOf(corrupt, SettlementFixtures.ACCEPTED_AT, priceOf(expected)),
        )
    }

    // -----------------------------------------------------------------------------------------
    // The non-vacuity floor: both payees, both outcomes, many times.
    // -----------------------------------------------------------------------------------------

    /**
     * Checks 4 and 5 see both payees and both outcomes across the settlement corpus.
     *
     * A control apiece proves a rule fires; it does not prove the rule is *reached* on the ordinary
     * path, for both roles, rather than short-circuited by something earlier. So every fixture in a
     * slice of the seeded corpus is settled twice — once against the split its invoice was built
     * for, which must evidence, and once against a split one msat away, which must be a mismatch —
     * and the counts are asserted per payee at the end. A check that had quietly stopped running
     * would take the refusing half of this to zero.
     */
    @JsName("checks_4_and_5_see_both_payees_and_both_outcomes_across_the_corpus")
    @Test
    fun `checks 4 and 5 see both payees and both outcomes across the corpus`() {
        val split = SettlementFixtures.split()
        val evidenced = HashMap<Payee, Int>()
        val refused = HashMap<Payee, Int>()

        for (fixture in SettlementFixtures.pairs(CORPUS)) {
            val store = PaymentRequestStore.inMemory()
            AcceptedPaymentRequest.accept(
                SettlementFixtures.request(fixture.requestTags, split),
                store,
                FakeClock(SettlementFixtures.ACCEPTED_AT),
            )
            val receipt = SettlementFixtures.receipt(fixture.receiptTags)

            assertIs<Settlement.Evidenced>(
                Settlement.verify(receipt, fixture.paymentHash, store, split),
                "fixture ${fixture.index}: its invoice was built for this split",
            )
            evidenced[fixture.payee] = (evidenced[fixture.payee] ?: 0) + 1

            // One msat away on **this fixture's own payee**, leaving the other payee's expectation
            // where it was — so what refuses is check 4 reading the right half of the split.
            val shifted = shiftedFor(fixture.payee, split)
            assertEquals(
                SettlementRejection.INVOICE_AMOUNT_MISMATCH,
                assertFailsWith<SettlementException>("fixture ${fixture.index}") {
                    Settlement.verify(receipt, fixture.paymentHash, store, shifted)
                }.reason,
            )
            refused[fixture.payee] = (refused[fixture.payee] ?: 0) + 1
        }

        for (payee in Payee.entries) {
            assertTrue((evidenced[payee] ?: 0) > 0, "check 4 must have passed for a $payee receipt")
            assertTrue((refused[payee] ?: 0) > 0, "and must have refused one")
        }
        assertEquals(
            CORPUS,
            evidenced.values.sum(),
            "every fixture in the slice must have been settled, and none skipped",
        )
        assertEquals(CORPUS, refused.values.sum(), "and every one refused against the shifted split")
    }

    /**
     * [split] with **[payee]'s** expected amount moved by one msat and the other left alone.
     *
     * The provider's moves by moving the price; the fee recipient's moves by moving the basis
     * points, which is the only handle §8.3 gives on `fee_msat`. Both are computed through
     * `FeeTerm` rather than assembled, so the shifted split is a split the library itself would
     * produce for some terms — the refusal under test is check 4's and not a malformed operand's.
     */
    private fun shiftedFor(payee: Payee, split: FeeSplit): FeeSplit = when (payee) {
        Payee.PROVIDER -> SettlementFixtures.split(
            priceMsat = split.price.millisatoshis + 1L,
            basisPoints = SettlementFixtures.BASIS_POINTS,
        )
        Payee.FEE -> SettlementFixtures.split(
            priceMsat = SettlementFixtures.PRICE_MSAT,
            basisPoints = SettlementFixtures.BASIS_POINTS + 1,
        )
    }

    /** The one all-lowercase valid example stating an amount and carrying no `x` at all. */
    private fun noExpiryExample(): Bolt11Examples.Example {
        val found = lowercaseValid().filter { it.amount != null && it.expirySeconds == null }
        if (found.isEmpty()) fail("${Bolt11Examples.PATH} holds no valid example with an amount and no expiry")
        return found.first()
    }
}
