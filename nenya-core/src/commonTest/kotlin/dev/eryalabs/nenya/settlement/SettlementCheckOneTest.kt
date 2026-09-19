package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.conformance.ConformanceStatus
import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.payment.PaymentException
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.payment.PaymentRejection
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.payment.VerifiedPayment
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §9.2 check 1 against the store, §9.4's other rails, and the §17 honesty rule the composition must
 * not break.
 *
 * Check 1 has **three** outcomes and this file keeps them apart: matched, not byte-identical, and
 * *no such request was stored*. §9.2 names the third separately for a reason a caller acts on —
 * told "mismatch" it looks for a wrong invoice, told this it looks for a `type=2` its store never
 * saw. The uppercase case is a fourth answer inside the second, and it exists because §4.3 permits
 * no case folding here while permitting it on the payment hash one line away.
 */
class SettlementCheckOneTest {

    private companion object {

        /** §9.2 check 6's three obligations, which apply to a fee receipt and to nothing else. */
        val CHECK_SIX: Set<PaymentCheck> = setOf(
            PaymentCheck.FEE_TERM_MATCH,
            PaymentCheck.FEE_SEALING_KEY,
            PaymentCheck.FEE_STATE_PRECONDITION,
        )

        /** The bech32 character U+017F folds onto under a Unicode case-insensitive comparison. */
        const val LONG_S_FOLD: Char = 's'

        /** LATIN SMALL LETTER LONG S, which is outside the bech32 alphabet and is not uppercase. */
        const val LONG_S: Char = 'ſ'

        /** How many fixtures to search for one carrying an `s`. Every derived invoice runs to
         * hundreds of bech32 characters and the alphabet has thirty-two of them, so missing `s`
         * in all sixteen is not a case anyone will meet — and the search fails loudly if it does. */
        const val HOMOGLYPH_SEARCH: Int = 16

        /**
         * §8.3's split every fixture invoice in this file was built for — check 4's expected
         * amounts, selected by the receipt's own payee.
         *
         * This file is about §9.2 check 1, so every control here wants check 4 to pass and get out
         * of the way. `SettlementCheckFourAndFiveTest` is where a split that disagrees with the
         * invoice is the point.
         */
        val SPLIT: FeeSplit = SettlementFixtures.split()
    }

    /** One stored request and the receipt that settles it, both from the seeded corpus. */
    private class Settled(
        val fixture: SettlementFixtures.Fixture,
        val store: PaymentRequestStore,
    )

    private fun settled(index: Int = 0): Settled {
        val fixture = SettlementFixtures.pairs(index + 1)[index]
        return Settled(fixture, storing(fixture))
    }

    /**
     * [fixture]'s own `type=2` accepted into a fresh store, through revision `1.5`'s door.
     *
     * Sealed by the key §8.6 requires for that fixture's payee and judged against that order's own
     * §7.6 acceptance — which is the shape every control in this file now needs, and which is why
     * it is a helper rather than four copies.
     */
    private fun storing(fixture: SettlementFixtures.Fixture): PaymentRequestStore {
        val store = PaymentRequestStore.inMemory()
        SettlementFixtures.accept(
            SettlementFixtures.requestFrom(fixture.payee, fixture.requestTags, index = fixture.index),
            store,
            FakeClock(SettlementFixtures.ACCEPTED_AT),
            SettlementFixtures.accepted(fixture.index),
        )
        return store
    }

    // ---------------------------------------------------------------------------------------
    // The accepting direction, and what it may claim.
    // ---------------------------------------------------------------------------------------

    @JsName("a_receipt_matching_its_stored_request_evidences_the_payment")
    @Test
    fun `a receipt matching its stored request evidences the payment`() {
        val settled = settled()
        val receipt = SettlementFixtures.receipt(settled.fixture.receiptTags)

        val settlement = Settlement.verify(receipt, settled.store, SPLIT)

        val evidenced = assertIs<Settlement.Evidenced>(settlement)
        assertEquals(settled.fixture.payee, evidenced.payee)
        assertEquals(PaymentMedium.LIGHTNING, evidenced.medium)
        // The hash on the result was read out of the **stored** invoice's `p` field: nothing in
        // this test handed one in. On its own this equality discriminates nothing — `verify` only
        // returns `Evidenced` when the operand hashes from the proof, and the proof is this
        // fixture's preimage — so what makes it a statement about *provenance* is the control it
        // is paired with: `a preimage for another invoice, against the stored one, yields T3's own
        // refusal`, which is probe G1b inverted and keeps the operand and the proof apart.
        assertEquals(settled.fixture.paymentHash, evidenced.payment.paymentHash)
    }

    @JsName("the_result_records_the_union_of_checks_1_3_4_and_5_and_t3s_two")
    @Test
    fun `the result records the union of checks 1, 3, 4 and 5 and T3's two`() {
        val settled = settled()
        val receipt = SettlementFixtures.receipt(settled.fixture.receiptTags)

        val evidenced = assertIs<Settlement.Evidenced>(
            Settlement.verify(receipt, settled.store, SPLIT),
        )

        assertEquals(
            VerifiedPayment.CHECKS_PERFORMED_HERE + setOf(
                PaymentCheck.INVOICE_IDENTITY,
                PaymentCheck.PAYMENT_HASH_PROVENANCE,
                PaymentCheck.INVOICE_AMOUNT,
                PaymentCheck.INVOICE_EXPIRY,
            ),
            evidenced.checksPerformed,
            "checks 1, 4 and 5 were performed on this path, check 3's operand came out of the " +
                "stored invoice's `p` field on it, and T3's two were performed inside it; the set " +
                "is composed from T3's rather than listed here",
        )
        assertEquals(
            Settlement.CHECKS_PERFORMED_ON_THE_STORE_PATH,
            evidenced.checksPerformed,
            "and the published constant is the same set, so a caller can branch on it",
        )
        assertFalse(
            PaymentCheck.INVOICE_IDENTITY in evidenced.checksNotPerformedHere,
            "a check cannot be on both sides of the same statement",
        )
        // Exact, not containment, and the exact answer is now **empty** — a provider receipt
        // through this door performed every §9.2 check that applies to it. An emptiness assertion
        // is weaker than the old one-element assertion on its own, which is why the containment
        // assertion above and the exact performed-set assertion at the top of this test stand
        // beside it: a path that recorded nothing at all would pass this line and fail those two.
        assertEquals(
            emptySet<PaymentCheck>(),
            evidenced.checksNotPerformedHere,
            "nothing is left: check 3's provenance closed when `verify` stopped taking a payment " +
                "hash, and it was the last check on this path that a caller could still have " +
                "supplied the operand for",
        )
    }

    @JsName("a_fee_receipt_still_names_check_6s_three_obligations_and_a_provider_receipt_does_not")
    @Test
    fun `a fee receipt still names check 6's three obligations, and a provider receipt does not`() {
        val provider = settled(index = 0)
        val fee = settled(index = 1)
        assertEquals(Payee.PROVIDER, provider.fixture.payee)
        assertEquals(Payee.FEE, fee.fixture.payee, "the corpus must cover both roles")

        val providerResult = assertIs<Settlement.Evidenced>(
            Settlement.verify(
                SettlementFixtures.receipt(provider.fixture.receiptTags),
                provider.store,
                SPLIT,
            ),
        )
        val feeResult = assertIs<Settlement.Evidenced>(
            Settlement.verify(
                SettlementFixtures.receipt(fee.fixture.receiptTags),
                fee.store,
                SPLIT,
            ),
        )

        assertTrue(
            CHECK_SIX.all { it in feeResult.checksNotPerformedHere },
            "§8.4's fee term, §8.7's sealing key and §8.5's state precondition are all unperformed " +
                "here: ${feeResult.checksNotPerformedHere}",
        )
        assertTrue(
            CHECK_SIX.none { it in providerResult.checksNotPerformedHere },
            "check 6 does not apply to a provider receipt at all, and recording an inapplicable " +
                "obligation as not-performed is a different false statement",
        )
        // And exactly, on both sides: the fee result is the provider result plus check 6 and
        // nothing else. A containment assertion alone cannot see a check dropped from the record,
        // which is what this file's own §17 rule is about.
        assertEquals(
            emptySet<PaymentCheck>(),
            providerResult.checksNotPerformedHere,
            "this door performs every check that applies to a provider receipt",
        )
        assertEquals(
            providerResult.checksNotPerformedHere + CHECK_SIX,
            feeResult.checksNotPerformedHere,
            "and a fee receipt through the **general** entry point is the provider's record plus " +
                "check 6's three — composed from the provider's rather than listed again. That is " +
                "the whole difference between the two doors, and it is why `verifyFeeReceipt` " +
                "exists",
        )
        assertEquals(
            CHECK_SIX,
            feeResult.checksNotPerformedHere,
            "spelled out as well as composed: with the provider's record empty the composition " +
                "above would hold of any set at all if check 6 had quietly been subtracted here",
        )
    }

    /**
     * The §17 honesty rule this task must not break, asserted from both ends.
     *
     * The mutation the queue names is to widen `VerifiedPayment.CHECKS_PERFORMED_HERE` to include
     * `INVOICE_IDENTITY` or `PAYMENT_HASH_PROVENANCE`. A bare `VerifiedPayment.verify` performs
     * neither — it holds no store and takes check 3's payment hash as a parameter — so either
     * would be a global claim about work only this package's store path does, and it would
     * silently empty `Capabilities.PAYMENT_CHECKS_NOT_PERFORMED`, which the conformance surface
     * derives from that constant by subtraction.
     *
     * §17 item 6 moved to `PERFORMED_HERE` because every one of §9.2's checks is now performed on
     * the path `OrderMachine` consumes. That is a claim about **this package's** two entry points
     * and not about T3's, and the two halves of this test are what keep those apart.
     */
    @JsName("t3s_global_claim_is_unchanged_and_s17_item_6_is_performed_here")
    @Test
    fun `T3's global claim is unchanged, and §17 item 6 is performed here`() {
        for (check in setOf(PaymentCheck.INVOICE_IDENTITY, PaymentCheck.PAYMENT_HASH_PROVENANCE)) {
            assertFalse(
                check in VerifiedPayment.CHECKS_PERFORMED_HERE,
                "a bare VerifiedPayment.verify performs neither check 1 nor check 3's provenance " +
                    "nor anything else this package adds; widening its constant would be the §17 " +
                    "over-claim",
            )
            assertTrue(
                check in Capabilities.PAYMENT_CHECKS_NOT_PERFORMED,
                "$check is a claim about a bare VerifiedPayment.verify, and the conformance " +
                    "surface derives that set from T3's constant by subtraction",
            )
            assertTrue(
                check in Settlement.CHECKS_PERFORMED_ON_THE_STORE_PATH,
                "and the store path performs it, which is the distinction the two records exist " +
                    "to draw",
            )
        }

        val item = Capabilities.item(6) ?: fail("§17 item 6 is not published")
        assertEquals(
            ConformanceStatus.PERFORMED_HERE,
            item.status,
            "§9.2's six checks are all performed on the path OrderMachine consumes: the store " +
                "path's four, T3's two inside them, and check 6's three through verifyFeeReceipt",
        )
        assertEquals(
            emptySet<Enum<*>>(),
            item.notPerformed,
            "a PERFORMED_HERE item has no constant to point at, and there is none left that " +
                "bears on §9.2. The remaining narrowing — durable persistence of the store is the " +
                "embedding client's — has no enum constant, which is the structural limit " +
                "ConformanceStatus.PARTIAL records and why it is written into the note instead",
        )
        assertTrue(
            item.note.contains("PaymentRequestStore.inMemory"),
            "and the note must say plainly which implementation survives no restart, or " +
                "PERFORMED_HERE is a claim about persistence this library has not earned",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Check 1's three outcomes.
    // ---------------------------------------------------------------------------------------

    @JsName("an_invoice_differing_by_one_character_is_refused_as_not_byte_identical")
    @Test
    fun `an invoice differing by one character is refused as not byte-identical`() {
        val settled = settled()
        val altered = settled.fixture.invoice.dropLast(1) + otherBech32(settled.fixture.invoice.last())
        val receipt = SettlementFixtures.receipt(
            SettlementFixtures.replacing(
                settled.fixture.receiptTags,
                SettlementFixtures.paymentTag(altered, settled.fixture.preimageHex),
            ),
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verify(receipt, settled.store, SPLIT)
        }

        assertEquals(SettlementRejection.INVOICE_NOT_IDENTICAL, refused.reason)
    }

    @JsName("the_same_invoice_in_uppercase_is_refused_as_uppercase_and_never_folded")
    @Test
    fun `the same invoice in uppercase is refused as uppercase, and never folded`() {
        val settled = settled()
        val receipt = SettlementFixtures.receipt(
            SettlementFixtures.replacing(
                settled.fixture.receiptTags,
                SettlementFixtures.paymentTag(
                    settled.fixture.invoice.uppercase(),
                    settled.fixture.preimageHex,
                ),
            ),
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verify(receipt, settled.store, SPLIT)
        }

        assertEquals(
            SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED,
            refused.reason,
            "§9.2 check 1 names case folding among the things it forbids and §4.3 lists the BOLT-11 " +
                "string as one of exactly two values where an uppercase form is rejected outright. " +
                "Comparing the two case-insensitively is the mutation this control exists for",
        )
    }

    @JsName("a_reference_differing_by_a_unicode_homoglyph_is_a_mismatch_and_not_uppercase")
    @Test
    fun `a reference differing by a unicode homoglyph is a mismatch, and not uppercase`() {
        // The diagnosis above asks whether the reference carries an ASCII uppercase character,
        // which is what Appendix C and §4.3 forbid — not whether the two strings are equal
        // ignoring case. `String.equals(ignoreCase = true)` folds Unicode: U+017F LATIN SMALL
        // LETTER LONG S folds onto `s`, which is in the bech32 alphabet, so the looser form would
        // tell a caller its encoder emitted uppercase when it emitted a homoglyph. The verdict
        // would be right and the reason would send a reader to the wrong place.
        val fixture = SettlementFixtures.pairs(HOMOGLYPH_SEARCH).firstOrNull { LONG_S_FOLD in it.invoice }
            ?: fail("no generated invoice in the first $HOMOGLYPH_SEARCH carries a `$LONG_S_FOLD`")
        val store = storing(fixture)
        val homoglyph = fixture.invoice.replaceFirst(LONG_S_FOLD, LONG_S)
        val receipt = SettlementFixtures.receipt(
            SettlementFixtures.replacing(
                fixture.receiptTags,
                SettlementFixtures.paymentTag(homoglyph, fixture.preimageHex),
            ),
        )

        val refused = assertFailsWith<SettlementException> {
            Settlement.verify(receipt, store, SPLIT)
        }

        assertTrue(
            homoglyph.equals(fixture.invoice, ignoreCase = true),
            "the fixture must be one a case-insensitive comparison would call equal, or this test " +
                "is about nothing",
        )
        assertEquals(SettlementRejection.INVOICE_NOT_IDENTICAL, refused.reason)
    }

    @JsName("the_paired_control_an_uppercase_payment_hash_is_accepted_and_normalised")
    @Test
    fun `the paired control — an uppercase payment hash is accepted and normalised`() {
        // §4.3's exception list is exhaustive and the payment hash is not on it, so the rule one
        // line away from the control above points the other way. Without this pair, "uppercase is
        // rejected" reads as a rule about hex rather than about these two values.
        //
        // The behavioural half runs through `VerifiedPayment.verify` and not through
        // `Settlement.verify`, and that is not a weakening but the consequence of the change this
        // test's own file is about: the settlement door takes no payment hash any more, so there is
        // no longer any way to hand it an uppercase one. T3's door still takes one, is still where
        // §4.3's reading lives, and is the one a caller with a hash from elsewhere reaches for.
        val settled = settled()
        val receipt = SettlementFixtures.receipt(settled.fixture.receiptTags)
        val uppercase = PaymentHash.ofHex(settled.fixture.paymentHash.toHex().uppercase())

        assertEquals(settled.fixture.paymentHash, uppercase)
        VerifiedPayment.verify(
            settled.fixture.payee,
            uppercase,
            assertNotNull(receipt.preimage, "the fixture receipt carries §9.2's `<proof>`"),
        )

        // And the settlement path evidences the same receipt without being told any hash at all,
        // which is what makes the normalisation above a statement about §4.3 rather than the only
        // way this receipt could ever have settled.
        assertIs<Settlement.Evidenced>(Settlement.verify(receipt, settled.store, SPLIT))
    }

    @JsName("a_receipt_with_no_stored_request_is_refused_as_no_stored_request")
    @Test
    fun `a receipt with no stored request is refused as no stored request`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val receipt = SettlementFixtures.receipt(fixture.receiptTags)

        val refused = assertFailsWith<SettlementException> {
            Settlement.verify(receipt, PaymentRequestStore.inMemory(), SPLIT)
        }

        assertEquals(
            SettlementRejection.NO_STORED_REQUEST,
            refused.reason,
            "§9.2 check 1: \"If no such payment request was received and stored, the receipt MUST " +
                "be rejected.\" Answering `matched` here is the other mutation this file pins",
        )
    }

    @JsName("a_receipt_matched_against_the_other_payees_request_is_refused")
    @Test
    fun `a receipt matched against the other payee's request is refused`() {
        // §8.6's two separate invoices, each for the amount its own payee is owed — so what refuses
        // the crossed receipt below is check 1's byte comparison and not check 4's arithmetic,
        // which would be the right verdict reported for the wrong reason.
        val preimages = PaymentFixtures.preimageHex(2)
        val invoices = listOf(
            SettlementFixtures.invoice(preimages[0], SettlementFixtures.amountFor(Payee.PROVIDER, SPLIT)),
            SettlementFixtures.invoice(preimages[1], SettlementFixtures.amountFor(Payee.FEE, SPLIT)),
        )
        val order = SettlementFixtures.orderHex(0)
        val accepted = SettlementFixtures.accepted(0)
        val store = PaymentRequestStore.inMemory()
        val clock = FakeClock(SettlementFixtures.ACCEPTED_AT)
        // Only the fee payee's request is stored, so a provider receipt finds nothing at all.
        SettlementFixtures.accept(
            SettlementFixtures.requestFrom(
                Payee.FEE,
                SettlementFixtures.requestTags(
                    order = order,
                    payment = SettlementFixtures.requestPaymentTag(invoices[1]),
                    payeeTag = SettlementFixtures.payeeTag(Payee.FEE),
                    fee = SettlementFixtures.feeTag(),
                ),
            ),
            store,
            clock,
            accepted,
        )
        val preimageHex = preimages[0]
        val providerReceipt = SettlementFixtures.receipt(
            SettlementFixtures.receiptTags(
                order = order,
                payment = SettlementFixtures.paymentTag(invoices[0], preimageHex),
                payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER),
            ),
        )

        val missing = assertFailsWith<SettlementException> {
            Settlement.verify(providerReceipt, store, SPLIT)
        }
        assertEquals(SettlementRejection.NO_STORED_REQUEST, missing.reason)

        // Now store the provider's own request too, and present a provider receipt carrying the
        // **fee** invoice: the key finds a record and the bytes disagree.
        SettlementFixtures.accept(
            SettlementFixtures.requestFrom(
                Payee.PROVIDER,
                SettlementFixtures.requestTags(
                    order = order,
                    payment = SettlementFixtures.requestPaymentTag(invoices[0]),
                    payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER),
                ),
            ),
            store,
            clock,
            accepted,
        )
        val crossed = SettlementFixtures.receipt(
            SettlementFixtures.receiptTags(
                order = order,
                payment = SettlementFixtures.paymentTag(invoices[1], preimageHex),
                payeeTag = SettlementFixtures.payeeTag(Payee.PROVIDER),
            ),
        )
        val mismatch =
            assertFailsWith<SettlementException> { Settlement.verify(crossed, store, SPLIT) }
        assertEquals(SettlementRejection.INVOICE_NOT_IDENTICAL, mismatch.reason)
    }

    // ---------------------------------------------------------------------------------------
    // §9.2 checks 2 and 3, composed through T3.
    // ---------------------------------------------------------------------------------------

    /**
     * **Probe G1b, inverted.** The stored invoice, and a preimage for a *different* one.
     *
     * The shape this closes was real and was `Evidenced` until check 3's operand stopped being a
     * parameter: a receipt carrying the **stored** provider invoice, a preimage for the buyer's own
     * invoice, and that preimage's hash. Check 1 compared the reference against the store and
     * passed; check 3 compared a hash the caller supplied against a preimage the caller supplied
     * and passed; and the two checks were about two different invoices.
     *
     * There is no argument left through which they can be separated, so the same intent has to be
     * expressed the only way still open — a receipt whose `<reference>` is the stored invoice and
     * whose `<proof>` is another derived invoice's preimage — and it is refused. What refuses it is
     * §9.2 check 3, reaching the caller as T3 threw it: re-badging it here would put two names on
     * the failure §9 calls the load-bearing rule.
     *
     * The positive control is `a receipt matching its stored request evidences the payment`, which
     * asserts on the same corpus that the operand equals `SHA-256(this fixture's own preimage)`.
     * Without it this test would pass over a path that compared the hash against nothing at all.
     */
    @JsName("a_preimage_for_another_invoice_against_the_stored_one_yields_t3s_own_refusal")
    @Test
    fun `a preimage for another invoice, against the stored one, yields T3's own refusal`() {
        val settled = settled()
        val other = SettlementFixtures.pairs(2)[1]
        assertFalse(
            settled.fixture.preimageHex == other.preimageHex,
            "the two fixtures must carry genuinely different preimages, or this proves nothing",
        )

        // The stored invoice verbatim — so check 1 passes and check 3 is reached — beside a
        // preimage the store's invoice was never built from.
        val receipt = SettlementFixtures.receipt(
            SettlementFixtures.replacing(
                settled.fixture.receiptTags,
                SettlementFixtures.paymentTag(settled.fixture.invoice, other.preimageHex),
            ),
        )

        val refused = assertFailsWith<PaymentException> {
            Settlement.verify(receipt, settled.store, SPLIT)
        }

        assertEquals(
            PaymentRejection.PREIMAGE_MISMATCH,
            refused.reason,
            "§9.2 check 3 is T3's rule and its refusal reaches the caller as T3 threw it. The hash " +
                "it compared against came out of the stored invoice's `p` field: there is no " +
                "parameter on this entry point through which the matching one could be supplied",
        )
    }

    @JsName("an_uppercase_proof_is_refused_as_check_2s_case_rule")
    @Test
    fun `an uppercase proof is refused as check 2's case rule`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val tags = SettlementFixtures.replacing(
            fixture.receiptTags,
            SettlementFixtures.paymentTag(fixture.invoice, fixture.preimageHex.uppercase()),
        )

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.receipt(tags) }

        assertEquals(SettlementRejection.PREIMAGE_MALFORMED, refused.reason)
        val cause = assertIs<PaymentException>(refused.cause, "T3's reason must survive on the cause")
        assertEquals(PaymentRejection.UPPERCASE_NOT_PERMITTED, cause.reason)
    }

    // ---------------------------------------------------------------------------------------
    // §9.4's other rails: parsed, and evidencing nothing.
    // ---------------------------------------------------------------------------------------

    @JsName("a_bitcoin_or_ecash_receipt_is_parsed_and_yields_no_evidence")
    @Test
    fun `a bitcoin or ecash receipt is parsed and yields no evidence`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val store = PaymentRequestStore.inMemory()

        for (medium in listOf(PaymentMedium.BITCOIN, PaymentMedium.ECASH, PaymentMedium.UNKNOWN)) {
            val token = medium.token ?: "some-rail-nenya-does-not-implement"
            val receipt = SettlementFixtures.receipt(
                SettlementFixtures.replacing(
                    fixture.receiptTags,
                    SettlementFixtures.paymentTag("whatever-that-rail-uses", "its-own-proof", token),
                ),
            )
            assertEquals(medium, receipt.medium)
            assertNull(receipt.preimage, "§9.4 defines no rule, so the proof is not read as a preimage")

            val settlement: Settlement = Settlement.verify(receipt, store, SPLIT)

            val unverified = assertIs<Settlement.Unverified>(
                settlement,
                "§9.4: an implementation encountering one MUST treat the payment as unverified",
            )
            assertEquals(
                emptySet<PaymentCheck>(),
                unverified.checksPerformed,
                "nothing was checked, and the empty store was never even consulted",
            )
            assertFalse(
                unverified is Settlement.Evidenced,
                "there is nothing here to advance an order",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The receipt envelope.
    // ---------------------------------------------------------------------------------------

    @JsName("a_rumor_of_another_kind_is_refused_as_not_a_receipt")
    @Test
    fun `a rumor of another kind is refused as not a receipt`() {
        val tags = SettlementFixtures.requestTags()

        val refused = assertFailsWith<SettlementException> {
            PaymentReceipt.decode(SettlementFixtures.bound(tags, NenyaKind.ORDER_MESSAGE))
        }

        assertEquals(SettlementRejection.NOT_A_RECEIPT, refused.reason)
    }

    @JsName("a_receipt_carrying_a_requests_three_element_payment_tag_is_refused")
    @Test
    fun `a receipt carrying a request's three-element payment tag is refused`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val tags = SettlementFixtures.replacing(
            fixture.receiptTags,
            SettlementFixtures.requestPaymentTag(fixture.invoice),
        )

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.receipt(tags) }

        assertEquals(SettlementRejection.WRONG_ARITY, refused.reason)
        assertEquals(SettlementVocabulary.PAYMENT, refused.tag)
    }

    @JsName("a_receipt_with_no_payee_tag_is_refused_because_check_1s_key_is_a_pair")
    @Test
    fun `a receipt with no payee tag is refused, because check 1's key is a pair`() {
        val fixture = SettlementFixtures.pairs(1).single()
        val tags = SettlementFixtures.without(fixture.receiptTags, SettlementVocabulary.PAYEE)

        val refused = assertFailsWith<SettlementException> { SettlementFixtures.receipt(tags) }

        assertEquals(SettlementRejection.MISSING_REQUIRED_TAG, refused.reason)
        assertEquals(SettlementVocabulary.PAYEE, refused.tag)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    /** A bech32 character that is not [character], so the altered invoice is still well-shaped. */
    private fun otherBech32(character: Char): Char =
        Bolt11Reference.BECH32_ALPHABET.first { it != character }
}
